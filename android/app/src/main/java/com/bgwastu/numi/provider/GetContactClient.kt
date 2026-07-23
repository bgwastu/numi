package com.bgwastu.numi.provider

import android.content.Context
import android.util.Log
import com.bgwastu.numi.crypto.GetContactCrypto
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class GetContactClient(private val context: Context) {

    private val client = HttpClient(OkHttp)
    private val jsonParser = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class RequestEnvelope(
        val data: String
    )

    @Serializable
    private data class ResponseEnvelope(
        val meta: ResponseMeta,
        val data: String? = null
    )

    @Serializable
    private data class ResponseMeta(
        val requestId: String,
        val httpStatusCode: Int
    )

    // Load extracted credentials from json
    private fun getCredentials(): GetContactCredentials? {
        val file = File(context.filesDir, "providers/getcontact.json")
        if (!file.exists()) {
            Log.e("GetContactClient", "No getcontact.json credentials file found at ${file.absolutePath}")
            return null
        }
        return try {
            val content = file.readText()
            jsonParser.decodeFromString<GetContactCredentials>(content)
        } catch (e: Exception) {
            Log.e("GetContactClient", "Error reading getcontact.json", e)
            null
        }
    }

    suspend fun performLookup(phoneNumber: String): String {
        val creds = getCredentials() ?: return "ERROR_NO_CREDENTIALS"

        val aesKey = creds.aes_key
        val hmacKey = creds.hmac_key
        val deviceToken = creds.device_token
        val deviceId = creds.device_id

        if (aesKey.isBlank() || hmacKey.isBlank()) {
            return "ERROR_INVALID_KEYS"
        }
        if (deviceToken.isBlank()) {
            return "ERROR_NO_TOKEN"
        }
        if (deviceId.isBlank()) {
            return "ERROR_NO_DEVICE_ID"
        }

        // Construct request payload
        val timestamp = System.currentTimeMillis().toString()
        val plainPayload = """{"source":"search","phone":"$phoneNumber"}"""
        
        // Encrypt data
        val encryptedData = try {
            GetContactCrypto.encryptAes(plainPayload, aesKey)
        } catch (e: Exception) {
            Log.e("GetContactClient", "Encryption failed", e)
            return "ERROR_ENCRYPTION_FAILED"
        }

        val requestBody = jsonParser.encodeToString(RequestEnvelope.serializer(), RequestEnvelope(encryptedData))
        
        // Calculate signature: Base64(HMAC-SHA256(timestamp + "-" + requestBody))
        val sigMessage = "$timestamp-$requestBody"
        val signature = GetContactCrypto.generateSignature(sigMessage, hmacKey)

        val url = "https://pb.teknasyon.net/v1/search"
        return try {
            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                header("X-Token", deviceToken)
                header("X-Device-Id", deviceId)
                header("X-Timestamp", timestamp)
                header("X-Signature", signature)
                header("X-Req-Sign", signature) // some versions use both headers
                header("User-Agent", "Getcontact/8.15.0 (Android/34)")
                setBody(requestBody)
            }

            val bodyText = response.bodyAsText()
            if (response.status.value == 200) {
                val envelope = jsonParser.decodeFromString<ResponseEnvelope>(bodyText)
                if (envelope.data != null) {
                    val decrypted = GetContactCrypto.decryptAes(envelope.data, aesKey)
                    Log.d("GetContactClient", "Decrypted search result: $decrypted")
                    decrypted
                } else {
                    Log.e("GetContactClient", "No data payload in response envelope")
                    "ERROR_EMPTY_DATA"
                }
            } else {
                Log.e("GetContactClient", "Request failed with HTTP ${response.status.value}: $bodyText")
                "ERROR_HTTP_${response.status.value}"
            }
        } catch (e: Exception) {
            Log.e("GetContactClient", "Network exception in lookup", e)
            "ERROR_NETWORK_EXCEPTION"
        }
    }
}

@Serializable
data class GetContactCredentials(
    val aes_key: String,
    val hmac_key: String,
    val device_token: String = "",
    val device_id: String = "",
    val updated_at: Long
)
