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
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class GetContactClient(private val context: Context) {
    companion object {
        private const val GC_PKG = "app.source.getcontact"
        private const val GC_DATA = "/data/data/$GC_PKG"
    }

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            socketTimeoutMillis = 30_000
            requestTimeoutMillis = 35_000
        }
    }
    private val jsonParser = Json { ignoreUnknownKeys = true }

    private sealed class ApiCallResult {
        data class Success(val body: kotlinx.serialization.json.JsonObject) : ApiCallResult()
        data class Failure(val message: String) : ApiCallResult()
    }

    private fun getCredentials(): GetContactCredentials? {
        val file = File(context.filesDir, "providers/getcontact.json")
        if (!file.exists()) return null
        return try {
            jsonParser.decodeFromString<GetContactCredentials>(file.readText())
        } catch (e: Exception) {
            Log.e("GetContactClient", "Could not read GetContact credentials", e)
            null
        }
    }

    fun isAvailable(): Boolean = getCredentials() != null

    /**
     * Force-resets the GetContact app to get a fresh token, then re-extracts.
     * Requires root (su). Non-blocking — runs in a background thread.
     */
    private fun refreshCredentials() {
        Thread {
            try {
                Log.i("GetContactClient", "Running credential auto-refresh...")
                val cmds = listOf(
                    "am force-stop $GC_PKG",
                    "rm -f $GC_DATA/files/datastore/tokens.preferences_pb",
                    "monkey -p $GC_PKG -c android.intent.category.LAUNCHER 1"
                )
                for (cmd in cmds) {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor()
                    Thread.sleep(1500)
                }
                Thread.sleep(4000) // Wait for app to generate new token
                GetContactCredentialExtractor.extractAndSave(context)
                Log.i("GetContactClient", "Credential auto-refresh complete")
            } catch (e: Exception) {
                Log.e("GetContactClient", "Auto-refresh failed", e)
            }
        }.start()
    }

    /**
     * Searches profile metadata, then calls number-detail for the actual tag list.
     * The service authenticates the pre-encryption JSON with HMAC, while the HTTP
     * body is {"data": AES(pre-encryption JSON)}. Do not sign the outer envelope.
     */
    suspend fun performLookup(phoneNumber: String): GetContactResult {
        val credentials = getCredentials() ?: return GetContactResult.Error("No GetContact credentials installed")
        return performLookupWithRetry(phoneNumber, credentials, retried = false)
    }

    private suspend fun performLookupWithRetry(
        phoneNumber: String,
        credentials: GetContactCredentials,
        retried: Boolean
    ): GetContactResult {
        if (credentials.aes_key.isBlank() || credentials.hmac_key.isBlank()) {
            return GetContactResult.Error("Invalid GetContact crypto keys")
        }
        if (credentials.device_token.isBlank() || credentials.device_id.isBlank()) {
            return GetContactResult.Error("Incomplete GetContact device credentials")
        }

        val normalizedPhone = normalizePhone(phoneNumber)
            ?: return GetContactResult.Error("Invalid phone number")
        val countryCode = countryCodeFor(normalizedPhone)

        // Keep field order and compact JSON aligned with the validated client.
        val searchPayload = """{"countryCode":"$countryCode","dn":null,"dualSim":null,"esim":false,"inC":false,"phoneNumber":"$normalizedPhone","source":"searchHistory","token":"${credentials.device_token}"}"""
        val search = callApi("search", searchPayload, credentials)
        val searchBody = when (search) {
            is ApiCallResult.Success -> search.body
            is ApiCallResult.Failure -> {
                // If token was expired and auto-refresh triggered, retry once
                if (!retried && search.message.contains("validation", ignoreCase = true)) {
                    Log.i("GetContactClient", "Retrying after auto-refresh...")
                    kotlinx.coroutines.delay(8_000) // wait for refresh to complete
                    val freshCreds = getCredentials()
                    if (freshCreds != null) {
                        return performLookupWithRetry(phoneNumber, freshCreds, retried = true)
                    }
                }
                return GetContactResult.Error(search.message)
            }
        }

        val searchResult = searchBody["result"]?.jsonObject
            ?: return GetContactResult.Error("GetContact returned no search result")
        val profile = searchResult["profile"]?.jsonObject
        val displayNumber = profile?.string("displayNumber") ?: normalizedPhone
        val displayName = profile?.string("displayName")?.takeIf { it.isNotBlank() }
        val profileImageUrl = profile?.string("profileImage")
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }

        // Search only contains metadata/counts. number-detail is the endpoint that carries tags.
        val detailPayload = """{"countryCode":"$countryCode","source":"details","token":"${credentials.device_token}","phoneNumber":"$normalizedPhone"}"""
        val detail = callApi("number-detail", detailPayload, credentials)
        val detailBody = when (detail) {
            is ApiCallResult.Success -> detail.body
            is ApiCallResult.Failure -> return GetContactResult.Error(detail.message)
        }

        val detailResult = detailBody["result"]?.jsonObject
            ?: return GetContactResult.Error("GetContact returned no detail result")
        val rawTags = detailResult["tags"]?.jsonArray ?: return GetContactResult.Success(
            tags = emptyList(),
            displayNumber = displayNumber,
            displayName = displayName,
            profileImageUrl = profileImageUrl,
            tagCount = 0
        )

        val tags = rawTags.mapNotNull { element ->
            val tagObject = element.jsonObject
            val tagText = tagObject.string("tag")?.trim().orEmpty()
            if (tagText.isBlank()) {
                null
            } else {
                GetContactTag(
                    name = tagText,
                    tag = tagText,
                    category = "",
                    count = tagObject["count"]?.jsonPrimitive?.intOrNull ?: 0
                )
            }
        }

        Log.i("GetContactClient", "GetContact lookup succeeded: tags=${tags.size}")
        return GetContactResult.Success(
            tags = tags,
            displayNumber = displayNumber,
            displayName = displayName,
            profileImageUrl = profileImageUrl,
            tagCount = tags.size
        )
    }

    private suspend fun callApi(
        path: String,
        plainPayload: String,
        credentials: GetContactCredentials
    ): ApiCallResult {
        val encryptedData = try {
            GetContactCrypto.encryptAes(plainPayload, credentials.aes_key)
        } catch (e: Exception) {
            Log.e("GetContactClient", "GetContact encryption failed for $path", e)
            return ApiCallResult.Failure("GetContact encryption failed")
        }
        val requestBody = """{"data":"$encryptedData"}"""
        val timestamp = System.currentTimeMillis().toString()
        val signature = GetContactCrypto.generateSignature(
            "$timestamp-$plainPayload",
            credentials.hmac_key
        )

        return try {
            val response = client.post("https://pbssrv-centralevents.com/v2.8/$path") {
                // The endpoint fingerprints this closely; this is deliberately the validated shape.
                contentType(ContentType.parse("application/json; charset=utf-8"))
                header("User-Agent", "Mozilla/5.0")
                header("X-Os", "android 10")
                header("X-Mobile-Service", "GMS")
                header("X-Store", "PLAYSTORE")
                header("X-App-Version", "8.15.0")
                header("X-Client-Device-Id", credentials.device_id)
                header("X-Lang", "en_US")
                header("X-Token", credentials.device_token)
                header("X-Encrypted", "1")
                header("X-Roaming-Country", "id")
                header("X-Network-Country", "id")
                header("X-Country-Code", "id")
                header("X-Req-Timestamp", timestamp)
                header("X-Req-Signature", signature)
                header("X-Path", "[v2.8, $path]")
                setBody(requestBody)
            }

            val responseText = response.bodyAsText()
            if (response.status.value != 200) {
                val reason = readApiError(responseText) ?: "HTTP ${response.status.value}"
                Log.w("GetContactClient", "GetContact $path rejected: HTTP ${response.status.value}, $reason")
                // Auto-refresh credentials on validation errors
                if (response.status.value == 403 && reason.contains("validation", ignoreCase = true)) {
                    Log.i("GetContactClient", "Token expired — triggering auto-refresh")
                    refreshCredentials()
                }
                return ApiCallResult.Failure(reason)
            }

            val responseBody = decodeResponse(responseText, credentials.aes_key)
                ?: return ApiCallResult.Failure("GetContact returned an unreadable response")
            val apiError = apiError(responseBody)
            if (apiError != null) {
                Log.w("GetContactClient", "GetContact $path returned an API error: $apiError")
                return ApiCallResult.Failure(apiError)
            }
            ApiCallResult.Success(responseBody)
        } catch (e: Exception) {
            Log.e("GetContactClient", "GetContact network failure for $path", e)
            ApiCallResult.Failure("GetContact network error")
        }
    }

    private fun decodeResponse(responseText: String, aesKey: String): kotlinx.serialization.json.JsonObject? {
        return try {
            val outer = jsonParser.parseToJsonElement(responseText).jsonObject
            val encryptedData = outer.string("data") ?: return outer
            val decrypted = GetContactCrypto.decryptAes(encryptedData, aesKey)
            jsonParser.parseToJsonElement(decrypted).jsonObject
        } catch (e: Exception) {
            Log.w("GetContactClient", "Could not decode GetContact response")
            null
        }
    }

    private fun readApiError(responseText: String): String? {
        return try {
            val root = jsonParser.parseToJsonElement(responseText).jsonObject
            apiError(root)
        } catch (_: Exception) {
            null
        }
    }

    private fun apiError(body: kotlinx.serialization.json.JsonObject): String? {
        val meta = body["meta"]?.jsonObject ?: return null
        val status = meta["httpStatusCode"]?.jsonPrimitive?.intOrNull ?: return null
        if (status == 200) return null
        val message = meta.string("errorMessage") ?: "GetContact error"
        val code = meta.string("errorCode")
        return if (code.isNullOrBlank()) "$message (HTTP $status)" else "$message ($code)"
    }

    private fun normalizePhone(raw: String): String? {
        var phone = raw.trim().replace(Regex("[^0-9+]"), "")
        if (phone.startsWith("00")) phone = "+${phone.drop(2)}"
        if (phone.startsWith("0")) phone = "+62${phone.drop(1)}"
        if (!phone.startsWith("+")) phone = "+$phone"
        return phone.takeIf { it.matches(Regex("^\\+[1-9][0-9]{7,14}$")) }
    }

    private fun countryCodeFor(e164: String): String {
        val digits = e164.removePrefix("+")
        return when {
            digits.startsWith("62") -> "62"
            digits.startsWith("63") -> "63"
            digits.startsWith("7") -> "7"
            digits.startsWith("1") -> "1"
            else -> digits.take(1)
        }
    }

    private fun kotlinx.serialization.json.JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.content
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
        val profileImageUrl: String? = null,
        val tagCount: Int = tags.size
    ) : GetContactResult()

    data class Error(val message: String) : GetContactResult()
}

@Serializable
data class GetContactCredentials(
    val aes_key: String,
    val hmac_key: String,
    val device_token: String = "",
    val device_id: String = "",
    val updated_at: Long
)
