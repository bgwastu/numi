package net.wastu.numi.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import net.wastu.numi.util.PremiumSmsAutoAllow
import net.wastu.numi.util.RootChecker
import net.wastu.numi.utils.SmsRoleManager
import net.wastu.numi.network.TelegramHtml
import java.util.concurrent.TimeUnit

data class PermissionCheck(
    val label: String,
    val ok: Boolean,
    val detail: String
)

data class PermissionHealth(
    val checks: List<PermissionCheck>
) {
    val needsFix: Boolean get() = checks.any { !it.ok }

    fun summaryBlock(): String = buildString {
        appendLine("Permissions:")
        checks.forEachIndexed { index, check ->
            val mark = if (check.ok) "✓" else "✗"
            val line = "- ${check.label}: ${check.detail} $mark"
            if (index == checks.lastIndex) append(line) else appendLine(line)
        }
    }

    fun summaryBlockHtml(): String = buildString {
        appendLine(TelegramHtml.bold("Permissions:"))
        checks.forEachIndexed { index, check ->
            val mark = if (check.ok) "✓" else "✗"
            val line =
                "- ${TelegramHtml.escape(check.label)}: ${TelegramHtml.code(check.detail)} $mark"
            if (index == checks.lastIndex) append(line) else appendLine(line)
        }
    }
}

object DevicePermissionFixer {
    private const val TAG = "DevicePermissionFixer"
    private const val PKG = "net.wastu.numi"
    private val SU_PATHS = arrayOf("/system/bin/su", "/system/xbin/su", "su")

    private val RUNTIME_PERMS: List<Pair<String, String>> = buildList {
        add(Manifest.permission.SEND_SMS to "SEND_SMS")
        add(Manifest.permission.RECEIVE_SMS to "RECEIVE_SMS")
        add(Manifest.permission.READ_SMS to "READ_SMS")
        add(Manifest.permission.RECEIVE_WAP_PUSH to "RECEIVE_WAP_PUSH")
        add(Manifest.permission.RECEIVE_MMS to "RECEIVE_MMS")
        add(Manifest.permission.READ_PHONE_STATE to "READ_PHONE_STATE")
        add(Manifest.permission.CALL_PHONE to "CALL_PHONE")
        add(Manifest.permission.READ_CALL_LOG to "READ_CALL_LOG")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS to "POST_NOTIFICATIONS")
        }
    }

    fun health(context: Context): PermissionHealth {
        val checks = mutableListOf<PermissionCheck>()

        val smsDefault = SmsRoleManager.isDefaultSmsApp(context)
        checks += PermissionCheck(
            label = "SMS role",
            ok = smsDefault,
            detail = if (smsDefault) "Default" else "Not default"
        )

        for ((perm, label) in RUNTIME_PERMS) {
            val granted = ContextCompat.checkSelfPermission(context, perm) ==
                PackageManager.PERMISSION_GRANTED
            checks += PermissionCheck(
                label = label,
                ok = granted,
                detail = if (granted) "granted" else "denied"
            )
        }

        val pm = context.getSystemService(PowerManager::class.java)
        val batteryOk = pm.isIgnoringBatteryOptimizations(context.packageName)
        checks += PermissionCheck(
            label = "Battery",
            ok = batteryOk,
            detail = if (batteryOk) "Unrestricted" else "Optimized"
        )

        return PermissionHealth(checks)
    }

    /**
     * Root-fix SMS role, runtime permissions, battery whitelist, and premium SMS allow.
     * Returns a short human-readable result line.
     */
    fun fix(context: Context): String {
        if (!RootChecker.isRootAvailable()) {
            return "Fix failed: root (su) unavailable"
        }

        val grants = RUNTIME_PERMS.joinToString(" && ") { (perm, _) ->
            "pm grant $PKG $perm"
        }
        runRoot(grants)

        runRoot(
            "cmd role add-role-holder android.app.role.SMS $PKG && " +
                "settings put secure sms_default_application $PKG && " +
                "settings put global sms_default_application $PKG"
        )

        runRoot(
            "dumpsys deviceidle whitelist +$PKG >/dev/null; " +
                "cmd deviceidle whitelist +$PKG >/dev/null; " +
                "cmd appops set $PKG RUN_ANY_IN_BACKGROUND allow; " +
                "cmd appops set $PKG RUN_IN_BACKGROUND allow"
        )

        PremiumSmsAutoAllow.ensureAllowed()

        val after = health(context)
        return if (after.needsFix) {
            val bad = after.checks.filter { !it.ok }.joinToString(", ") { it.label }
            "Fix applied, still unhealthy: $bad"
        } else {
            "Fix applied — all permissions OK"
        }
    }

    private fun runRoot(cmd: String, timeoutMs: Long = 12_000): String {
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
            } catch (_: java.io.IOException) {
                // try next su path
            } catch (e: Exception) {
                Log.w(TAG, "su via $suPath failed", e)
            }
        }
        return ""
    }
}
