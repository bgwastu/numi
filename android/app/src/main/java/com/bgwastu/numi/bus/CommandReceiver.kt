package com.bgwastu.numi.bus

import android.content.Context
import android.util.Log
import com.bgwastu.numi.database.DbHelper
import com.bgwastu.numi.network.TelegramSender
import com.bgwastu.numi.provider.GetContactResult
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
                        put("timeout", 60)
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
                            handleUpdate(update)
                        }
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
                Log.d("CommandReceiver", "GC lookup requested from chat $chatId")
                dispatchGetContactReply(chatId, number)
                return
            }

            // Plain phone number — auto-detect and lookup
            val phoneClean = text.trim().replace(Regex("[^0-9+]"), "")
            if (phoneClean.length in 8..15 && phoneClean.all { it.isDigit() || it == '+' }) {
                Log.d("CommandReceiver", "Auto GC lookup requested from chat $chatId")
                dispatchGetContactReply(chatId, phoneClean)
            }
        }

        private fun dispatchGetContactReply(chatId: String, phoneNumber: String) {
            Thread {
                try {
                    when (val result = executor.gcLookup(phoneNumber)) {
                        is GetContactResult.Error -> {
                            sendText(chatId, "❌ Lookup failed: ${result.message}")
                        }
                        is GetContactResult.Success -> {
                            val profileSummary = buildProfileSummary(result)
                            val imageSent = result.profileImageUrl?.let { imageUrl ->
                                sendPhoto(chatId, imageUrl, profileSummary)
                            } ?: false
                            if (!imageSent) {
                                // A missing or expired image must not suppress the lookup results.
                                sendText(chatId, profileSummary)
                            }

                            if (result.tags.isEmpty()) {
                                sendText(chatId, "📭 No tags found for this number.")
                            } else {
                                val chunks = buildTagChunks(result)
                                for (chunk in chunks) {
                                    if (!sendText(chatId, chunk)) {
                                        Log.e("CommandReceiver", "Stopped tag delivery after a Telegram send failure")
                                        break
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CommandReceiver", "Failed to process GetContact lookup", e)
                    sendText(chatId, "❌ Lookup failed due to an internal error")
                }
            }.start()
        }

        private fun buildProfileSummary(result: GetContactResult.Success): String = buildString {
            appendLine("📞 Number analysis")
            appendLine("Number: ${result.displayNumber}")
            result.displayName?.takeIf { it.isNotBlank() }?.let { appendLine("Name: $it") }
            append("Tags: ${result.tags.size} (full list follows)")
        }

        /** Telegram allows 4096 chars for text. Leave margin for headers and API changes. */
        private fun buildTagChunks(result: GetContactResult.Success): List<String> {
            val maxChars = 3_800
            val headerAllowance = 80
            val maxLinePartChars = maxChars - headerAllowance
            // Keep the originating tag number with each segment. A huge tag is continued,
            // never truncated, so every returned tag remains visible in Telegram.
            val tagLines: List<Pair<Int, String>> = result.tags.flatMapIndexed { index, tag ->
                val name = tag.name.ifBlank { tag.tag }
                    .replace(Regex("[\r\n]+"), " ")
                    .trim()
                val count = if (tag.count > 0) " (${tag.count}x)" else ""
                val line = "${index + 1}. $name$count"
                line.chunked(maxLinePartChars).mapIndexed { partIndex, part ->
                    (index + 1) to if (partIndex == 0) part else "   ↳ $part"
                }
            }

            val chunks = mutableListOf<String>()
            var lineIndex = 0
            while (lineIndex < tagLines.size) {
                val start = tagLines[lineIndex].first
                val body = StringBuilder()
                while (lineIndex < tagLines.size) {
                    val line = tagLines[lineIndex].second
                    val separatorLength = if (body.isEmpty()) 0 else 1
                    if (body.isNotEmpty() && body.length + separatorLength + line.length + headerAllowance > maxChars) {
                        break
                    }
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(line)
                    lineIndex++
                }
                val end = tagLines[lineIndex - 1].first
                chunks += "🏷️ Tags ($start–$end of ${result.tags.size})\n\n$body"
            }
            return chunks
        }

        private fun sendPhoto(chatId: String, photoUrl: String, caption: String): Boolean =
            sendTelegram("sendPhoto", JSONObject().apply {
                put("chat_id", chatId)
                put("photo", photoUrl)
                put("caption", caption.take(1_000))
            })

        private fun sendText(chatId: String, text: String): Boolean =
            sendTelegram("sendMessage", JSONObject().apply {
                put("chat_id", chatId)
                put("text", text.take(4_000))
            })

        private fun sendTelegram(method: String, body: JSONObject): Boolean {
            val request = Request.Builder()
                .url("https://api.telegram.org/bot$botToken/$method")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            return try {
                pollingClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        true
                    } else {
                        Log.e("CommandReceiver", "Telegram $method failed: HTTP ${response.code}")
                        false
                    }
                }
            } catch (e: IOException) {
                Log.e("CommandReceiver", "Telegram $method I/O failure", e)
                false
            }
        }

        private fun dispatchReply(chatId: String, block: () -> String) {
            Thread {
                try {
                    sendText(chatId, block())
                } catch (e: Exception) {
                    Log.e("CommandReceiver", "Failed to process command", e)
                }
            }.start()
        }
    }
}
