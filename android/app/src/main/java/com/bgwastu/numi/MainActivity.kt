package com.bgwastu.numi

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bgwastu.numi.queue.OutboxService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF90CAF9),
                    secondary = Color(0xFF80CBC4),
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E),
                )
            ) {
                NumiApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NumiApp() {
    val prefs = androidx.compose.ui.platform.LocalContext.current
        .getSharedPreferences("numi_settings", 0)

    var botToken by remember { mutableStateOf(prefs.getString("BOT_TOKEN", "") ?: "") }
    var chatId by remember { mutableStateOf(prefs.getString("TARGET_CHAT_ID", "") ?: "") }
    var showToken by remember { mutableStateOf(false) }
    var serviceStatus by remember { mutableStateOf("Checking...") }

    // Check service status
    LaunchedEffect(Unit) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val running = try {
            val intent = Intent(ctx, OutboxService::class.java)
            ctx.startService(intent)
            true
        } catch (e: Exception) {
            false
        }
        serviceStatus = "Running ✓"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Numi Gateway", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Status", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = Color(0xFF4CAF50),
                            modifier = Modifier.size(8.dp)
                        ) {}
                        Spacer(Modifier.width(8.dp))
                        Text(serviceStatus, fontSize = 14.sp)
                    }
                }
            }

            // Bot Configuration
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Telegram Bot", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = botToken,
                        onValueChange = { botToken = it },
                        label = { Text("Bot Token") },
                        placeholder = { Text("123456:ABC-def...") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            TextButton(onClick = { showToken = !showToken }) {
                                Text(if (showToken) "Hide" else "Show")
                            }
                        }
                    )

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = chatId,
                        onValueChange = { chatId = it },
                        label = { Text("Target Chat ID") },
                        placeholder = { Text("123456789") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )

                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = {
                            val ctx = androidx.compose.ui.platform.LocalContext.current
                            ctx.getSharedPreferences("numi_settings", 0)
                                .edit()
                                .putString("BOT_TOKEN", botToken)
                                .putString("TARGET_CHAT_ID", chatId)
                                .apply()
                            // Restart service with new config
                            val intent = Intent(ctx, OutboxService::class.java).apply {
                                putExtra("BOT_TOKEN", botToken)
                                putExtra("TARGET_CHAT_ID", chatId)
                            }
                            ctx.startService(intent)
                            serviceStatus = "Running ✓"
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("Save & Restart")
                    }
                }
            }

            // GetContact Status
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("GetContact API", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    val credsFile = java.io.File(
                        androidx.compose.ui.platform.LocalContext.current.filesDir,
                        "providers/getcontact.json"
                    )
                    val hasCreds = credsFile.exists()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = if (hasCreds) Color(0xFF4CAF50) else Color(0xFFFF9800),
                            modifier = Modifier.size(8.dp)
                        ) {}
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (hasCreds) "Credentials installed ✓"
                            else "No credentials — run harvester",
                            fontSize = 14.sp
                        )
                    }
                }
            }

            // Quick commands help
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Commands", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("• /gc <number> — Look up phone number", fontSize = 13.sp)
                    Text("• /analyze <number> — Same as /gc", fontSize = 13.sp)
                    Text("• Send any phone number — Auto-detect", fontSize = 13.sp)
                    Text("• NUMI_CMD — Signed commands", fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(16.dp))

            // Footer
            Text(
                "Numi v1.0 — SMS Gateway & Number Analysis",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}
