package net.wastu.numi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import net.wastu.numi.device.DevicePermissionFixer
import net.wastu.numi.device.DeviceStatusProvider
import net.wastu.numi.queue.OutboxService
import net.wastu.numi.ui.NumiPage
import net.wastu.numi.ui.SettingRow
import net.wastu.numi.ui.SettingsGroup
import net.wastu.numi.ui.rememberStatus
import net.wastu.numi.ui.setNumiContent
import net.wastu.numi.utils.SmsRoleManager

class DeviceAccessActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setNumiContent { DeviceAccessScreen(this) }
    }
}

@Composable
private fun DeviceAccessScreen(activity: DeviceAccessActivity) {
    val access by rememberStatus { PhoneAccess.read(activity) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { OutboxService.wake(activity) }
    val smsRole = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { OutboxService.wake(activity) }
    val scope = rememberCoroutineScope()
    NumiPage("Device access", back = { activity.finish() }) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SettingsGroup("Default apps") {
                SettingRow("SMS app", if (access?.smsDefault == true) "Numi" else "Set default") {
                    SmsRoleManager.createRequestDefaultSmsIntent(activity)?.let(smsRole::launch)
                }
                SettingRow("Phone app", if (access?.phoneDefault == true) "Numi" else "Set default") {
                    activity.startActivity(Intent(activity, org.fossify.phone.activities.SplashActivity::class.java))
                }
            }
            SettingsGroup("Permissions") {
                SettingRow("SMS and contacts", if (access?.smsPermissions == true) "Allowed" else "Allow access") {
                    permissions.launch(arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS))
                }
                SettingRow("Notifications", if (access?.notifications == true) "Allowed" else "Off") {
                    if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                    } else DevicePermissionFixer.openNotificationSettings(activity)
                }
            }
            SettingsGroup("Background") {
                SettingRow("Battery", if (access?.batteryUnrestricted == true) "Unrestricted" else "Optimized") { DevicePermissionFixer.openBatteryOptimizationRequest(activity) }
                SettingRow("Startup after reboot", if (access?.startup == true) "On" else "Off") {
                    scope.launch { DevicePermissionFixer.ensureStartupReceiverEnabled(activity); OutboxService.wake(activity) }
                }
            }
            SettingsGroup("SIM cards") { SettingRow("Active SIMs", access?.sims ?: "Checking") }
        }
    }
}

private data class PhoneAccess(
    val smsDefault: Boolean, val phoneDefault: Boolean, val smsPermissions: Boolean,
    val sims: String, val notifications: Boolean, val batteryUnrestricted: Boolean, val startup: Boolean
) {
    companion object {
        fun read(context: Context) = PhoneAccess(
            SmsRoleManager.isDefaultSmsApp(context), context.getSystemService(TelecomManager::class.java).defaultDialerPackage == context.packageName,
            listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS)
                .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED },
            DeviceStatusProvider.activeSims(context).joinToString("\n") { "SIM ${it.slotIndex + 1}, ${it.label}" }.ifBlank { "None" },
            DevicePermissionFixer.notificationsVisibleInDrawer(context),
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
            DevicePermissionFixer.startupEnabled(context)
        )
    }
}
