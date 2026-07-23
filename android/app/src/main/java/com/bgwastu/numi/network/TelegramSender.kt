package com.bgwastu.numi.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import android.util.Log

class TelegramSender(private val botToken: String) {

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                prettyPrint = true
            })
        }
        socketTimeout = 30_000
    }

    @Serializable
    private data class TelegramMessageRequest(
        val chat_id: String,
        val text: String,
        val parse_mode: String? = "Markdown"
    )

    suspend fun sendMessage(chatId: String, text: String): Boolean {
        if (botToken.isBlank()) {
            Log.e("TelegramSender", "Bot token is empty, cannot send")
            return false
        }
        val url = "https://api.telegram.org/bot$botToken/sendMessage"
        return try {
            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(TelegramMessageRequest(chatId, text))
            }
            if (response.status.value == 200) {
                Log.d("TelegramSender", "Message sent successfully to $chatId")
                true
            } else {
                Log.e("TelegramSender", "Failed to send message: ${response.status.value} - ${response.bodyAsText()}")
                false
            }
        } catch (e: Exception) {
            Log.e("TelegramSender", "Exception sending telegram message", e)
            false
        }
    }

    fun close() {
        client.close()
    }
}
