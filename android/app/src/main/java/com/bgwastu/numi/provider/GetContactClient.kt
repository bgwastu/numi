package com.bgwastu.numi.provider

import android.content.Context
import android.os.Build
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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class GetContactClient(private val context: Context) {
    companion object {
        private const val GC_PKG = "app.source.getcontact"
        private const val GC_DATA = "/data/data/$GC_PKG"
        private const val MAX_AUTO_CAPTCHA_ROUNDS = 4
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
        data class Failure(
            val message: String,
            val code: String? = null,
            val captchaImageBase64: String? = null
        ) : ApiCallResult()
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

    private val SU_PATHS = arrayOf("/system/bin/su", "/system/xbin/su", "su")

    private fun runRoot(cmd: String): Boolean {
        val quoted = "'" + cmd.replace("'", "'\\''") + "'"
        val nsenterCmd = "nsenter -t 1 -m -- sh -c $quoted"
        for (suPath in SU_PATHS) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf(suPath, "-c", nsenterCmd))
                p.outputStream.close()
                p.waitFor()
                if (p.exitValue() == 0) return true
            } catch (e: Exception) {
                // Try next
            }
        }
        return false
    }

    private fun reqSigHeader(privateKey: String): String {
        val key = privateKey.trim()
        if (key.length < 4) return "--****--"
        return key.take(2) + "****" + key.takeLast(2)
    }

    /** Submits a GetContact text captcha code (POST /v2.8/verify-code). */
    suspend fun verifyCaptcha(validationCode: String): GetContactResult {
        val credentials = getCredentials()
            ?: return GetContactResult.Error("No GetContact credentials installed")
        val code = validationCode.trim()
        if (code.length !in 4..12) {
            return GetContactResult.Error("Captcha code looks invalid")
        }
        val payload =
            """{"validationCode":"$code","token":"${credentials.device_token}"}"""
        return when (val result = callApi("verify-code", payload, credentials)) {
            is ApiCallResult.Success -> GetContactResult.Success(
                tags = emptyList(),
                displayNumber = "",
                displayName = "captcha_ok"
            )
            is ApiCallResult.Failure -> GetContactResult.Error(
                message = result.message,
                captchaImageBase64 = result.captchaImageBase64,
                errorCode = result.code
            )
        }
    }

    /**
     * Searches profile metadata, then calls number-detail for the actual tag list.
     * The service authenticates the pre-encryption JSON with HMAC, while the HTTP
     * body is {"data": AES(pre-encryption JSON)}. Do not sign the outer envelope.
     *
     * When GetContact returns a text captcha (403004), attempts on-device OCR + verify
     * a few times before surfacing the latest captcha image to the caller.
     */
    suspend fun performLookup(phoneNumber: String): GetContactResult {
        val credentials = getCredentials()
            ?: return GetContactResult.Error("No GetContact credentials installed")
        return performLookupWithAutoCaptcha(phoneNumber, credentials)
    }

    private suspend fun performLookupWithAutoCaptcha(
        phoneNumber: String,
        credentials: GetContactCredentials
    ): GetContactResult {
        var result = performLookupInternal(phoneNumber, credentials)
        var rounds = 0
        while (result is GetContactResult.Error && isCaptchaError(result) && rounds < MAX_AUTO_CAPTCHA_ROUNDS) {
            val image = result.captchaImageBase64 ?: break
            val guesses = GcCaptchaOcr.guessCodes(context, image)
            if (guesses.isEmpty()) {
                Log.i("GetContactClient", "Captcha OCR produced no guesses; handing off to user")
                break
            }

            var verified = false
            // One verify burns/rotates the captcha — only attempt the best guess per round.
            val guess = guesses.firstOrNull() ?: break
            Log.i(
                "GetContactClient",
                "Auto-solving captcha with ${guess.code} (${guess.source}, conf=${guess.confidence})"
            )
            when (val verifiedResult = verifyCaptcha(guess.code)) {
                is GetContactResult.Success -> {
                    verified = true
                }
                is GetContactResult.Error -> {
                    if (verifiedResult.captchaImageBase64 != null) {
                        result = verifiedResult
                    } else if (isCaptchaError(verifiedResult)) {
                        result = verifiedResult.copy(captchaImageBase64 = image)
                    } else {
                        return verifiedResult
                    }
                }
            }

            rounds++
            if (!verified) {
                val latest = (result as? GetContactResult.Error)?.captchaImageBase64
                if (latest == null || latest == image) break
                continue
            }

            result = performLookupInternal(phoneNumber, credentials)
        }
        return result
    }

    private fun isCaptchaError(error: GetContactResult.Error): Boolean {
        if (error.errorCode == "403004") return true
        if (error.captchaImageBase64 != null) return true
        return error.message.contains("validation", ignoreCase = true)
    }

    private suspend fun performLookupInternal(
        phoneNumber: String,
        credentials: GetContactCredentials
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
            is ApiCallResult.Failure -> return failureResult(search)
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
            is ApiCallResult.Failure -> return failureResult(detail)
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

    private fun failureResult(failure: ApiCallResult.Failure): GetContactResult.Error {
        // Captcha is handled in Telegram — do not force-open GetContact for that path.
        return GetContactResult.Error(
            message = failure.message,
            captchaImageBase64 = failure.captchaImageBase64,
            errorCode = failure.code
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
        // Sign the pre-encryption JSON (matches GC interceptor order: sign then encrypt).
        val signature = GetContactCrypto.generateSignature(
            "$timestamp-$plainPayload",
            credentials.hmac_key
        )
        val userAgent = System.getProperty("http.agent")
            ?.takeIf { it.isNotBlank() }
            ?: "Dalvik/2.1.0 (Linux; U; Android ${Build.VERSION.RELEASE})"

        return try {
            val response = client.post("https://pbssrv-centralevents.com/v2.8/$path") {
                contentType(ContentType.parse("application/json; charset=utf-8"))
                header("User-Agent", userAgent)
                header("X-Os", "android ${Build.VERSION.RELEASE}")
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
                header("X-Req-Sig", reqSigHeader(credentials.private_key))
                header("X-Path", "[v2.8, $path]")
                setBody(requestBody)
            }

            val responseText = response.bodyAsText()
            val responseBody = decodeResponse(responseText, credentials.aes_key)
            if (response.status.value != 200) {
                val reason = responseBody?.let { apiError(it) }
                    ?: readApiError(responseText)
                    ?: "HTTP ${response.status.value}"
                val code = responseBody?.let { errorCode(it) } ?: readErrorCode(responseText)
                val captcha = responseBody?.let { captchaImage(it) }
                Log.w("GetContactClient", "GetContact $path rejected: HTTP ${response.status.value}, $reason")
                return ApiCallResult.Failure(reason, code, captcha)
            }

            if (responseBody == null) {
                return ApiCallResult.Failure("GetContact returned an unreadable response")
            }
            val apiError = apiError(responseBody)
            if (apiError != null) {
                Log.w("GetContactClient", "GetContact $path returned an API error: $apiError")
                return ApiCallResult.Failure(
                    apiError,
                    errorCode(responseBody),
                    captchaImage(responseBody)
                )
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

    private fun errorCode(body: kotlinx.serialization.json.JsonObject): String? =
        body["meta"]?.jsonObject?.string("errorCode")

    private fun readErrorCode(responseText: String): String? = try {
        jsonParser.parseToJsonElement(responseText).jsonObject
            .let { errorCode(it) }
    } catch (_: Exception) {
        null
    }

    private fun captchaImage(body: kotlinx.serialization.json.JsonObject): String? {
        val image = body["result"]?.jsonObject?.string("image") ?: return null
        return image.takeIf { it.startsWith("/9j/") || it.startsWith("iVBOR") }
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

    data class Error(
        val message: String,
        val captchaImageBase64: String? = null,
        val errorCode: String? = null
    ) : GetContactResult()
}

@Serializable
data class GetContactCredentials(
    val aes_key: String,
    val hmac_key: String,
    val private_key: String = "",
    val device_token: String = "",
    val device_id: String = "",
    val updated_at: Long
)
