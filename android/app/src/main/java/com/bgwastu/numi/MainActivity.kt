package com.bgwastu.numi

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.bgwastu.numi.queue.OutboxService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    val savedToken = prefs.getString("BOT_TOKEN", "") ?: ""
    val savedChatId = prefs.getString("TARGET_CHAT_ID", "") ?: ""

    val isDirty = botToken != savedToken || targetChatId != savedChatId
    val isFormValid = botToken.isNotBlank() && targetChatId.isNotBlank()

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
            Text(
                text = "Numi",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "SMS gateway · GetContact · Telegram bot",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ── Status Card ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Status", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    StatusRow("Service", if (savedToken.isNotBlank()) "Configured" else "Not configured")
                    StatusRow("Bot token", if (savedToken.isNotBlank()) "✓ Set" else "✗ Missing")
                    StatusRow("Target chat", if (savedChatId.isNotBlank()) "✓ $savedChatId" else "✗ Missing")
                }
            }

            // ── Configuration ──
            Text("Configuration", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = botToken,
                onValueChange = { botToken = it },
                label = { Text("Bot Token") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = { Text("From @BotFather") }
            )

            OutlinedTextField(
                value = targetChatId,
                onValueChange = { targetChatId = it },
                label = { Text("Target Chat ID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text("Your Telegram user ID") }
            )

            // ── Save button (only when dirty) ──
            AnimatedVisibility(visible = isDirty && isFormValid) {
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
                    enabled = isFormValid
                ) {
                    Text("Save & Restart Service")
                }
            }

            // ── Commands ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
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
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = if (value.startsWith("✗")) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CommandRow(command: String, description: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            command,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.widthIn(min = 130.dp)
        )
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
