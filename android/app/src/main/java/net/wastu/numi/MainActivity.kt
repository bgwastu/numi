package net.wastu.numi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import net.wastu.numi.database.DbHelper
import net.wastu.numi.database.IncomingSms
import net.wastu.numi.database.OutboxMessage
import net.wastu.numi.device.DeviceStatusProvider
import net.wastu.numi.device.NetworkMeter
import net.wastu.numi.device.SimInfo
import net.wastu.numi.queue.OutboxService
import net.wastu.numi.utils.SmsRoleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

private enum class GatewayTab(val label: String, val glyph: String) {
    Overview("Overview", "⌂"),
    Messages("Messages", "▤"),
    Sims("SIMs", "▥"),
    Settings("Settings", "⚙")
}

private data class GatewaySnapshot(
    val sims: List<SimInfo> = emptyList(),
    val signalLines: List<net.wastu.numi.device.SimCellularLine> = emptyList(),
    val outbox: List<OutboxMessage> = emptyList(),
    val inbox: List<IncomingSms> = emptyList(),
    val counts: Map<String, Int> = emptyMap(),
    val serviceRunning: Boolean = false,
    val defaultSms: Boolean = false,
    val phonePermission: Boolean = false,
    val sendSmsPermission: Boolean = false,
    val batteryExempt: Boolean = false
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
    val serviceIntent = Intent(context, OutboxService::class.java)
    ContextCompat.startForegroundService(context, serviceIntent)
}

@Composable
private fun NumiGatewayScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember { context.getSharedPreferences("numi_settings", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(GatewayTab.Overview) }
    var snapshot by remember { mutableStateOf(GatewaySnapshot()) }
    var isRefreshing by remember { mutableStateOf(false) }
    var showCompose by remember { mutableStateOf(false) }
    var showInbox by remember { mutableStateOf(false) }
    var defaultSimId by remember { mutableStateOf(prefs.getInt("DEFAULT_SUBSCRIPTION_ID", -1)) }
    var botToken by remember { mutableStateOf(prefs.getString("BOT_TOKEN", "") ?: "") }
    var allowedUserIds by remember { mutableStateOf(prefs.getString("ALLOWED_USER_IDS", "") ?: "") }
    var routeMenuOpen by remember { mutableStateOf(false) }
    var isSavingSettings by remember { mutableStateOf(false) }

    fun refresh() {
        if (isRefreshing) return
        isRefreshing = true
        scope.launch {
            snapshot = withContext(Dispatchers.IO) {
                val pm = context.getSystemService(PowerManager::class.java)
                val phonePermission = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.READ_PHONE_STATE
                ) == PackageManager.PERMISSION_GRANTED
                val meter = NetworkMeter.snapshot(context)
                GatewaySnapshot(
                    sims = DeviceStatusProvider.activeSims(context),
                    signalLines = meter.lines,
                    outbox = DbHelper(context).listOutbox(),
                    inbox = DbHelper(context).listInbox(),
                    counts = DbHelper(context).outboxCounts(),
                    serviceRunning = OutboxService.isServiceRunning(),
                    defaultSms = SmsRoleManager.isDefaultSmsApp(context),
                    phonePermission = phonePermission,
                    sendSmsPermission = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.SEND_SMS
                    ) == PackageManager.PERMISSION_GRANTED,
                    batteryExempt = pm.isIgnoringBatteryOptimizations(context.packageName)
                )
            }
            defaultSimId = prefs.getInt("DEFAULT_SUBSCRIPTION_ID", -1)
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

    val counts = snapshot.counts
    val queued = (counts["PENDING"] ?: 0) + (counts["SENDING"] ?: 0)
    val failed = counts["FAILED"] ?: 0

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            GatewayHeader(snapshot.serviceRunning, isRefreshing)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    GatewayTab.Overview -> OverviewPage(
                        snapshot = snapshot,
                        queued = queued,
                        failed = failed,
                        defaultSimId = defaultSimId,
                        onOpenSims = { tab = GatewayTab.Sims },
                        onOpenMessages = { tab = GatewayTab.Messages },
                        onCompose = { showCompose = true },
                        onRefresh = ::refresh
                    )
                    GatewayTab.Messages -> MessagesPage(
                        outbox = snapshot.outbox,
                        inbox = snapshot.inbox,
                        counts = counts,
                        showInbox = showInbox,
                        onToggleInbox = { showInbox = it },
                        onCompose = { showCompose = true },
                        onRetry = { id ->
                            scope.launch {
                                withContext(Dispatchers.IO) { DbHelper(context).retryMessage(id) }
                                refresh()
                            }
                        },
                        onDelete = { id ->
                            scope.launch {
                                withContext(Dispatchers.IO) { DbHelper(context).deleteMessage(id) }
                                refresh()
                            }
                        },
                        onDeleteIncoming = { id ->
                            scope.launch {
                                withContext(Dispatchers.IO) { DbHelper(context).deleteIncomingSms(id) }
                                refresh()
                            }
                        },
                        onRefresh = ::refresh,
                        sims = snapshot.sims
                    )
                    GatewayTab.Sims -> SimsPage(
                        sims = snapshot.sims,
                        signalLines = snapshot.signalLines,
                        defaultSimId = defaultSimId,
                        onSetDefault = { id ->
                            defaultSimId = id ?: -1
                            prefs.edit().putInt("DEFAULT_SUBSCRIPTION_ID", defaultSimId).apply()
                        },
                        onPermission = {
                            phonePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                        },
                        onSendPermission = {
                            sendPermissionLauncher.launch(Manifest.permission.SEND_SMS)
                        },
                        hasPhonePermission = snapshot.phonePermission,
                        hasSendSmsPermission = snapshot.sendSmsPermission,
                        onRefresh = ::refresh
                    )
                    GatewayTab.Settings -> SettingsPage(
                        snapshot = snapshot,
                        onSendPermission = { sendPermissionLauncher.launch(Manifest.permission.SEND_SMS) },
                        defaultSimId = defaultSimId,
                        onRoleRequest = {
                            SmsRoleManager.createRequestDefaultSmsIntent(context)?.let {
                                smsRoleLauncher.launch(it)
                            }
                        },
                        onPhonePermission = {
                            phonePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                        },
                        onBatterySettings = {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        },
                        onSaveRemote = {
                            isSavingSettings = true
                            prefs.edit().putString("BOT_TOKEN", botToken)
                                .putString("ALLOWED_USER_IDS", allowedUserIds).apply()
                            val intent = Intent(context, OutboxService::class.java).apply {
                                putExtra("BOT_TOKEN", botToken)
                                putExtra("ALLOWED_USER_IDS", allowedUserIds)
                            }
                            ContextCompat.startForegroundService(context, intent)
                            scope.launch {
                                kotlinx.coroutines.delay(300)
                                refresh()
                                isSavingSettings = false
                                Toast.makeText(context, "Gateway settings saved", Toast.LENGTH_SHORT).show()
                            }
                        },
                        botToken = botToken,
                        onBotTokenChange = { botToken = it },
                        allowedUserIds = allowedUserIds,
                        onAllowedUserIdsChange = { allowedUserIds = it },
                        isSaving = isSavingSettings
                    )
                }
            }
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                GatewayTab.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = tab == destination,
                        onClick = { tab = destination },
                        icon = { Text(destination.glyph, fontSize = 19.sp) },
                        label = { Text(destination.label) }
                    )
                }
            }
        }
    }

    if (showCompose) {
        ComposeSmsDialog(
            sims = snapshot.sims,
            defaultSimId = defaultSimId.takeIf { it >= 0 },
            onDismiss = { showCompose = false },
            onSend = { recipient, body, simId ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        DbHelper(context).enqueueMessage(
                            type = "SMS",
                            recipient = recipient,
                            body = body,
                            subscriptionId = simId
                        )
                    }
                    refresh()
                    Toast.makeText(context, "SMS added to outbox", Toast.LENGTH_SHORT).show()
                }
                showCompose = false
                tab = GatewayTab.Messages
            }
        )
    }
}

@Composable
private fun GatewayHeader(running: Boolean, refreshing: Boolean) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text("N", color = MaterialTheme.colorScheme.onPrimary, fontSize = 21.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text("Numi", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("SMS GATEWAY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.5.sp)
        }
        StatusPill(if (running) "ONLINE" else if (refreshing) "STARTING" else "OFFLINE", running)
    }
}

@Composable
private fun OverviewPage(
    snapshot: GatewaySnapshot,
    queued: Int,
    failed: Int,
    defaultSimId: Int,
    onOpenSims: () -> Unit,
    onOpenMessages: () -> Unit,
    onCompose: () -> Unit,
    onRefresh: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Gateway overview", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Your device, SIMs and SMS traffic at a glance.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onRefresh) { Text("Refresh") }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(Modifier.fillMaxWidth().padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).clip(CircleShape).background(if (snapshot.serviceRunning) Color(0xFF163D30) else Color(0xFF482722)), contentAlignment = Alignment.Center) {
                        Text(if (snapshot.serviceRunning) "✓" else "!", color = if (snapshot.serviceRunning) GatewayColors.primary else GatewayColors.error, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (snapshot.serviceRunning) "Gateway is running" else "Gateway is stopped", fontWeight = FontWeight.SemiBold)
                        val selected = snapshot.sims.firstOrNull { it.subscriptionId == defaultSimId }
                        val route = when {
                            selected != null -> "Default route · SIM ${selected.slotIndex + 1} · ${selected.carrier ?: "Carrier unavailable"}"
                            defaultSimId >= 0 -> "Default route · selected SIM unavailable"
                            else -> "Default route · Android system SIM"
                        }
                        Text(
                            route,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text("›", fontSize = 28.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile("QUEUED", queued.toString(), "waiting to send", Modifier.weight(1f), MaterialTheme.colorScheme.secondary)
                MetricTile("SENT", (snapshot.counts["SENT"] ?: 0).toString(), "accepted by Android", Modifier.weight(1f), GatewayColors.primary)
                MetricTile("FAILED", failed.toString(), "needs attention", Modifier.weight(1f), if (failed > 0) GatewayColors.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("SIM cards", "${snapshot.sims.size} active")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onOpenSims) { Text("Manage") }
            }
        }
        if (snapshot.sims.isEmpty()) {
            item { EmptyCard("No active SIM found", "Insert or enable a SIM, then refresh to see its gateway status.") }
        } else {
            items(snapshot.sims.take(2), key = { it.subscriptionId }) { sim ->
                val signal = snapshot.signalLines.firstOrNull { it.slotIndex == sim.slotIndex }
                SimSummaryCard(sim, signal, sim.subscriptionId == defaultSimId)
            }
            if (snapshot.sims.size > 2) {
                item { TextButton(onClick = onOpenSims) { Text("View all ${snapshot.sims.size} SIMs") } }
            }
        }
        item {
            SectionTitle("SMS activity", "Latest gateway traffic")
            Spacer(Modifier.height(9.dp))
            val latest = snapshot.outbox.firstOrNull()
            if (latest == null) EmptyCard("No outgoing messages yet", "Send a message to start using this gateway.")
            else MessageOutboxCard(latest, snapshot.sims, onRetry = null, onDelete = null)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onCompose, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = GatewayColors.primary, contentColor = GatewayColors.onPrimary)) {
                    Text("＋  New SMS", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onOpenMessages, modifier = Modifier.weight(1f)) { Text("Open messages") }
            }
        }
    }
}

@Composable
private fun MessagesPage(
    outbox: List<OutboxMessage>,
    inbox: List<IncomingSms>,
    counts: Map<String, Int>,
    showInbox: Boolean,
    onToggleInbox: (Boolean) -> Unit,
    onCompose: () -> Unit,
    onRetry: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onDeleteIncoming: (Long) -> Unit,
    onRefresh: () -> Unit,
    sims: List<SimInfo>
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 17.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Messages", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Manage queued, sent and received SMS.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = onRefresh) { Text("Refresh") }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip("Outbox", !showInbox, "${outbox.size}") { onToggleInbox(false) }
                FilterChip("Inbox", showInbox, "${inbox.size}") { onToggleInbox(true) }
                Spacer(Modifier.weight(1f))
                Button(onClick = onCompose, contentPadding = PaddingValues(horizontal = 15.dp, vertical = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = GatewayColors.primary, contentColor = GatewayColors.onPrimary)) { Text("＋ SMS") }
            }
            if (!showInbox) {
                Spacer(Modifier.height(10.dp))
                Text("${counts["PENDING"] ?: 0} queued  ·  ${counts["SENT"] ?: 0} accepted  ·  ${counts["FAILED"] ?: 0} failed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (showInbox) {
            if (inbox.isEmpty()) EmptyCard("Inbox is empty", "Incoming SMS received while Numi is the default SMS app will appear here.", Modifier.padding(horizontal = 18.dp))
            else LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(inbox, key = { it.id }) { message ->
                    IncomingSmsCard(message, sims) { onDeleteIncoming(message.id) }
                }
            }
        } else {
            if (outbox.isEmpty()) EmptyCard("Outbox is empty", "Messages sent through the gateway will show up here.", Modifier.padding(horizontal = 18.dp))
            else LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(outbox, key = { it.id }) { message ->
                    MessageOutboxCard(message, sims, { onRetry(message.id) }, { onDelete(message.id) })
                }
            }
        }
    }
}

@Composable
private fun SimsPage(
    sims: List<SimInfo>,
    signalLines: List<net.wastu.numi.device.SimCellularLine>,
    defaultSimId: Int,
    onSetDefault: (Int?) -> Unit,
    onPermission: () -> Unit,
    onSendPermission: () -> Unit,
    hasPhonePermission: Boolean,
    hasSendSmsPermission: Boolean,
    onRefresh: () -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 19.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("SIM management", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Choose the route used for new gateway messages.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = onRefresh) { Text("Refresh") }
            }
        }
        item { Text("DEFAULT ROUTE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.3.sp) }
        item {
            RouteChoiceCard(
                title = "Android system default",
                detail = "Use the SIM selected in your phone’s SMS settings.",
                selected = defaultSimId < 0,
                onClick = { onSetDefault(null) }
            )
        }
        if (!hasPhonePermission) {
            item {
                PermissionCard(
                    title = "Allow phone state access",
                    detail = "Numi needs this to list active subscriptions and route SMS to a specific SIM.",
                    action = "Allow access",
                    onClick = onPermission
                )
            }
        }
        if (!hasSendSmsPermission) {
            item {
                PermissionCard(
                    title = "Allow SMS sending",
                    detail = "Numi needs SMS permission to submit gateway messages.",
                    action = "Allow SMS sending",
                    onClick = onSendPermission
                )
            }
        }
        if (sims.isEmpty()) {
            item { EmptyCard("No active SIM cards", "Check that your SIMs are enabled, then refresh.") }
        } else {
            items(sims, key = { it.subscriptionId }) { sim ->
                val signal = signalLines.firstOrNull { it.slotIndex == sim.slotIndex }
                SimManagementCard(
                    sim = sim,
                    signal = signal,
                    selected = defaultSimId == sim.subscriptionId,
                    onSelect = { onSetDefault(sim.subscriptionId) }
                )
            }
        }
        item {
            InfoNote("SIM routing is stored with each message when it enters the outbox. Changing the default route won’t reroute queued messages.")
        }
    }
}

@Composable
private fun SettingsPage(
    snapshot: GatewaySnapshot,
    onSendPermission: () -> Unit,
    defaultSimId: Int,
    onRoleRequest: () -> Unit,
    onPhonePermission: () -> Unit,
    onBatterySettings: () -> Unit,
    onSaveRemote: () -> Unit,
    botToken: String,
    onBotTokenChange: (String) -> Unit,
    allowedUserIds: String,
    onAllowedUserIdsChange: (String) -> Unit,
    isSaving: Boolean
) {
    val context = LocalContext.current
    val savedToken = context.getSharedPreferences("numi_settings", Context.MODE_PRIVATE).getString("BOT_TOKEN", "") ?: ""
    val savedIds = context.getSharedPreferences("numi_settings", Context.MODE_PRIVATE).getString("ALLOWED_USER_IDS", "") ?: ""
    val dirty = botToken != savedToken || allowedUserIds != savedIds
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            Text("Gateway settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Device access and remote SMS control.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        item {
            SettingsSection("DEVICE READINESS", "SMS gateway") {
                SettingsStatusRow("Gateway service", if (snapshot.serviceRunning) "Running" else "Stopped", snapshot.serviceRunning)
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                SettingsStatusRow("Default SMS app", if (snapshot.defaultSms) "Enabled" else "Required", snapshot.defaultSms)
                if (!snapshot.defaultSms) TextButton(onClick = onRoleRequest) { Text("Set Numi as default SMS app") }
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                SettingsStatusRow("Send SMS access", if (snapshot.sendSmsPermission) "Allowed" else "Needed to send", snapshot.sendSmsPermission)
                if (!snapshot.sendSmsPermission) TextButton(onClick = onSendPermission) { Text("Grant SMS sending") }
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                SettingsStatusRow("Phone state access", if (snapshot.phonePermission) "Allowed" else "Needed for SIM routing", snapshot.phonePermission)
                if (!snapshot.phonePermission) TextButton(onClick = onPhonePermission) { Text("Grant phone access") }
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                SettingsStatusRow("Battery optimization", if (snapshot.batteryExempt) "Unrestricted" else "May pause in background", snapshot.batteryExempt)
                if (!snapshot.batteryExempt) TextButton(onClick = onBatterySettings) { Text("Allow background operation") }
                val selectedSim = snapshot.sims.firstOrNull { it.subscriptionId == defaultSimId }
                val route = when {
                    selectedSim != null -> "SIM ${selectedSim.slotIndex + 1} · ${selectedSim.carrier ?: "carrier"}"
                    defaultSimId >= 0 -> "SIM unavailable · choose a new route"
                    else -> "Android system default"
                }
                Text("Current route: $route", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 5.dp))
            }
        }
        item {
            SettingsSection("REMOTE CONTROL", "Telegram bridge · optional") {
                Text("Use the bot to compose SMS, choose SIM routes, and review incoming messages. Only approved private chats can control Numi. The first ID receives SMS alerts.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(13.dp))
                OutlinedTextField(
                    value = botToken,
                    onValueChange = onBotTokenChange,
                    label = { Text("Bot token") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                Spacer(Modifier.height(9.dp))
                OutlinedTextField(
                    value = allowedUserIds,
                    onValueChange = onAllowedUserIdsChange,
                    label = { Text("Allowed Telegram user IDs") },
                    supportingText = { Text("Comma-separated IDs") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(Modifier.height(7.dp))
                Button(
                    onClick = onSaveRemote,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = dirty && !isSaving && (botToken.isBlank() || allowedUserIds.isNotBlank()),
                    colors = ButtonDefaults.buttonColors(containerColor = GatewayColors.primary, contentColor = GatewayColors.onPrimary)
                ) { Text(if (isSaving) "Saving…" else "Save remote settings") }
                Text("SMS commands: /send <number> <message> · /status · /help", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SettingsSection("ABOUT", "Numi SMS Gateway") {
                Text("Numi keeps a local SMS outbox on this device. A message marked accepted means Android accepted it for sending; carrier delivery receipts are not currently shown.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ComposeSmsDialog(
    sims: List<SimInfo>,
    defaultSimId: Int?,
    onDismiss: () -> Unit,
    onSend: (String, String, Int?) -> Unit
) {
    var recipient by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var chosenSimId by remember { mutableStateOf<Int?>(null) }
    var routeMenuOpen by remember { mutableStateOf(false) }
    val effectiveDefault = defaultSimId?.let { id -> sims.firstOrNull { it.subscriptionId == id } }
    val gatewayDefaultLabel = when {
        effectiveDefault != null -> "Gateway default · SIM ${effectiveDefault.slotIndex + 1} · ${effectiveDefault.carrier ?: "SIM"}"
        defaultSimId != null -> "Gateway default · SIM unavailable"
        else -> "Gateway default · Android system SIM"
    }
    val routeLabel = if (chosenSimId == null) {
        gatewayDefaultLabel
    } else {
        sims.firstOrNull { it.subscriptionId == chosenSimId }?.let { "SIM ${it.slotIndex + 1} · ${it.carrier ?: "Carrier"}" } ?: "Selected SIM unavailable"
    }
    val cleanRecipient = recipient.filter { it.isDigit() || it == '+' }
    val numberValid = recipient.trim().matches(Regex("""\+?[\d\s().-]{3,24}""")) &&
        cleanRecipient.filter { it.isDigit() }.length in 3..15
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New SMS", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                OutlinedTextField(
                    value = recipient,
                    onValueChange = { recipient = it },
                    label = { Text("Phone number") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Message") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 5
                )
                if (recipient.isNotBlank() && !numberValid) {
                    Text("Enter a valid phone number or short code.", style = MaterialTheme.typography.bodySmall, color = GatewayColors.error)
                }
                Box {
                    OutlinedButton(onClick = { routeMenuOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("Route: $routeLabel") }
                    DropdownMenu(expanded = routeMenuOpen, onDismissRequest = { routeMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(gatewayDefaultLabel) },
                            onClick = { chosenSimId = null; routeMenuOpen = false }
                        )
                        sims.forEach { sim ->
                            DropdownMenuItem(
                                text = { Text("SIM ${sim.slotIndex + 1} · ${sim.carrier ?: "Carrier unavailable"}") },
                                onClick = { chosenSimId = sim.subscriptionId; routeMenuOpen = false }
                            )
                        }
                    }
                }
                Text("Long messages are split into SMS parts by Android.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val selected = chosenSimId ?: defaultSimId
                    onSend(cleanRecipient, body.trim(), selected)
                },
                enabled = numberValid && body.trim().isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = GatewayColors.primary, contentColor = GatewayColors.onPrimary)
            ) { Text("Add to outbox") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SimSummaryCard(sim: SimInfo, signal: net.wastu.numi.device.SimCellularLine?, isDefault: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SimGlyph(sim.slotIndex)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("SIM ${sim.slotIndex + 1}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(sim.carrier ?: "Carrier unavailable", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (isDefault) StatusPill("DEFAULT", true)
                else StatusPill(if (signal?.inService == true) "READY" else "NO SERVICE", signal?.inService == true)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Subscription ${sim.subscriptionId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(signal?.let { "${if (it.inService) "In service" else "No service"} · ${it.level?.let { level -> "$level/4" } ?: "signal n/a"}" } ?: "Signal unavailable", style = MaterialTheme.typography.bodySmall, color = if (signal?.inService == true) GatewayColors.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SimManagementCard(sim: SimInfo, signal: net.wastu.numi.device.SimCellularLine?, selected: Boolean, onSelect: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFF172B25) else MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SimGlyph(sim.slotIndex)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("SIM ${sim.slotIndex + 1}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(sim.carrier ?: "Carrier unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                StatusPill(if (signal?.inService == true) "IN SERVICE" else "NO SERVICE", signal?.inService == true)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("SUBSCRIPTION", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp)
                    Text("ID ${sim.subscriptionId}", style = MaterialTheme.typography.bodyMedium)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("SIGNAL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp)
                    Text(signal?.level?.let { "$it / 4" } ?: "Unavailable", style = MaterialTheme.typography.bodyMedium)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            if (selected) {
                Text("Default route selected", color = GatewayColors.primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            } else {
                OutlinedButton(onClick = onSelect, modifier = Modifier.fillMaxWidth()) { Text("Set as default route") }
            }
        }
    }
}

@Composable
private fun MessageOutboxCard(message: OutboxMessage, sims: List<SimInfo>, onRetry: (() -> Unit)?, onDelete: (() -> Unit)?) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(message.recipient, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("${formatTime(message.createdAt)} · ${routeName(message.subscriptionId, sims)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusPill(statusLabel(message.status), message.status == "SENT" || message.status == "PENDING")
            }
            Text(message.body, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
            if (!message.lastError.isNullOrBlank()) Text(message.lastError, style = MaterialTheme.typography.bodySmall, color = GatewayColors.error)
            if (message.status != "SENDING" && (onRetry != null || onDelete != null)) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (message.status == "FAILED" && onRetry != null) OutlinedButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 5.dp)) { Text("Retry") }
                    if (onDelete != null) TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 5.dp)) { Text("Remove", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (message.status == "FAILED" && message.attempts > 0) Text("${message.attempts} attempts", modifier = Modifier.align(Alignment.CenterVertically), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun IncomingSmsCard(message: IncomingSms, sims: List<SimInfo>, onDelete: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(message.sender, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("${formatTime(message.receivedAt)} · ${routeName(message.subscriptionId, sims)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusPill("INCOMING", true)
            }
            Text(message.body, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDelete, contentPadding = PaddingValues(start = 0.dp, top = 0.dp, end = 8.dp, bottom = 0.dp)) {
                Text("Remove from inbox", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MetricTile(label: String, value: String, detail: String, modifier: Modifier, tint: Color) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 13.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = tint, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun StatusPill(label: String, good: Boolean) {
    val color = if (good) GatewayColors.primary else GatewayColors.error
    val background = if (good) Color(0xFF16352B) else Color(0xFF402522)
    Row(Modifier.clip(RoundedCornerShape(50)).background(background).padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
    }
}

@Composable
private fun SimGlyph(slot: Int) {
    Box(Modifier.size(35.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Text("${slot + 1}", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionTitle(title: String, trailing: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(trailing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, count: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick),
        color = if (selected) Color(0xFF1D372E) else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(50)
    ) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = if (selected) GatewayColors.primary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            Text(count, color = if (selected) GatewayColors.primary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun RouteChoiceCard(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Card(modifier = Modifier.clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFF172B25) else MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) Text("✓", color = GatewayColors.primary, fontSize = 20.sp)
        }
    }
}

@Composable
private fun SettingsSection(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(15.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 3.dp, bottom = 12.dp))
            content()
        }
    }
}

@Composable
private fun SettingsStatusRow(label: String, value: String, good: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodySmall, color = if (good) GatewayColors.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PermissionCard(title: String, detail: String, action: String, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(15.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
            TextButton(onClick = onClick, contentPadding = PaddingValues(start = 0.dp, top = 5.dp, end = 0.dp, bottom = 0.dp)) { Text(action) }
        }
    }
}

@Composable
private fun EmptyCard(title: String, detail: String, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InfoNote(text: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(13.dp), verticalAlignment = Alignment.Top) {
        Text("ⓘ", color = MaterialTheme.colorScheme.secondary, fontSize = 17.sp)
        Spacer(Modifier.width(9.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun statusLabel(status: String): String = when (status) {
    "PENDING" -> "QUEUED"
    "SENDING" -> "SENDING"
    "SENT" -> "ACCEPTED"
    "FAILED" -> "FAILED"
    else -> status
}

private fun routeName(subscriptionId: Int?, sims: List<SimInfo>): String =
    subscriptionId?.let { id -> sims.firstOrNull { it.subscriptionId == id }?.let { "SIM ${it.slotIndex + 1}" } ?: "SIM unavailable" }
        ?: "System default SIM"

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(timestamp))
