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

            // -- /help command --
            if (command.equals("/help", ignoreCase = true) || command.equals("/start", ignoreCase = true)) {
                sendHome(chatId)
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
                data == "menu:inbox" -> sendInbox(chatId)
                data == "menu:home" -> sendHome(chatId)
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
                    val incoming = rowId?.let { id -> dbHelper.listInbox(500).firstOrNull { it.id == id } }
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
                        val subscriptionId = chosenSubId ?: pending.subscriptionId
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
                    actionStore.removePending(actionId)
                    editOrSend(chatId, confirmMessageId, "❌ SMS cancelled.")
                }
                data == "fix_perms" -> {
                    runBusy(chatId) {
                        try {
                            val result = DevicePermissionFixer.fix(context)
                            val text = "${gatewayStatusText()}\n\n🛠 ${TelegramHtml.escape(result)}"
                            editStatus(chatId, confirmMessageId, text)
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
            appendLine("Gateway: ${TelegramHtml.code("ONLINE")}")
            appendLine("SMS role: ${if (defaultSms) "✅ ready" else "⚠️ not enabled"}")
            appendLine("Send permission: ${if (smsPermission) "✅ granted" else "⚠️ missing"}")
            appendLine("Outbox: ${TelegramHtml.code("$queued queued · ${counts["FAILED"] ?: 0} failed")}")
            appendLine()
            appendLine(TelegramHtml.bold("SIM routes"))
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
            editStatus(chatId, editMessageId, gatewayStatusText())

        private fun editStatus(chatId: String, messageId: Int?, text: String) {
            if (messageId != null) {
                val edited = sendTelegram("editMessageText", JSONObject().apply {
                    put("chat_id", chatId)
                    put("message_id", messageId)
                    put("text", text.take(3_900))
                    put("parse_mode", "HTML")
                    put("reply_markup", statusKeyboard())
                })
                if (edited != null) return
            }
            sendTelegram("sendMessage", JSONObject().apply {
                put("chat_id", chatId)
                put("text", text.take(3_900))
                put("parse_mode", "HTML")
                put("reply_markup", statusKeyboard())
            })
        }

        private fun sendHome(chatId: String) {
            sendTelegram("sendMessage", JSONObject().apply {
                put("chat_id", chatId)
                put("text", "📡 ${TelegramHtml.bold("Numi SMS Gateway")}\nManage device SMS, multi-SIM routing, and recent inbound messages.")
                put("parse_mode", "HTML")
                put("reply_markup", homeKeyboard())
            })
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
            if (editMessageId != null) {
                val edited = sendTelegram("editMessageText", JSONObject().apply {
                    put("chat_id", chatId)
                    put("message_id", editMessageId)
                    put("text", text)
                    put("parse_mode", "HTML")
                    put("reply_markup", markup)
                })
                if (edited != null) return
            }
            sendTelegram("sendMessage", JSONObject().apply {
                put("chat_id", chatId)
                put("text", text)
                put("parse_mode", "HTML")
                put("reply_markup", markup)
            })
        }

        private fun sendInbox(chatId: String) {
            val incoming = dbHelper.listInbox(6)
            if (incoming.isEmpty()) {
                sendTelegram("sendMessage", JSONObject().apply {
                    put("chat_id", chatId)
                    put("text", "📥 No incoming SMS yet.")
                    put("reply_markup", homeKeyboard())
                })
                return
            }
            val sims = DeviceStatusProvider.activeSims(context).associateBy { it.subscriptionId }
            val rows = JSONArray()
            val text = buildString {
                appendLine("📥 ${TelegramHtml.bold("Recent SMS")} · ${incoming.size}")
                appendLine()
                incoming.forEachIndexed { index, sms ->
                    val sim = sms.subscriptionId?.let { sims[it] }
                    val simLabel = sim?.let { " · SIM ${it.slotIndex + 1}" }.orEmpty()
                    val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(sms.receivedAt))
                    appendLine("${TelegramHtml.bold(sms.sender)}$simLabel · ${TelegramHtml.code(time)}")
                    appendLine(TelegramHtml.escape(sms.body.compactExcerpt(170)))
                    if (index < incoming.lastIndex) appendLine()
                    rows.put(JSONArray().put(JSONObject().apply {
                        put("text", "↩ Reply · ${sms.sender}".take(60))
                        put("callback_data", "reply_inbox:${sms.id}")
                    }))
                }
                rows.put(JSONArray().put(JSONObject().apply {
                    put("text", "📤 New SMS")
                    put("callback_data", "menu:compose")
                }).put(JSONObject().apply {
                    put("text", "⌂ Home")
                    put("callback_data", "menu:home")
                }))
            }
            sendTelegram("sendMessage", JSONObject().apply {
                put("chat_id", chatId)
                put("text", text.take(3_900))
                put("parse_mode", "HTML")
                put("reply_markup", JSONObject().put("inline_keyboard", rows))
            })
        }

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
            put(JSONArray().put(JSONObject().put("text", "📊 Gateway status").put("callback_data", "menu:status"))
                .put(JSONObject().put("text", "📤 New SMS").put("callback_data", "menu:compose")))
            put(JSONArray().put(JSONObject().put("text", "📶 SIM routes").put("callback_data", "menu:sims"))
                .put(JSONObject().put("text", "📥 Recent SMS").put("callback_data", "menu:inbox")))
            put(JSONArray().put(JSONObject().put("text", "❔ Help").put("callback_data", "menu:home")))
        })

        private fun statusKeyboard(): JSONObject = JSONObject().put("inline_keyboard", JSONArray().apply {
            put(JSONArray().put(JSONObject().put("text", "📤 New SMS").put("callback_data", "menu:compose"))
                .put(JSONObject().put("text", "📶 SIM routes").put("callback_data", "menu:sims")))
            put(JSONArray().put(JSONObject().put("text", "📥 Recent SMS").put("callback_data", "menu:inbox"))
                .put(JSONObject().put("text", "🔄 Refresh").put("callback_data", "menu:status")))
            put(JSONArray().put(JSONObject().put("text", "🔧 Fix SMS permissions").put("callback_data", "fix_perms")))
            put(JSONArray().put(JSONObject().put("text", "⌂ Home").put("callback_data", "menu:home")))
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
            // Never auto-pick "default SMS" when multiple SIMs — ask in confirmation.
            val single = activeSims.singleOrNull()
            val pending = PendingSmsAction(
                chatId = chatId,
                recipient = number,
                body = body.take(1_000),
                subscriptionId = single?.subscriptionId,
                simLabel = single?.label
            )
            actionStore.savePending(pending)
            sendConfirmation(chatId, pending, activeSims)
        }

        private fun sendConfirmation(
            chatId: String,
            action: PendingSmsAction,
            sims: List<net.wastu.numi.device.SimInfo> = DeviceStatusProvider.activeSims(context)
        ) {
            val keyboard = JSONObject().apply {
                put("inline_keyboard", JSONArray().apply {
                    if (sims.size > 1) {
                        // One SIM button per row so choices stay clear ("1 by 1").
                        for (sim in sims) {
                            put(JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", "📤 SIM ${sim.slotIndex + 1}: ${sim.carrier ?: sim.label}")
                                    put("callback_data", "confirm_sms:${action.id}:${sim.subscriptionId}")
                                })
                            })
                        }
                        put(JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", "❌ Cancel")
                                put("callback_data", "cancel_sms:${action.id}")
                            })
                        })
                    } else {
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
                    }
                })
            }
            val prompt = if (sims.size > 1) {
                "📤 Send SMS to ${action.recipient}?\nChoose SIM:\n\n${action.body}"
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
                        Log.e("CommandReceiver", "Telegram $method rejected the request (${result.optInt("error_code")})")
                        null
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
