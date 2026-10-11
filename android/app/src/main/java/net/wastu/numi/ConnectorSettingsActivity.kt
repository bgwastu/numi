package net.wastu.numi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.wastu.numi.bus.TelegramOwnerConfig
import net.wastu.numi.bus.TelegramPollHealth
import net.wastu.numi.device.DevicePermissionFixer
import net.wastu.numi.device.DeviceStatusProvider
import net.wastu.numi.network.TelegramApi
import net.wastu.numi.network.TelegramApiException
import net.wastu.numi.queue.OutboxService
import net.wastu.numi.utils.SmsRoleManager
import org.json.JSONObject

class ConnectorSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OutboxService.wake(this)
        setContent {
            val dark = isSystemInDarkTheme()
            val colors = if (Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            } else if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) { ConnectorSettings { finish() } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectorSettings(back: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("numi_settings", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(prefs.getBoolean("TELEGRAM_ENABLED", true)) }
    var topics by remember { mutableStateOf(prefs.getBoolean("TELEGRAM_TOPICS", true)) }
    var token by remember { mutableStateOf(prefs.getString("BOT_TOKEN", "").orEmpty()) }
    var owner by remember { mutableStateOf(TelegramOwnerConfig.normalize(prefs.getString("TELEGRAM_OWNER_ID", null))) }
    var setup by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var access by remember { mutableStateOf(PhoneAccess()) }
    var checking by remember { mutableStateOf(false) }
    var connectionResult by remember { mutableStateOf<String?>(null) }
    var topicsAvailable by remember { mutableStateOf(false) }

    suspend fun refresh() {
        access = withContext(Dispatchers.IO) { PhoneAccess.read(context) }
        status = if (enabled) TelegramPollHealth.summary(context, token.isNotBlank() && owner.isNotBlank()) else "Telegram connection paused"
        topicsAvailable = prefs.getBoolean("telegram_topics_available", false)
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { scope.launch { refresh(); OutboxService.wake(context) } }
    val smsRole = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { scope.launch { refresh(); OutboxService.wake(context) } }
    LaunchedEffect(Unit) { while (true) { refresh(); delay(2000) } }

    Scaffold(topBar = { TopAppBar(title = { Text("Telegram & device") }, navigationIcon = { TextButton(onClick = back) { Text("Back") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row {
                        Column(Modifier.weight(1f)) {
                            Text("Telegram connector", style = MaterialTheme.typography.titleMedium)
                            Text(status, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(enabled = token.isNotBlank() && owner.isNotBlank(), checked = enabled && token.isNotBlank() && owner.isNotBlank(),
                            onCheckedChange = { enabled = it; prefs.edit().putBoolean("TELEGRAM_ENABLED", it).apply(); OutboxService.wake(context) })
                    }
                    Text("Your inbox and dialer work on this phone. Telegram lets your approved account reply to SMS and manage active calls.")
                    Button(onClick = { setup = true }) { Text(if (token.isBlank()) "Connect bot" else "Edit connection") }
                    if (token.isNotBlank()) {
                        TextButton(enabled = !checking, onClick = {
                            checking = true
                            scope.launch {
                                val api = TelegramApi(token)
                                try {
                                    val bot = api.request("getMe") as JSONObject
                                    topicsAvailable = bot.optBoolean("has_topics_enabled")
                                    prefs.edit().putBoolean("telegram_topics_available", topicsAvailable).apply()
                                    connectionResult = "Connected to @${bot.optString("username")}."
                                } catch (exception: Exception) {
                                    connectionResult = if (exception is TelegramApiException) "Telegram API error ${exception.code}. Check your token." else "Could not reach Telegram."
                                } finally { api.close(); checking = false }
                            }
                        }) { Text(if (checking) "Checking…" else "Check connection") }
                        connectionResult?.let { Text(it) }
                        Row {
                            Column(Modifier.weight(1f)) {
                                Text("Conversation topics", style = MaterialTheme.typography.titleSmall)
                                Text(if (topicsAvailable) "One topic per sender and SIM." else "Enable Topics in your bot's BotFather settings. SMS replies also work in a normal chat.",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = topics, onCheckedChange = { topics = it; prefs.edit().putBoolean("TELEGRAM_TOPICS", it).apply() })
                        }
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text("Phone access", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                    AccessRow("Default SMS app", if (access.smsDefault) "Numi receives and stores SMS and MMS" else "Choose Numi to receive SMS and MMS",
                        if (access.smsDefault) null else "Set default") { SmsRoleManager.createRequestDefaultSmsIntent(context)?.let(smsRole::launch) }
                    AccessRow("Default phone app", if (access.phoneDefault) "Numi handles incoming and ongoing calls" else "Choose Numi for the dialer, call history and Telegram alerts",
                        if (access.phoneDefault) null else "Set up") { context.startActivity(Intent(context, org.fossify.phone.activities.SplashActivity::class.java)) }
                    AccessRow("SIMs and SMS", access.sims, if (access.smsPermissions) null else "Allow") {
                        permissions.launch(arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS))
                    }
                    AccessRow("Notifications", if (access.notifications) "SMS and call notifications enabled" else "Allow notifications for incoming SMS and calls", "Manage") {
                        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                        } else DevicePermissionFixer.openNotificationSettings(context)
                    }
                    AccessRow("Background connection", if (access.batteryUnrestricted) "Battery use is unrestricted" else "Battery optimization may pause Telegram",
                        if (access.batteryUnrestricted) null else "Allow") { DevicePermissionFixer.openBatteryOptimizationRequest(context) }
                    AccessRow("Startup after reboot", if (access.startup) "Enabled" else "Disabled", if (access.startup) null else "Enable") {
                        DevicePermissionFixer.ensureStartupReceiverEnabled(context)
                    }
                }
            }
            TextButton(onClick = { context.startActivity(Intent(context, net.wastu.numi.spam.SpamActivity::class.java)) }) { Text("SMS filtering & Spam folder") }
            Text("SMS and call settings, blocking, backups, and appearance are available in Messages and Calls.", style = MaterialTheme.typography.bodySmall)
        }
    }

    if (setup) {
        var draftToken by remember { mutableStateOf(token) }
        var draftOwner by remember { mutableStateOf(owner) }
        val clear = draftToken.isBlank() && draftOwner.isBlank()
        val valid = clear || (draftToken.isNotBlank() && TelegramOwnerConfig.normalize(draftOwner).isNotBlank())
        AlertDialog(onDismissRequest = { setup = false }, title = { Text("Telegram connection") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Create a bot with BotFather, start its private chat, and enter the user ID allowed to control this phone.")
                OutlinedTextField(value = draftToken, onValueChange = { draftToken = it }, label = { Text("Bot token") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                OutlinedTextField(value = draftOwner, onValueChange = { draftOwner = it.filter(Char::isDigit) }, label = { Text("Your Telegram user ID") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        }, confirmButton = {
            Button(enabled = valid, onClick = {
                token = draftToken.trim(); owner = TelegramOwnerConfig.normalize(draftOwner); enabled = !clear
                prefs.edit().putString("BOT_TOKEN", token).putString("TELEGRAM_OWNER_ID", owner).putBoolean("TELEGRAM_ENABLED", enabled).apply()
                connectionResult = null; setup = false; OutboxService.wake(context)
            }) { Text(if (clear) "Disconnect" else "Save") }
        }, dismissButton = { TextButton(onClick = { setup = false }) { Text("Cancel") } })
    }
}

@Composable
private fun AccessRow(title: String, detail: String, action: String?, click: () -> Unit) {
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(detail) }, trailingContent = {
        action?.let { TextButton(onClick = click) { Text(it) } }
    })
}

private data class PhoneAccess(
    val smsDefault: Boolean = false, val phoneDefault: Boolean = false, val smsPermissions: Boolean = false,
    val sims: String = "Checking…", val notifications: Boolean = false, val batteryUnrestricted: Boolean = false, val startup: Boolean = false
) {
    companion object {
        fun read(context: Context) = PhoneAccess(
            SmsRoleManager.isDefaultSmsApp(context), context.getSystemService(TelecomManager::class.java).defaultDialerPackage == context.packageName,
            listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS).all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED },
            DeviceStatusProvider.activeSims(context).joinToString("\n") { "SIM ${it.slotIndex + 1} · ${it.label}" }.ifBlank { "No active SIM detected" },
            DevicePermissionFixer.notificationsVisibleInDrawer(context),
            context.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
            DevicePermissionFixer.startupEnabled(context)
        )
    }
}
