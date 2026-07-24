package com.bgwastu.numi.bus

import android.content.Context
import android.util.Log
import com.bgwastu.numi.database.DbHelper
import com.bgwastu.numi.bus.CommandError
import com.bgwastu.numi.bus.PendingSmsAction
import com.bgwastu.numi.bus.TelegramActionStore
import com.bgwastu.numi.device.DevicePermissionFixer
import com.bgwastu.numi.device.DeviceStatusProvider
import com.bgwastu.numi.device.SimInfo
import com.bgwastu.numi.device.TelkomselCheck
import com.bgwastu.numi.device.UssdRunner
import com.bgwastu.numi.network.TelegramHtml
import com.bgwastu.numi.network.TelegramSender
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
                    put(JSONObject().apply {
                        put("command", "status")
                        put("description", "Device status, SIMs, signal & permissions")
                    })
                    put(JSONObject().apply {
                        put("command", "check")
                        put("description", "Phone number lookup")
                    })
                    put(JSONObject().apply {
                        put("command", "send")
                        put("description", "Send SMS via device")
                    })
                    put(JSONObject().apply {
                        put("command", "ussd")
                        put("description", "Dial USSD code, e.g. /ussd *888#")
                    })
                    put(JSONObject().apply {
                        put("command", "refresh")
                        put("description", "Refresh GetContact credentials")
                    })
                    put(JSONObject().apply {
                        put("command", "help")
                        put("description", "List available commands")
                    })
                }
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()

                // Register for multiple scopes so the "/" menu appears in private chats.
                val scopes = mutableListOf<JSONObject?>(
                    null, // default
                    JSONObject().put("type", "all_private_chats"),
                    JSONObject().put("type", "all_group_chats")
                )
                prefs.getString("TARGET_CHAT_ID", null)?.toLongOrNull()?.let { chatId ->
                    scopes += JSONObject().put("type", "chat").put("chat_id", chatId)
                }

                for (scope in scopes) {
                    val body = JSONObject().apply {
                        put("commands", commands)
                        if (scope != null) put("scope", scope)
                    }
                    val request = Request.Builder()
                        .url("https://api.telegram.org/bot$botToken/setMyCommands")
                        .post(body.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    client.newCall(request).execute().use { response ->
                        val respBody = response.body?.string().orEmpty()
                        val label = scope?.optString("type") ?: "default"
                        if (response.isSuccessful) {
                            Log.i("CommandReceiver", "Commands registered ($label): $respBody")
                        } else {
                            Log.w("CommandReceiver", "Command registration failed ($label): HTTP ${response.code} $respBody")
                        }
                    }
                }

                // Ensure the chat menu button opens the command list.
                val menuBody = JSONObject().apply {
                    put("menu_button", JSONObject().put("type", "commands"))
                    prefs.getString("TARGET_CHAT_ID", null)?.toLongOrNull()?.let { put("chat_id", it) }
                }
                val menuReq = Request.Builder()
                    .url("https://api.telegram.org/bot$botToken/setChatMenuButton")
                    .post(menuBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(menuReq).execute().use { response ->
                    Log.i(
                        "CommandReceiver",
                        "Menu button: HTTP ${response.code} ${response.body?.string().orEmpty()}"
                    )
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
            if (text.isBlank()) return

            // -- REPLY-TO-MESSAGE --
            val replyTo = message.optJSONObject("reply_to_message")
            if (replyTo != null && text.isNotBlank()) {
                val replyMsgId = replyTo.optInt("message_id", 0)
                val replyTarget = if (replyMsgId > 0) actionStore.getReplyTarget(replyMsgId) else null
                if (replyTarget?.source == "gc_captcha") {
                    handleCaptchaCode(chatId, text.trim(), replyTarget.phoneNumber, replyMsgId)
                    return
                }
                handleReplyForward(chatId, replyTo, text)
                return
            }

            // Pending captcha: accept a bare code (4–12 alnum) without requiring reply.
            val pendingCaptcha = actionStore.getPendingCaptcha(chatId)
            if (pendingCaptcha != null && text.trim().matches(Regex("^[A-Za-z0-9]{4,12}$"))) {
                handleCaptchaCode(chatId, text.trim(), pendingCaptcha.phoneNumber, null)
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
                dispatchTextReply(
                    chatId,
                    """
                    |Numi commands:
                    |/status - device status, SIMs, signal & permissions
                    |/check <number> - phone lookup
                    |/send <number> <message> - send SMS
                    |/ussd <code> - dial USSD (e.g. /ussd *888#)
                    |/refresh - refresh GetContact credentials
                    |/help - this list
                    |
                    |Tip: tap / next to the message box to open the command menu.
                    """.trimMargin()
                )
                return
            }

            // -- /status command --
            if (command.equals("/status", ignoreCase = true)) {
                sendStatus(chatId)
                return
            }

            // -- /ussd <code> --
            val ussdMatch = Regex("""^/ussd(?:@\S+)?\s+(\S+)$""", RegexOption.IGNORE_CASE).find(text.trim())
            if (ussdMatch != null) {
                handleUssdCommand(chatId, ussdMatch.groupValues[1].trim())
                return
            }

            // -- /send [number] [message] command --
            // Allow short codes (e.g. 3636) as well as E.164 numbers.
            val sendMatch = Regex("""^/send(?:@\S+)?\s+(\+?\d{3,15})\s+([\s\S]+)$""", RegexOption.IGNORE_CASE).find(text)
            if (sendMatch != null) {
                handleSendCommand(chatId, sendMatch.groupValues[1].trim(), sendMatch.groupValues[2].trim())
                return
            }

            // -- /refresh command --
            if (command.equals("/refresh", ignoreCase = true)) {
                dispatchTextReply(chatId, executor.execute("""NUMI_CMD v1
actor: owner
action: provider.refresh
args: e30=
"""))
                return
            }

            // -- /check <number> command — phone number lookup --
            val checkMatch = Regex("""^/(?:check|gc|lookup|analyze)(?:@\S+)?\s+(.+)$""", RegexOption.IGNORE_CASE).find(text)
            if (checkMatch != null) {
                val number = checkMatch.groupValues[1].trim()
                dispatchPhoneLookup(chatId, number)
                return
            }

            // -- Plain phone number auto-detect --
            val phoneClean = text.trim().replace(Regex("[^0-9+]"), "")
            if (phoneClean.length in 8..15 && phoneClean.all { it.isDigit() || it == '+' }) {
                dispatchPhoneLookup(chatId, phoneClean)
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
                    "Unrecognized message. Send a phone number, or use /help."
                )
            }
        }

        private fun handleCallback(callback: JSONObject) {
            val message = callback.optJSONObject("message") ?: return
            val chat = message.optJSONObject("chat") ?: return
            val chatId = chat.optLong("id", 0).toString()
            val targetChatId = prefs.getString("TARGET_CHAT_ID", null) ?: return
            if (chatId != targetChatId) return

            val data = callback.optString("data", "")
            val callbackId = callback.optString("id", "")
            val confirmMessageId = message.optInt("message_id", 0).takeIf { it > 0 }

            // Answer callback query to stop the spinner
            answerCallback(callbackId)

            when {
                data.startsWith("confirm_sms:") -> {
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
                        return
                    }
                    val subscriptionId = chosenSubId ?: pending.subscriptionId
                    val sims = DeviceStatusProvider.activeSims(context)
                    val simLabel = sims.firstOrNull { it.subscriptionId == subscriptionId }?.label
                        ?: pending.simLabel
                    val smsSender = com.bgwastu.numi.device.SmsSender(context)
                    val result = smsSender.sendSms(
                        recipient = pending.recipient,
                        body = pending.body,
                        subscriptionId = subscriptionId
                    )
                    actionStore.removePending(actionId)
                    val statusText = when (result) {
                        is com.bgwastu.numi.device.SmsSendResult.Sent -> {
                            val label = simLabel?.let { " via $it" } ?: ""
                            "✅ SMS queued to ${pending.recipient}$label"
                        }
                        is com.bgwastu.numi.device.SmsSendResult.PermissionDenied ->
                            "❌ SMS permission not granted. Grant SEND_SMS to Numi and try again."
                        is com.bgwastu.numi.device.SmsSendResult.NoSim ->
                            "❌ No active SIM available."
                        is com.bgwastu.numi.device.SmsSendResult.Error ->
                            "❌ Failed to send: ${result.reason}"
                    }
                    editOrSend(chatId, confirmMessageId, statusText)
                    // Anchor the outbound tip so the carrier's inbound reply threads under this.
                    if (result is com.bgwastu.numi.device.SmsSendResult.Sent && confirmMessageId != null) {
                        actionStore.saveThreadTip(pending.recipient, confirmMessageId)
                    }
                }
                data.startsWith("cancel_sms:") -> {
                    val actionId = data.removePrefix("cancel_sms:")
                    actionStore.removePending(actionId)
                    editOrSend(chatId, confirmMessageId, "❌ SMS cancelled.")
                }
                data == "fix_perms" -> {
                    Thread {
                        try {
                            val result = DevicePermissionFixer.fix(context)
                            val text = buildString {
                                appendLine(DeviceStatusProvider.summary(context))
                                appendLine()
                                append("🛠 $result")
                            }
                            editStatus(chatId, confirmMessageId, text)
                        } catch (e: Exception) {
                            Log.e("CommandReceiver", "fix_perms failed", e)
                            editOrSend(
                                chatId,
                                confirmMessageId,
                                "❌ Fix failed: ${e.message ?: "unknown error"}"
                            )
                        }
                    }.start()
                }
                data == "tsel_kuota" -> {
                    Thread {
                        try {
                            val result = TelkomselCheck.requestKuota(context)
                            when (result) {
                                is com.bgwastu.numi.device.SmsSendResult.Sent -> {
                                    val ackId = sendTextForId(
                                        chatId,
                                        "📶 Cek kuota sent (CEK → 3636). Reply will arrive as inbound SMS."
                                    )
                                    if (ackId != null) {
                                        actionStore.saveThreadTip(TelkomselCheck.KUOTA_DEST, ackId)
                                    }
                                }
                                is com.bgwastu.numi.device.SmsSendResult.PermissionDenied ->
                                    sendText(chatId, "❌ SMS permission denied. Use Fix on /status first.")
                                is com.bgwastu.numi.device.SmsSendResult.NoSim ->
                                    sendText(chatId, "❌ No active SIM.")
                                is com.bgwastu.numi.device.SmsSendResult.Error ->
                                    sendText(chatId, "❌ Failed to send: ${result.reason}")
                            }
                        } catch (e: Exception) {
                            Log.e("CommandReceiver", "tsel_kuota failed", e)
                            sendText(chatId, "❌ Cek kuota failed: ${e.message ?: "unknown"}")
                        }
                    }.start()
                }
                data == "tsel_aktif" -> {
                    Thread {
                        try {
                            val pendingId = sendTextForId(chatId, "📅 Dialing *888# for masa aktif…")
                            if (pendingId != null) {
                                actionStore.saveThreadTip("TELKOMSEL", pendingId)
                            }
                            val ussd = TelkomselCheck.requestMasaAktifUssd(context)
                            if (ussd.isSuccess) {
                                val raw = ussd.getOrThrow()
                                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                                    .format(Date())
                                val msg = buildString {
                                    appendLine("📬 ${TelegramHtml.bold("USSD (*888#)")}")
                                    appendLine("${TelegramHtml.bold("From:")} ${TelegramHtml.code("TELKOMSEL")}")
                                    appendLine("${TelegramHtml.bold("Time:")} ${TelegramHtml.code(time)}")
                                    appendLine()
                                    append(TelegramHtml.pre(raw))
                                }
                                // Replace the dialing placeholder so it doesn't look stuck.
                                if (pendingId != null) {
                                    val edited = sendTelegram("editMessageText", JSONObject().apply {
                                        put("chat_id", chatId)
                                        put("message_id", pendingId)
                                        put("text", msg.take(4_000))
                                        put("parse_mode", "HTML")
                                    })
                                    if (edited != null) {
                                        actionStore.saveThreadTip("TELKOMSEL", pendingId)
                                        return@Thread
                                    }
                                }
                                val replyTo = actionStore.getThreadTip("TELKOMSEL")
                                val body = JSONObject().apply {
                                    put("chat_id", chatId)
                                    put("text", msg.take(4_000))
                                    put("parse_mode", "HTML")
                                    if (replyTo != null) put("reply_to_message_id", replyTo)
                                }
                                val sent = sendTelegram("sendMessage", body)
                                val mid = sent?.optJSONObject("result")?.optInt("message_id")
                                    ?.takeIf { it > 0 }
                                if (mid != null) actionStore.saveThreadTip("TELKOMSEL", mid)
                            } else {
                                val err =
                                    "❌ Masa aktif USSD failed: ${ussd.exceptionOrNull()?.message ?: "unknown"}"
                                if (pendingId != null) {
                                    editOrSend(chatId, pendingId, err)
                                } else {
                                    sendText(chatId, err)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("CommandReceiver", "tsel_aktif failed", e)
                            sendText(chatId, "❌ Cek masa aktif failed: ${e.message ?: "unknown"}")
                        }
                    }.start()
                }
            }
        }

        private fun sendStatus(chatId: String) {
            val text = DeviceStatusProvider.summary(context)
            val body = JSONObject().apply {
                put("chat_id", chatId)
                put("text", text.take(4_000))
                put("parse_mode", "HTML")
                put("reply_markup", statusKeyboard())
            }
            sendTelegram("sendMessage", body)
        }

        private fun editStatus(chatId: String, messageId: Int?, text: String) {
            if (messageId != null) {
                val body = JSONObject().apply {
                    put("chat_id", chatId)
                    put("message_id", messageId)
                    put("text", text.take(4_000))
                    put("parse_mode", "HTML")
                    put("reply_markup", statusKeyboard())
                }
                if (sendTelegram("editMessageText", body) != null) return
            }
            sendTelegram("sendMessage", JSONObject().apply {
                put("chat_id", chatId)
                put("text", text.take(4_000))
                put("parse_mode", "HTML")
                put("reply_markup", statusKeyboard())
            })
        }

        private fun statusKeyboard(): JSONObject {
            val rows = JSONArray().apply {
                put(JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", "📶 Cek kuota")
                        put("callback_data", "tsel_kuota")
                    })
                    put(JSONObject().apply {
                        put("text", "📅 Cek masa aktif")
                        put("callback_data", "tsel_aktif")
                    })
                })
                put(JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", "🔧 Auto-fix all permissions")
                        put("callback_data", "fix_perms")
                    })
                })
            }
            return JSONObject().put("inline_keyboard", rows)
        }

        private fun handleCaptchaCode(
            chatId: String,
            code: String,
            phoneNumber: String,
            replyMessageId: Int?
        ) {
            Thread {
                try {
                    sendText(chatId, "Verifying captcha…")
                    val verified = executor.verifyGetContactCaptcha(code)
                    if (verified.isFailure) {
                        val err = verified.exceptionOrNull()
                        val pe = err as? com.bgwastu.numi.provider.PhoneLookupException
                        // Stale reply target must not accept codes for a previous captcha image.
                        replyMessageId?.let { actionStore.removeReplyTarget(it) }
                        val msg = "Captcha failed: ${err?.message ?: "unknown error"}"
                        val imageSent = pe?.captchaImageBase64?.let { b64 ->
                            // Always send the rotated captcha image so the user never solves a stale one.
                            actionStore.savePendingCaptcha(
                                PendingCaptcha(chatId = chatId, phoneNumber = phoneNumber)
                            )
                            sendCaptchaPhoto(
                                chatId,
                                phoneNumber,
                                b64,
                                "$msg\nNew captcha — reply to this photo with the code."
                            )
                        } ?: false
                        if (!imageSent) {
                            actionStore.savePendingCaptcha(
                                PendingCaptcha(chatId = chatId, phoneNumber = phoneNumber)
                            )
                            sendText(
                                chatId,
                                "$msg\nNo new captcha image returned. Retry /check to get a fresh one."
                            )
                        }
                        return@Thread
                    }
                    actionStore.clearPendingCaptcha()
                    replyMessageId?.let { actionStore.removeReplyTarget(it) }
                    sendText(chatId, "Captcha accepted. Looking up $phoneNumber…")
                    dispatchPhoneLookup(chatId, phoneNumber)
                } catch (e: Exception) {
                    Log.e("CommandReceiver", "Captcha verify failed", e)
                    sendText(chatId, "Captcha verify failed due to an internal error")
                }
            }.start()
        }

        private fun handleReplyForward(chatId: String, replyTo: JSONObject, text: String) {
            val messageId = replyTo.optInt("message_id", 0)
            if (messageId <= 0) return
            val target = actionStore.getReplyTarget(messageId)
            if (target == null) {
                sendText(chatId, "No SMS reply target found for this message.")
                return
            }
            if (target.source == "gc_captcha") {
                handleCaptchaCode(chatId, text.trim(), target.phoneNumber, messageId)
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

        private fun handleUssdCommand(chatId: String, code: String) {
            Thread {
                val normalized = UssdRunner.normalizeUssd(code)
                if (normalized == null) {
                    sendText(chatId, "❌ Invalid USSD. Example: /ussd *888#")
                    return@Thread
                }
                val pendingId = sendTextForId(chatId, "📞 Dialing ${TelegramHtml.code(normalized)}…".let {
                    // sendTextForId doesn't set parse_mode — keep plain
                    "📞 Dialing $normalized…"
                })
                try {
                    val result = UssdRunner.run(context, normalized)
                    if (result.isSuccess) {
                        val raw = result.getOrThrow()
                        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                            .format(Date())
                        val msg = buildString {
                            appendLine("📬 ${TelegramHtml.bold("USSD")}")
                            appendLine("${TelegramHtml.bold("Code:")} ${TelegramHtml.code(normalized)}")
                            appendLine("${TelegramHtml.bold("Time:")} ${TelegramHtml.code(time)}")
                            appendLine()
                            append(TelegramHtml.pre(raw))
                        }
                        if (pendingId != null) {
                            val edited = sendTelegram("editMessageText", JSONObject().apply {
                                put("chat_id", chatId)
                                put("message_id", pendingId)
                                put("text", msg.take(4_000))
                                put("parse_mode", "HTML")
                            })
                            if (edited != null) return@Thread
                        }
                        sendTelegram("sendMessage", JSONObject().apply {
                            put("chat_id", chatId)
                            put("text", msg.take(4_000))
                            put("parse_mode", "HTML")
                        })
                    } else {
                        val err = "❌ USSD failed: ${result.exceptionOrNull()?.message ?: "unknown"}"
                        if (pendingId != null) editOrSend(chatId, pendingId, err) else sendText(chatId, err)
                    }
                } catch (e: Exception) {
                    Log.e("CommandReceiver", "ussd failed", e)
                    val err = "❌ USSD failed: ${e.message ?: "unknown"}"
                    if (pendingId != null) editOrSend(chatId, pendingId, err) else sendText(chatId, err)
                }
            }.start()
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
            sims: List<com.bgwastu.numi.device.SimInfo> = DeviceStatusProvider.activeSims(context)
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

        private fun answerCallback(callbackId: String) {
            val body = JSONObject().apply {
                put("callback_query_id", callbackId)
            }
            sendTelegram("answerCallbackQuery", body)
        }

        private fun dispatchPhoneLookup(chatId: String, phoneNumber: String) {
            Thread {
                try {
                    sendText(chatId, "Looking up $phoneNumber…")
                    val batch = executor.checkNumber(phoneNumber)
                    if (batch.results.isEmpty() && batch.errors.isEmpty()) {
                        sendText(chatId, "No providers available. Extract GetContact credentials first (/refresh).")
                        return@Thread
                    }

                    for (error in batch.errors) {
                        val isCaptcha = error.errorCode == "403004" ||
                            error.message.contains("validation", ignoreCase = true) ||
                            error.captchaImageBase64 != null
                        if (isCaptcha && error.captchaImageBase64 != null) {
                            actionStore.savePendingCaptcha(
                                PendingCaptcha(chatId = chatId, phoneNumber = phoneNumber)
                            )
                            val caption =
                                "Auto-solve failed. GetContact captcha for $phoneNumber\n" +
                                    "Reply to this photo with the code (or just send the code)."
                            sendCaptchaPhoto(chatId, phoneNumber, error.captchaImageBase64, caption)
                            continue
                        }
                        sendText(chatId, "${error.provider}: ${error.message}")
                    }

                    if (batch.results.isEmpty()) return@Thread

                    for (result in batch.results) {
                        val header = buildLookupHeader(result)
                        val imageSent = result.profileImageUrl?.let { url ->
                            sendPhoto(chatId, url, header)
                        } ?: false
                        if (!imageSent) sendText(chatId, header)

                        if (result.tags.isEmpty()) {
                            sendText(chatId, "No tags from ${result.provider}.")
                        } else {
                            val chunks = buildTagChunks(result)
                            for (chunk in chunks) {
                                if (!sendText(chatId, chunk)) break
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CommandReceiver", "Lookup failed", e)
                    sendText(chatId, "Lookup failed due to an internal error")
                }
            }.start()
        }

        private fun sendCaptchaPhoto(
            chatId: String,
            phoneNumber: String,
            jpegBase64: String,
            caption: String
        ): Boolean {
            val messageId = sendPhotoBase64(
                chatId = chatId,
                jpegBase64 = jpegBase64,
                caption = caption,
                forceReply = true
            )
            if (messageId != null) {
                actionStore.saveReplyTarget(
                    messageId,
                    ReplyTarget(phoneNumber = phoneNumber, source = "gc_captcha")
                )
                return true
            }
            return false
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
            }) != null

        /** Returns Telegram message_id on success. */
        private fun sendPhotoBase64(
            chatId: String,
            jpegBase64: String,
            caption: String,
            forceReply: Boolean = false
        ): Int? {
            return try {
                val bytes = android.util.Base64.decode(jpegBase64, android.util.Base64.DEFAULT)
                val boundary = "----NumiBoundary${System.currentTimeMillis()}"
                val crlf = "\r\n"
                val forceReplyJson = if (forceReply) {
                    """{"force_reply":true,"selective":true}"""
                } else null
                val preamble = buildString {
                    append("--$boundary$crlf")
                    append("Content-Disposition: form-data; name=\"chat_id\"$crlf$crlf")
                    append(chatId).append(crlf)
                    append("--$boundary$crlf")
                    append("Content-Disposition: form-data; name=\"caption\"$crlf$crlf")
                    append(caption.take(1_000)).append(crlf)
                    if (forceReplyJson != null) {
                        append("--$boundary$crlf")
                        append("Content-Disposition: form-data; name=\"reply_markup\"$crlf$crlf")
                        append(forceReplyJson).append(crlf)
                    }
                    append("--$boundary$crlf")
                    append("Content-Disposition: form-data; name=\"photo\"; filename=\"captcha.jpg\"$crlf")
                    append("Content-Type: image/jpeg$crlf$crlf")
                }.toByteArray(Charsets.UTF_8)
                val epilogue = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
                val bodyBytes = preamble + bytes + epilogue
                val body = bodyBytes.toRequestBody("multipart/form-data; boundary=$boundary".toMediaType())
                val request = Request.Builder()
                    .url("https://api.telegram.org/bot$botToken/sendPhoto")
                    .post(body)
                    .build()
                pollingClient.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        Log.e("CommandReceiver", "sendPhotoBase64 failed: HTTP ${response.code} $raw")
                        return null
                    }
                    JSONObject(raw).optJSONObject("result")?.optInt("message_id")
                        ?.takeIf { it > 0 }
                }
            } catch (e: Exception) {
                Log.e("CommandReceiver", "sendPhotoBase64 failed", e)
                null
            }
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
                    if (response.isSuccessful) {
                        try {
                            JSONObject(raw)
                        } catch (_: Exception) {
                            JSONObject()
                        }
                    } else {
                        Log.e("CommandReceiver", "Telegram $method failed: HTTP ${response.code} $raw")
                        null
                    }
                }
            } catch (e: IOException) {
                Log.e("CommandReceiver", "Telegram $method I/O failure", e)
                null
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
