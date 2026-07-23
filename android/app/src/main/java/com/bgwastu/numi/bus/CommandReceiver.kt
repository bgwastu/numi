package com.bgwastu.numi.bus

import android.content.Context
import android.util.Log
import com.bgwastu.numi.database.DbHelper
import com.bgwastu.numi.network.TelegramSender
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CommandReceiver(
    private val context: Context,
    private val botToken: String,
    private val replySender: TelegramSender
) {
    private val dbHelper = DbHelper(context)
    private val executor = CommandExecutor(context)
    private val isRunning = AtomicBoolean(false)
    private var pollingThread: Thread? = null
    private var offset = 0L

    // Separate client for long-polling with a high read timeout (> 60s poll timeout)
    private val pollingClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(65, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    init {
        offset = context.getSharedPreferences("numi_settings", Context.MODE_PRIVATE)
            .getLong("telegram_offset", 0L)
    }

    fun start() {
        if (!isRunning.compareAndSet(false, true)) return
        pollingThread = Thread(PollRunnable())
        pollingThread?.start()
    }

    fun stop() {
        isRunning.set(false)
        pollingThread?.interrupt()
    }

    private fun saveOffset() {
        context.getSharedPreferences("numi_settings", Context.MODE_PRIVATE)
            .edit()
            .putLong("telegram_offset", offset)
            .apply()
    }

    private inner class PollRunnable : Runnable {
        private val MIN_RETRY_MS = 1000L
        private val MAX_RETRY_MS = 30000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        override fun run() {
            Log.d("CommandReceiver", "Polling thread started")
            var retryMs = MIN_RETRY_MS
            var firstRequest = true

            while (isRunning.get()) {
                if (botToken.isBlank()) {
                    Log.w("CommandReceiver", "Bot token is empty, waiting...")
                    sleepWithCheck(5000)
                    continue
                }

                try {
                    val url = "https://api.telegram.org/bot$botToken/getUpdates"
                    val bodyObj = JSONObject().apply {
                        put("offset", offset)
                        put("timeout", if (firstRequest) 0 else 60)
                    }
                    val body = bodyObj.toString().toRequestBody(JSON_MEDIA_TYPE)
                    val request = Request.Builder().url(url).post(body).build()

                    pollingClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            Log.e("CommandReceiver", "Poll error HTTP ${response.code}")
                            sleepWithCheck(retryMs)
                            retryMs = (retryMs * 2).coerceAtMost(MAX_RETRY_MS)
                            return@use
                        }

                        val resultStr = response.body?.string() ?: return@use
                        val resultObj = JSONObject(resultStr)
                        if (!resultObj.optBoolean("ok", false)) {
                            Log.e("CommandReceiver", "Poll API returned not ok")
                            sleepWithCheck(retryMs)
                            retryMs = (retryMs * 2).coerceAtMost(MAX_RETRY_MS)
                            return@use
                        }

                        val updates = resultObj.optJSONArray("result") ?: JSONArray()
                        for (i in 0 until updates.length()) {
                            val update = updates.getJSONObject(i)
                            val updateId = update.optLong("update_id", 0)
                            if (updateId > offset) {
                                offset = updateId + 1
                                saveOffset()
                            }
                            if (firstRequest) {
                                Log.d("CommandReceiver", "Drain: update_id=$updateId")
                                continue
                            }
                            handleUpdate(update)
                        }
                        firstRequest = false
                        retryMs = MIN_RETRY_MS
                    }
                } catch (e: IOException) {
                    if (!isRunning.get()) break
                    Log.e("CommandReceiver", "Poll IO error: ${e.message}")
                    sleepWithCheck(retryMs)
                    retryMs = (retryMs * 2).coerceAtMost(MAX_RETRY_MS)
                } catch (e: Exception) {
                    if (!isRunning.get()) break
                    Log.e("CommandReceiver", "Poll unexpected error", e)
                    sleepWithCheck(retryMs)
                    retryMs = (retryMs * 2).coerceAtMost(MAX_RETRY_MS)
                }
            }
            Log.d("CommandReceiver", "Polling thread stopped")
        }

        private fun sleepWithCheck(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        private fun handleUpdate(update: JSONObject) {
            val message = update.optJSONObject("message") ?: return
            val chat = message.optJSONObject("chat") ?: return
            val chatId = chat.optLong("id", 0).toString()
            val text = message.optString("text", "")

            // Numi signed commands
            if (text.startsWith("NUMI_CMD v1")) {
                Log.d("CommandReceiver", "Received Numi cmd from chat $chatId")
                dispatchReply(chatId) { executor.execute(text) }
                return
            }

            // /gc <number> command — GetContact lookup
            val gcMatch = Regex("^/(?:gc|lookup|analyze)\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)
            if (gcMatch != null) {
                val number = gcMatch.groupValues[1].trim()
                Log.d("CommandReceiver", "GC lookup for $number from chat $chatId")
                dispatchReply(chatId) { executor.gcLookup(number) }
                return
            }

            // Plain phone number — auto-detect and lookup
            val phoneClean = text.trim().replace(Regex("[^0-9+]"), "")
            if (phoneClean.length in 8..15 && phoneClean.all { it.isDigit() || it == '+' }) {
                Log.d("CommandReceiver", "Auto GC lookup for $phoneClean from chat $chatId")
                dispatchReply(chatId) { executor.gcLookup(phoneClean) }
            }
        }

        private fun dispatchReply(chatId: String, block: () -> String) {
            Thread {
                try {
                    val result = block()
                    val replyUrl = "https://api.telegram.org/bot$botToken/sendMessage"
                    val replyObj = JSONObject().apply {
                        put("chat_id", chatId)
                        put("text", result)
                        put("parse_mode", "Markdown")
                    }
                    val replyBody = replyObj.toString().toRequestBody(JSON_MEDIA_TYPE)
                    val replyReq = Request.Builder().url(replyUrl).post(replyBody).build()
                    pollingClient.newCall(replyReq).execute()
                } catch (e: Exception) {
                    Log.e("CommandReceiver", "Failed to process command", e)
                }
            }.start()
        }
    }
}
