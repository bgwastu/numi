package net.wastu.numi.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import net.wastu.numi.utils.SmsRoleManager
import net.wastu.numi.network.TelegramHtml

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

    /** Reports the Android settings the user must change; Numi never escalates through su. */
    fun fix(context: Context): String {
        val missing = health(context).checks.filterNot { it.ok }.map { it.label }
        if (missing.isEmpty()) return "All Android permissions and settings are ready."

        val actions = buildList {
            if ("SMS role" in missing) add("set Numi as the default SMS app")
            val runtimePermissions = missing - setOf("SMS role", "Battery")
            if (runtimePermissions.isNotEmpty()) {
                add("grant ${runtimePermissions.joinToString(", ")} in Android permissions")
            }
            if ("Battery" in missing) add("set Numi battery use to Unrestricted")
        }

        return "No root commands were run. Please ${actions.joinToString("; ")}. " +
            "Android handles premium SMS confirmations."
    }
}
