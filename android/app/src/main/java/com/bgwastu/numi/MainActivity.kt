package com.bgwastu.numi

import android.content.Context
import android.content.Intent
import android.app.ActivityManager
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.bgwastu.numi.provider.GetContactCredentialExtractor
import com.bgwastu.numi.queue.OutboxService
import com.bgwastu.numi.util.RootChecker

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Only auto-start if service isn't already running
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val alreadyRunning = am.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == OutboxService::class.java.name }
        if (!alreadyRunning) {
            startForegroundService(Intent(this, OutboxService::class.java))
        }

        // Kick off credential extraction in background
        Thread {
            GetContactCredentialExtractor.extractAndSave(this)
        }.start()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                NumiScreen()
            }
        }
    }
}

@Composable
fun NumiScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = context.getSharedPreferences("numi_settings", Context.MODE_PRIVATE)

    var botToken by remember { mutableStateOf(prefs.getString("BOT_TOKEN", "") ?: "") }
    var targetChatId by remember { mutableStateOf(prefs.getString("TARGET_CHAT_ID", "") ?: "") }
    var hasCredentials by remember { mutableStateOf(GetContactCredentialExtractor.isGetContactInstalled(context)) }
    val credsFile = java.io.File(context.filesDir, "providers/getcontact.json")
    var credsExtracted by remember { mutableStateOf(credsFile.exists()) }

    val savedToken = prefs.getString("BOT_TOKEN", "") ?: ""
    val savedChatId = prefs.getString("TARGET_CHAT_ID", "") ?: ""
    val isDirty = botToken != savedToken || targetChatId != savedChatId

    val pm = context.getSystemService(PowerManager::class.java)
    val isBatteryExempt = pm.isIgnoringBatteryOptimizations(context.packageName)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Header ──
            Text("Numi", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
            Text("SMS gateway · GetContact · Telegram bot",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // ── Live Status ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Status", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    StatusRow("Service", "Running ✓")
                    StatusRow("Bot", if (savedToken.isNotBlank()) "Configured ✓" else "Not configured ✗")
                    StatusRow("GetContact", if (credsExtracted) "Extracted ✓" else if (hasCredentials) "App installed — refresh below" else "Not installed ✗")
                    StatusRow("Root", if (RootChecker.isRootAvailable()) "Available ✓" else "Denied ✗ — check KernelSU")
                    StatusRow("Battery", if (isBatteryExempt) "Unrestricted ✓" else "Optimized — tap to fix") {
                        if (!isBatteryExempt) {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        }
                    }
                }
            }

            // ── Configuration ──
            Text("Configuration", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = botToken, onValueChange = { botToken = it },
                label = { Text("Bot Token") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = { Text("From @BotFather") }
            )

            OutlinedTextField(
                value = targetChatId, onValueChange = { targetChatId = it },
                label = { Text("Target Chat ID") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text("Your Telegram user ID") }
            )

            // Save button only when dirty
            AnimatedVisibility(visible = isDirty) {
                Button(
                    onClick = {
                        prefs.edit()
                            .putString("BOT_TOKEN", botToken)
                            .putString("TARGET_CHAT_ID", targetChatId)
                            .apply()
                        val intent = Intent(context, OutboxService::class.java).apply {
                            putExtra("BOT_TOKEN", botToken)
                            putExtra("TARGET_CHAT_ID", targetChatId)
                        }
                        context.startForegroundService(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = botToken.isNotBlank() && targetChatId.isNotBlank()
                ) { Text("Save & Restart") }
            }

            // ── GetContact Refresh ──
            if (hasCredentials) {
                OutlinedButton(
                    onClick = {
                        Thread {
                            GetContactCredentialExtractor.extractAndSave(context)
                            credsExtracted = credsFile.exists()
                        }.start()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Refresh GetContact Credentials") }
            }

            // ── Commands ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Commands", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    CommandRow("/status", "Device battery, network, SIM status")
                    CommandRow("/check <number>", "Phone number lookup (GetContact + more)")
                    CommandRow("/send <number> <msg>", "Send SMS via device SIM")
                    CommandRow("/refresh", "Refresh GetContact credentials")
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    val color = if (value.contains("✗")) MaterialTheme.colorScheme.error
                else if (value.contains("✓")) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (onClick != null) {
            TextButton(onClick = onClick, contentPadding = PaddingValues(0.dp)) {
                Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = color)
            }
        } else {
            Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = color)
        }
    }
}

@Composable
private fun CommandRow(command: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(command, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.widthIn(min = 140.dp))
        Text(description, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
