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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.wastu.numi.device.DeviceStatusProvider
import net.wastu.numi.queue.OutboxService
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
    val sendSms: Boolean = false
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
    var showTelegramSetup by remember { mutableStateOf(false) }
    var isSavingSettings by remember { mutableStateOf(false) }
    var botToken by remember { mutableStateOf(prefs.getString("BOT_TOKEN", "") ?: "") }
    var allowedUserIds by remember {
        mutableStateOf(parseAllowedUserIds(prefs.getString("ALLOWED_USER_IDS", "") ?: ""))
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
                    ) == PackageManager.PERMISSION_GRANTED
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

    val telegramConfigured = botToken.isNotBlank() && allowedUserIds.isNotEmpty()

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
                            "Bot configured · ${allowedUserIds.size} approved ${if (allowedUserIds.size == 1) "user" else "users"}"
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
                }
            }
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
                val savedIds = allowedUserIds.joinToString(",")
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

private fun parseAllowedUserIds(value: String): List<String> = value
    .split(',')
    .map(String::trim)
    .filter(String::isNotEmpty)
    .distinct()

@Composable
private fun TelegramSetupDialog(
    botToken: String,
    onBotTokenChange: (String) -> Unit,
    allowedUserIds: List<String>,
    onAllowedUserIdsChange: (List<String>) -> Unit,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    var userIdInput by remember { mutableStateOf("") }
    val hasExistingConfig = remember { botToken.isNotBlank() && allowedUserIds.isNotEmpty() }
    val bothBlank = botToken.isBlank() && allowedUserIds.isEmpty()
    val userIdsValid = allowedUserIds.isNotEmpty() && allowedUserIds.all { it.matches(Regex("[0-9]+")) }
    val valuesValid = bothBlank || (botToken.isNotBlank() && userIdsValid)
    val canSave = valuesValid && (!bothBlank || hasExistingConfig) && !isSaving
    val inputIsValid = userIdInput.isNotBlank() && userIdInput.matches(Regex("[0-9]+"))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Telegram bot") },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Add a bot token and approve users who can control Numi. The first user also receives SMS alerts.",
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
                Text("Allowed users", style = MaterialTheme.typography.titleSmall)
                if (allowedUserIds.isEmpty()) {
                    Text(
                        "No users added",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    allowedUserIds.forEachIndexed { index, userId ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Row(
                                Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(userId, style = MaterialTheme.typography.bodyMedium)
                                    if (index == 0) {
                                        Text(
                                            "Receives SMS alerts",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                }
                                TextButton(
                                    onClick = {
                                        onAllowedUserIdsChange(allowedUserIds.filterIndexed { itemIndex, _ -> itemIndex != index })
                                    }
                                ) {
                                    Text("Remove")
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = userIdInput,
                    onValueChange = { userIdInput = it.filter { character -> character in '0'..'9' } },
                    label = { Text("Telegram user ID") },
                    supportingText = { Text("Find the numeric ID in your Telegram profile or bot tools.") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Button(
                    onClick = {
                        if (inputIsValid && userIdInput !in allowedUserIds) {
                            onAllowedUserIdsChange(allowedUserIds + userIdInput)
                        }
                        userIdInput = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = inputIsValid && userIdInput !in allowedUserIds
                ) {
                    Text("Add user")
                }
                if (!valuesValid) {
                    Text(
                        "Enter a bot token and at least one numeric user ID, or clear both to disconnect.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onSave, enabled = canSave) {
                Text(if (isSaving) "Saving…" else if (bothBlank && hasExistingConfig) "Disconnect" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
