package net.wastu.numi.bus

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.telephony.SubscriptionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.wastu.numi.device.DeviceStatusProvider
import net.wastu.numi.messaging.AppStore
import net.wastu.numi.messaging.MessageTarget
import net.wastu.numi.messaging.MessagesRepository
import net.wastu.numi.messaging.SendPolicy
import net.wastu.numi.messaging.SimRouting
import net.wastu.numi.network.TelegramApiException
import net.wastu.numi.network.TelegramConnector
import net.wastu.numi.network.TelegramHtml
import net.wastu.numi.network.IncomingSmsTelegramMessage
import net.wastu.numi.queue.OutboxService
import net.wastu.numi.telephony.CallBridge
import net.wastu.numi.utils.SmsRoleManager
import net.wastu.numi.utils.SmsAddress
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** Owner-only private chat controls. SMS commands use the same sender as the phone UI. */
class CommandReceiver(private val context: Context, private val connector: TelegramConnector) {
    private val prefs = context.getSharedPreferences("numi_settings", Context.MODE_PRIVATE)
    private val store = AppStore(context)
    private val legacyActions = TelegramActionStore(context)
    private val offsetKey = "telegram_offset_${connector.botIdentity}"
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch {
            var connected = false
            while (isActive) {
                try {
                    if (!connected) { connector.connect(); connected = true }
                    val updates = connector.api.request("getUpdates", JSONObject()
                        .put("offset", prefs.getLong(offsetKey, if (prefs.getString("numi_legacy_bot_identity", "") == connector.botIdentity) prefs.getLong("telegram_offset", 0) else 0))
                        .put("timeout", 50).put("allowed_updates", JSONArray().put("message").put("callback_query"))) as JSONArray
                    TelegramPollHealth.recordSuccess(context)
                    for (index in 0 until updates.length()) {
                        val update = updates.getJSONObject(index)
                        handle(update)
                        check(prefs.edit().putLong(offsetKey, update.getLong("update_id") + 1).commit())
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    val error = when (exception) {
                        is TelegramApiException -> when (exception.code) {
                            401 -> "Bot token is invalid."
                            409 -> "Another instance is polling this bot, or a webhook is configured."
                            429 -> "Telegram is limiting requests; retrying."
                            else -> "Telegram API error ${exception.code}."
                        }
                        else -> "Connection interrupted; retrying."
                    }
                    TelegramPollHealth.recordError(context, error)
                    delay(((exception as? TelegramApiException)?.retryAfter?.toLong()?.times(1000) ?: 5000).coerceIn(1000, 300000))
                }
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    private suspend fun handle(update: JSONObject) {
        if (!net.wastu.numi.network.TelegramEvents.enabled(context) ||
            TelegramOwnerConfig.normalize(prefs.getString("TELEGRAM_OWNER_ID", "")) != connector.owner ||
            prefs.getString("BOT_TOKEN", "").orEmpty().substringBefore(':') != connector.botIdentity) return
        val callback = update.optJSONObject("callback_query")
        val message = callback?.optJSONObject("message") ?: update.optJSONObject("message") ?: return
        val actor = callback?.optJSONObject("from") ?: message.optJSONObject("from") ?: return
        val chat = message.optJSONObject("chat") ?: return
        if (!TelegramOwnerConfig.isAuthorized(connector.owner, actor.optString("id"), chat.optString("id"), chat.optString("type"))) return
        val thread = message.optInt("message_thread_id").takeIf { it > 0 }
        if (callback != null) {
            val action = callback.optString("data")
            var answer = ""
            try {
                when {
                    action == "inbox" -> inbox(thread)
                    action == "spam" -> spam(thread)
                    action.startsWith("spam:restore:") || action.startsWith("spam:allow:") -> {
                        val id = action.substringAfterLast(':').toLongOrNull()
                        require(id != null) { "This spam message is no longer available." }
                        require(net.wastu.numi.spam.SpamStore(context).restore(id, action.startsWith("spam:allow:"))) { "This message is no longer available." }
                        org.fossify.messages.helpers.refreshMessages(); org.fossify.messages.helpers.refreshConversations()
                        answer = "Restored to the inbox"
                    }
                    action == "calls" -> calls(thread)
                    action == "status" -> status(thread)
                    action == "compose" -> compose(thread)
                    action == "history" -> {
                        val recipient = target(message.getInt("message_id")) ?: thread?.let { store.topicTarget(connector.storageKey, it) }
                        require(recipient != null) { "Open the inbox to choose a conversation." }
                        conversation(recipient, thread)
                    }
                    action.startsWith("sim:") -> {
                        val sub = action.substringAfter(':').toIntOrNull()
                        require(sub != null && DeviceStatusProvider.activeSims(context).any { it.subscriptionId == sub }) { "This SIM is unavailable." }
                        prefs.edit().putInt("TELEGRAM_SIM", sub).commit()
                        answer = "Default SMS SIM updated."
                    }
                    action == "reply" || action == "reply_sms" -> {
                        val target = target(message.getInt("message_id")) ?: thread?.let { store.topicTarget(connector.storageKey, it) }
                        require(target != null && SendPolicy.canReply(target.address)) { "This sender cannot receive SMS replies." }
                        connector.forceReply(target, thread, "Reply with your SMS to ${TelegramHtml.code(target.address)}.")
                    }
                    action.startsWith("call:") -> {
                        val fields = action.split(':')
                        require(fields.size == 3) { "Invalid call action." }
                        answer = if (CallBridge.control(fields[2], fields[1])) "Call control requested." else "That call is no longer available for this action."
                    }
                    else -> answer = "This control has expired. Use /start."
                }
            } catch (exception: IllegalArgumentException) {
                answer = exception.message ?: "Could not complete this action."
            }
            connector.api.request("answerCallbackQuery", JSONObject().put("callback_query_id", callback.getString("id")).put("text", answer.take(180)))
            return
        }
        val text = message.optString("text")
        if (text.isBlank()) return
        val command = text.substringBefore(' ').substringBefore('@').lowercase()
        try {
            when (command) {
                "/start", "/help" -> connector.text(
                    "<b>Numi</b>\n\nReply to an SMS to answer it.\n\n/send number message\n/history number\n/inbox\n/spam\n/calls\n/sim\n/status",
                    thread, markup = TelegramConnector.homeMarkup())
                "/inbox" -> inbox(thread)
                "/spam" -> spam(thread)
                "/calls" -> calls(thread)
                "/status" -> status(thread)
                "/history" -> {
                    val address = text.substringAfter(' ', "").trim().ifBlank { null }
                    val recipient = address?.let { MessageTarget(it, null) } ?: thread?.let { store.topicTarget(connector.storageKey, it) }
                    require(recipient != null) { "Use /history number or open Conversation on an SMS." }
                    conversation(recipient, thread)
                }
                "/sim" -> sims(thread)
                "/new", "/compose" -> compose(thread)
                "/send" -> {
                    require(text.contains(' ')) { "Use /send number message." }
                    val (address, body) = parseCompose(text.substringAfter(' ').trim())
                    queue(update, message, MessageTarget(address, selectedSim()), body, thread)
                }
                else -> {
                    val repliedId = message.optJSONObject("reply_to_message")?.optInt("message_id")
                    val recipient = repliedId?.let(::target) ?: thread?.let { store.topicTarget(connector.storageKey, it) }
                    if (recipient != null) {
                        if (recipient.address == COMPOSE_TARGET) {
                            val (address, body) = parseCompose(text.trim())
                            queue(update, message, MessageTarget(address, selectedSim()), body, thread)
                        } else queue(update, message, recipient, text, thread)
                    } else connector.text("Reply to an SMS, open its topic, or use /send number message.", thread, markup = TelegramConnector.homeMarkup())
                }
            }
        } catch (exception: IllegalArgumentException) {
            connector.text(TelegramHtml.escape(exception.message ?: "Check the recipient and SIM, then try again."), thread)
        } catch (exception: IllegalStateException) {
            connector.text(TelegramHtml.escape(exception.message ?: "Open Numi and check phone access."), thread)
        }
    }

    private suspend fun queue(update: JSONObject, message: JSONObject, target: MessageTarget, body: String, thread: Int?) {
        require(SendPolicy.isFresh(message.optLong("date") * 1000, System.currentTimeMillis())) { "This SMS command is over 10 minutes old. Send it again to confirm your intent." }
        require(SmsRoleManager.isDefaultSmsApp(context)) { "Set Numi as the default SMS app on the phone first." }
        require(context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) { "Allow SMS access on the phone first." }
        val sub = resolveSim(target.subscriptionId)
        store.queueSms(target.address, body, sub, "telegram:${connector.botIdentity}:${connector.owner}:${update.getLong("update_id")}")
        MessagesRepository(context).markRead(target.address)
        OutboxService.wake(context)
        val sim = DeviceStatusProvider.activeSims(context).firstOrNull { it.subscriptionId == sub }
        connector.text("Queued, ${TelegramHtml.code(target.address)}${sim?.let { ", SIM ${it.slotIndex + 1}" }.orEmpty()}", thread)
    }

    private fun selectedSim(): Int? = prefs.getInt("TELEGRAM_SIM", -1).takeIf { it >= 0 }
    private fun resolveSim(requested: Int?): Int = SimRouting.resolve(requested,
        DeviceStatusProvider.activeSims(context).map { it.subscriptionId }, SubscriptionManager.getDefaultSmsSubscriptionId())

    private fun target(id: Int): MessageTarget? = store.target(connector.storageKey, id)
        ?: if (prefs.getString("numi_legacy_bot_identity", "") == connector.botIdentity &&
            TelegramOwnerConfig.normalize(prefs.getString("numi_legacy_owner", "")) == connector.owner) {
            legacyActions.getReplyTarget(id)?.let { MessageTarget(it.phoneNumber, it.subscriptionId) }
        } else null

    private suspend fun compose(thread: Int?) = connector.forceReply(MessageTarget(COMPOSE_TARGET, null), thread,
        "Reply with <code>number message</code>, for example <code>+621234567890 Hello</code>. This sends an SMS using your default SIM.")

    private fun parseCompose(text: String): Pair<String, String> {
        val split = text.indexOfFirst { it.isWhitespace() }
        require(split > 0 && split < text.lastIndex) { "Enter a number followed by the message." }
        return text.substring(0, split) to text.substring(split).trimStart()
    }

    private suspend fun inbox(thread: Int?) {
        require(context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) { "Allow SMS access on the phone first." }
        val conversations = MessagesRepository(context).conversations().take(10)
        if (conversations.isEmpty()) { connector.text("Your SMS inbox is empty.", thread); return }
        conversations.forEach { conversation ->
            val latest = conversation.latest
            val text = "<b>${TelegramHtml.escape(conversation.name)}</b>${if (conversation.unread > 0) ", ${conversation.unread} unread" else ""}\n" +
                "${TelegramHtml.escape(latest.body.take(180))}\n\n${TelegramHtml.code(conversation.address)}, ${TelegramHtml.escape(latest.status)}"
            val actions = JSONArray().put(TelegramConnector.button("Conversation", "history"))
            if (SendPolicy.canReply(conversation.address)) actions.put(TelegramConnector.button("Reply by SMS", "reply"))
            val markup = JSONObject().put("inline_keyboard", JSONArray().put(actions))
            val id = connector.text(text, thread, markup = markup)
            store.saveTarget(connector.storageKey, id, conversation.address, latest.subscriptionId)
        }
    }

    private suspend fun spam(thread: Int?) {
        val rows = net.wastu.numi.spam.SpamStore(context).spam()
        if (rows.isEmpty()) { connector.text("Your Spam folder is empty.", thread); return }
        connector.text("<b>Spam</b>, ${rows.size} messages kept on the phone. Showing the latest ${minOf(rows.size, 10)}.", thread)
        for (row in rows.take(10)) {
            val markup = JSONObject().put("inline_keyboard", JSONArray().put(JSONArray()
                .put(TelegramConnector.button("Not spam", "spam:restore:${row.id}"))
                .put(TelegramConnector.button("Always allow sender", "spam:allow:${row.id}"))))
            connector.text("${TelegramHtml.code(row.address)}\n${TelegramHtml.escape(row.body.take(2500))}\n\n${TelegramHtml.escape(row.reason)}", thread, markup = markup)
        }
    }

    private suspend fun conversation(target: MessageTarget, thread: Int?) {
        require(context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) { "Allow SMS access on the phone first." }
        val conversation = MessagesRepository(context).conversations().firstOrNull { SmsAddress.normalize(it.address) == SmsAddress.normalize(target.address) }
        if (conversation == null) { connector.text("This SMS conversation is empty.", thread); return }
        val messages = conversation.messages.takeLast(10)
        connector.text("<b>${TelegramHtml.escape(conversation.name)}</b>, latest ${messages.size} SMS", thread)
        messages.forEach { message ->
            val parts = IncomingSmsTelegramMessage.format(context, message.address, message.body, message.date, message.subscriptionId,
                if (message.incoming) "Received" else message.status)
            for (part in parts) {
                val id = connector.text(part, thread)
                store.saveTarget(connector.storageKey, id, message.address, message.subscriptionId)
            }
        }
        MessagesRepository(context).markRead(conversation.address)
    }

    private suspend fun calls(thread: Int?) {
        require(context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) { "Open Calls in Numi and allow call history access first." }
        val entries = mutableListOf<String>()
        context.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE),
            null, null, "${CallLog.Calls.DATE} DESC")?.use { cursor ->
            while (entries.size < 10 && cursor.moveToNext()) {
                val kind = when (cursor.getInt(3)) {
                    CallLog.Calls.INCOMING_TYPE -> "Incoming"
                    CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                    CallLog.Calls.MISSED_TYPE -> "Missed"
                    CallLog.Calls.REJECTED_TYPE -> "Declined"
                    else -> "Call"
                }
                val address = cursor.getString(0).orEmpty().ifBlank { "Unknown caller" }
                entries += "${TelegramHtml.code(address)}, $kind, ${cursor.getLong(2)}s\n${TelegramHtml.escape(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(cursor.getLong(1))))}"
            }
        }
        connector.text(if (entries.isEmpty()) "Your call history is empty." else "<b>Recent calls</b>\n\n${entries.joinToString("\n\n")}", thread)
    }

    private suspend fun sims(thread: Int?) {
        val sims = DeviceStatusProvider.activeSims(context)
        if (sims.isEmpty()) { connector.text("No active SIM is available. Check phone access in Numi.", thread); return }
        val rows = JSONArray()
        sims.forEach { rows.put(JSONArray().put(TelegramConnector.button("SIM ${it.slotIndex + 1}, ${it.label}", "sim:${it.subscriptionId}"))) }
        connector.text("Choose the default SIM for new SMS. Replies keep the SIM that received the message.", thread,
            markup = JSONObject().put("inline_keyboard", rows))
    }

    private suspend fun status(thread: Int?) {
        val sims = DeviceStatusProvider.activeSims(context).joinToString("\n") { "SIM ${it.slotIndex + 1}, ${TelegramHtml.escape(it.label)}" }.ifBlank { "No active SIM" }
        connector.text("<b>${TelegramHtml.escape(android.os.Build.MODEL)}</b>\n\n$sims\nSMS app: ${if (SmsRoleManager.isDefaultSmsApp(context)) "Numi" else "Set Numi as default"}\nSpam: ${net.wastu.numi.spam.SpamStore(context).spam().size} kept, ${TelegramHtml.escape(net.wastu.numi.spam.GemmaSpamFilter.status(context))}\n${TelegramHtml.escape(TelegramPollHealth.summary(context, true))}",
            thread, markup = TelegramConnector.homeMarkup())
    }

    companion object { private const val COMPOSE_TARGET = "#compose" }
}
