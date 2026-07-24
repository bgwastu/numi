package com.bgwastu.numi.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit

/**
 * Dial an arbitrary USSD code via root, scrape the response dialog, then force-close it.
 */
object UssdRunner {
    private const val TAG = "UssdRunner"
    private val SU_PATHS = arrayOf("/system/bin/su", "/system/xbin/su", "su")

    fun run(context: Context, code: String, timeoutSec: Long = 25): Result<String> {
        val normalized = normalizeUssd(code)
            ?: return Result.failure(IllegalArgumentException("Invalid USSD code. Example: *888#"))
        ensureCallPhone(context)?.let { return Result.failure(it) }
        return dialAndScrape(normalized, timeoutSec)
    }

    fun normalizeUssd(raw: String): String? {
        val trimmed = raw.trim().replace(" ", "")
        if (trimmed.isEmpty()) return null
        // Allow *123#, **21*..., #31#, etc.
        if (!trimmed.matches(Regex("""^[*#][*#\d]+#$"""))) return null
        return trimmed
    }

    private fun ensureCallPhone(context: Context): Exception? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        DevicePermissionFixer.fix(context)
        return if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            SecurityException("CALL_PHONE not granted. Tap Auto-fix on /status, then retry.")
        } else {
            null
        }
    }

    private fun dialAndScrape(code: String, timeoutSec: Long): Result<String> {
        Log.d(TAG, "Dialing USSD $code")
        forceCloseUssdUi()
        Thread.sleep(350)

        val telUri = "tel:" + urlEncodeTel(code)
        val startOut = runRoot(
            "am start -a android.intent.action.CALL -d '$telUri'",
            timeoutMs = 5_000
        )
        Log.d(TAG, "am start: ${startOut.take(200)}")

        val deadline = System.currentTimeMillis() + timeoutSec * 1000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(600)
            val dump = dumpUi()
            if (dump.isBlank()) continue
            val message = extractUssdDialogText(dump) ?: continue
            Log.d(TAG, "Captured USSD (${message.length} chars)")
            forceCloseUssdUi(dump)
            return Result.success(message)
        }
        forceCloseUssdUi()
        return Result.failure(IllegalStateException("USSD dialog not found / timed out after ${timeoutSec}s"))
    }

    /** Encode * and # for tel: URIs while leaving digits intact. */
    private fun urlEncodeTel(code: String): String =
        code.map { ch ->
            when (ch) {
                '*' -> "%2A"
                '#' -> "%23"
                else -> ch.toString()
            }
        }.joinToString("")

    private fun dumpUi(): String = runRoot(
        "uiautomator dump /data/local/tmp/numi_ussd.xml >/dev/null 2>&1; " +
            "cat /data/local/tmp/numi_ussd.xml 2>/dev/null",
        timeoutMs = 6_000
    )

    private fun extractUssdDialogText(dump: String): String? {
        val texts = Regex("""text="([^"]*)"""")
            .findAll(dump)
            .map { it.groupValues[1] }
            .map { decodeXmlEntities(it) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        val body = texts.firstOrNull { t ->
            t.contains("Pulsa", ignoreCase = true) ||
                t.contains("masa aktif", ignoreCase = true) ||
                (t.contains("Telkomsel", ignoreCase = true) && t.length > 30) ||
                Regex("""\bsd\b|\bs/d\b""", RegexOption.IGNORE_CASE).containsMatchIn(t) ||
                (t.contains("Rp", ignoreCase = true) && t.any { ch -> ch.isDigit() }) ||
                // Generic USSD bodies tend to be multi-line / long
                (t.contains('\n') && t.length >= 24)
        }
        if (body != null && !body.equals("Telkomsel Message", ignoreCase = true)) return body

        return texts
            .filterNot {
                it.equals("OK", true) ||
                    it.equals("Cancel", true) ||
                    it.equals("Batalkan", true) ||
                    it.equals("Telkomsel Message", true) ||
                    it.equals("Send", true) ||
                    it.equals("Kirim", true)
            }
            .filter { it.length >= 20 }
            .maxByOrNull { it.length }
    }

    /**
     * Aggressively dismiss USSD / MMI dialogs: tap OK if present, then Back/Back/Home/Endcall.
     */
    fun forceCloseUssdUi(dump: String? = null) {
        val ui = dump ?: dumpUi()
        if (ui.isNotBlank()) {
            tapDismissButton(ui)
            Thread.sleep(200)
        }
        // Always stack escapes so interactive menus don't linger.
        runRoot(
            "input keyevent 4; input keyevent 4; input keyevent 6; input keyevent 3",
            timeoutMs = 4_000
        )
        // If a dialog is still up, tap OK once more.
        val again = dumpUi()
        if (again.isNotBlank() && looksLikeUssdDialog(again)) {
            tapDismissButton(again)
            runRoot("input keyevent 4; input keyevent 3", timeoutMs = 3_000)
        }
    }

    private fun looksLikeUssdDialog(dump: String): Boolean {
        val lower = dump.lowercase()
        return "telkomsel message" in lower ||
            "ussd" in lower ||
            Regex("""text="OK"""").containsMatchIn(dump) &&
            (Regex("""text="[^"]{20,}"""").containsMatchIn(dump))
    }

    private fun tapDismissButton(dump: String) {
        val patterns = listOf(
            Regex("""text="(?:OK|Ok|Tutup|Close|Cancel|Batalkan)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]""""),
            Regex("""bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*text="(?:OK|Ok|Tutup|Close|Cancel|Batalkan)""""),
        )
        for (pattern in patterns) {
            val match = pattern.find(dump) ?: continue
            val g = match.groupValues
            val x = (g[1].toInt() + g[3].toInt()) / 2
            val y = (g[2].toInt() + g[4].toInt()) / 2
            runRoot("input tap $x $y", timeoutMs = 3_000)
            return
        }
    }

    private fun decodeXmlEntities(s: String): String =
        s.replace("&#10;", "\n")
            .replace("&#13;", "\r")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")

    private fun runRoot(cmd: String, timeoutMs: Long = 8_000): String {
        for (suPath in SU_PATHS) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf(suPath, "-c", cmd))
                process.outputStream.close()
                val died = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!died) {
                    process.destroyForcibly()
                    Log.w(TAG, "root timed out (${timeoutMs}ms): ${cmd.take(80)}")
                    continue
                }
                val out = process.inputStream.bufferedReader().readText()
                if (process.exitValue() == 0 || out.isNotBlank()) return out
            } catch (_: java.io.IOException) {
            } catch (e: Exception) {
                Log.w(TAG, "su via $suPath failed", e)
            }
        }
        return ""
    }
}
