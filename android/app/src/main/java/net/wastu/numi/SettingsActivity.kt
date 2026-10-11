package net.wastu.numi

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import net.wastu.numi.spam.SpamActivity
import net.wastu.numi.spam.SpamFilterActivity
import net.wastu.numi.ui.Destination
import net.wastu.numi.ui.FilterStatus
import net.wastu.numi.ui.NumiNavigation
import net.wastu.numi.ui.NumiPage
import net.wastu.numi.ui.SettingRow
import net.wastu.numi.ui.SettingsGroup
import net.wastu.numi.ui.rememberStatus
import net.wastu.numi.ui.setNumiContent
import net.wastu.numi.ui.telegramStatus

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setNumiContent { SettingsScreen(this) }
    }
}

@Composable
private fun SettingsScreen(activity: SettingsActivity) {
    val telegram by rememberStatus { telegramStatus(activity) }
    val filter by rememberStatus { FilterStatus.read(activity) }
    fun open(target: Class<*>) { activity.startActivity(Intent(activity, target)) }
    NumiPage("Settings", bottomBar = { NumiNavigation(Destination.SETTINGS, activity) }) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SettingsGroup("Connections") {
                SettingRow("Telegram", telegram ?: "Checking") { open(ConnectorSettingsActivity::class.java) }
                SettingRow("Device access") { open(DeviceAccessActivity::class.java) }
            }
            SettingsGroup("Messages") {
                SettingRow("Spam filter", filter?.title ?: "Checking") { open(SpamFilterActivity::class.java) }
                SettingRow("Spam", filter?.let { "${it.spam} messages" }) { open(SpamActivity::class.java) }
                SettingRow("Message settings") { open(org.fossify.messages.activities.SettingsActivity::class.java) }
            }
            SettingsGroup("Calls") {
                SettingRow("Call settings") { open(org.fossify.phone.activities.SettingsActivity::class.java) }
            }
            SettingsGroup("About") { SettingRow("Numi", BuildConfig.VERSION_NAME) }
        }
    }
}
