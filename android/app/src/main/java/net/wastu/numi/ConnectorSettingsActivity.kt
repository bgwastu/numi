package net.wastu.numi

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.wastu.numi.bus.TelegramOwnerConfig
import net.wastu.numi.network.TelegramApi
import net.wastu.numi.network.TelegramApiException
import net.wastu.numi.queue.OutboxService
import net.wastu.numi.ui.NumiPage
import net.wastu.numi.ui.SettingRow
import net.wastu.numi.ui.ToggleRow
import net.wastu.numi.ui.rememberStatus
import net.wastu.numi.ui.setNumiContent
import net.wastu.numi.ui.telegramStatus
import org.json.JSONObject

class ConnectorSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OutboxService.wake(this)
        setNumiContent { TelegramScreen(this) }
    }
}

@Composable
private fun TelegramScreen(activity: ConnectorSettingsActivity) {
    val prefs = remember { activity.getSharedPreferences("numi_settings", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(prefs.getBoolean("TELEGRAM_ENABLED", true)) }
    var topics by remember { mutableStateOf(prefs.getBoolean("TELEGRAM_TOPICS", true)) }
    var token by remember { mutableStateOf(prefs.getString("BOT_TOKEN", "").orEmpty()) }
    var owner by remember { mutableStateOf(TelegramOwnerConfig.normalize(prefs.getString("TELEGRAM_OWNER_ID", null))) }
    var bot by remember { mutableStateOf(prefs.getString("telegram_bot_username", "").orEmpty()) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var disconnect by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var topicsAvailable by remember { mutableStateOf(prefs.getBoolean("telegram_topics_available", false)) }
    val status by rememberStatus { telegramStatus(activity) }
    val configured = token.isNotBlank() && owner.isNotBlank()
    BackHandler(editing) { editing = false }

    if (editing) {
        ConnectionForm(token, owner, back = { editing = false }) { newToken, newOwner ->
            val change = prefs.edit().putString("BOT_TOKEN", newToken).putString("TELEGRAM_OWNER_ID", newOwner).putBoolean("TELEGRAM_ENABLED", true)
            if (newToken != token) {
                change.remove("telegram_bot_username").remove("telegram_topics_available").remove("telegram_last_poll_success").remove("telegram_last_poll_error")
                bot = ""; topicsAvailable = false
            }
            change.apply()
            token = newToken; owner = newOwner; enabled = true; result = null; editing = false
            OutboxService.wake(activity)
        }
        return
    }

    NumiPage("Telegram", back = { activity.finish() }) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            ToggleRow("Telegram", configured && enabled, enabled = configured, value = status ?: "Checking") {
                enabled = it
                prefs.edit().putBoolean("TELEGRAM_ENABLED", it).apply()
                OutboxService.wake(activity)
            }
            Column {
                SettingRow("Connection", if (configured) bot.ifBlank { "Configured" } else "Connect bot") { editing = true }
                if (configured) {
                    SettingRow("Test connection", result, enabled = !checking,
                        trailing = if (checking) { { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) } } else null) {
                        checking = true
                        scope.launch {
                            val api = TelegramApi(token)
                            try {
                                val response = api.request("getMe") as JSONObject
                                val username = response.optString("username")
                                bot = if (username.isBlank()) "" else "@$username"
                                topicsAvailable = response.optBoolean("has_topics_enabled")
                                prefs.edit().putString("telegram_bot_username", bot).putBoolean("telegram_topics_available", topicsAvailable).apply()
                                result = "Connected"
                            } catch (exception: Exception) {
                                result = if (exception is TelegramApiException && exception.code == 401) "Invalid bot token" else "Could not connect"
                            } finally { api.close(); checking = false }
                        }
                    }
                }
            }
            if (configured) {
                Column {
                    ToggleRow("Conversation topics", topics, enabled = topicsAvailable,
                        value = if (topicsAvailable) null else "Enable in BotFather") {
                        topics = it; prefs.edit().putBoolean("TELEGRAM_TOPICS", it).apply()
                    }
                }
                SettingRow("Disconnect") { disconnect = true }
            }
        }
    }
    if (disconnect) {
        AlertDialog(onDismissRequest = { disconnect = false }, title = { Text("Disconnect Telegram?") },
            confirmButton = { TextButton(onClick = {
                prefs.edit().remove("BOT_TOKEN").remove("TELEGRAM_OWNER_ID").remove("telegram_bot_username")
                    .remove("telegram_topics_available").putBoolean("TELEGRAM_ENABLED", false).apply()
                token = ""; owner = ""; bot = ""; enabled = false; result = null; disconnect = false
                OutboxService.wake(activity)
            }) { Text("Disconnect") } },
            dismissButton = { TextButton(onClick = { disconnect = false }) { Text("Cancel") } })
    }
}

@Composable
private fun ConnectionForm(token: String, owner: String, back: () -> Unit, save: (String, String) -> Unit) {
    var draftToken by remember { mutableStateOf(token) }
    var draftOwner by remember { mutableStateOf(owner) }
    var visible by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    val validToken = draftToken.trim().isNotEmpty()
    val validOwner = TelegramOwnerConfig.normalize(draftOwner).isNotEmpty()
    NumiPage("Connection", back = back, actions = {
        TextButton(onClick = {
            submitted = true
            if (validToken && validOwner) save(draftToken.trim(), TelegramOwnerConfig.normalize(draftOwner))
        }) { Text("Save") }
    }) {
        Column(Modifier.imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(
                value = draftToken, onValueChange = { draftToken = it }, label = { Text("Bot token") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) "Hide" else "Show") } },
                isError = submitted && !validToken, supportingText = if (submitted && !validToken) { { Text("Enter a bot token") } } else null
            )
            OutlinedTextField(
                value = draftOwner, onValueChange = { draftOwner = it.filter(Char::isDigit) }, label = { Text("Owner ID") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                isError = submitted && !validOwner, supportingText = if (submitted && !validOwner) { { Text("Enter your Telegram user ID") } } else null
            )
            TextButton(onClick = { help = true }) { Text("Connection help") }
        }
    }
    if (help) {
        val context = androidx.compose.ui.platform.LocalContext.current
        AlertDialog(onDismissRequest = { help = false }, title = { Text("Connect a bot") },
            text = { Text("Create a bot with BotFather and start its chat. Enter its token and your numeric Telegram user ID.") },
            confirmButton = { TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/BotFather"))) }) { Text("BotFather") } },
            dismissButton = { TextButton(onClick = { help = false }) { Text("Close") } })
    }
}
