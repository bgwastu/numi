package com.bgwastu.numi.network

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONObject

class TelegramSender(private val botToken: String) {

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            socketTimeoutMillis = 30_000
            requestTimeoutMillis = 35_000
        }
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = false
            })
        }
    }

    @Serializable
    private data class TelegramMessageRequest(
        val chat_id: String,
        val text: String,
        val parse_mode: String = "HTML",
        val reply_to_message_id: Int? = null
    )

    suspend fun sendMessage(chatId: String, text: String, replyToMessageId: Int? = null): Boolean =
        sendMessageForId(chatId, text, replyToMessageId) != null

    /** Returns Telegram `message_id` on success, or null on failure. */
    suspend fun sendMessageForId(
        chatId: String,
        text: String,
        replyToMessageId: Int? = null
    ): Int? {
        if (botToken.isBlank()) {
            Log.e("TelegramSender", "Bot token is empty, cannot send")
            return null
        }
        val url = "https://api.telegram.org/bot$botToken/sendMessage"
        return try {
            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(
                    TelegramMessageRequest(
                        chat_id = chatId,
                        text = text,
                        parse_mode = "HTML",
                        reply_to_message_id = replyToMessageId?.takeIf { it > 0 }
                    )
                )
            }
            val raw = response.bodyAsText()
            if (response.status.value != 200) {
                Log.e("TelegramSender", "Failed to send message: ${response.status.value} - $raw")
                return null
            }
            val messageId = JSONObject(raw).optJSONObject("result")?.optInt("message_id")
            if (messageId == null || messageId <= 0) {
                Log.e("TelegramSender", "sendMessage ok but missing message_id: $raw")
                return null
            }
            Log.d("TelegramSender", "Message sent successfully to $chatId id=$messageId")
            messageId
        } catch (e: Exception) {
            Log.e("TelegramSender", "Exception sending telegram message", e)
            null
        }
    }

    fun close() {
        client.close()
    }
}
