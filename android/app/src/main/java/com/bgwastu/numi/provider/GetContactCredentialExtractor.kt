package com.bgwastu.numi.provider

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Extracts GetContact credentials from the GetContact app's data directory using root.
 *
 * AES key (`sharedKey`) and `privateKey` live in encrypted `encryption_keys.preferences_pb`.
 * HMAC is an APK-embedded constant (stable across installs). Device ID comes from SharedPreferences.
 * The API token is Base64(AES-CBC(IV||ciphertext)) under Android Keystore alias
 * `crypto_client` (CryptoSerializer) — decrypted in-process via frida-inject.
 */
object GetContactCredentialExtractor {

    private const val TAG = "GCExtractor"
    private const val GC_PKG = "app.source.getcontact"
    private const val GC_DATA = "/data/data/$GC_PKG"
    private const val TOKENS_FILE = "$GC_DATA/files/datastore/tokens.preferences_pb"
    private const val TOKEN_OUT = "$GC_DATA/cache/gc_app_token.txt"
    private const val KEYS_OUT = "$GC_DATA/cache/gc_crypto_keys.txt"

    private const val FRIDA_INJECT = "/data/local/tmp/frida-inject"
    private const val DUMP_SCRIPT = "/data/local/tmp/gc_dump_token.bundle.js"
    private const val EXTRACT_SH = "/data/local/tmp/gc_extract_token.sh"

    // APK-embedded HMAC (hex of the obfuscated signing key). AES is per-install — extracted.
    private const val HMAC_KEY = "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
    // Last-known-good AES fallback if frida key dump fails (this device). Prefer extracted sharedKey.
    private const val AES_KEY_FALLBACK = "0000000000000000000000000000000000000000000000000000000000000000"

    private val PLAIN_TOKEN = Regex("^[A-Za-z0-9+/=]{40,60}$")
    private val BASE64_BLOB = Regex("^[A-Za-z0-9+/=\\s]+$")
    private val AES_HEX = Regex("^[0-9a-fA-F]{64}$")

    fun extract(context: Context): GetContactCredentials? {
        return try {
            ensureExtractTools(context)
            val deviceId = extractDeviceId() ?: run {
                Log.w(TAG, "Could not extract device ID")
                return null
            }
            val token = extractToken() ?: run {
                Log.w(TAG, "Could not extract token from DataStore")
                return null
            }
            val crypto = extractCryptoKeys()
            val aes = crypto.aesKey ?: AES_KEY_FALLBACK
            if (!aes.matches(AES_HEX)) {
                Log.w(TAG, "Invalid AES key format")
                return null
            }
            GetContactCredentials(
                aes_key = aes.lowercase(),
                hmac_key = HMAC_KEY,
                private_key = crypto.privateKey.orEmpty(),
                device_token = token,
                device_id = deviceId,
                updated_at = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed", e)
            null
        }
    }

    fun extractAndSave(context: Context): Boolean {
        val creds = extract(context) ?: return false
        val file = File(context.filesDir, "providers")
        file.mkdirs()
        val json = JSONObject().apply {
            put("aes_key", creds.aes_key)
            put("hmac_key", creds.hmac_key)
            put("private_key", creds.private_key)
            put("device_token", creds.device_token)
            put("device_id", creds.device_id)
            put("updated_at", creds.updated_at)
        }
        File(file, "getcontact.json").writeText(json.toString())
        Log.i(TAG, "Credentials extracted and saved (aes=${creds.aes_key.take(8)}…)")
        return true
    }

    fun isGetContactInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(GC_PKG, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun extractDeviceId(): String? {
        val xml = runRoot("cat $GC_DATA/shared_prefs/GetContactSettingsPref.xml")
        val match = Regex("""<string name="PREF_KEY_FOR_UNIQ_ID">([^<]+)</string>""").find(xml)
        return match?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    private data class CryptoKeys(val aesKey: String?, val privateKey: String?)

    private fun extractCryptoKeys(): CryptoKeys {
        // Keys file is written by the same frida dump that extracts the token.
        val text = runRoot("cat $KEYS_OUT").trim()
        if (text.isBlank() || text.startsWith("ERROR:")) {
            Log.w(TAG, "Crypto keys dump missing; using AES fallback")
            return CryptoKeys(AES_KEY_FALLBACK, null)
        }
        var aes: String? = null
        var privateKey: String? = null
        for (line in text.lines()) {
            val t = line.trim()
            when {
                t.startsWith("sharedKey=") -> aes = t.removePrefix("sharedKey=").trim()
                t.startsWith("privateKey=") -> privateKey = t.removePrefix("privateKey=").trim()
            }
        }
        // Also accept raw JSON from older dumps
        if (aes == null && text.contains("sharedKey")) {
            aes = Regex(""""sharedKey"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
            privateKey = privateKey
                ?: Regex(""""privateKey"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
        }
        return CryptoKeys(
            aesKey = aes?.takeIf { it.matches(AES_HEX) },
            privateKey = privateKey?.takeIf { it.isNotBlank() }
        )
    }

    private fun extractToken(): String? {
        extractTokenLegacy()?.let { return it }
        if (isEncryptedTokensFile()) {
            Log.i(TAG, "tokens.preferences_pb is encrypted; decrypting via frida-inject")
            return extractTokenEncrypted()
        }
        Log.w(TAG, "Token not found (not plaintext, not recognized encrypted blob)")
        return null
    }

    private fun extractTokenLegacy(): String? {
        val strings = runRoot("strings $TOKENS_FILE")
        return strings.lines()
            .map { it.trim() }
            .firstOrNull { it.matches(PLAIN_TOKEN) }
            ?.takeIf { it.isNotBlank() }
    }

    private fun isEncryptedTokensFile(): Boolean {
        val head = runRoot("head -c 512 $TOKENS_FILE").trim()
        if (head.isEmpty() || !head.matches(BASE64_BLOB)) return false
        return try {
            val decoded = Base64.decode(head.replace("\\s".toRegex(), ""), Base64.DEFAULT)
            decoded.size > 16
        } catch (_: Exception) {
            false
        }
    }

    private fun extractTokenEncrypted(): String? {
        val injectOk = runRoot("test -x $FRIDA_INJECT && echo OK").trim() == "OK"
        if (!injectOk) {
            Log.e(TAG, "Missing $FRIDA_INJECT (push frida-inject android-arm64 matching frida-server)")
            return null
        }
        val scriptOk = runRoot("test -f $DUMP_SCRIPT && echo OK").trim() == "OK"
        if (!scriptOk) {
            Log.e(TAG, "Missing $DUMP_SCRIPT")
            return null
        }

        runRoot("rm -f $TOKEN_OUT $KEYS_OUT")
        val out = runRoot("sh $EXTRACT_SH", timeoutMs = 20_000)
        val token = out.lines().map { it.trim() }.firstOrNull {
            it.isNotEmpty() && !it.startsWith("ERROR:") && it.matches(PLAIN_TOKEN)
        }
        if (token != null) {
            Log.i(TAG, "Encrypted token extracted (${token.length} chars)")
            return token
        }
        Log.w(TAG, "Encrypted extract failed. stdout=$out")
        return null
    }

    private fun ensureExtractTools(context: Context) {
        try {
            val cache = File(context.cacheDir, "gcextract")
            cache.mkdirs()
            val scriptLocal = File(cache, "gc_dump_token.bundle.js")
            val shLocal = File(cache, "gc_extract_token.sh")
            copyAsset(context, "gcextract/gc_dump_token.bundle.js", scriptLocal)
            copyAsset(context, "gcextract/gc_extract_token.sh", shLocal)

            runRoot(
                "cp ${scriptLocal.absolutePath} $DUMP_SCRIPT && " +
                    "cp ${shLocal.absolutePath} $EXTRACT_SH && " +
                    "chmod 644 $DUMP_SCRIPT && chmod 755 $EXTRACT_SH"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not deploy extract tools from assets", e)
        }
    }

    private fun copyAsset(context: Context, assetPath: String, dest: File) {
        context.assets.open(assetPath).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private val SU_PATHS = arrayOf("/system/bin/su", "/system/xbin/su", "su")

    private fun runRoot(cmd: String, timeoutMs: Long = 15_000): String {
        // Quote so flags inside $cmd (e.g. head -c) are not eaten by nsenter.
        val quoted = "'" + cmd.replace("'", "'\\''") + "'"
        val nsenterCmd = "nsenter -t 1 -m -- sh -c $quoted"
        for (suPath in SU_PATHS) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf(suPath, "-c", nsenterCmd))
                process.outputStream.close()
                val died = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (!died) {
                    process.destroyForcibly()
                    Log.w(TAG, "Command timed out after ${timeoutMs}ms: $cmd")
                    continue
                }
                val out = process.inputStream.bufferedReader().readText()
                val err = process.errorStream.bufferedReader().readText()
                val exitCode = process.exitValue()
                if (exitCode == 0) {
                    return out
                } else {
                    Log.w(TAG, "Command ($suPath) exit $exitCode. stderr=$err stdout=$out")
                    if (out.isNotBlank()) return out
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to exec $suPath -c '$nsenterCmd'", e)
            }
        }
        Log.e(TAG, "Root command failed for all SU paths: $nsenterCmd")
        return ""
    }
}
