package net.wastu.numi.spam

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.wastu.numi.queue.OutboxService
import net.wastu.numi.ui.NumiIcon
import net.wastu.numi.ui.NumiPage
import net.wastu.numi.ui.SettingRow
import net.wastu.numi.ui.rememberStatus
import net.wastu.numi.ui.setNumiContent
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages

class SpamActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        OutboxService.wake(this)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setNumiContent { SpamScreen(this) }
    }
}

@Composable
private fun SpamScreen(activity: SpamActivity) {
    val scope = rememberCoroutineScope()
    val rows by rememberStatus { SpamStore(activity).spam() }
    val snackbar = remember { SnackbarHostState() }
    var selected by remember { mutableStateOf<FilteredSms?>(null) }
    var restoring by remember { mutableStateOf(false) }
    var restored by remember { mutableStateOf(emptySet<Long>()) }
    fun restore(row: FilteredSms, allow: Boolean) {
        restoring = true
        scope.launch {
            val success = withContext(Dispatchers.IO) { runCatching { SpamStore(activity).restore(row.id, allow) }.getOrDefault(false) }
            restoring = false
            if (success) {
                restored = restored + row.id
                selected = null
                refreshMessages(); refreshConversations()
                snackbar.showSnackbar("Moved to inbox")
            } else snackbar.showSnackbar("Could not move message")
        }
    }
    NumiPage("Spam", back = { activity.finish() }, actions = {
        IconButton(onClick = { activity.startActivity(Intent(activity, SpamFilterActivity::class.java)) }) {
            NumiIcon(org.fossify.commons.R.drawable.ic_settings_cog_vector, "Spam filter")
        }
    }, snackbarHost = { SnackbarHost(snackbar) }) {
        val visible = rows?.filterNot { it.id in restored }
        if (visible.isNullOrEmpty()) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (rows == null) CircularProgressIndicator()
                else Text("No spam", style = MaterialTheme.typography.titleMedium)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(visible, key = { it.id }) { row ->
                    val date = DateUtils.formatDateTime(activity, row.date,
                        if (DateUtils.isToday(row.date)) DateUtils.FORMAT_SHOW_TIME else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR)
                    SettingRow(row.address, row.body, icon = org.fossify.commons.R.drawable.ic_person_outline_vector, valueMaxLines = 2,
                        trailing = { Text(date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }) { selected = row }
                }
            }
        }
    }
    selected?.let { row ->
        AlertDialog(onDismissRequest = { if (!restoring) selected = null }, title = { Text(row.address) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(row.body)
                Text(row.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } },
            confirmButton = { TextButton(enabled = !restoring, onClick = { restore(row, false) }) { Text("Move to inbox") } },
            dismissButton = { TextButton(enabled = !restoring, onClick = { restore(row, true) }) { Text("Always allow sender") } })
    }
}
