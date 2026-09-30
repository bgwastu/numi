package net.wastu.numi.device

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import net.wastu.numi.utils.SmsRoleManager
import net.wastu.numi.network.TelegramHtml
import net.wastu.numi.receiver.BootReceiver

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
    }

    fun ensureStartupReceiverEnabled(context: Context): Boolean {
        val packageManager = context.packageManager
        val receiver = ComponentName(context, BootReceiver::class.java)
        return try {
            when (packageManager.getComponentEnabledSetting(receiver)) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> {
                    packageManager.setComponentEnabledSetting(
                        receiver,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    true
                }
                else -> true
            }
        } catch (_: RuntimeException) {
            false
        }
    }

    fun openBatteryOptimizationRequest(context: Context): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) return true
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        return try {
            context.startActivity(request)
            true
        } catch (_: RuntimeException) {
            try {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                true
            } catch (_: RuntimeException) {
                false
            }
        }
    }

    fun openNotificationSettings(context: Context): Boolean {
        val appSettings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        return try {
            context.startActivity(appSettings)
            true
        } catch (_: RuntimeException) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:${context.packageName}"))
                )
                true
            } catch (_: RuntimeException) {
                false
            }
        }
    }

    fun startupEnabled(context: Context): Boolean = try {
        val state = context.packageManager.getComponentEnabledSetting(
            ComponentName(context, BootReceiver::class.java)
        )
        state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED &&
            state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER &&
            state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
    } catch (_: RuntimeException) {
        false
    }

    fun notificationsVisibleInDrawer(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = context.getSystemService(android.app.NotificationManager::class.java)
                .getNotificationChannel("NumiOutboxChannel")
            if (channel?.importance == android.app.NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
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

        val startupOk = startupEnabled(context)
        checks += PermissionCheck(
            label = "Startup",
            ok = startupOk,
            detail = if (startupOk) "Enabled after reboot" else "Disabled"
        )

        val notificationVisible = notificationsVisibleInDrawer(context)
        checks += PermissionCheck(
            label = "Service notification",
            ok = true,
            detail = if (notificationVisible) "Visible in drawer" else "Hidden from drawer"
        )

        return PermissionHealth(checks)
    }

    /** Reports the Android settings the user must change; Numi never escalates through su. */
    fun fix(context: Context): String {
        ensureStartupReceiverEnabled(context)
        val missing = health(context).checks.filterNot { it.ok }.map { it.label }
        if (missing.isEmpty()) {
            return if (notificationsVisibleInDrawer(context)) {
                "Gateway access is ready. To hide the service notice, open Numi → Phone access → Manage notifications and turn off Numi notifications."
            } else {
                "All gateway permissions and background settings are ready."
            }
        }

        val actions = buildList {
            if ("SMS role" in missing) add("set Numi as the default SMS app")
            val runtimePermissions = missing - setOf("SMS role", "Battery", "Startup")
            if (runtimePermissions.isNotEmpty()) {
                add("grant ${runtimePermissions.joinToString(", ")} in Android permissions")
            }
            if ("Battery" in missing) add("set Numi battery use to Unrestricted")
            if ("Startup" in missing) add("open Numi once to enable its startup receiver")
        }

        return "No root commands were run. Please ${actions.joinToString("; ")}. " +
            "Android handles premium SMS confirmations."
    }
}
