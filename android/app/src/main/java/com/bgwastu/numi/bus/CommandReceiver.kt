package com.bgwastu.numi.bus

import android.content.Context
import android.util.Log
import com.bgwastu.numi.database.DbHelper
import com.bgwastu.numi.network.TelegramSender
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

class CommandReceiver(
    private val context: Context,
    private val botToken: String,
    private val replySender: TelegramSender
) {
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
    
    private val dbHelper = DbHelper(context)
    private val executor = CommandExecutor(context)
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    
    private var offset = 0L
    private var isPolling = false

    init {
        // Read offset from shared preferences
        val sharedPrefs = context.getSharedPreferences("numi_settings", Context.MODE_PRIVATE)
        offset = sharedPrefs.getLong("telegram_offset", 0L)
    }

    fun start() {
        if (isPolling) return
        isPolling = true
        scope.launch {
            while (isActive && isPolling) {
                if (botToken.isBlank()) {
                    delay(5000)
                    continue
                }
                try {
                    pollTelegramUpdates()
                } catch (e: Exception) {
                    Log.e("CommandReceiver", "Error polling updates", e)
                    delay(5000) // Backoff on error
                }
                delay(2000) // Poll every 2 seconds
            }
        }
    }

    fun stop() {
        isPolling = false
        job.cancel()
        client.close()
    }

    private suspend fun pollTelegramUpdates() {
        val url = "https://api.telegram.org/bot$botToken/getUpdates?offset=$offset&timeout=10"
        val response = client.get(url)
        val bodyText = response.bodyAsText()
        
        if (response.status.value != 200) {
            Log.e("CommandReceiver", "getUpdates returned status: ${response.status.value}")
            return
        }

        val jsonElement = Json.parseToJsonElement(bodyText)
        val ok = jsonElement.jsonObject["ok"]?.jsonPrimitive?.boolean ?: false
        if (!ok) return

        val result = jsonElement.jsonObject["result"]?.jsonArray ?: return
        for (update in result) {
            val updateId = update.jsonObject["update_id"]?.jsonPrimitive?.long ?: continue
            offset = updateId + 1
            
            // Persist offset
            context.getSharedPreferences("numi_settings", Context.MODE_PRIVATE)
                .edit()
                .putLong("telegram_offset", offset)
                .apply()

            val message = update.jsonObject["message"]
            if (message != null) {
                val chatId = message.jsonObject["chat"]?.jsonObject?.get("id")?.jsonPrimitive?.long?.toString() ?: continue
                val text = message.jsonObject["text"]?.jsonPrimitive?.content ?: continue
                
                // If it's a signed Numi command, process it
                if (text.startsWith("NUMI_CMD v1")) {
                    Log.d("CommandReceiver", "Received potential Numi command from chat $chatId")
                    
                    scope.launch {
                        val outcome = executor.execute(text)
                        
                        // Format response message
                        val receipt = """
                            🤖 *Command Receipt*
                            *Status:* `$outcome`
                        """.trimIndent()
                        
                        // Reply to the same chat
                        replySender.sendMessage(chatId, receipt)
                    }
                }
            }
        }
    }
}
