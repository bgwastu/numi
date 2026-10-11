package net.wastu.numi.spam

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.wastu.numi.queue.OutboxService
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages
import java.text.DateFormat
import java.util.Date

class SpamActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        OutboxService.wake(this)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            val colors = if (Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            } else if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) { SpamScreen { finish() } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpamScreen(back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<FilteredSms>>(emptyList()) }
    var enabled by remember { mutableStateOf(GemmaSpamFilter.enabled(context)) }
    var modelStatus by remember { mutableStateOf("Checking filter…") }
    var pending by remember { mutableStateOf(0) }
    var modelPresent by remember { mutableStateOf(GemmaModel.present(context)) }
    var selected by remember { mutableStateOf<FilteredSms?>(null) }
    suspend fun refresh() {
        withContext(Dispatchers.IO) {
            rows = SpamStore(context).spam()
            pending = SpamStore(context).pendingCount()
            modelStatus = GemmaSpamFilter.status(context)
            modelPresent = GemmaModel.present(context)
        }
    }
    fun restore(row: FilteredSms, allow: Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) { SpamStore(context).restore(row.id, allow) }
            selected = null; refresh(); refreshMessages(); refreshConversations()
        }
    }
    LaunchedEffect(Unit) { while (true) { refresh(); delay(2000) } }
    Scaffold(topBar = { TopAppBar(title = { Text("Spam") }, navigationIcon = { TextButton(onClick = back) { Text("Back") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row {
                            Column(Modifier.weight(1f)) {
                                Text("Automatic SMS filtering", style = MaterialTheme.typography.titleMedium)
                                Text("Spam stays here. It is kept on the phone and does not trigger Telegram alerts.")
                            }
                            Switch(checked = enabled, onCheckedChange = {
                                enabled = it
                                context.getSharedPreferences("numi_settings", android.content.Context.MODE_PRIVATE).edit().putBoolean(GemmaSpamFilter.ENABLED, it).apply()
                                scope.launch { withContext(Dispatchers.IO) { SpamCoordinator.scanHistory(context) }; refresh() }
                            })
                        }
                        Text(if (enabled) modelStatus else "Automatic filtering is off. Blocked senders and keywords still go to Spam.")
                        if (pending > 0) Text("Checking $pending messages…")
                        Row {
                            if (!modelPresent || modelStatus.contains("damaged")) TextButton(onClick = { scope.launch {
                                withContext(Dispatchers.IO) { GemmaModel.download(context) }; refresh()
                            } }) { Text("Download Gemma · 165 MB") }
                            TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) {
                                GemmaSpamFilter.requestRetry(context); SpamStore(context).recheck(); SpamCoordinator.scanHistory(context)
                            }; OutboxService.wake(context); refresh() } }) { Text("Recheck messages") }
                        }
                        Text("Gemma processes messages on this phone.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (rows.isEmpty()) item { Text("No messages in Spam.", Modifier.padding(vertical = 16.dp)) }
            items(rows, key = { it.id }) { row ->
                Card(onClick = { selected = row }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(row.address, style = MaterialTheme.typography.titleMedium)
                        Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(row.date)), style = MaterialTheme.typography.labelSmall)
                        Text(row.body, maxLines = 4)
                        Text(row.reason, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { restore(row, false) }) { Text("Not spam · restore") }
                    }
                }
            }
        }
    }
    selected?.let { row ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(row.address) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(row.body); Text(row.reason) } },
            confirmButton = { Button(onClick = { restore(row, false) }) { Text("Not spam") } },
            dismissButton = { TextButton(onClick = { restore(row, true) }) { Text("Always allow sender") } })
    }
}
