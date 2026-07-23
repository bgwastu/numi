package com.bgwastu.numi.provider

import android.content.Context
import android.util.Log
import com.bgwastu.numi.crypto.GetContactCrypto
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class GetContactClient(private val context: Context) {

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            socketTimeoutMillis = 30_000
            requestTimeoutMillis = 35_000
        }
    }
    private val jsonParser = Json { ignoreUnknownKeys = true }

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

    suspend fun performLookup(phoneNumber: String): GetContactResult {
        val creds = getCredentials() ?: return GetContactResult.Error("No credentials found")

        val aesKey = creds.aes_key
        val hmacKey = creds.hmac_key
        val deviceToken = creds.device_token
        val deviceId = creds.device_id

        if (aesKey.isBlank() || hmacKey.isBlank()) return GetContactResult.Error("Invalid crypto keys")
        if (deviceToken.isBlank()) return GetContactResult.Error("No device token")
        if (deviceId.isBlank()) return GetContactResult.Error("No device ID")

        val timestamp = System.currentTimeMillis().toString()
        val plainPayload = """{"countryCode":"62","dn":null,"dualSim":null,"esim":false,"inC":false,"phoneNumber":"$phoneNumber","source":"searchHistory","token":"$deviceToken"}"""

        // Encrypt the full payload
        val encryptedData = try {
            GetContactCrypto.encryptAes(plainPayload, aesKey)
        } catch (e: Exception) {
            Log.e("GetContactClient", "Encryption failed", e)
            return GetContactResult.Error("Encryption failed: ${e.message}")
        }

        val requestBody = """{"data":"$encryptedData"}"""

        // HMAC sign: sign(timestamp + "-" + requestBody)
        val sigMessage = "$timestamp-$requestBody"
        val signature = GetContactCrypto.generateSignature(sigMessage, hmacKey)

        // Build API path: /v2.8/search
        val apiPath = "/v2.8/search"
        val url = "https://pbssrv-centralevents.com$apiPath"

        return try {
            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                header("X-Token", deviceToken)
                header("X-Client-Device-Id", deviceId)
                header("X-Encrypted", "1")
                header("X-Req-Timestamp", timestamp)
                header("X-Req-Signature", signature)
                header("X-Path", "[v2.8, search]")
                header("X-Os", "android 10")
                header("X-Mobile-Service", "GMS")
                header("X-Store", "PLAYSTORE")
                header("X-App-Version", "8.15.0")
                header("X-Lang", "en_US")
                header("X-Country-Code", "id")
                header("X-Roaming-Country", "id")
                header("X-Network-Country", "id")
                header("User-Agent", "Getcontact/8.15.0 (Android/34)")
                setBody(requestBody)
            }

            val bodyText = response.bodyAsText()
            if (response.status.value == 200) {
                parseResponse(bodyText, aesKey)
            } else {
                Log.e("GetContactClient", "API error ${response.status.value}: $bodyText")
                GetContactResult.Error("HTTP ${response.status.value}")
            }
        } catch (e: Exception) {
            Log.e("GetContactClient", "Network exception", e)
            GetContactResult.Error("Network error: ${e.message}")
        }
    }

    private fun parseResponse(bodyText: String, aesKey: String): GetContactResult {
        return try {
            val root = jsonParser.parseToJsonElement(bodyText).jsonObject

            // Check for error
            val meta = root["meta"]?.jsonObject
            val httpStatus = meta?.get("httpStatusCode")?.jsonPrimitive?.intOrNull
            if (httpStatus != null && httpStatus != 200) {
                val errorMsg = meta["errorMessage"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
                return GetContactResult.Error("$errorMsg (code: $httpStatus)")
            }

            // Decrypt data
            val encryptedData = root["data"]?.jsonPrimitive?.contentOrNull
            if (encryptedData == null) {
                return GetContactResult.Error("No data in response")
            }

            val decryptedJson = try {
                GetContactCrypto.decryptAes(encryptedData, aesKey)
            } catch (e: Exception) {
                return GetContactResult.Error("Decryption failed: ${e.message}")
            }

            // Parse decrypted tags
            val decrypted = jsonParser.parseToJsonElement(decryptedJson).jsonObject

            // Extract tags from the response
            val dataObj = decrypted["data"]?.jsonObject
            val items = dataObj?.get("items")?.jsonArray

            if (items != null && items.isNotEmpty()) {
                val tags = mutableListOf<GetContactTag>()
                for (item in items) {
                    val obj = item.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val tag = obj["tag"]?.jsonPrimitive?.contentOrNull ?: ""
                    val category = obj["category"]?.jsonPrimitive?.contentOrNull ?: ""
                    val count = obj["count"]?.jsonPrimitive?.intOrNull ?: 0
                    tags.add(GetContactTag(name, tag, category, count))
                }

                // Profile info
                val profile = dataObj["profile"]?.jsonObject
                val displayNumber = profile?.get("displayNumber")?.jsonPrimitive?.contentOrNull ?: phoneNumber
                val displayName = profile?.get("displayName")?.jsonPrimitive?.contentOrNull

                return GetContactResult.Success(
                    tags = tags,
                    displayNumber = displayNumber,
                    displayName = displayName
                )
            } else {
                // Check for "no result" / profile-only response
                val profile = decrypted["result"]?.jsonObject?.get("profile")?.jsonObject
                val displayNumber = profile?.get("displayNumber")?.jsonPrimitive?.contentOrNull ?: phoneNumber
                val displayName = profile?.get("displayName")?.jsonPrimitive?.contentOrNull
                val tagCount = profile?.get("tagCount")?.jsonPrimitive?.intOrNull ?: 0

                return GetContactResult.Success(
                    tags = emptyList(),
                    displayNumber = displayNumber ?: phoneNumber,
                    displayName = displayName,
                    tagCount = tagCount
                )
            }
        } catch (e: Exception) {
            Log.e("GetContactClient", "Response parse failed", e)
            GetContactResult.Error("Parse error: ${e.message}")
        }
    }
}

data class GetContactTag(
    val name: String,
    val tag: String,
    val category: String,
    val count: Int
)

sealed class GetContactResult {
    data class Success(
        val tags: List<GetContactTag>,
        val displayNumber: String,
        val displayName: String? = null,
        val tagCount: Int = tags.size
    ) : GetContactResult()

    data class Error(val message: String) : GetContactResult()
}
