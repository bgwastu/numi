package com.bgwastu.numi.provider

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

/**
 * On-device GetContact captcha OCR.
 *
 * GC captchas are usually 5–6 mixed-case alphanumerics. Background can be light
 * (dark glyphs) or dark (light glyphs). Primary engine: ddddocr `common_old.onnx`.
 * Fallback: ML Kit Latin text recognition on upscaled preprocessed bitmaps.
 */
object GcCaptchaOcr {
    private const val TAG = "GcCaptchaOcr"
    private const val MODEL_ASSET = "ddddocr/common_old.onnx"
    private const val CHARSET_ASSET = "ddddocr/charset.json"
    private const val TARGET_HEIGHT = 64
    private const val MIN_CONFIDENCE = 0.25f

    data class Guess(
        val code: String,
        val confidence: Float,
        val source: String
    )

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val sessionRef = AtomicReference<OrtSession?>(null)
    private val charsetRef = AtomicReference<List<String>?>(null)

    fun guessCodes(context: Context, jpegBase64: String): List<Guess> {
        val bytes = try {
            Base64.decode(jpegBase64, Base64.DEFAULT)
        } catch (e: Exception) {
            Log.w(TAG, "Invalid captcha base64", e)
            return emptyList()
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return emptyList()
        return guessCodes(context, bitmap)
    }

    fun guessCodes(context: Context, bitmap: Bitmap): List<Guess> {
        val mean = meanLuma(bitmap)
        val lightBackground = mean > 130f
        Log.i(TAG, "Captcha luma mean=$mean lightBg=$lightBackground")

        val variants = LinkedHashMap<String, Bitmap>()
        variants["raw"] = bitmap
        variants["invert"] = invertGray(bitmap)
        variants["dark_glyphs"] = isolateDarkGlyphs(bitmap)
        variants["bright_glyphs"] = isolateBrightGlyphs(bitmap)
        if (lightBackground) {
            variants["inv_dark"] = invertGray(isolateDarkGlyphs(bitmap))
        } else {
            variants["contrast"] = boostContrast(bitmap)
        }

        val guesses = LinkedHashMap<String, Guess>()

        for ((label, img) in variants) {
            runCatching { recognizeDdddocr(context, img) }
                .onSuccess { guess ->
                    if (guess != null) mergeGuess(guesses, guess.copy(source = "ddddocr:$label"), lightBackground)
                }
                .onFailure { Log.w(TAG, "ddddocr failed ($label)", it) }
        }

        // ML Kit needs larger glyphs; prefer cleaned variants.
        val mlkitInputs = listOf(
            "dark3" to scaleBitmap(variants.getValue("dark_glyphs"), 3f),
            "inv3" to scaleBitmap(variants.getValue("invert"), 3f),
            "raw3" to scaleBitmap(bitmap, 3f)
        )
        for ((label, img) in mlkitInputs) {
            runCatching { recognizeMlKit(img) }
                .onSuccess { guess ->
                    if (guess != null) mergeGuess(guesses, guess.copy(source = "mlkit:$label"), lightBackground)
                }
                .onFailure { Log.w(TAG, "ML Kit failed ($label)", it) }
        }

        return guesses.values
            .filter { it.code.length in 4..8 }
            .sortedWith(
                compareByDescending<Guess> { scoreGuess(it, lightBackground) }
                    .thenByDescending { it.confidence }
            )
            .also {
                Log.i(
                    TAG,
                    "Captcha guesses: ${it.map { g -> "${g.code}(${g.source}/${"%.2f".format(g.confidence)})" }}"
                )
            }
    }

    private fun mergeGuess(
        into: LinkedHashMap<String, Guess>,
        guess: Guess,
        lightBackground: Boolean
    ) {
        val key = guess.code
        val existing = into[key]
        if (existing == null || scoreGuess(guess, lightBackground) > scoreGuess(existing, lightBackground)) {
            into[key] = guess
        }
    }

    /** Prefer typical GC length (5–6) and ddddocr over ML Kit. */
    private fun scoreGuess(guess: Guess, lightBackground: Boolean): Float {
        var score = guess.confidence
        score += when (guess.code.length) {
            6 -> 0.35f
            5 -> 0.25f
            4, 7, 8 -> 0.05f
            else -> 0f
        }
        if (guess.source.startsWith("ddddocr")) score += 0.15f
        if (lightBackground && guess.source.contains("invert")) score += 0.1f
        if (lightBackground && guess.source.contains("dark")) score += 0.08f
        if (!lightBackground && guess.source.contains("raw")) score += 0.08f
        if (!lightBackground && guess.source.contains("bright")) score += 0.08f
        // Slightly prefer mixed case (GC often mixes case).
        val hasLower = guess.code.any { it.isLowerCase() }
        val hasUpper = guess.code.any { it.isUpperCase() }
        if (hasLower && hasUpper) score += 0.2f
        return score
    }

    private fun recognizeDdddocr(context: Context, bitmap: Bitmap): Guess? {
        val session = session(context) ?: return null
        val charset = charset(context) ?: return null
        val gray = toGrayHeight64(bitmap)
        val width = gray.width
        val height = gray.height
        val pixels = IntArray(width * height)
        gray.getPixels(pixels, 0, width, 0, 0, width, height)

        val floatBuf = java.nio.ByteBuffer
            .allocateDirect(1 * 1 * height * width * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer()
        for (p in pixels) {
            floatBuf.put(Color.red(p) / 255f)
        }
        floatBuf.rewind()

        val shape = longArrayOf(1, 1, height.toLong(), width.toLong())
        OnnxTensor.createTensor(env, floatBuf, shape).use { input ->
            val inputName = session.inputNames.iterator().next()
            session.run(mapOf(inputName to input)).use { result ->
                return decodeCtc(result[0].value, charset)
                    ?.takeIf { it.confidence >= MIN_CONFIDENCE }
            }
        }
    }

    private fun decodeCtc(value: Any?, charset: List<String>): Guess? {
        val (indices, confidences) = when (value) {
            is Array<*> -> {
                if (value.isEmpty()) return null
                when (val first = value[0]) {
                    is Array<*> -> {
                        if (first.isNotEmpty() && first[0] is FloatArray) {
                            // [seq][batch][classes]
                            val seq = value.size
                            val idxs = IntArray(seq)
                            val confs = FloatArray(seq)
                            for (t in 0 until seq) {
                                @Suppress("UNCHECKED_CAST")
                                val row = (value[t] as Array<FloatArray>)[0]
                                val (idx, conf) = argmaxSoftmax(row)
                                idxs[t] = idx
                                confs[t] = conf
                            }
                            idxs to confs
                        } else if (first.isNotEmpty() && first[0] is Array<*>) {
                            // [1][seq][classes]
                            @Suppress("UNCHECKED_CAST")
                            val seqArr = value[0] as Array<FloatArray>
                            val idxs = IntArray(seqArr.size)
                            val confs = FloatArray(seqArr.size)
                            for (t in seqArr.indices) {
                                val (idx, conf) = argmaxSoftmax(seqArr[t])
                                idxs[t] = idx
                                confs[t] = conf
                            }
                            idxs to confs
                        } else {
                            return null
                        }
                    }
                    else -> return null
                }
            }
            else -> return null
        }

        val chars = ArrayList<Char>()
        val usedConfs = ArrayList<Float>()
        var prev = -1
        for (i in indices.indices) {
            val idx = indices[i]
            if (idx != prev && idx != 0 && idx in charset.indices) {
                val ch = charset[idx]
                if (ch.isNotEmpty()) {
                    chars.add(ch[0])
                    usedConfs.add(confidences[i])
                }
            }
            prev = idx
        }
        if (chars.isEmpty()) return null
        val code = normalizeCode(chars.joinToString("")) ?: return null
        val conf = if (usedConfs.isEmpty()) 0f else usedConfs.average().toFloat()
        return Guess(code = code, confidence = conf, source = "ddddocr")
    }

    private fun argmaxSoftmax(logits: FloatArray): Pair<Int, Float> {
        var maxLogit = Float.NEGATIVE_INFINITY
        var maxIdx = 0
        for (i in logits.indices) {
            if (logits[i] > maxLogit) {
                maxLogit = logits[i]
                maxIdx = i
            }
        }
        var sum = 0.0
        for (logit in logits) {
            sum += kotlin.math.exp((logit - maxLogit).toDouble())
        }
        val conf = (1.0 / sum).toFloat()
        return maxIdx to conf
    }

    private fun recognizeMlKit(bitmap: Bitmap): Guess? {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val visionText = Tasks.await(recognizer.process(image), 8, TimeUnit.SECONDS)
            val raw = visionText.text.replace('\n', ' ').replace('\r', ' ').trim()
            val code = normalizeCode(raw) ?: return null
            Guess(code = code, confidence = 0.4f, source = "mlkit")
        } finally {
            recognizer.close()
        }
    }

    /** Keep letter/digit case — GetContact codes are case-sensitive. */
    private fun normalizeCode(raw: String): String? {
        val cleaned = raw.filter { it.isLetterOrDigit() }
        if (cleaned.length !in 4..8) return null
        return cleaned
    }

    private fun meanLuma(src: Bitmap): Float {
        val w = src.width
        val h = src.height
        val stepX = max(1, w / 24)
        val stepY = max(1, h / 12)
        var sum = 0.0
        var count = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val c = src.getPixel(x, y)
                sum += 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
                count++
                x += stepX
            }
            y += stepY
        }
        return if (count == 0) 128f else (sum / count).toFloat()
    }

    private fun toGrayHeight64(src: Bitmap): Bitmap {
        val targetW = max(8, (src.width * (TARGET_HEIGHT.toFloat() / src.height)).toInt())
        val scaled = Bitmap.createScaledBitmap(src, targetW, TARGET_HEIGHT, true)
        val out = Bitmap.createBitmap(targetW, TARGET_HEIGHT, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(targetW * TARGET_HEIGHT)
        scaled.getPixels(pixels, 0, targetW, 0, 0, targetW, TARGET_HEIGHT)
        for (i in pixels.indices) {
            val c = pixels[i]
            val y = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c))
                .toInt()
                .coerceIn(0, 255)
            pixels[i] = Color.rgb(y, y, y)
        }
        out.setPixels(pixels, 0, targetW, 0, 0, targetW, TARGET_HEIGHT)
        return out
    }

    private fun invertGray(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val y = 255 - (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)).toInt()
            pixels[i] = Color.rgb(y, y, y)
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /** Dark maroon/green glyphs on pale backgrounds (drop light scratch lines). */
    private fun isolateDarkGlyphs(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            val vmax = max(r, max(g, b))
            val vmin = min(r, min(g, b))
            val sat = if (vmax == 0) 0 else ((vmax - vmin) * 255 / vmax)
            val isGlyph = vmax < 170 && (sat > 25 || vmax < 120)
            pixels[i] = if (isGlyph) Color.BLACK else Color.WHITE
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /** Pale glyphs on near-black backgrounds. */
    private fun isolateBrightGlyphs(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            val vmax = max(r, max(g, b))
            val isGlyph = vmax >= 140
            pixels[i] = if (isGlyph) Color.WHITE else Color.BLACK
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun boostContrast(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val y = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)).toInt()
            val v = if (y >= 110) 255 else 0
            pixels[i] = Color.rgb(v, v, v)
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun scaleBitmap(src: Bitmap, factor: Float): Bitmap {
        val w = max(1, (src.width * factor).toInt())
        val h = max(1, (src.height * factor).toInt())
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun session(context: Context): OrtSession? {
        sessionRef.get()?.let { return it }
        synchronized(this) {
            sessionRef.get()?.let { return it }
            return try {
                val modelFile = materializeAsset(context, MODEL_ASSET, "common_old.onnx")
                val opts = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                    setIntraOpNumThreads(2)
                }
                val created = env.createSession(modelFile.absolutePath, opts)
                sessionRef.set(created)
                created
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load ddddocr ONNX", e)
                null
            }
        }
    }

    private fun charset(context: Context): List<String>? {
        charsetRef.get()?.let { return it }
        synchronized(this) {
            charsetRef.get()?.let { return it }
            return try {
                val text = context.assets.open(CHARSET_ASSET).bufferedReader().use { it.readText() }
                val arr = Json.parseToJsonElement(text).jsonArray
                val list = arr.map { it.jsonPrimitive.content }
                charsetRef.set(list)
                list
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load ddddocr charset", e)
                null
            }
        }
    }

    private fun materializeAsset(context: Context, assetPath: String, fileName: String): File {
        val out = File(context.filesDir, "ddddocr/$fileName")
        if (out.exists() && out.length() > 1_000_000L) return out
        out.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        return out
    }
}
