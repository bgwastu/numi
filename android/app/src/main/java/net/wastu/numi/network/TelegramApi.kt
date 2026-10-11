package net.wastu.numi.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TelegramApiException(val code: Int, val description: String, val retryAfter: Int = 0) :
    IOException(description)

/** Cancelling a poll cancels its HTTP call, including when the bot configuration changes. */
class TelegramApi(private val token: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(70, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS).build()

    suspend fun request(method: String, args: JSONObject = JSONObject()): Any = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url("https://api.telegram.org/bot$token/$method")
            .post(args.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("Telegram connection failed."))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val json = JSONObject(response.body?.string().orEmpty())
                        if (!json.optBoolean("ok")) throw TelegramApiException(
                            json.optInt("error_code", response.code),
                            json.optString("description", "Telegram request failed.").replace(token, "[token]"),
                            json.optJSONObject("parameters")?.optInt("retry_after", 0) ?: 0
                        )
                        if (continuation.isActive) continuation.resume(json.get("result"))
                    } catch (exception: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            if (exception is TelegramApiException) exception else IOException("Telegram returned an invalid response.")
                        )
                    }
                }
            }
        })
    }

    fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}
