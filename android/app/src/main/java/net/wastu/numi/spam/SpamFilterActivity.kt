package net.wastu.numi.spam

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.wastu.numi.queue.OutboxService
import net.wastu.numi.ui.FilterStatus
import net.wastu.numi.ui.NumiIcon
import net.wastu.numi.ui.NumiPage
import net.wastu.numi.ui.SettingRow
import net.wastu.numi.ui.SettingsGroup
import net.wastu.numi.ui.ToggleRow
import net.wastu.numi.ui.rememberStatus
import net.wastu.numi.ui.setNumiContent

class SpamFilterActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setNumiContent { SpamFilterScreen(this) }
    }
}

@Composable
private fun SpamFilterScreen(activity: SpamFilterActivity) {
    val status by rememberStatus { FilterStatus.read(activity) }
    var enabled by remember { mutableStateOf(GemmaSpamFilter.enabled(activity)) }
    var busy by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun work(action: () -> Unit) {
        busy = true
        scope.launch {
            val error = withContext(Dispatchers.IO) { runCatching(action).exceptionOrNull() }
            busy = false
            if (error != null) detail = error.message ?: "Could not update filter"
            OutboxService.wake(activity)
        }
    }
    NumiPage("Spam filter", back = { activity.finish() }, actions = {
        IconButton(onClick = { detail = "Gemma checks messages on this phone. Spam stays in the Spam folder and has no Telegram alerts. Blocked senders and keywords remain active when automatic filtering is off." }) {
            NumiIcon(org.fossify.commons.R.drawable.ic_info_outline_vector, "About spam filtering")
        }
    }) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            ToggleRow("Automatic filtering", enabled) {
                enabled = it
                activity.getSharedPreferences("numi_settings", Context.MODE_PRIVATE).edit().putBoolean(GemmaSpamFilter.ENABLED, it).apply()
                work { SpamCoordinator.scanHistory(activity) }
            }
            SettingsGroup("Filter") {
                val filter = status
                SettingRow("Gemma", if (enabled) filter?.title ?: "Checking" else "Off",
                    trailing = if (filter?.downloading == true || busy || (enabled && (filter?.pending ?: 0) > 0)) {
                        { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
                    } else null)
                if (filter?.downloading == true) {
                    val percent = Regex("([0-9]+)%").find(filter.download.orEmpty())?.groupValues?.get(1)
                    SettingRow("Download", if (percent != null) "$percent%, Wi-Fi" else "Verifying")
                } else if (filter != null && (!filter.present || filter.issue?.contains("damaged") == true || filter.download != null)) {
                    SettingRow("Download filter", "165 MB, Wi-Fi", enabled = !busy) { work { GemmaModel.download(activity) } }
                }
                if (filter?.issue != null || (filter?.download != null && !filter.downloading)) {
                    SettingRow("Filter issue", "View details") { detail = filter.issue ?: filter.download }
                    if (filter.present) SettingRow("Retry", enabled = !busy) { work { GemmaSpamFilter.requestRetry(activity); SpamCoordinator.scanHistory(activity) } }
                }
                SettingRow("Recheck messages", enabled = enabled && !busy && status?.downloading != true) {
                    work { GemmaSpamFilter.requestRetry(activity); SpamStore(activity).recheck(); SpamCoordinator.scanHistory(activity) }
                }
            }
            SettingsGroup("Messages") {
                SettingRow("Spam", status?.let { "${it.spam} messages" }) { activity.startActivity(Intent(activity, SpamActivity::class.java)) }
                SettingRow("Blocked numbers") { activity.startActivity(Intent(activity, org.fossify.commons.activities.ManageBlockedNumbersActivity::class.java)) }
                SettingRow("Blocked keywords") { activity.startActivity(Intent(activity, org.fossify.messages.activities.ManageBlockedKeywordsActivity::class.java)) }
            }
        }
    }
    detail?.let { text ->
        AlertDialog(onDismissRequest = { detail = null }, title = { Text("Spam filter") },
            text = { Text(text) }, confirmButton = { TextButton(onClick = { detail = null }) { Text("Close") } })
    }
}
