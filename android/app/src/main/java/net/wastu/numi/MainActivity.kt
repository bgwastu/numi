package net.wastu.numi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.wastu.numi.device.DeviceStatusProvider
import net.wastu.numi.device.DevicePermissionFixer
import net.wastu.numi.bus.TelegramOwnerConfig
import net.wastu.numi.queue.OutboxService
import net.wastu.numi.spam.GemmaDownloadStatus
import net.wastu.numi.spam.GemmaSpamModel
import net.wastu.numi.utils.SmsRoleManager

private val NumiLightColors = lightColorScheme(
    primary = Color(0xFF006C4C),
    onPrimary = Color.White,
    secondary = Color(0xFF4D6357),
    tertiary = Color(0xFF38656A)
)

private val NumiDarkColors = darkColorScheme(
    primary = Color(0xFF75DBAE),
    onPrimary = Color(0xFF003824),
    secondary = Color(0xFFB5CCBC),
    tertiary = Color(0xFFA0CFD3)
)

private data class PhoneAccess(
    val simCount: Int = 0,
    val defaultSms: Boolean = false,
    val readPhoneState: Boolean = false,
    val sendSms: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    val startupEnabled: Boolean = false,
    val notificationsVisible: Boolean = false
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureGatewayService(this)
        setContent {
            NumiTheme {
                NumiGatewayScreen()
            }
        }
    }
}

@Composable
private fun NumiTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> NumiDarkColors
        else -> NumiLightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

private fun ensureGatewayService(context: Context) {
    DevicePermissionFixer.ensureStartupReceiverEnabled(context)
    ContextCompat.startForegroundService(context, Intent(context, OutboxService::class.java))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NumiGatewayScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember { context.getSharedPreferences("numi_settings", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var phoneAccess by remember { mutableStateOf(PhoneAccess()) }
    var gemmaStatus by remember { mutableStateOf(GemmaSpamModel.status(context)) }
    var showTelegramSetup by remember { mutableStateOf(false) }
    var showGemmaDownloadConfirmation by remember { mutableStateOf(false) }
    var isSavingSettings by remember { mutableStateOf(false) }
    var botToken by remember { mutableStateOf(prefs.getString("BOT_TOKEN", "") ?: "") }
    var ownerId by remember {
        mutableStateOf(TelegramOwnerConfig.normalize(prefs.getString("TELEGRAM_OWNER_ID", null)))
    }

    fun refreshPhoneAccess() {
        scope.launch {
            phoneAccess = withContext(Dispatchers.IO) {
                PhoneAccess(
                    simCount = DeviceStatusProvider.activeSims(context).size,
                    defaultSms = SmsRoleManager.isDefaultSmsApp(context),
                    readPhoneState = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.READ_PHONE_STATE
                    ) == PackageManager.PERMISSION_GRANTED,
                    sendSms = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.SEND_SMS
                    ) == PackageManager.PERMISSION_GRANTED,
                    batteryUnrestricted = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M ||
                        context.getSystemService(android.os.PowerManager::class.java)
                            .isIgnoringBatteryOptimizations(context.packageName),
                    startupEnabled = DevicePermissionFixer.startupEnabled(context),
                    notificationsVisible = DevicePermissionFixer.notificationsVisibleInDrawer(context)
                )
            }
        }
    }

    val smsRoleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshPhoneAccess() }
    val phonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPhoneAccess() }
    val sendPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPhoneAccess() }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshPhoneAccess()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        refreshPhoneAccess()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(gemmaStatus.downloadId, gemmaStatus.downloadInProgress) {
        while (gemmaStatus.downloadInProgress) {
            delay(1_000)
            val latestStatus = withContext(Dispatchers.IO) { GemmaSpamModel.status(context) }
            gemmaStatus = latestStatus
        }
    }

    fun setSpamFilterEnabled(enabled: Boolean) {
        if (enabled && !gemmaStatus.modelReady) {
            showGemmaDownloadConfirmation = true
            return
        }
        prefs.edit().putBoolean(GemmaSpamModel.KEY_FILTER_ENABLED, enabled).apply()
        gemmaStatus = gemmaStatus.copy(filterEnabled = enabled)
    }

    val telegramConfigured = botToken.isNotBlank() && ownerId.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NumiIcon(Modifier.size(36.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Numi", style = MaterialTheme.typography.titleLarge)
                    }
                }
            )
        }
    ) { contentPadding ->
        Column(
            Modifier.fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Telegram", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (telegramConfigured) {
                            "Bot configured · one approved owner"
                        } else {
                            "Connect a bot to manage SMS and SIM routing in Telegram."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { showTelegramSetup = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (telegramConfigured) "Manage bot" else "Connect bot")
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Local spam filter", style = MaterialTheme.typography.titleMedium)
                            Text(
                                when {
                                    gemmaStatus.downloadInProgress -> "Downloading Gemma 4 over Wi-Fi${gemmaStatus.progressPercent?.let { " · $it%" } ?: ""}"
                                    gemmaStatus.filterEnabled -> "Gemma 4 checks SMS on this phone."
                                    gemmaStatus.modelReady -> "Gemma 4 is ready to check incoming SMS."
                                    gemmaStatus.downloadFailed -> "Download failed. Tap to try again."
                                    else -> "Optional Gemma 4 model · ${GemmaSpamModel.MODEL_SIZE_LABEL}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = gemmaStatus.filterEnabled,
                            enabled = !gemmaStatus.downloadInProgress,
                            onCheckedChange = ::setSpamFilterEnabled
                        )
                    }
                    if (gemmaStatus.downloadInProgress) {
                        LinearProgressIndicator(
                            progress = { (gemmaStatus.progressPercent ?: 0) / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(
                                onClick = {
                                    gemmaStatus.downloadId?.let { id -> GemmaSpamModel.cancelDownload(context, id) }
                                    gemmaStatus = GemmaDownloadStatus(false, false, null, false, null, false)
                                }
                            ) { Text("Cancel download") }
                        }
                    } else if (!gemmaStatus.modelReady) {
                        Text(
                            "SMS stays in your inbox; likely spam is only skipped in Telegram.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(
                        "Phone access",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleMedium
                    )
                    PhoneAccessRow(
                        title = "Default SMS app",
                        detail = if (phoneAccess.defaultSms) "Receives incoming messages" else "Needed to receive incoming messages",
                        action = if (phoneAccess.defaultSms) null else "Set default",
                        onAction = {
                            SmsRoleManager.createRequestDefaultSmsIntent(context)?.let(smsRoleLauncher::launch)
                        }
                    )
                    PhoneAccessRow(
                        title = "Send SMS",
                        detail = if (phoneAccess.sendSms) "Permission granted" else "Needed to send messages",
                        action = if (phoneAccess.sendSms) null else "Allow",
                        onAction = { sendPermissionLauncher.launch(Manifest.permission.SEND_SMS) }
                    )
                    PhoneAccessRow(
                        title = "SIM access",
                        detail = when {
                            !phoneAccess.readPhoneState -> "Needed to identify and choose a SIM"
                            phoneAccess.simCount == 0 -> "No active SIM detected"
                            phoneAccess.simCount == 1 -> "1 active SIM"
                            else -> "${phoneAccess.simCount} active SIMs"
                        },
                        action = if (phoneAccess.readPhoneState) null else "Allow",
                        onAction = { phonePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE) }
                    )
                    PhoneAccessRow(
                        title = "Startup after reboot",
                        detail = if (phoneAccess.startupEnabled) "Enabled" else "Could not enable startup receiver",
                        action = if (phoneAccess.startupEnabled) null else "Enable",
                        onAction = {
                            DevicePermissionFixer.ensureStartupReceiverEnabled(context)
                            refreshPhoneAccess()
                        }
                    )
                    PhoneAccessRow(
                        title = "Background battery use",
                        detail = if (phoneAccess.batteryUnrestricted) "Unrestricted" else "Optimized · may pause background polling",
                        action = if (phoneAccess.batteryUnrestricted) null else "Allow",
                        onAction = {
                            if (!DevicePermissionFixer.openBatteryOptimizationRequest(context)) {
                                Toast.makeText(context, "Open Android battery settings for Numi", Toast.LENGTH_LONG).show()
                            }
                        }
                    )
                    PhoneAccessRow(
                        title = "Service notification",
                        detail = if (phoneAccess.notificationsVisible) {
                            "Visible in drawer · manage to hide"
                        } else {
                            "Hidden from drawer · still shown in Android Task Manager"
                        },
                        action = "Manage",
                        onAction = {
                            if (!DevicePermissionFixer.openNotificationSettings(context)) {
                                Toast.makeText(context, "Could not open notification settings", Toast.LENGTH_LONG).show()
                            }
                        }
                    )
                }
            }
        }
    }

    if (showGemmaDownloadConfirmation) {
        AlertDialog(
            onDismissRequest = { showGemmaDownloadConfirmation = false },
            title = { Text("Download local spam filter?") },
            text = {
                Text(
                    "Download the Gemma 4 model (${GemmaSpamModel.MODEL_SIZE_LABEL}) over Wi-Fi. SMS is classified on this phone. Likely spam stays in the local inbox and is skipped in Telegram."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showGemmaDownloadConfirmation = false
                        scope.launch {
                            gemmaStatus = withContext(Dispatchers.IO) {
                                GemmaSpamModel.enqueueDownload(context)
                                GemmaSpamModel.status(context)
                            }
                        }
                    }
                ) { Text("Download model") }
            },
            dismissButton = {
                TextButton(onClick = { showGemmaDownloadConfirmation = false }) { Text("Cancel") }
            }
        )
    }

    if (showTelegramSetup) {
        TelegramSetupDialog(
            botToken = botToken,
            onBotTokenChange = { botToken = it },
            ownerId = ownerId,
            onOwnerIdChange = { ownerId = it },
            isSaving = isSavingSettings,
            onDismiss = { showTelegramSetup = false },
            onSave = {
                isSavingSettings = true
                val savedToken = botToken.trim()
                val savedOwnerId = ownerId.trim()
                prefs.edit()
                    .putString("BOT_TOKEN", savedToken)
                    .putString("TELEGRAM_OWNER_ID", savedOwnerId)
                    .remove("ALLOWED_USER_IDS")
                    .remove("TARGET_CHAT_ID")
                    .apply()
                val intent = Intent(context, OutboxService::class.java).apply {
                    putExtra("BOT_TOKEN", savedToken)
                    putExtra("TELEGRAM_OWNER_ID", savedOwnerId)
                }
                ContextCompat.startForegroundService(context, intent)
                scope.launch {
                    kotlinx.coroutines.delay(300)
                    refreshPhoneAccess()
                    isSavingSettings = false
                    showTelegramSetup = false
                    Toast.makeText(context, "Telegram settings saved", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}

@Composable
private fun NumiIcon(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_launcher_foreground),
        contentDescription = null,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF121212)),
        contentScale = ContentScale.Fit
    )
}

@Composable
private fun PhoneAccessRow(
    title: String,
    detail: String,
    action: String?,
    onAction: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        trailingContent = {
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        },
        colors = androidx.compose.material3.ListItemDefaults.colors(
            containerColor = Color.Transparent
        )
    )
}

@Composable
private fun TelegramSetupDialog(
    botToken: String,
    onBotTokenChange: (String) -> Unit,
    ownerId: String,
    onOwnerIdChange: (String) -> Unit,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    val bothBlank = botToken.isBlank() && ownerId.isBlank()
    val ownerIdValid = ownerId.matches(Regex("[0-9]+"))
    val valuesValid = bothBlank || (botToken.isNotBlank() && ownerIdValid)
    val canSave = valuesValid && !isSaving

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Telegram bot") },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Connect a Telegram bot and set the one user ID allowed to manage Numi and receive SMS alerts.",
                    style = MaterialTheme.typography.bodyMedium,
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
                    value = ownerId,
                    onValueChange = { onOwnerIdChange(it.filter { character -> character in '0'..'9' }) },
                    label = { Text("Owner Telegram user ID") },
                    supportingText = { Text("This user can control Numi and receives SMS alerts.") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                if (!valuesValid) {
                    Text(
                        "Enter a bot token and one numeric Telegram user ID, or clear both to disconnect.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onSave, enabled = canSave) {
                Text(if (isSaving) "Saving…" else if (bothBlank) "Disconnect" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
