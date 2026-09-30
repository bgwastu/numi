package net.wastu.numi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.wastu.numi.device.DeviceStatusProvider
import net.wastu.numi.queue.OutboxService
import net.wastu.numi.utils.SmsRoleManager

private val GatewayColors = darkColorScheme(
    primary = Color(0xFF8CE6BC),
    onPrimary = Color(0xFF082116),
    secondary = Color(0xFF7BD9E7),
    background = Color(0xFF0C1117),
    surface = Color(0xFF141B23),
    surfaceVariant = Color(0xFF1C2731),
    onSurface = Color(0xFFEAF1F4),
    onSurfaceVariant = Color(0xFF9BAAB4),
    error = Color(0xFFFF827A),
    onError = Color(0xFF30100D)
)

private data class GatewaySnapshot(
    val simCount: Int = 0,
    val serviceRunning: Boolean = false,
    val defaultSms: Boolean = false,
    val phonePermission: Boolean = false,
    val sendSmsPermission: Boolean = false
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureGatewayService(this)
        setContent {
            MaterialTheme(colorScheme = GatewayColors) {
                NumiGatewayScreen()
            }
        }
    }
}

private fun ensureGatewayService(context: Context) {
    ContextCompat.startForegroundService(context, Intent(context, OutboxService::class.java))
}

@Composable
private fun NumiGatewayScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember { context.getSharedPreferences("numi_settings", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var snapshot by remember { mutableStateOf(GatewaySnapshot()) }
    var isRefreshing by remember { mutableStateOf(false) }
    var showTelegramSetup by remember { mutableStateOf(false) }
    var isSavingSettings by remember { mutableStateOf(false) }
    var botToken by remember { mutableStateOf(prefs.getString("BOT_TOKEN", "") ?: "") }
    var allowedUserIds by remember { mutableStateOf(prefs.getString("ALLOWED_USER_IDS", "") ?: "") }

    fun refresh() {
        if (isRefreshing) return
        isRefreshing = true
        scope.launch {
            snapshot = withContext(Dispatchers.IO) {
                GatewaySnapshot(
                    simCount = DeviceStatusProvider.activeSims(context).size,
                    serviceRunning = OutboxService.isServiceRunning(),
                    defaultSms = SmsRoleManager.isDefaultSmsApp(context),
                    phonePermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.READ_PHONE_STATE
                    ) == PackageManager.PERMISSION_GRANTED,
                    sendSmsPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.SEND_SMS
                    ) == PackageManager.PERMISSION_GRANTED
                )
            }
            isRefreshing = false
        }
    }

    val smsRoleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refresh() }
    val phonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh() }
    val sendPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh() }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        refresh()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val telegramConfigured = botToken.isNotBlank() && allowedUserIds.isNotBlank()
    val ready = snapshot.serviceRunning && snapshot.defaultSms &&
        snapshot.sendSmsPermission && snapshot.phonePermission && snapshot.simCount > 0 && telegramConfigured

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BoxMark()
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Numi", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "SMS GATEWAY",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.5.sp
                    )
                }
                StatePill(if (ready) "READY" else "SETUP", ready)
            }

            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    "SMS, controlled in Telegram.",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Use your bot to send messages, choose a SIM, and read incoming SMS.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Telegram bot", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (telegramConfigured) "Configured · use /start in Telegram"
                                else "Connect a bot to manage this gateway",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        StatePill(if (telegramConfigured) "ON" else "OFF", telegramConfigured)
                    }
                    Button(
                        onClick = { showTelegramSetup = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GatewayColors.primary,
                            contentColor = GatewayColors.onPrimary
                        )
                    ) {
                        Text(if (telegramConfigured) "Manage bot connection" else "Set up Telegram bot")
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Phone setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    SetupRow(
                        title = "Default SMS app",
                        detail = "Receives incoming SMS",
                        ready = snapshot.defaultSms,
                        action = if (snapshot.defaultSms) null else "Set up",
                        onAction = {
                            SmsRoleManager.createRequestDefaultSmsIntent(context)?.let(smsRoleLauncher::launch)
                        }
                    )
                    SetupRow(
                        title = "SMS permission",
                        detail = "Sends messages from this phone",
                        ready = snapshot.sendSmsPermission,
                        action = if (snapshot.sendSmsPermission) null else "Allow",
                        onAction = { sendPermissionLauncher.launch(Manifest.permission.SEND_SMS) }
                    )
                    SetupRow(
                        title = "SIM access",
                        detail = if (snapshot.phonePermission) {
                            if (snapshot.simCount == 1) "1 active SIM" else "${snapshot.simCount} active SIMs"
                        } else {
                            "Needed to detect and route across SIMs"
                        },
                        ready = snapshot.phonePermission && snapshot.simCount > 0,
                        action = if (snapshot.phonePermission) null else "Allow",
                        onAction = { phonePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE) }
                    )
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(9.dp).clip(CircleShape).background(
                            if (snapshot.serviceRunning) GatewayColors.primary else GatewayColors.error
                        )
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (snapshot.serviceRunning) "Gateway service is running" else "Gateway service is stopped",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (!snapshot.serviceRunning) {
                        TextButton(onClick = {
                            ensureGatewayService(context)
                            refresh()
                        }) { Text("Start") }
                    }
                    TextButton(onClick = { refresh() }, enabled = !isRefreshing) {
                        Text(if (isRefreshing) "…" else "Refresh")
                    }
                }
            }

            Text(
                "Messages, inbox, and SIM routing are managed from your Telegram bot.",
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showTelegramSetup) {
        TelegramSetupDialog(
            botToken = botToken,
            onBotTokenChange = { botToken = it },
            allowedUserIds = allowedUserIds,
            onAllowedUserIdsChange = { allowedUserIds = it },
            isSaving = isSavingSettings,
            onDismiss = { showTelegramSetup = false },
            onSave = {
                isSavingSettings = true
                val savedToken = botToken.trim()
                val savedIds = allowedUserIds.trim()
                prefs.edit()
                    .putString("BOT_TOKEN", savedToken)
                    .putString("ALLOWED_USER_IDS", savedIds)
                    .apply()
                val intent = Intent(context, OutboxService::class.java).apply {
                    putExtra("BOT_TOKEN", savedToken)
                    putExtra("ALLOWED_USER_IDS", savedIds)
                }
                ContextCompat.startForegroundService(context, intent)
                scope.launch {
                    kotlinx.coroutines.delay(300)
                    refresh()
                    isSavingSettings = false
                    showTelegramSetup = false
                    Toast.makeText(context, "Telegram settings saved", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}

@Composable
private fun BoxMark() {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(GatewayColors.primary),
        contentAlignment = Alignment.Center
    ) {
        Text("N", color = GatewayColors.onPrimary, fontSize = 22.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun SetupRow(
    title: String,
    detail: String,
    ready: Boolean,
    action: String?,
    onAction: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(
                if (ready) Color(0xFF16352B) else Color(0xFF402522)
            ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (ready) "✓" else "!",
                color = if (ready) GatewayColors.primary else GatewayColors.error,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun StatePill(label: String, ready: Boolean) {
    val tint = if (ready) GatewayColors.primary else GatewayColors.error
    val background = if (ready) Color(0xFF16352B) else Color(0xFF402522)
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(background).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(tint))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TelegramSetupDialog(
    botToken: String,
    onBotTokenChange: (String) -> Unit,
    allowedUserIds: String,
    onAllowedUserIdsChange: (String) -> Unit,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    val hasExistingConfig = remember { botToken.isNotBlank() && allowedUserIds.isNotBlank() }
    val bothBlank = botToken.isBlank() && allowedUserIds.isBlank()
    val idsValid = allowedUserIds.split(',').all { it.trim().matches(Regex("\\d+")) }
    val valuesValid = bothBlank || (botToken.isNotBlank() && allowedUserIds.isNotBlank() && idsValid)
    val canSave = valuesValid && (!bothBlank || hasExistingConfig) && !isSaving

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Telegram bot", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Create a bot with @BotFather, then add its token and your numeric Telegram user ID. Only approved private chats can control Numi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = botToken,
                    onValueChange = onBotTokenChange,
                    label = { Text("Bot token") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                OutlinedTextField(
                    value = allowedUserIds,
                    onValueChange = onAllowedUserIdsChange,
                    label = { Text("Allowed user IDs") },
                    supportingText = { Text("Comma-separated numbers; first ID receives SMS alerts") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                )
                if (!valuesValid) {
                    Text(
                        "Enter a bot token and numeric user ID, or clear both fields to disconnect.",
                        style = MaterialTheme.typography.bodySmall,
                        color = GatewayColors.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                enabled = canSave,
                colors = ButtonDefaults.buttonColors(
                    containerColor = GatewayColors.primary,
                    contentColor = GatewayColors.onPrimary
                )
            ) {
                Text(if (isSaving) "Saving…" else if (bothBlank && hasExistingConfig) "Disconnect" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
