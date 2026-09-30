package net.wastu.numi.bus

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import net.wastu.numi.database.DbHelper
import net.wastu.numi.bus.CommandError
import net.wastu.numi.bus.PendingSmsAction
import net.wastu.numi.bus.TelegramActionStore
import net.wastu.numi.device.DevicePermissionFixer
import net.wastu.numi.device.DeviceStatusProvider
import net.wastu.numi.device.SimInfo
import net.wastu.numi.database.IncomingSms
import net.wastu.numi.network.TelegramSender
import net.wastu.numi.network.TelegramHtml
import net.wastu.numi.queue.OutboxService
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

private const val SMS_LIST_PAGE_SIZE = 6

class CommandReceiver(
    private val context: Context,
    private val botToken: String,
    private val replySender: TelegramSender
) {
    private val dbHelper = DbHelper(context)
    private val executor = CommandExecutor(context)
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
                val scopes = listOf(
                    JSONObject().put("type", "default"),
                    JSONObject().put("type", "all_private_chats")
                ) + allowedIds().mapNotNull { it.toLongOrNull() }
                    .map { JSONObject().put("type", "chat").put("chat_id", it) }

                for (scope in scopes.distinctBy { it.toString() }) {
                    val body = JSONObject().apply {
                        put("commands", commands)
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
    }

    private fun commandList() = JSONArray().apply {
        put(JSONObject().put("command", "start").put("description", "Open SMS gateway controls"))
        put(JSONObject().put("command", "status").put("description", "Gateway, SIMs and queue status"))
        put(JSONObject().put("command", "send").put("description", "Compose an SMS"))
        put(JSONObject().put("command", "sims").put("description", "Manage SIM routing"))
        put(JSONObject().put("command", "inbox").put("description", "Recent incoming SMS"))
        put(JSONObject().put("command", "outbox").put("description", "Review and manage sent SMS"))
        put(JSONObject().put("command", "help").put("description", "Show gateway help"))
    }

    private fun allowedIds(): Set<String> = (prefs.getString("ALLOWED_USER_IDS", null)
        ?: prefs.getString("TARGET_CHAT_ID", "").orEmpty())
        .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun isAllowedPrivateChat(chat: JSONObject, actorId: String): Boolean {
        val chatId = chat.optLong("id", 0L).toString()
        return chat.optString("type") == "private" && actorId == chatId && actorId in allowedIds()
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
                        put("allowed_updates", JSONArray().put("message").put("callback_query"))
                    }
                    val body = bodyObj.toString().toRequestBody(JSON_MEDIA_TYPE)
                    val request = Request.Builder().url(url).post(body).build()
                    val pollCall = pollingClient.newCall(request)
                    activePoll = pollCall
                    try {
                    pollCall.execute().use { response ->
                        if (!response.isSuccessful) {
                            Log.e("CommandReceiver", "Poll error HTTP ${response.code}")
                            sleepWithCheck(retryMs)
                            retryMs = (retryMs * 2).coerceAtMost(MAX_RETRY_MS)
                            return@use
                        }

                        val resultStr = response.body?.string() ?: return@use
                        val resultObj = JSONObject(resultStr)
                        if (!resultObj.optBoolean("ok", false)) {
                            Log.e("CommandReceiver", "Poll API rejected the request")
                            sleepWithCheck(retryMs)
                            retryMs = (retryMs * 2).coerceAtMost(MAX_RETRY_MS)
                            return@use
                        }

                        val updates = resultObj.optJSONArray("result") ?: JSONArray()
                        for (i in 0 until updates.length()) {
                            val update = updates.getJSONObject(i)
                            val updateId = update.optLong("update_id", 0)
                            // Telegram returns updates with update_id >= offset. If offset was
                            // incorrectly saved as the last update_id (not +1), the same update
                            // would be handled forever because updateId > offset never becomes true.
                            if (updateId < offset) continue
                            handleUpdate(update)
                            offset = updateId + 1
                            saveOffset()
                        }
                        retryMs = MIN_RETRY_MS
                    }
                    } finally {
                        if (activePoll === pollCall) activePoll = null
                    }
                } catch (e: IOException) {
                    if (!isRunning.get()) break
                    Log.e("CommandReceiver", "Poll IO error: ${e.javaClass.simpleName}")
                    sleepWithCheck(retryMs)
                    retryMs = (retryMs * 2).coerceAtMost(MAX_RETRY_MS)
                } catch (e: Exception) {
                    if (!isRunning.get()) break
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
            val actorId = message.optJSONObject("from")?.optLong("id", 0L)?.toString().orEmpty()
            if (!isAllowedPrivateChat(chat, actorId)) return

            val text = message.optString("text", "")
            if (text.isBlank()) return

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

            // -- Numi signed commands --
            if (text.startsWith("NUMI_CMD v1")) {
                Log.d("CommandReceiver", "Received Numi cmd from chat $chatId")
                dispatchReply(chatId) { executor.execute(text) }
                return
            }

            val command = text.trim().substringBefore('@').substringBefore(' ')

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
            val message = callback.optJSONObject("message") ?: return
            val chat = message.optJSONObject("chat") ?: return
            val chatId = chat.optLong("id", 0).toString()
            val actorId = callback.optJSONObject("from")?.optLong("id", 0L)?.toString().orEmpty()
            val callbackId = callback.optString("id", "")
            if (!isAllowedPrivateChat(chat, actorId)) {
                answerCallback(callbackId, "This Numi control is limited to approved private chats.")
                return
            }

            val data = callback.optString("data", "")
            val confirmMessageId = message.optInt("message_id", 0).takeIf { it > 0 }

            // Answer callback query to stop the spinner
            answerCallback(callbackId)

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
                data.startsWith("default_sim:") -> {
                    val selectedRaw = data.removePrefix("default_sim:")
                    if (selectedRaw == "system") {
                        prefs.edit().remove("DEFAULT_SUBSCRIPTION_ID").apply()
                        sendSims(chatId, confirmMessageId)
                    } else {
                        val selectedId = selectedRaw.toIntOrNull()
                        val activeIds = DeviceStatusProvider.activeSims(context).map { it.subscriptionId }.toSet()
                        if (selectedId == null || selectedId !in activeIds) {
                            sendText(chatId, "That SIM is no longer active. Refresh /sims and choose again.")
                        } else {
                            prefs.edit().putInt("DEFAULT_SUBSCRIPTION_ID", selectedId).apply()
                            sendSims(chatId, confirmMessageId)
                        }
                    }
                }
                data.startsWith("confirm_sms:") -> {
                    runBusy(chatId) {
                        val payload = data.removePrefix("confirm_sms:")
                        val parts = payload.split(":", limit = 2)
                        val actionId = parts[0]
                        val chosenSubId = parts.getOrNull(1)?.toIntOrNull()
                        val pending = actionStore.getPending(actionId)
                        if (pending == null) {
                            editOrSend(
                                chatId,
                                confirmMessageId,
                                "❌ This confirmation has expired. Please re-issue the command."
                            )
                            return@runBusy
                        }
                        if (pending.chatId != chatId) {
                            editOrSend(chatId, confirmMessageId, "❌ This SMS confirmation belongs to another chat.")
                            return@runBusy
                        }
                        val selectedRoute = parts.getOrNull(1)
                        val subscriptionId = when {
                            selectedRoute == "system" -> null
                            chosenSubId != null -> chosenSubId
                            else -> pending.subscriptionId
                        }
                        val sims = DeviceStatusProvider.activeSims(context)
                        val simLabel = sims.firstOrNull { it.subscriptionId == subscriptionId }?.label
                            ?: pending.simLabel
                        val smsSender = net.wastu.numi.device.SmsSender(context)
                        val result = smsSender.sendSms(
                            recipient = pending.recipient,
                            body = pending.body,
                            subscriptionId = subscriptionId
                        )
                        if (result is net.wastu.numi.device.SmsSendResult.Sent) {
                            DbHelper(context).recordAcceptedSms(
                                recipient = pending.recipient,
                                body = pending.body,
                                subscriptionId = subscriptionId
                            )
                        }
                        actionStore.removePending(actionId)
                        val statusText = when (result) {
                            is net.wastu.numi.device.SmsSendResult.Sent -> {
                                val label = simLabel?.let { " via $it" } ?: ""
                                "✅ SMS queued to ${pending.recipient}$label"
                            }
                            is net.wastu.numi.device.SmsSendResult.PermissionDenied ->
                                "❌ SMS permission not granted. Grant SEND_SMS to Numi and try again."
                            is net.wastu.numi.device.SmsSendResult.NoSim ->
                                "❌ No active SIM available."
                            is net.wastu.numi.device.SmsSendResult.Error ->
                                "❌ Failed to send: ${result.reason}"
                        }
                        editOrSend(chatId, confirmMessageId, statusText)
                        // Anchor the outbound tip so the carrier's inbound reply threads under this.
                        if (result is net.wastu.numi.device.SmsSendResult.Sent && confirmMessageId != null) {
                            actionStore.saveThreadTip(pending.recipient, confirmMessageId)
                        }
                    }
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
            }
        }

        private fun gatewayStatusText(): String = buildString {
            val sims = DeviceStatusProvider.activeSims(context)
            val defaultSimId = prefs.getInt("DEFAULT_SUBSCRIPTION_ID", -1)
            val counts = dbHelper.outboxCounts()
            val queued = (counts["PENDING"] ?: 0) + (counts["SENDING"] ?: 0)
            val smsPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
                PackageManager.PERMISSION_GRANTED
            val defaultSms = net.wastu.numi.utils.SmsRoleManager.isDefaultSmsApp(context)

            appendLine("📟 ${TelegramHtml.bold("Numi SMS Gateway")}")
            appendLine("Service: ${if (OutboxService.isServiceRunning()) "✅ running" else "⚠️ stopped"}")
            appendLine("Default SMS app: ${if (defaultSms) "✅ enabled" else "⚠️ needed for incoming SMS"}")
            appendLine("Send permission: ${if (smsPermission) "✅ granted" else "⚠️ missing"}")
            appendLine("Outbox: ${TelegramHtml.code("$queued queued · ${counts["SENT"] ?: 0} accepted · ${counts["FAILED"] ?: 0} failed")}")
            appendLine()
            appendLine(TelegramHtml.bold("Active SIMs · ${sims.size}"))
            if (sims.isEmpty()) {
                append("No active SIMs found. Check SIM and phone permissions.")
            } else {
                sims.forEach { sim ->
                    val route = if (sim.subscriptionId == defaultSimId) " · default" else ""
                    appendLine("• SIM ${sim.slotIndex + 1} · ${TelegramHtml.escape(sim.carrier ?: sim.label)}$route")
                }
                if (defaultSimId < 0) append("• Android system default SIM is used when no SIM is chosen")
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
                put("text", text.take(3_900))
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
            val status = if (OutboxService.isServiceRunning()) "online" else "stopped"
            val text = buildString {
                appendLine("📡 ${TelegramHtml.bold("Numi SMS Gateway")}")
                appendLine("Service $status · ${sims.size} active SIM${if (sims.size == 1) "" else "s"}")
                append("$queued queued · $failed failed")
            }
            sendScreen(chatId, text, homeKeyboard(), editMessageId)
        }

        private fun sendHelp(chatId: String, editMessageId: Int? = null) {
            val text = buildString {
                appendLine("❔ ${TelegramHtml.bold("Numi help")}")
                appendLine("Use the buttons to manage the gateway.")
                appendLine()
                appendLine("${TelegramHtml.code("/send <number> <message>")}  ·  compose SMS")
                appendLine("/sims  ·  choose the default route")
                appendLine("/inbox  ·  incoming SMS")
                appendLine("/outbox  ·  sent and queued SMS")
                appendLine("/status  ·  device and queue health")
                append("Each outgoing SMS asks you to confirm. With multiple SIMs, choose one before sending.")
            }
            sendScreen(chatId, text, helpKeyboard(), editMessageId)
        }

        private fun sendSims(chatId: String, editMessageId: Int? = null) {
            val sims = DeviceStatusProvider.activeSims(context)
            val selected = prefs.getInt("DEFAULT_SUBSCRIPTION_ID", -1)
            val text = buildString {
                appendLine("📶 ${TelegramHtml.bold("SIM routing")}")
                appendLine("Choose the default SIM for messages without an explicit route.")
                appendLine()
                if (sims.isEmpty()) append("No active SIMs found.")
                sims.forEach { sim ->
                    val mark = if (sim.subscriptionId == selected) " ✓ default" else ""
                    appendLine("SIM ${sim.slotIndex + 1} · ${TelegramHtml.escape(sim.carrier ?: sim.label)}$mark")
                }
                if (selected < 0) append("Android system default SIM is currently selected.")
            }.trimEnd()
            val keyboardRows = JSONArray().apply {
                sims.forEach { sim ->
                    put(JSONArray().put(JSONObject().apply {
                        put("text", "Use SIM ${sim.slotIndex + 1} · ${sim.carrier ?: sim.label}".take(60))
                        put("callback_data", "default_sim:${sim.subscriptionId}")
                    }))
                }
                put(JSONArray().put(JSONObject().apply {
                    put("text", "Use Android system default")
                    put("callback_data", "default_sim:system")
                }))
                put(JSONArray().put(JSONObject().apply {
                    put("text", "📤 New SMS")
                    put("callback_data", "menu:compose")
                }).put(JSONObject().apply {
                    put("text", "⌂ Home")
                    put("callback_data", "menu:home")
                }))
            }
            val markup = JSONObject().put("inline_keyboard", keyboardRows)
            sendScreen(chatId, text, markup, editMessageId)
        }

        private fun sendInbox(chatId: String, page: Int = 0, editMessageId: Int? = null) {
            val lastPage = (dbHelper.incomingSmsCount() - 1).coerceAtLeast(0) / SMS_LIST_PAGE_SIZE
            val safePage = page.coerceIn(0, lastPage)
            val incomingPage = dbHelper.listInbox(SMS_LIST_PAGE_SIZE + 1, safePage * SMS_LIST_PAGE_SIZE)
            val hasNext = incomingPage.size > SMS_LIST_PAGE_SIZE
            val incoming = incomingPage.take(SMS_LIST_PAGE_SIZE)
            if (incoming.isEmpty()) {
                if (safePage > 0) {
                    sendInbox(chatId, safePage - 1, editMessageId)
                    return
                }
                sendScreen(chatId, "📥 No incoming SMS yet.", inboxKeyboard(safePage, false, false), editMessageId)
                return
            }
            val sims = DeviceStatusProvider.activeSims(context).associateBy { it.subscriptionId }
            val rows = JSONArray()
            val text = buildString {
                appendLine("📥 ${TelegramHtml.bold("Incoming SMS")} · page ${safePage + 1}")
                appendLine()
                incoming.forEachIndexed { index, sms ->
                    val sim = sms.subscriptionId?.let { sims[it] }
                    val simLabel = sim?.let { " · SIM ${it.slotIndex + 1}" }.orEmpty()
                    val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(sms.receivedAt))
                    appendLine("${TelegramHtml.bold(sms.sender)}$simLabel · ${TelegramHtml.code(time)}")
                    appendLine(TelegramHtml.escape(sms.body.compactExcerpt(130)))
                    if (index < incoming.lastIndex) appendLine()
                    rows.put(JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", "↩ Reply")
                            put("callback_data", "reply_inbox:${sms.id}")
                        })
                        put(JSONObject().apply {
                            put("text", "✕ Remove")
                            put("callback_data", "delete_inbox:${sms.id}:$safePage")
                        })
                    })
                }
            }
            val navigation = inboxKeyboard(safePage, safePage > 0, hasNext)
                .optJSONArray("inline_keyboard") ?: JSONArray()
            for (index in 0 until navigation.length()) rows.put(navigation.getJSONArray(index))
            sendScreen(chatId, text, JSONObject().put("inline_keyboard", rows), editMessageId)
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
            val preferredSimId = prefs.getInt("DEFAULT_SUBSCRIPTION_ID", -1)
            val preferredSim = activeSims.firstOrNull { it.subscriptionId == preferredSimId }
                ?: activeSims.singleOrNull()
            val pending = PendingSmsAction(
                chatId = chatId,
                recipient = number,
                body = body.take(1_000),
                subscriptionId = preferredSim?.subscriptionId,
                simLabel = preferredSim?.label
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
                    if (sims.size > 1) {
                        // One SIM button per row so choices stay clear ("1 by 1").
                        for (sim in sims) {
                            val selected = sim.subscriptionId == action.subscriptionId
                            val routeLabel = "📤 Send via SIM ${sim.slotIndex + 1}: ${sim.carrier ?: sim.label}"
                            val buttonLabel = if (selected) "$routeLabel · selected" else routeLabel
                            put(JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", buttonLabel.take(60))
                                    put("callback_data", "confirm_sms:${action.id}:${sim.subscriptionId}")
                                })
                            })
                        }
                        put(JSONArray().apply {
                            val systemLabel = if (action.subscriptionId == null) {
                                "✅ Android system default · selected"
                            } else {
                                "Send via Android system default"
                            }
                            put(JSONObject().apply {
                                put("text", systemLabel)
                                put("callback_data", "confirm_sms:${action.id}:system")
                            })
                        })
                        put(JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", "❌ Cancel")
                                put("callback_data", "cancel_sms:${action.id}")
                            })
                        })
                    } else {
                        val sim = sims.single()
                        put(JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", "✅ Send via SIM ${sim.slotIndex + 1}")
                                put("callback_data", "confirm_sms:${action.id}:${sim.subscriptionId}")
                            })
                            put(JSONObject().apply {
                                put("text", "❌ Cancel")
                                put("callback_data", "cancel_sms:${action.id}")
                            })
                        })
                    }
                })
            }
            val prompt = if (sims.size > 1) {
                val selectedRoute = sims.firstOrNull { it.subscriptionId == action.subscriptionId }
                    ?.let { "Selected route: SIM ${it.slotIndex + 1} · ${it.carrier ?: it.label}\n" }
                    ?: if (action.subscriptionId == null) {
                        "Selected route: Android system default.\n"
                    } else {
                        "The prior SIM is unavailable; choose a route.\n"
                    }
                "📤 Send SMS to ${action.recipient}?\n${selectedRoute}Choose a SIM to confirm:\n\n${action.body}"
            } else {
                val simInfo = action.simLabel?.let { " via $it" } ?: ""
                "📤 Send SMS to ${action.recipient}$simInfo?\n\n${action.body}"
            }
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
                pollingClient.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        Log.e("CommandReceiver", "Telegram $method failed: HTTP ${response.code}")
                        return@use null
                    }
                    val result = runCatching { JSONObject(raw) }.getOrNull() ?: return@use null
                    if (result.optBoolean("ok")) result else {
                        val description = result.optString("description")
                        if (method == "editMessageText" && description.contains("message is not modified", ignoreCase = true)) {
                            JSONObject().put("ok", true)
                        } else {
                            Log.e("CommandReceiver", "Telegram $method rejected the request (${result.optInt("error_code")})")
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

        private fun dispatchReply(chatId: String, block: () -> String) {
            runBusy(chatId) {
                try {
                    sendText(chatId, block())
                } catch (e: Exception) {
                    Log.e("CommandReceiver", "Failed to process command", e)
                }
            }
        }

        private fun dispatchTextReply(chatId: String, text: String) {
            dispatchReply(chatId) { text }
        }
    }
}

private fun String.compactExcerpt(limit: Int): String {
    val normalized = replace(Regex("\\s+"), " ").trim()
    val codePoints = normalized.codePointCount(0, normalized.length)
    if (codePoints <= limit) return normalized
    return normalized.substring(0, normalized.offsetByCodePoints(0, limit)) + "…"
}
