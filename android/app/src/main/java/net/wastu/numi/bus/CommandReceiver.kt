package net.wastu.numi.bus

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import net.wastu.numi.database.DbHelper
import net.wastu.numi.bus.PendingSmsAction
import net.wastu.numi.bus.TelegramActionStore
import net.wastu.numi.device.DevicePermissionFixer
import net.wastu.numi.device.DeviceStatusProvider
import net.wastu.numi.network.TelegramSender
import net.wastu.numi.network.TelegramHtml
import net.wastu.numi.queue.OutboxService
import net.wastu.numi.utils.RecentActivity
import net.wastu.numi.utils.SmsAddress
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

private const val SMS_LIST_PAGE_SIZE = 6

private fun canReplyToSmsAddress(address: String): Boolean {
    if (SmsAddress.normalize(address) == null) return false
    val digits = address.filter { it.isDigit() }
    return digits.length in 3..15 || address.any { it.isLetter() }
}

class CommandReceiver(
    private val context: Context,
    private val botToken: String
) {
    companion object {
        private val pollerLock = ReentrantLock(true)
    }

    private val dbHelper = DbHelper(context)
    private val isRunning = AtomicBoolean(false)
    private var pollingThread: Thread? = null
    @Volatile private var activePoll: Call? = null
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

    private val apiClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
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
                val commands = commandList()
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
                val ownerId = allowedIds().singleOrNull()?.toLongOrNull()
                val scopes = listOf(
                    JSONObject().put("type", "default") to JSONArray(),
                    JSONObject().put("type", "all_private_chats") to JSONArray()
                ) + listOfNotNull(ownerId?.let {
                    JSONObject().put("type", "chat").put("chat_id", it) to commands
                })

                for ((scope, scopedCommands) in scopes) {
                    val body = JSONObject().apply {
                        put("commands", scopedCommands)
                        put("scope", scope)
                    }
                    val request = Request.Builder()
                        .url("https://api.telegram.org/bot$botToken/setMyCommands")
                        .post(body.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    client.newCall(request).execute().use { response ->
                        val label = scope.optString("type")
                        if (response.isSuccessful) {
                            Log.i("CommandReceiver", "Commands registered ($label)")
                        } else {
                            Log.w("CommandReceiver", "Command registration failed ($label): HTTP ${response.code}")
                        }
                    }
                }

                for (chatId in allowedIds().mapNotNull { it.toLongOrNull() }) {
                    val menuBody = JSONObject().apply {
                        put("chat_id", chatId)
                        put("menu_button", JSONObject().put("type", "commands"))
                    }
                    val menuReq = Request.Builder()
                        .url("https://api.telegram.org/bot$botToken/setChatMenuButton")
                        .post(menuBody.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    client.newCall(menuReq).execute().use { response ->
                        Log.i("CommandReceiver", "Menu button setup: HTTP ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.w("CommandReceiver", "Command registration error: ${e.javaClass.simpleName}")
            }
        }.start()
    }

    fun stop() {
        isRunning.set(false)
        activePoll?.cancel()
        pollingThread?.interrupt()
        pollingClient.dispatcher.cancelAll()
        apiClient.dispatcher.cancelAll()
        pollingClient.connectionPool.evictAll()
        apiClient.connectionPool.evictAll()
    }

    private fun commandList() = JSONArray().apply {
        put(JSONObject().put("command", "start").put("description", "Open SMS gateway controls"))
        put(JSONObject().put("command", "status").put("description", "Gateway and device health"))
        put(JSONObject().put("command", "send").put("description", "Compose an SMS"))
        put(JSONObject().put("command", "sims").put("description", "View active SIM cards"))
        put(JSONObject().put("command", "inbox").put("description", "Recent incoming SMS"))
        put(JSONObject().put("command", "outbox").put("description", "Review and manage sent SMS"))
        put(JSONObject().put("command", "help").put("description", "Show gateway help"))
    }

        private fun allowedIds(): Set<String> = setOf(
        TelegramOwnerConfig.normalize(prefs.getString("TELEGRAM_OWNER_ID", null))
    ).filter(String::isNotEmpty).toSet()

    private fun isAllowedPrivateChat(chat: JSONObject, actorId: String): Boolean {
        val chatId = chat.optLong("id", 0L).toString()
        return chat.optString("type") == "private" && actorId == chatId && actorId in allowedIds()
    }

    private fun saveOffset(nextOffset: Long): Boolean =
        context.getSharedPreferences("numi_settings", Context.MODE_PRIVATE)
            .edit()
            .putLong("telegram_offset", nextOffset)
            .commit()

    private inner class PollRunnable : Runnable {
        private val MIN_RETRY_MS = 1000L
        private val MAX_RETRY_MS = 30000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        override fun run() {
            try {
                pollerLock.lockInterruptibly()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            try {
                runPollingLoop()
            } finally {
                pollerLock.unlock()
            }
        }

        private fun runPollingLoop() {
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
                        put("allowed_updates", JSONArray().put("message").put("callback_query"))
                    }
                    val body = bodyObj.toString().toRequestBody(JSON_MEDIA_TYPE)
                    val request = Request.Builder().url(url).post(body).build()
                    val pollCall = pollingClient.newCall(request)
                    activePoll = pollCall
                    try {
                    pollCall.execute().use { response ->
                        val resultStr = response.body?.string().orEmpty()
                        val resultObj = runCatching { JSONObject(resultStr) }.getOrNull()
                        if (!response.isSuccessful) {
                            val error = pollError(response.code, resultObj)
                            TelegramPollHealth.recordError(context, error)
                            Log.e("CommandReceiver", error)
                            sleepWithCheck(retryMs)
                            retryMs = (retryMs * 2).coerceAtMost(MAX_RETRY_MS)
                            return@use
                        }

                        if (resultObj?.optBoolean("ok", false) != true) {
                            val error = pollError(
                                resultObj?.optInt("error_code", response.code) ?: response.code,
                                resultObj
                            )
                            TelegramPollHealth.recordError(context, error)
                            Log.e("CommandReceiver", error)
                            sleepWithCheck(retryMs)
                            retryMs = (retryMs * 2).coerceAtMost(MAX_RETRY_MS)
                            return@use
                        }

                        TelegramPollHealth.recordSuccess(context)
                        val updates = resultObj.optJSONArray("result") ?: JSONArray()
                        var handlerFailed = false
                        for (i in 0 until updates.length()) {
                            val update = updates.getJSONObject(i)
                            val updateId = update.optLong("update_id", 0)
                            if (updateId < offset) continue
                            if (!handleUpdate(update)) {
                                handlerFailed = true
                                TelegramPollHealth.recordError(context, "Update handler failed")
                                break
                            }
                            val nextOffset = updateId + 1
                            if (!saveOffset(nextOffset)) {
                                handlerFailed = true
                                TelegramPollHealth.recordError(context, "Could not save Telegram update")
                                break
                            }
                            offset = nextOffset
                        }
                        if (handlerFailed) {
                            sleepWithCheck(retryMs)
                            retryMs = (retryMs * 2).coerceAtMost(MAX_RETRY_MS)
                        } else {
                            retryMs = MIN_RETRY_MS
                        }
                    }
                    } finally {
                        if (activePoll === pollCall) activePoll = null
                    }
                } catch (e: IOException) {
                    if (!isRunning.get()) break
                    TelegramPollHealth.recordError(context, "Network unavailable · ${e.javaClass.simpleName}")
                    Log.e("CommandReceiver", "Poll IO error: ${e.javaClass.simpleName}")
                    sleepWithCheck(retryMs)
                    retryMs = (retryMs * 2).coerceAtMost(MAX_RETRY_MS)
                } catch (e: Exception) {
                    if (!isRunning.get()) break
                    TelegramPollHealth.recordError(context, "Polling failed · ${e.javaClass.simpleName}")
                    Log.e("CommandReceiver", "Poll unexpected error: ${e.javaClass.simpleName}")
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

        private fun pollError(httpCode: Int, result: JSONObject?): String {
            val errorCode = result?.optInt("error_code", httpCode) ?: httpCode
            val description = result?.optString("description").orEmpty()
            return if (errorCode == 409) {
                "HTTP 409 · polling conflicts with another poller or webhook"
            } else if (errorCode == 401) {
                "HTTP 401 · bot token rejected"
            } else {
                "HTTP $errorCode · ${description.ifBlank { "Telegram API unavailable" }.take(60)}"
            }
        }

        private fun handleUpdate(update: JSONObject): Boolean {
            try {
                handleUpdateSafe(update)
                return true
            } catch (e: Exception) {
                Log.e("CommandReceiver", "Update handler failed; update will be retried", e)
                return false
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
            val actorId = message.optJSONObject("from")?.optLong("id", 0L)?.toString().orEmpty()
            if (!isAllowedPrivateChat(chat, actorId)) return

            val text = message.optString("text", "")
            if (text.isBlank()) {
                sendText(chatId, "Use the buttons for SMS controls, or send text to compose an SMS.")
                return
            }

            val command = text.trim().substringBefore('@').substringBefore(' ')
            when {
                command.equals("/status", ignoreCase = true) -> Unit
                text.startsWith("/") -> RecentActivity.record(context, command.take(40))
                else -> RecentActivity.record(context, "Telegram reply")
            }

            // -- REPLY-TO-MESSAGE --
            val replyTo = message.optJSONObject("reply_to_message")
            if (replyTo != null && text.isNotBlank()) {
                val repliedMessageId = replyTo.optInt("message_id", 0)
                if (actionStore.takeComposePrompt(repliedMessageId)) {
                    handleComposeReply(chatId, text)
                } else {
                    handleReplyForward(chatId, replyTo, text)
                }
                return
            }

            if (command.equals("/start", ignoreCase = true)) {
                sendHome(chatId)
                return
            }

            if (command.equals("/help", ignoreCase = true)) {
                sendHelp(chatId)
                return
            }

            // -- /status command --
            if (command.equals("/status", ignoreCase = true)) {
                runBusy(chatId) { sendStatus(chatId) }
                return
            }

            if (command.equals("/sims", ignoreCase = true)) {
                sendSims(chatId)
                return
            }

            if (command.equals("/inbox", ignoreCase = true)) {
                sendInbox(chatId)
                return
            }

            if (command.equals("/outbox", ignoreCase = true)) {
                sendOutbox(chatId)
                return
            }

            // -- /send [number] [message] command --
            // Allow short codes (e.g. 3636) as well as E.164 numbers.
            if (command.equals("/send", ignoreCase = true)) {
                val sendMatch = Regex("""^/send(?:@\S+)?\s+(\+?\d{3,15})\s+([\s\S]+)$""", RegexOption.IGNORE_CASE).find(text)
                if (sendMatch != null) {
                    handleSendCommand(chatId, sendMatch.groupValues[1].trim(), sendMatch.groupValues[2].trim())
                } else {
                    sendComposerPrompt(chatId)
                }
                return
            }

            // Unrecognized text from the owner — reply so it doesn't feel dead.
            if (text.startsWith("/")) {
                sendText(
                    chatId,
                    "Unrecognized command. Try /help for the list."
                )
            } else {
                sendText(
                    chatId,
                    "Use /send <number> <message> to send an SMS, or /help for commands."
                )
            }
        }

        private fun handleCallback(callback: JSONObject) {
            val callbackId = callback.optString("id", "")
            val message = callback.optJSONObject("message")
            val chat = message?.optJSONObject("chat")
            val chatId = chat?.optLong("id", 0L)?.toString().orEmpty()
            val actorId = callback.optJSONObject("from")?.optLong("id", 0L)?.toString().orEmpty()
            if (message == null || chat == null) {
                answerCallback(callbackId)
                return
            }
            if (!isAllowedPrivateChat(chat, actorId)) {
                answerCallback(callbackId, "This Numi control is limited to approved private chats.")
                return
            }

            // Always stop Telegram's button spinner before doing any work.
            answerCallback(callbackId)

            val data = callback.optString("data", "")
            val confirmMessageId = message.optInt("message_id", 0).takeIf { it > 0 }

            callbackActivity(data)?.let { RecentActivity.record(context, it) }

            when {
                data == "menu:status" -> runBusy(chatId) { sendStatus(chatId, confirmMessageId) }
                data == "menu:compose" -> sendComposerPrompt(chatId)
                data == "menu:sims" -> sendSims(chatId)
                data == "menu:inbox" -> sendInbox(chatId, editMessageId = confirmMessageId)
                data == "menu:outbox" -> sendOutbox(chatId, editMessageId = confirmMessageId)
                data == "menu:help" -> sendHelp(chatId, confirmMessageId)
                data == "menu:home" -> sendHome(chatId, confirmMessageId)
                data.startsWith("inbox:page:") -> {
                    val page = data.removePrefix("inbox:page:").toIntOrNull() ?: 0
                    sendInbox(chatId, page, confirmMessageId)
                }
                data.startsWith("inbox:open:") -> {
                    val parts = data.removePrefix("inbox:open:").split(":", limit = 2)
                    val rowId = parts.firstOrNull()?.toLongOrNull()
                    val page = parts.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                    if (rowId == null || dbHelper.getIncomingSms(rowId) == null) {
                        sendInbox(chatId, page, confirmMessageId)
                    } else {
                        sendConversation(chatId, true, rowId, page, editMessageId = confirmMessageId)
                    }
                }
                data.startsWith("inbox:move:") -> {
                    val parts = data.removePrefix("inbox:move:").split(":", limit = 4)
                    val kind = parts.getOrNull(0)
                    val rowId = parts.getOrNull(1)?.toLongOrNull()
                    val direction = parts.getOrNull(2)
                    val page = parts.getOrNull(3)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                    if ((kind != "in" && kind != "out") || rowId == null || direction == null) {
                        sendInbox(chatId, page, confirmMessageId)
                    } else {
                        moveConversation(chatId, kind == "in", rowId, direction, page, confirmMessageId)
                    }
                }
                data.startsWith("inbox:text:") -> {
                    val parts = data.removePrefix("inbox:text:").split(":", limit = 4)
                    val kind = parts.getOrNull(0)
                    val rowId = parts.getOrNull(1)?.toLongOrNull()
                    val offset = parts.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                    val page = parts.getOrNull(3)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                    if ((kind != "in" && kind != "out") || rowId == null) {
                        sendInbox(chatId, page, confirmMessageId)
                    } else {
                        sendConversation(chatId, kind == "in", rowId, page, offset, confirmMessageId)
                    }
                }
                data.startsWith("inbox:reply:") -> {
                    val parts = data.removePrefix("inbox:reply:").split(":", limit = 2)
                    val kind = parts.firstOrNull()
                    val rowId = parts.getOrNull(1)?.toLongOrNull()
                    val messages = if ((kind == "in" || kind == "out") && rowId != null) {
                        dbHelper.conversationMessagesForAnchor(kind == "in", rowId)
                    } else emptyList()
                    val selected = messages.firstOrNull { it.id == rowId && it.isIncoming }
                        ?: messages.lastOrNull { it.isIncoming }
                    if (selected == null || !canReplyToSmsAddress(selected.address)) {
                        editOrSend(chatId, confirmMessageId, "No received SMS is available to reply to in this conversation.")
                    } else {
                        sendReplyPrompt(
                            chatId,
                            confirmMessageId,
                            ReplyTarget(selected.address, selected.subscriptionId, "inbox")
                        )
                    }
                }
                data.startsWith("inbox:delete:") -> {
                    val parts = data.removePrefix("inbox:delete:").split(":", limit = 2)
                    val rowId = parts.firstOrNull()?.toLongOrNull()
                    val page = parts.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                    if (rowId == null || dbHelper.deleteIncomingSms(rowId) == 0) {
                        editOrSend(chatId, confirmMessageId, "That incoming SMS is no longer available.")
                    } else {
                        sendInbox(chatId, page, confirmMessageId)
                    }
                }
                data.startsWith("outbox:page:") -> {
                    val page = data.removePrefix("outbox:page:").toIntOrNull() ?: 0
                    sendOutbox(chatId, page, confirmMessageId)
                }
                data.startsWith("retry_outbox:") -> {
                    val parts = data.removePrefix("retry_outbox:").split(":", limit = 2)
                    val rowId = parts.firstOrNull()?.toLongOrNull()
                    val page = parts.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                    if (rowId == null || dbHelper.retryMessage(rowId) == 0) {
                        editOrSend(chatId, confirmMessageId, "That failed message is no longer available to retry.")
                    } else {
                        sendOutbox(chatId, page, confirmMessageId)
                    }
                }
                data.startsWith("delete_outbox:") -> {
                    val parts = data.removePrefix("delete_outbox:").split(":", limit = 2)
                    val rowId = parts.firstOrNull()?.toLongOrNull()
                    val page = parts.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                    if (rowId == null || dbHelper.deleteMessage(rowId) == 0) {
                        editOrSend(chatId, confirmMessageId, "That message can no longer be removed.")
                    } else {
                        sendOutbox(chatId, page, confirmMessageId)
                    }
                }
                data == "reply_sms" -> {
                    val target = confirmMessageId?.let(actionStore::getReplyTarget)
                    if (target == null) {
                        sendText(chatId, "This SMS reply action has expired.")
                    } else {
                        sendReplyPrompt(chatId, confirmMessageId, target)
                    }
                }
                data.startsWith("reply_inbox:") -> {
                    val rowId = data.removePrefix("reply_inbox:").toLongOrNull()
                    val incoming = rowId?.let(dbHelper::getIncomingSms)
                    if (incoming == null) {
                        sendText(chatId, "That inbox message is no longer available.")
                    } else {
                        sendReplyPrompt(
                            chatId,
                            confirmMessageId,
                            ReplyTarget(incoming.sender, incoming.subscriptionId, "inbox")
                        )
                    }
                }
                data.startsWith("delete_inbox:") -> {
                    val parts = data.removePrefix("delete_inbox:").split(":", limit = 2)
                    val rowId = parts.firstOrNull()?.toLongOrNull()
                    val page = parts.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                    if (rowId == null || dbHelper.deleteIncomingSms(rowId) == 0) {
                        editOrSend(chatId, confirmMessageId, "That incoming SMS is no longer available.")
                    } else {
                        sendInbox(chatId, page, confirmMessageId)
                    }
                }
                data.startsWith("default_sim:") -> sendSims(chatId, confirmMessageId)
                data.startsWith("confirm_sms:") -> {
                    handleSmsConfirmation(chatId, data, confirmMessageId)
                }
                data.startsWith("cancel_sms:") -> {
                    val actionId = data.removePrefix("cancel_sms:")
                    val pending = actionStore.getPending(actionId)
                    if (pending?.chatId != chatId) {
                        editOrSend(chatId, confirmMessageId, "❌ This SMS confirmation is no longer available here.")
                    } else {
                        actionStore.removePending(actionId)
                        editOrSend(chatId, confirmMessageId, "❌ SMS cancelled.")
                    }
                }
                data == "fix_perms" -> {
                    runBusy(chatId) {
                        try {
                            val result = DevicePermissionFixer.fix(context)
                            val text = "${gatewayStatusText()}\n\n🛠 ${TelegramHtml.escape(result)}"
                            sendScreen(chatId, text, statusKeyboard(), confirmMessageId)
                        } catch (e: Exception) {
                            Log.e("CommandReceiver", "fix_perms failed", e)
                            editOrSend(
                                chatId,
                                confirmMessageId,
                                "❌ Fix failed: ${e.message ?: "unknown error"}"
                            )
                        }
                    }
                }
                else -> editOrSend(chatId, confirmMessageId, "This control has expired. Use /start to open the current menu.")
            }
        }

        private fun handleSmsConfirmation(chatId: String, data: String, messageId: Int?) {
            val parts = data.removePrefix("confirm_sms:").split(":", limit = 2)
            val actionId = parts.firstOrNull().orEmpty()
            val chosenSubId = parts.getOrNull(1)?.toIntOrNull()
            val pending = actionStore.getPending(actionId)
            if (pending == null) {
                editOrSend(chatId, messageId, "❌ This confirmation has expired. Please re-issue the command.")
                return
            }
            if (pending.chatId != chatId) {
                editOrSend(chatId, messageId, "❌ This SMS confirmation belongs to another chat.")
                return
            }
            if (chosenSubId == null) {
                editOrSend(chatId, messageId, "Choose an active SIM to send this SMS.")
                return
            }
            val selectedSim = DeviceStatusProvider.activeSims(context)
                .firstOrNull { it.subscriptionId == chosenSubId }
            if (selectedSim == null) {
                editOrSend(chatId, messageId, "That SIM is no longer active. Refresh /sims and retry.")
                return
            }
            val confirmed = actionStore.consumePending(actionId)
            if (confirmed == null) {
                editOrSend(chatId, messageId, "❌ This confirmation has expired or was already used.")
                return
            }

            val subscriptionId = selectedSim.subscriptionId
            val result = net.wastu.numi.device.SmsSender(context).sendSms(
                recipient = confirmed.recipient,
                body = confirmed.body,
                subscriptionId = subscriptionId
            )
            if (result is net.wastu.numi.device.SmsSendResult.Sent) {
                dbHelper.recordAcceptedSms(
                    recipient = confirmed.recipient,
                    body = confirmed.body,
                    subscriptionId = subscriptionId
                )
            }
            val statusText = when (result) {
                is net.wastu.numi.device.SmsSendResult.Sent ->
                    "✅ SMS queued to ${confirmed.recipient} via SIM ${selectedSim.slotIndex + 1}"
                is net.wastu.numi.device.SmsSendResult.PermissionDenied ->
                    "❌ SMS permission not granted. Grant SEND_SMS to Numi and try again."
                is net.wastu.numi.device.SmsSendResult.NoSim -> "❌ No active SIM available."
                is net.wastu.numi.device.SmsSendResult.Error -> "❌ Failed to send: ${result.reason}"
            }
            editOrSend(chatId, messageId, statusText)
            if (result is net.wastu.numi.device.SmsSendResult.Sent && messageId != null) {
                actionStore.saveThreadTip(confirmed.recipient, messageId)
            }
        }

        private fun callbackActivity(data: String): String? = when {
            data == "menu:status" -> null
            data == "menu:compose" -> "SMS composer opened"
            data == "menu:sims" -> "SIM routing viewed"
            data == "menu:inbox" || data.startsWith("inbox:page:") || data.startsWith("inbox:open:") -> "Inbox viewed"
            data.startsWith("inbox:move:") || data.startsWith("inbox:text:") -> "Inbox message viewed"
            data.startsWith("inbox:delete:") -> "Inbox message removed"
            data.startsWith("inbox:reply:") -> "SMS reply started"
            data == "menu:outbox" || data.startsWith("outbox:page:") -> "Outbox viewed"
            data == "menu:help" -> "Help viewed"
            data == "menu:home" -> "Home opened"
            data.startsWith("default_sim:") -> "SIM list refreshed"
            data.startsWith("retry_outbox:") -> "SMS retry requested"
            data.startsWith("delete_outbox:") -> "Outbox message removed"
            data.startsWith("delete_inbox:") -> "Inbox message removed"
            data.startsWith("reply_inbox:") -> "SMS reply started"
            data.startsWith("confirm_sms:") -> "SMS send confirmed"
            data.startsWith("cancel_sms:") -> "SMS send cancelled"
            else -> "Telegram control action"
        }

        private fun recentActivityLine(): String {
            val recent = RecentActivity.read(context)
                ?: return "Last activity: none yet"
            return "Last activity: ${TelegramHtml.escape(recent.label)} · ${RecentActivity.relativeTime(recent.timestamp)}"
        }

        private fun gatewayStatusText(): String = buildString {
            val sims = DeviceStatusProvider.activeSims(context)
            val counts = dbHelper.outboxCounts()
            val queued = (counts["PENDING"] ?: 0) + (counts["SENDING"] ?: 0)
            val smsPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
                PackageManager.PERMISSION_GRANTED
            val defaultSms = net.wastu.numi.utils.SmsRoleManager.isDefaultSmsApp(context)

            appendLine("📟 ${TelegramHtml.bold("Numi")}")
            appendLine("Service: ${if (OutboxService.isServiceRunning()) "✅ running" else "⚠️ stopped"}")
            appendLine(TelegramHtml.escape(TelegramPollHealth.summary(context, botToken.isNotBlank())))
            appendLine("Default SMS app: ${if (defaultSms) "✅ enabled" else "⚠️ needed for incoming SMS"}")
            appendLine("Send permission: ${if (smsPermission) "✅ granted" else "⚠️ missing"}")
            DevicePermissionFixer.health(context).checks
                .filter { it.label in setOf("Startup", "Battery", "Service notification") }
                .forEach { check ->
                    appendLine("${check.label}: ${TelegramHtml.escape(check.detail)}")
                }
            appendLine("Outbox: ${TelegramHtml.code("$queued queued · ${counts["SENT"] ?: 0} accepted · ${counts["FAILED"] ?: 0} failed")}")
            appendLine(recentActivityLine())
            appendLine()
            appendLine(TelegramHtml.bold("Active SIMs · ${sims.size}"))
            if (sims.isEmpty()) {
                append("No active SIMs found. Check SIM and phone permissions.")
            } else {
                sims.forEach { sim ->
                    appendLine("• SIM ${sim.slotIndex + 1} · ${TelegramHtml.escape(sim.carrier ?: sim.label)}")
                }
            }
        }.trimEnd()

        private fun sendStatus(chatId: String, editMessageId: Int? = null) =
            sendScreen(chatId, gatewayStatusText(), statusKeyboard(), editMessageId)

        private fun sendScreen(
            chatId: String,
            text: String,
            keyboard: JSONObject,
            editMessageId: Int? = null
        ) {
            fun requestBody(messageId: Int? = null) = JSONObject().apply {
                put("chat_id", chatId)
                put("text", text)
                put("parse_mode", "HTML")
                if (messageId != null) put("message_id", messageId)
                put("reply_markup", keyboard)
            }
            if (editMessageId != null && sendTelegram("editMessageText", requestBody(editMessageId)) != null) return
            sendTelegram("sendMessage", requestBody())
        }

        private fun sendHome(chatId: String, editMessageId: Int? = null) {
            val counts = dbHelper.outboxCounts()
            val queued = (counts["PENDING"] ?: 0) + (counts["SENDING"] ?: 0)
            val failed = counts["FAILED"] ?: 0
            val sims = DeviceStatusProvider.activeSims(context)
            val status = if (OutboxService.isServiceRunning()) "running" else "stopped"
            val text = buildString {
                appendLine("📡 ${TelegramHtml.bold("Numi")}")
                appendLine("Service: $status · ${sims.size} active SIM${if (sims.size == 1) "" else "s"}")
                appendLine("$queued queued · $failed failed")
                append(recentActivityLine())
            }
            sendScreen(chatId, text, homeKeyboard(), editMessageId)
        }

        private fun sendHelp(chatId: String, editMessageId: Int? = null) {
            val text = buildString {
                appendLine("❔ ${TelegramHtml.bold("Numi help")}")
                appendLine("Use the buttons to manage the gateway.")
                appendLine()
                appendLine("${TelegramHtml.code("/send <number> <message>")}  ·  compose SMS")
                appendLine("/sims  ·  view active SIMs")
                appendLine("/inbox  ·  incoming SMS")
                appendLine("/outbox  ·  sent and queued SMS")
                appendLine("/status  ·  device and queue health")
                append("Every outgoing SMS asks for confirmation. Choose its SIM before sending; replies start with the SIM that received the message.")
            }
            sendScreen(chatId, text, helpKeyboard(), editMessageId)
        }

        private fun sendSims(chatId: String, editMessageId: Int? = null) {
            val sims = DeviceStatusProvider.activeSims(context)
            val text = buildString {
                appendLine("📶 ${TelegramHtml.bold("SIM routing")}")
                appendLine("Choose a SIM for each outgoing SMS.")
                appendLine()
                if (sims.isEmpty()) append("No active SIMs found.")
                sims.forEach { sim ->
                    appendLine("SIM ${sim.slotIndex + 1} · ${TelegramHtml.escape(sim.carrier ?: sim.label)}")
                }
            }.trimEnd()
            val keyboardRows = JSONArray().apply {
                put(JSONArray().put(JSONObject().apply {
                    put("text", "📤 New SMS")
                    put("callback_data", "menu:compose")
                }).put(JSONObject().apply {
                    put("text", "⌂ Home")
                    put("callback_data", "menu:home")
                }))
                put(JSONArray().put(JSONObject().apply {
                    put("text", "🔄 Refresh SIMs")
                    put("callback_data", "menu:sims")
                }))
            }
            val markup = JSONObject().put("inline_keyboard", keyboardRows)
            sendScreen(chatId, text, markup, editMessageId)
        }

        private fun sendInbox(chatId: String, page: Int = 0, editMessageId: Int? = null) {
            val total = dbHelper.incomingSmsCount()
            val pageCount = ((total + SMS_LIST_PAGE_SIZE - 1) / SMS_LIST_PAGE_SIZE).coerceAtLeast(1)
            val safePage = page.coerceIn(0, pageCount - 1)
            val incoming = dbHelper.listInbox(SMS_LIST_PAGE_SIZE, safePage * SMS_LIST_PAGE_SIZE)
            if (incoming.isEmpty() && safePage > 0) {
                sendInbox(chatId, safePage - 1, editMessageId)
                return
            }

            val sims = DeviceStatusProvider.activeSims(context).associateBy { it.subscriptionId }
            val keyboardRows = JSONArray()
            val text = buildString {
                appendLine("📥 ${TelegramHtml.bold("Inbox")}")
                if (total == 0) {
                    append("No incoming SMS yet.")
                } else {
                    val firstNumber = safePage * SMS_LIST_PAGE_SIZE + 1
                    val lastNumber = (firstNumber + incoming.size - 1).coerceAtMost(total)
                    appendLine("$firstNumber–$lastNumber of $total · choose a number to open the conversation")
                    appendLine()
                    incoming.forEachIndexed { index, sms ->
                        val number = firstNumber + index
                        val sim = sms.subscriptionId?.let { sims[it] }
                        val simLabel = sim?.let { " · SIM ${it.slotIndex + 1}" }.orEmpty()
                        val time = java.text.SimpleDateFormat("MMM d · HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(sms.receivedAt))
                        appendLine("${TelegramHtml.bold("$number. ${sms.sender}")}$simLabel · ${TelegramHtml.code(time)}")
                        appendLine(TelegramHtml.escape(sms.body.compactExcerpt(105)))
                        if (index < incoming.lastIndex) appendLine()
                        keyboardRows.put(JSONArray().put(JSONObject().apply {
                            put("text", "${number} · ${sms.sender.compactExcerpt(24)}")
                            put("callback_data", "inbox:open:${sms.id}:$safePage")
                        }))
                    }
                    appendLine()
                    append("Page ${safePage + 1} of $pageCount")
                }
            }
            val navigation = inboxKeyboard(safePage, safePage > 0, safePage < pageCount - 1)
                .optJSONArray("inline_keyboard") ?: JSONArray()
            for (index in 0 until navigation.length()) keyboardRows.put(navigation.getJSONArray(index))
            sendScreen(chatId, text.trimEnd(), JSONObject().put("inline_keyboard", keyboardRows), editMessageId)
        }

        private fun inboxKeyboard(page: Int, hasPrevious: Boolean, hasNext: Boolean): JSONObject =
            JSONObject().put("inline_keyboard", JSONArray().apply {
                if (hasPrevious || hasNext) {
                    val row = JSONArray()
                    if (hasPrevious) row.put(JSONObject().put("text", "‹ Newer").put("callback_data", "inbox:page:${page - 1}"))
                    if (hasNext) row.put(JSONObject().put("text", "Older ›").put("callback_data", "inbox:page:${page + 1}"))
                    put(row)
                }
                put(JSONArray().put(JSONObject().put("text", "📤 New SMS").put("callback_data", "menu:compose"))
                    .put(JSONObject().put("text", "⌂ Home").put("callback_data", "menu:home")))
                put(JSONArray().put(JSONObject().put("text", "🔄 Refresh").put("callback_data", "inbox:page:$page")))
            })

        private fun moveConversation(
            chatId: String,
            isIncoming: Boolean,
            messageId: Long,
            direction: String,
            page: Int,
            editMessageId: Int?
        ) {
            val messages = dbHelper.conversationMessagesForAnchor(isIncoming, messageId)
            val currentIndex = messages.indexOfFirst { it.id == messageId && it.isIncoming == isIncoming }
            val targetIndex = when (direction) {
                "previous" -> currentIndex - 1
                "next" -> currentIndex + 1
                else -> -1
            }
            if (currentIndex < 0 || targetIndex !in messages.indices) {
                sendConversation(chatId, isIncoming, messageId, page, editMessageId = editMessageId)
                return
            }
            val target = messages[targetIndex]
            sendConversation(chatId, target.isIncoming, target.id, page, editMessageId = editMessageId)
        }

        private fun sendConversation(
            chatId: String,
            isIncoming: Boolean,
            messageId: Long,
            page: Int,
            bodyOffset: Int = 0,
            editMessageId: Int? = null
        ) {
            val messages = dbHelper.conversationMessagesForAnchor(isIncoming, messageId)
            val currentIndex = messages.indexOfFirst { it.id == messageId && it.isIncoming == isIncoming }
            if (currentIndex < 0) {
                sendInbox(chatId, page, editMessageId)
                return
            }
            val current = messages[currentIndex]
            val bodyLength = current.body.codePointCount(0, current.body.length)
            val chunkSize = 1500
            val safeOffset = (bodyOffset / chunkSize * chunkSize).coerceIn(0, bodyLength)
            val bodyStart = current.body.offsetByCodePoints(0, safeOffset)
            val bodyEnd = current.body.offsetByCodePoints(0, minOf(safeOffset + chunkSize, bodyLength))
            val bodyChunk = current.body.substring(bodyStart, bodyEnd)
            val sims = DeviceStatusProvider.activeSims(context).associateBy { it.subscriptionId }
            val time = java.text.SimpleDateFormat("MMM d, yyyy · HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(current.sentAt))
            val simLabel = current.subscriptionId?.let { sims[it]?.let { sim -> " · SIM ${sim.slotIndex + 1}" } }.orEmpty()
            val directionLabel = if (current.isIncoming) "← Received" else "→ Sent from Numi"
            val text = buildString {
                appendLine("📱 ${TelegramHtml.bold(current.address.compactExcerpt(128))}")
                appendLine("${currentIndex + 1} of ${messages.size} · $directionLabel · ${TelegramHtml.code(time)}$simLabel")
                appendLine()
                if (bodyChunk.isEmpty()) append("<i>(empty message)</i>")
                else append(TelegramHtml.escape(bodyChunk))
                if (bodyLength > chunkSize) {
                    val shownThrough = minOf(safeOffset + chunkSize, bodyLength)
                    appendLine()
                    appendLine()
                    append("Text ${safeOffset + 1}–$shownThrough of $bodyLength")
                }
            }
            val keyboard = JSONArray()
            if (currentIndex > 0 || currentIndex < messages.lastIndex) {
                keyboard.put(JSONArray().apply {
                    if (currentIndex > 0) put(JSONObject().put("text", "‹ Previous").put("callback_data", "inbox:move:${if (current.isIncoming) "in" else "out"}:${current.id}:previous:$page"))
                    if (currentIndex < messages.lastIndex) put(JSONObject().put("text", "Next ›").put("callback_data", "inbox:move:${if (current.isIncoming) "in" else "out"}:${current.id}:next:$page"))
                })
            }
            if (bodyLength > chunkSize) {
                keyboard.put(JSONArray().apply {
                    if (safeOffset > 0) put(JSONObject().put("text", "‹ Earlier text").put("callback_data", "inbox:text:${if (current.isIncoming) "in" else "out"}:${current.id}:${safeOffset - chunkSize}:$page"))
                    if (safeOffset + chunkSize < bodyLength) put(JSONObject().put("text", "More text ›").put("callback_data", "inbox:text:${if (current.isIncoming) "in" else "out"}:${current.id}:${safeOffset + chunkSize}:$page"))
                })
            }
            val actions = JSONArray()
            if (messages.any { it.isIncoming && canReplyToSmsAddress(it.address) }) {
                actions.put(JSONObject().put("text", "↩ Reply").put("callback_data", "inbox:reply:${if (current.isIncoming) "in" else "out"}:${current.id}"))
            }
            if (current.isIncoming) {
                actions.put(JSONObject().put("text", "✕ Remove").put("callback_data", "inbox:delete:${current.id}:$page"))
            }
            if (actions.length() > 0) keyboard.put(actions)
            keyboard.put(JSONArray().put(JSONObject().put("text", "‹ Inbox").put("callback_data", "inbox:page:$page"))
                .put(JSONObject().put("text", "⌂ Home").put("callback_data", "menu:home")))
            sendScreen(chatId, text, JSONObject().put("inline_keyboard", keyboard), editMessageId)
        }

        private fun sendOutbox(chatId: String, page: Int = 0, editMessageId: Int? = null) {
            val total = dbHelper.outboxCounts().values.sum()
            val lastPage = (total - 1).coerceAtLeast(0) / SMS_LIST_PAGE_SIZE
            val safePage = page.coerceIn(0, lastPage)
            val outboxPage = dbHelper.listOutbox("SMS", SMS_LIST_PAGE_SIZE + 1, safePage * SMS_LIST_PAGE_SIZE)
            val hasNext = outboxPage.size > SMS_LIST_PAGE_SIZE
            val messages = outboxPage.take(SMS_LIST_PAGE_SIZE)
            if (messages.isEmpty()) {
                if (safePage > 0) {
                    sendOutbox(chatId, safePage - 1, editMessageId)
                    return
                }
                sendScreen(chatId, "📤 Outbox is empty.", outboxKeyboard(safePage, false, false), editMessageId)
                return
            }

            val sims = DeviceStatusProvider.activeSims(context).associateBy { it.subscriptionId }
            val rows = JSONArray()
            val text = buildString {
                appendLine("📤 ${TelegramHtml.bold("SMS outbox")} · page ${safePage + 1}")
                appendLine()
                messages.forEachIndexed { index, message ->
                    val status = when (message.status) {
                        "PENDING" -> "queued"
                        "SENDING" -> "sending"
                        "SENT" -> "accepted"
                        "FAILED" -> "failed"
                        else -> message.status.lowercase()
                    }
                    val route = message.subscriptionId?.let { sims[it]?.let { sim -> " · SIM ${sim.slotIndex + 1}" } }.orEmpty()
                    val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(message.createdAt))
                    appendLine("${TelegramHtml.bold(message.recipient)} · ${TelegramHtml.code(status)} · ${TelegramHtml.code(time)}$route")
                    appendLine(TelegramHtml.escape(message.body.compactExcerpt(110)))
                    if (!message.lastError.isNullOrBlank()) {
                        appendLine("⚠️ ${TelegramHtml.escape(message.lastError.compactExcerpt(90))}")
                    }
                    if (index < messages.lastIndex) appendLine()

                    val actions = JSONArray()
                    if (message.status == "FAILED") {
                        actions.put(JSONObject().put("text", "↻ Retry").put("callback_data", "retry_outbox:${message.id}:$safePage"))
                    }
                    if (message.status != "SENDING") {
                        val label = if (message.status == "PENDING") "Cancel" else "Remove"
                        actions.put(JSONObject().put("text", "✕ $label").put("callback_data", "delete_outbox:${message.id}:$safePage"))
                    }
                    if (actions.length() > 0) rows.put(actions)
                }
            }
            val navigation = outboxKeyboard(safePage, safePage > 0, hasNext)
                .optJSONArray("inline_keyboard") ?: JSONArray()
            for (index in 0 until navigation.length()) rows.put(navigation.getJSONArray(index))
            sendScreen(chatId, text, JSONObject().put("inline_keyboard", rows), editMessageId)
        }

        private fun outboxKeyboard(page: Int, hasPrevious: Boolean, hasNext: Boolean): JSONObject =
            JSONObject().put("inline_keyboard", JSONArray().apply {
                if (hasPrevious || hasNext) {
                    val row = JSONArray()
                    if (hasPrevious) row.put(JSONObject().put("text", "‹ Newer").put("callback_data", "outbox:page:${page - 1}"))
                    if (hasNext) row.put(JSONObject().put("text", "Older ›").put("callback_data", "outbox:page:${page + 1}"))
                    put(row)
                }
                put(JSONArray().put(JSONObject().put("text", "📤 New SMS").put("callback_data", "menu:compose"))
                    .put(JSONObject().put("text", "⌂ Home").put("callback_data", "menu:home")))
                put(JSONArray().put(JSONObject().put("text", "🔄 Refresh").put("callback_data", "outbox:page:$page")))
            })

        private fun sendComposerPrompt(chatId: String) {
            val messageId = sendForceReply(
                chatId,
                "📤 New SMS\nReply with: phone number, then a space, then your message.",
                "Example: +628123456789 Hello"
            )
            messageId?.let(actionStore::saveComposePrompt)
        }

        private fun sendReplyPrompt(chatId: String, sourceMessageId: Int?, target: ReplyTarget) {
            val simLabel = target.subscriptionId?.let { id ->
                DeviceStatusProvider.activeSims(context).firstOrNull { it.subscriptionId == id }
                    ?.let { " · received on SIM ${it.slotIndex + 1}" }
            }.orEmpty()
            val messageId = sendForceReply(
                chatId,
                "↩ Reply to ${target.phoneNumber}$simLabel\nType the SMS message to send.",
                "Type your reply",
                sourceMessageId
            )
            messageId?.let { actionStore.saveReplyTarget(it, target) }
        }

        private fun sendForceReply(
            chatId: String,
            prompt: String,
            placeholder: String,
            replyToMessageId: Int? = null
        ): Int? {
            val body = JSONObject().apply {
                put("chat_id", chatId)
                put("text", prompt)
                if (replyToMessageId != null) put("reply_to_message_id", replyToMessageId)
                put("reply_markup", JSONObject().apply {
                    put("force_reply", true)
                    put("selective", true)
                    put("input_field_placeholder", placeholder.take(64))
                })
            }
            val response = sendTelegram("sendMessage", body) ?: return null
            return response.optJSONObject("result")?.optInt("message_id")?.takeIf { it > 0 }
        }

        private fun handleComposeReply(chatId: String, text: String) {
            val match = Regex("""^\s*(\+?\d{3,15})\s+([\s\S]+)$""").find(text)
            if (match == null) {
                sendText(chatId, "Include the number and message, for example:\n+628123456789 Hello")
                return
            }
            handleSendCommand(chatId, match.groupValues[1], match.groupValues[2].trim())
        }

        private fun homeKeyboard(): JSONObject = JSONObject().put("inline_keyboard", JSONArray().apply {
            put(JSONArray().put(JSONObject().put("text", "📤 Send SMS").put("callback_data", "menu:compose"))
                .put(JSONObject().put("text", "📥 Inbox").put("callback_data", "menu:inbox")))
            put(JSONArray().put(JSONObject().put("text", "📤 Outbox").put("callback_data", "menu:outbox"))
                .put(JSONObject().put("text", "📶 SIM routes").put("callback_data", "menu:sims")))
            put(JSONArray().put(JSONObject().put("text", "📊 Status").put("callback_data", "menu:status"))
                .put(JSONObject().put("text", "❔ Help").put("callback_data", "menu:help")))
        })

        private fun statusKeyboard(): JSONObject = JSONObject().put("inline_keyboard", JSONArray().apply {
            put(JSONArray().put(JSONObject().put("text", "📤 Send SMS").put("callback_data", "menu:compose"))
                .put(JSONObject().put("text", "📥 Inbox").put("callback_data", "menu:inbox")))
            put(JSONArray().put(JSONObject().put("text", "📤 Outbox").put("callback_data", "menu:outbox"))
                .put(JSONObject().put("text", "📶 SIM routes").put("callback_data", "menu:sims")))
            put(JSONArray().put(JSONObject().put("text", "🔄 Refresh").put("callback_data", "menu:status"))
                .put(JSONObject().put("text", "🔧 Permissions").put("callback_data", "fix_perms")))
            put(JSONArray().put(JSONObject().put("text", "⌂ Home").put("callback_data", "menu:home"))
                .put(JSONObject().put("text", "❔ Help").put("callback_data", "menu:help")))
        })

        private fun helpKeyboard(): JSONObject = JSONObject().put("inline_keyboard", JSONArray().apply {
            put(JSONArray().put(JSONObject().put("text", "📤 Send SMS").put("callback_data", "menu:compose"))
                .put(JSONObject().put("text", "⌂ Home").put("callback_data", "menu:home")))
            put(JSONArray().put(JSONObject().put("text", "📥 Inbox").put("callback_data", "menu:inbox"))
                .put(JSONObject().put("text", "📤 Outbox").put("callback_data", "menu:outbox")))
        })

        private fun handleReplyForward(chatId: String, replyTo: JSONObject, text: String) {
            val messageId = replyTo.optInt("message_id", 0)
            if (messageId <= 0) return
            val target = actionStore.getReplyTarget(messageId)
            if (target == null) {
                sendText(chatId, "No SMS reply target found for this message.")
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
            val activeSims = DeviceStatusProvider.activeSims(context)
            if (activeSims.isEmpty()) {
                sendText(chatId, "❌ No active SIM available.")
                return
            }
            val pending = PendingSmsAction(
                chatId = chatId,
                recipient = number,
                body = body.take(1_000),
                subscriptionId = null,
                simLabel = null
            )
            actionStore.savePending(pending)
            sendConfirmation(chatId, pending, activeSims)
        }

        private fun sendConfirmation(
            chatId: String,
            action: PendingSmsAction,
            sims: List<net.wastu.numi.device.SimInfo> = DeviceStatusProvider.activeSims(context)
        ) {
            if (sims.isEmpty()) {
                actionStore.removePending(action.id)
                sendText(chatId, "❌ No active SIM available to send this SMS.")
                return
            }
            val keyboard = JSONObject().apply {
                put("inline_keyboard", JSONArray().apply {
                    for (sim in sims) {
                        val selected = sim.subscriptionId == action.subscriptionId
                        val routeLabel = "Send via SIM ${sim.slotIndex + 1} · ${sim.carrier ?: sim.label}"
                        val buttonLabel = if (selected) "✅ $routeLabel" else "📤 $routeLabel"
                        put(JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", buttonLabel.take(60))
                                put("callback_data", "confirm_sms:${action.id}:${sim.subscriptionId}")
                            })
                        })
                    }
                    put(JSONArray().put(JSONObject().apply {
                        put("text", "❌ Cancel")
                        put("callback_data", "cancel_sms:${action.id}")
                    }))
                })
            }
            val selectedRoute = action.subscriptionId?.let { id ->
                sims.firstOrNull { it.subscriptionId == id }?.let { sim ->
                    "Reply SIM ${sim.slotIndex + 1} is selected. Choose a SIM to confirm:\n"
                }
            }.orEmpty()
            val prompt = "📤 Send SMS to ${action.recipient}?\n${selectedRoute}Choose a SIM to confirm:\n\n${action.body}"
            sendTelegram("sendMessage", JSONObject().apply {
                put("chat_id", chatId)
                put("text", prompt)
                put("reply_markup", keyboard)
            })
        }

        private fun editOrSend(chatId: String, messageId: Int?, text: String) {
            if (messageId != null) {
                val edited = sendTelegram("editMessageText", JSONObject().apply {
                    put("chat_id", chatId)
                    put("message_id", messageId)
                    put("text", text)
                    put("reply_markup", JSONObject().put("inline_keyboard", JSONArray()))
                })
                if (edited != null) return
            }
            sendText(chatId, text)
        }

        private fun answerCallback(callbackId: String, text: String? = null) {
            val body = JSONObject().apply {
                put("callback_query_id", callbackId)
                if (!text.isNullOrBlank()) put("text", text.take(180))
            }
            sendTelegram("answerCallbackQuery", body)
        }

        private fun sendText(chatId: String, text: String): Boolean =
            sendTextForId(chatId, text) != null

        private fun sendTextForId(chatId: String, text: String, replyToMessageId: Int? = null): Int? {
            val body = JSONObject().apply {
                put("chat_id", chatId)
                put("text", text.take(4_000))
                if (replyToMessageId != null && replyToMessageId > 0) {
                    put("reply_to_message_id", replyToMessageId)
                }
            }
            val resp = sendTelegram("sendMessage", body) ?: return null
            return resp.optJSONObject("result")?.optInt("message_id")?.takeIf { it > 0 }
        }

        private fun sendTelegram(method: String, body: JSONObject): JSONObject? {
            val request = Request.Builder()
                .url("https://api.telegram.org/bot$botToken/$method")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            return try {
                apiClient.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    val result = runCatching { JSONObject(raw) }.getOrNull() ?: return@use null
                    if (result.optBoolean("ok")) result else {
                        val description = result.optString("description")
                        if (method == "editMessageText" && description.contains("message is not modified", ignoreCase = true)) {
                            JSONObject().put("ok", true)
                        } else {
                            Log.e(
                                "CommandReceiver",
                                "Telegram $method rejected the request: HTTP ${response.code}, ${description.take(100)}"
                            )
                            null
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e("CommandReceiver", "Telegram $method I/O failure: ${e.javaClass.simpleName}")
                null
            }
        }

        private fun sendTyping(chatId: String) {
            sendTelegram(
                "sendChatAction",
                JSONObject().apply {
                    put("chat_id", chatId)
                    put("action", "typing")
                }
            )
        }

        /** Run work off the poller thread while showing Telegram "typing…" (refreshed every ~4s). */
        private fun runBusy(chatId: String, block: () -> Unit) {
            Thread {
                val stop = AtomicBoolean(false)
                val typer = Thread({
                    while (!stop.get()) {
                        sendTyping(chatId)
                        try {
                            Thread.sleep(4_000)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }, "tg-typing").apply { isDaemon = true }
                typer.start()
                try {
                    block()
                } catch (e: Exception) {
                    Log.e("CommandReceiver", "Busy work failed", e)
                } finally {
                    stop.set(true)
                    typer.interrupt()
                }
            }.start()
        }

    }
}

private fun String.compactExcerpt(limit: Int): String {
    val normalized = replace(Regex("\\s+"), " ").trim()
    val codePoints = normalized.codePointCount(0, normalized.length)
    if (codePoints <= limit) return normalized
    return normalized.substring(0, normalized.offsetByCodePoints(0, limit)) + "…"
}
