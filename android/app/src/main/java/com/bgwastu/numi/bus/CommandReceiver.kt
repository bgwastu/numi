package com.bgwastu.numi.bus

import android.content.Context
import android.util.Log
import com.bgwastu.numi.database.DbHelper
import com.bgwastu.numi.bus.CommandError
import com.bgwastu.numi.bus.PendingSmsAction
import com.bgwastu.numi.bus.TelegramActionStore
import com.bgwastu.numi.device.DeviceStatusProvider
import com.bgwastu.numi.device.SimInfo
import com.bgwastu.numi.network.TelegramSender
import com.bgwastu.numi.provider.PhoneLookupResult
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
    private val actionStore = TelegramActionStore(context)
    private val prefs = context.getSharedPreferences("numi_settings", Context.MODE_PRIVATE)

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
        registerCommands()
    }

    private fun registerCommands() {
        Thread {
            try {
                val commands = JSONArray().apply {
                    put(JSONObject().apply { put("command", "status"); put("description", "Device status — battery, network, SIMs") })
                    put(JSONObject().apply { put("command", "check"); put("description", "Phone number lookup (GetContact + more)") })
                    put(JSONObject().apply { put("command", "send"); put("description", "Send SMS via device") })
                    put(JSONObject().apply { put("command", "refresh"); put("description", "Refresh GetContact credentials") })
                }
                val body = JSONObject().apply { put("commands", commands) }
                val request = Request.Builder()
                    .url("https://api.telegram.org/bot$botToken/setMyCommands")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d("CommandReceiver", "Bot commands registered")
                    } else {
                        Log.w("CommandReceiver", "Command registration failed: HTTP ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.w("CommandReceiver", "Command registration error: ${e.message}")
            }
        }.start()
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
                            handleUpdate(update)
                            // Save offset AFTER handling so a crash restarts cleanly
                            if (updateId > offset) {
                                offset = updateId + 1
                                saveOffset()
                            }
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
            try {
                handleUpdateSafe(update)
            } catch (e: Exception) {
                Log.e("CommandReceiver", "Handler crash (offset will advance)", e)
            }
        }

        private fun handleUpdateSafe(update: JSONObject) {
            // -- CALLBACK QUERY (inline button taps) --
            val callback = update.optJSONObject("callback_query")
            if (callback != null) {
                handleCallback(callback)
                return
            }

            val message = update.optJSONObject("message") ?: return
            val chat = message.optJSONObject("chat") ?: return
            val chatId = chat.optLong("id", 0).toString()

            // OWNER GATE — only respond to the configured target chat
            val targetChatId = prefs.getString("TARGET_CHAT_ID", null)
                ?: return  // not configured yet → silent
            if (chatId != targetChatId) return

            val text = message.optString("text", "")

            // -- REPLY-TO-MESSAGE: forward the replied-to text back via SMS --
            val replyTo = message.optJSONObject("reply_to_message")
            if (replyTo != null && text.isNotBlank()) {
                handleReplyForward(chatId, replyTo, text)
                return
            }

            // -- Numi signed commands --
            if (text.startsWith("NUMI_CMD v1")) {
                Log.d("CommandReceiver", "Received Numi cmd from chat $chatId")
                dispatchReply(chatId) { executor.execute(text) }
                return
            }

            // -- /status command --
            if (text.trim().equals("/status", ignoreCase = true)) {
                dispatchTextReply(chatId, DeviceStatusProvider.summary(context))
                return
            }

            // -- /send [number] [message] command --
            val sendMatch = Regex("""^/send\s+(\+?\d{7,15})\s+([\s\S]+)$""", RegexOption.IGNORE_CASE).find(text)
            if (sendMatch != null) {
                handleSendCommand(chatId, sendMatch.groupValues[1].trim(), sendMatch.groupValues[2].trim())
                return
            }

            // -- /refresh command --
            if (text.trim().equals("/refresh", ignoreCase = true)) {
                dispatchTextReply(chatId, executor.execute("""NUMI_CMD v1
actor: owner
action: provider.refresh
args: e30=
"""))
                return
            }

            // -- /check <number> command — phone number lookup --
            val checkMatch = Regex("""^/(?:check|gc|lookup|analyze)\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)
            if (checkMatch != null) {
                val number = checkMatch.groupValues[1].trim()
                dispatchPhoneLookup(chatId, number)
                return
            }

            // -- Plain phone number auto-detect --
            val phoneClean = text.trim().replace(Regex("[^0-9+]"), "")
            if (phoneClean.length in 8..15 && phoneClean.all { it.isDigit() || it == '+' }) {
                dispatchPhoneLookup(chatId, phoneClean)
            }
        }

        private fun handleCallback(callback: JSONObject) {
            val chat = callback.optJSONObject("message")?.optJSONObject("chat") ?: return
            val chatId = chat.optLong("id", 0).toString()
            val targetChatId = prefs.getString("TARGET_CHAT_ID", null) ?: return
            if (chatId != targetChatId) return

            val data = callback.optString("data", "")
            val callbackId = callback.optString("id", "")

            // Answer callback query to stop the spinner
            answerCallback(callbackId)

            when {
                data.startsWith("confirm_sms:") -> {
                    val actionId = data.removePrefix("confirm_sms:")
                    val pending = actionStore.getPending(actionId)
                    if (pending == null) {
                        sendText(chatId, "❌ This confirmation has expired. Please re-issue the command.")
                        return
                    }
                    // Execute the SMS send
                    val smsSender = com.bgwastu.numi.device.SmsSender(context)
                    val result = smsSender.sendSms(
                        recipient = pending.recipient,
                        body = pending.body,
                        subscriptionId = pending.subscriptionId
                    )
                    actionStore.removePending(actionId)
                    when (result) {
                        is com.bgwastu.numi.device.SmsSendResult.Sent -> {
                            val label = pending.simLabel?.let { "via $it" } ?: ""
                            sendText(chatId, "✅ SMS queued to ${pending.recipient} $label".trim())
                        }
                        is com.bgwastu.numi.device.SmsSendResult.PermissionDenied ->
                            sendText(chatId, "❌ SMS permission not granted. Grant SEND_SMS to Numi and try again.")
                        is com.bgwastu.numi.device.SmsSendResult.NoSim ->
                            sendText(chatId, "❌ No active SIM available.")
                        is com.bgwastu.numi.device.SmsSendResult.Error ->
                            sendText(chatId, "❌ Failed to send: ${result.reason}")
                    }
                }
                data.startsWith("cancel_sms:") -> {
                    val actionId = data.removePrefix("cancel_sms:")
                    actionStore.removePending(actionId)
                    sendText(chatId, "❌ SMS cancelled.")
                }
            }
        }

        private fun handleReplyForward(chatId: String, replyTo: JSONObject, text: String) {
            val messageId = replyTo.optInt("message_id", 0)
            if (messageId <= 0) return
            val target = actionStore.getReplyTarget(messageId)
            if (target == null) {
                sendText(chatId, "❌ No SMS reply target found for this message.")
                return
            }
            val pending = PendingSmsAction(
                chatId = chatId,
                recipient = target.phoneNumber,
                body = text.take(1_000),
                subscriptionId = target.subscriptionId,
                simLabel = null
            )
            actionStore.savePending(pending)
            sendConfirmation(chatId, pending)
        }

        private fun handleSendCommand(chatId: String, number: String, body: String) {
            if (body.isBlank()) {
                sendText(chatId, "❌ Message body cannot be empty.")
                return
            }
            val activeSims = com.bgwastu.numi.device.DeviceStatusProvider.activeSims(context)
            val subId = if (activeSims.size == 1) activeSims[0].subscriptionId else null
            val simLabel = if (activeSims.size == 1) activeSims[0].label else null

            val pending = PendingSmsAction(
                chatId = chatId,
                recipient = number,
                body = body.take(1_000),
                subscriptionId = subId,
                simLabel = simLabel
            )
            actionStore.savePending(pending)
            sendConfirmation(chatId, pending)
        }

        private fun sendConfirmation(chatId: String, action: PendingSmsAction) {
            val keyboard = JSONObject().apply {
                put("inline_keyboard", JSONArray().apply {
                    put(JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", "✅ Send")
                            put("callback_data", "confirm_sms:${action.id}")
                        })
                        put(JSONObject().apply {
                            put("text", "❌ Cancel")
                            put("callback_data", "cancel_sms:${action.id}")
                        })
                    })
                })
            }
            val simInfo = action.simLabel?.let { " via $it" } ?: ""
            val body = JSONObject().apply {
                put("chat_id", chatId)
                put("text", "📤 Send SMS to ${action.recipient}$simInfo?\n\n${action.body}")
                put("reply_markup", keyboard)
            }
            sendTelegram("sendMessage", body)
        }

        private fun answerCallback(callbackId: String) {
            val body = JSONObject().apply {
                put("callback_query_id", callbackId)
            }
            sendTelegram("answerCallbackQuery", body)
        }

        private fun dispatchPhoneLookup(chatId: String, phoneNumber: String) {
            Thread {
                try {
                    val results = executor.checkNumber(phoneNumber)
                    if (results.isEmpty()) {
                        sendText(chatId, "❌ No results found for this number (or no providers available).")
                        return@Thread
                    }
                    for (result in results) {
                        val header = buildLookupHeader(result)
                        val imageSent = result.profileImageUrl?.let { url ->
                            sendPhoto(chatId, url, header)
                        } ?: false
                        if (!imageSent) sendText(chatId, header)

                        if (result.tags.isEmpty()) {
                            sendText(chatId, "📭 No tags from ${result.provider}.")
                        } else {
                            val chunks = buildTagChunks(result)
                            for (chunk in chunks) {
                                if (!sendText(chatId, chunk)) break
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CommandReceiver", "Lookup failed", e)
                    sendText(chatId, "❌ Lookup failed due to an internal error")
                }
            }.start()
        }

        private fun buildLookupHeader(result: PhoneLookupResult): String = buildString {
            appendLine("📞 ${result.provider} lookup")
            appendLine("Number: ${result.displayNumber}")
            result.displayName?.takeIf { it.isNotBlank() }?.let { appendLine("Name: $it") }
            append("Tags: ${result.tags.size}")
        }

        private fun buildTagChunks(result: PhoneLookupResult): List<String> {
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

        private fun dispatchTextReply(chatId: String, text: String) {
            dispatchReply(chatId) { text }
        }
    }
}
