package com.bgwastu.numi.util

import android.util.Log
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Auto-allow Android's premium / short-code SMS cost confirmation.
 *
 * Short codes (e.g. Indonesia `3636`) are flagged as possible premium SMS and normally
 * show a blocking "this may cost money" dialog. With root we:
 *  1. Set [SmsManager] premium consent to ALWAYS_ALLOW via the ISms binder
 *  2. Persist the same policy under `/data/misc/sms/premium_sms_policy.xml`
 *  3. Best-effort auto-tap Allow/Send if a confirm dialog still appears
 */
object PremiumSmsAutoAllow {
    private const val TAG = "PremiumSmsAutoAllow"
    private const val PKG = "com.bgwastu.numi"
    /** SmsManager.PREMIUM_SMS_CONSENT_ALWAYS_ALLOW */
    private const val ALWAYS_ALLOW = 3
    /** ISms.Stub TRANSACTION_setPremiumSmsPermission (AOSP 16 / BP4A) */
    private const val TX_SET = 16
    /** ISms.Stub TRANSACTION_setPremiumSmsPermissionForSubscriber */
    private const val TX_SET_FOR_SUB = 17

    private val SU_PATHS = arrayOf("/system/bin/su", "/system/xbin/su", "su")
    private val granting = AtomicBoolean(false)

    fun ensureAllowed() {
        if (!granting.compareAndSet(false, true)) return
        try {
            writePolicyFile()
            // Global package policy
            runRoot("service call isms $TX_SET s16 $PKG i32 $ALWAYS_ALLOW >/dev/null")
            // Per-subscription (dual-SIM)
            for (subId in 1..3) {
                runRoot(
                    "service call isms $TX_SET_FOR_SUB i32 $subId s16 $PKG i32 $ALWAYS_ALLOW >/dev/null"
                )
            }
            // Indonesia: treat common info short codes as free so the dialog never arms.
            patchIndonesiaFreeShortCodes()
            Log.d(TAG, "Premium SMS ALWAYS_ALLOW applied for $PKG")
        } catch (e: Exception) {
            Log.w(TAG, "ensureAllowed failed", e)
        } finally {
            granting.set(false)
        }
    }

    /**
     * After queueing an SMS, watch briefly for a premium/cost confirmation dialog and tap Send/Allow.
     */
    fun dismissConfirmDialogAsync() {
        Thread({
            try {
                // Give the system time to show the dialog.
                Thread.sleep(350)
                for (attempt in 0 until 10) {
                    if (tryTapConfirmButton()) {
                        Log.d(TAG, "Tapped premium SMS confirm button (attempt=$attempt)")
                        return@Thread
                    }
                    Thread.sleep(400)
                }
            } catch (e: Exception) {
                Log.w(TAG, "dismissConfirmDialogAsync failed", e)
            }
        }, "premium-sms-auto-allow").start()
    }

    private fun writePolicyFile() {
        val xml = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <premium-sms-policy>
            <package name="$PKG" sms-policy="$ALWAYS_ALLOW" />
            </premium-sms-policy>
        """.trimIndent()
        val escaped = xml.replace("'", "'\\''")
        runRoot(
            "printf '%s\\n' '$escaped' > /data/misc/sms/premium_sms_policy.xml && " +
                "chown radio:radio /data/misc/sms/premium_sms_policy.xml && " +
                "chmod 600 /data/misc/sms/premium_sms_policy.xml"
        )
    }

    private fun patchIndonesiaFreeShortCodes() {
        // Add 3636 (and keep existing free list). Idempotent.
        runRoot(
            """
            CODES=/data/misc/sms/codes
            if [ -f "${'$'}CODES" ] && grep -q 'country="id"' "${'$'}CODES"; then
              if ! grep -q 'country="id"[^>]*free="[^"]*3636' "${'$'}CODES"; then
                sed -i 's/country="id" pattern="\\d{1,5}" free="/country="id" pattern="\\d{1,5}" free="3636|/' "${'$'}CODES"
              fi
            fi
            """.trimIndent().replace('\n', ' ')
        )
    }

    private fun tryTapConfirmButton(): Boolean {
        val dump = runRoot(
            "uiautomator dump /data/local/tmp/numi_uidump.xml >/dev/null 2>&1; " +
                "cat /data/local/tmp/numi_uidump.xml 2>/dev/null"
        )
        if (dump.isBlank()) return false

        // Prefer positive actions; never tap Cancel/Batalkan/Deny.
        val patterns = listOf(
            Regex("""text="(Always allow|ALWAYS ALLOW|Allow|ALLOW|Send|SEND|Kirim|Izinkan|OK)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]""""),
            Regex("""content-desc="(Always allow|Allow|Send|Kirim|Izinkan|OK)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]""""),
            Regex("""bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*text="(Always allow|Allow|Send|Kirim|Izinkan|OK)""""),
        )

        // Only act if the dump looks like an SMS cost / premium confirm.
        val lower = dump.lowercase()
        val looksRelevant = listOf(
            "premium",
            "short code",
            "shortcode",
            "may cost",
            "might cost",
            "biaya",
            "premium sms",
            "pesan berbayar",
            "sms berbayar",
        ).any { it in lower }
        if (!looksRelevant) return false

        for (pattern in patterns) {
            val match = pattern.find(dump) ?: continue
            val groups = match.groupValues
            // Support both text-first and bounds-first patterns.
            val (x1, y1, x2, y2) = if (groups.size >= 6 && groups[2].all { it.isDigit() }) {
                listOf(groups[2].toInt(), groups[3].toInt(), groups[4].toInt(), groups[5].toInt())
            } else if (groups.size >= 5 && groups[1].all { it.isDigit() }) {
                listOf(groups[1].toInt(), groups[2].toInt(), groups[3].toInt(), groups[4].toInt())
            } else {
                continue
            }
            val x = (x1 + x2) / 2
            val y = (y1 + y2) / 2
            runRoot("input tap $x $y")
            return true
        }
        return false
    }

    private fun runRoot(cmd: String, timeoutMs: Long = 8_000): String {
        for (suPath in SU_PATHS) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf(suPath, "-c", cmd))
                process.outputStream.close()
                val died = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!died) {
                    process.destroyForcibly()
                    continue
                }
                val out = process.inputStream.bufferedReader().readText()
                if (process.exitValue() == 0 || out.isNotBlank()) return out
            } catch (e: java.io.IOException) {
                // Missing su binary on this path — try next.
            } catch (e: Exception) {
                Log.w(TAG, "su via $suPath failed", e)
            }
        }
        return ""
    }
}
