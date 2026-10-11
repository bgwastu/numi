package net.wastu.numi.network

import android.content.ContentValues
import android.content.Context
import net.wastu.numi.bus.TelegramOwnerConfig
import net.wastu.numi.device.DeviceStatusProvider
import net.wastu.numi.messaging.AppStore
import net.wastu.numi.messaging.MessageTarget
import net.wastu.numi.messaging.MessagesRepository
import net.wastu.numi.messaging.QueuedMessage
import net.wastu.numi.messaging.SendPolicy
import net.wastu.numi.utils.SmsAddress
import org.json.JSONArray
import org.json.JSONObject

class TelegramConnector(private val context: Context, token: String) {
    val api = TelegramApi(token)
    val botIdentity = token.substringBefore(':')
    private val prefs = context.getSharedPreferences("numi_settings", Context.MODE_PRIVATE)
    val owner: String = TelegramOwnerConfig.normalize(prefs.getString("TELEGRAM_OWNER_ID", null))
    val storageKey: String = "$botIdentity:$owner"
    private val store = AppStore(context)
    private var topicsAvailable = false

    suspend fun connect() {
        val bot = api.request("getMe") as JSONObject
        topicsAvailable = bot.optBoolean("has_topics_enabled")
        prefs.edit().putBoolean("telegram_topics_available", topicsAvailable)
            .putString("telegram_bot_username", bot.optString("username")).apply()
        val menuKey = "telegram_menu_${botIdentity}_$owner"
        if (prefs.getInt(menuKey, 0) < 5) {
            val commands = JSONArray()
            listOf("start" to "Open Numi controls", "send" to "Send SMS: number message", "inbox" to "Read your SMS inbox",
                "history" to "SMS conversation: number", "spam" to "Review and restore filtered SMS", "calls" to "Recent phone calls", "sim" to "Choose the SMS SIM", "status" to "Phone and connection status").forEach { (command, description) ->
                commands.put(JSONObject().put("command", command).put("description", description))
            }
            try {
                api.request("setMyCommands", JSONObject().put("commands", commands)
                    .put("scope", JSONObject().put("type", "chat").put("chat_id", owner)))
                prefs.edit().putInt(menuKey, 5).apply()
            } catch (exception: TelegramApiException) {
                // A newly configured owner may still need to start the private bot chat.
                if (exception.code != 400 && exception.code != 403) throw exception
            }
        }
    }

    suspend fun text(text: String, thread: Int? = null, replyTo: Int? = null, markup: JSONObject? = null): Int {
        val args = JSONObject().put("chat_id", owner).put("text", text).put("parse_mode", "HTML")
        if (thread != null) args.put("message_thread_id", thread)
        if (replyTo != null) args.put("reply_parameters", JSONObject().put("message_id", replyTo).put("allow_sending_without_reply", true))
        if (markup != null) args.put("reply_markup", markup)
        return (api.request("sendMessage", args) as JSONObject).getInt("message_id")
    }

    suspend fun forceReply(target: MessageTarget, thread: Int?, prompt: String) {
        val id = text(prompt, thread, markup = JSONObject().put("force_reply", true).put("selective", true))
        store.saveTarget(storageKey, id, target.address, target.subscriptionId)
    }

    suspend fun deliver(row: QueuedMessage) {
        val event = row.event?.let(::JSONObject) ?: JSONObject().put("kind", "sms").put("direction", "incoming")
        val kind = event.optString("kind")
        val outgoing = event.optString("direction") == "outgoing"
        val key = when {
            kind == "call" -> "call:${event.optString("call_id")}"
            outgoing -> "outgoing:${event.optLong("outbox_id")}".takeIf { event.optLong("outbox_id") > 0 }
            else -> null
        }
        val state = when (event.optString("state")) {
            "SENT" -> "Sent"
            "DELIVERED" -> "Delivered"
            "FAILED" -> "Failed · ${event.optString("error", "Open Numi for details.").take(200)}"
            else -> null
        }
        val messages = IncomingSmsTelegramMessage.format(context, row.address, row.body, row.createdAt, row.subscriptionId, state)
        val markup = markupFor(event, row.address)
        if (key != null) store.receipt(storageKey, key)?.let { existing ->
            try {
                edit(existing, messages.last(), markup)
                return
            } catch (exception: TelegramApiException) {
                if (exception.description.contains("message is not modified", true)) return
                if (exception.code != 400) throw exception
            }
        }
        var thread = thread(row.address, row.subscriptionId)
        var previous = row.telegramMessageId
        for (index in row.telegramPart.coerceIn(0, messages.size) until messages.size) {
            val last = index == messages.lastIndex
            val id = try {
                text(messages[index], thread, previous, markup.takeIf { last })
            } catch (exception: TelegramApiException) {
                if (thread == null || exception.code != 400 ||
                    !exception.description.contains("thread", true)) throw exception
                store.forgetTopic(storageKey, thread)
                thread = null
                text(messages[index], null, previous, markup.takeIf { last })
            }
            store.update(row.id, ContentValues().apply {
                put("telegram_part_index", index + 1); put("telegram_last_message_id", id)
            })
            store.saveTarget(storageKey, id, row.address, row.subscriptionId)
            previous = id
        }
        if (key != null && previous != null) store.saveReceipt(storageKey, key, previous, thread)
    }

    private suspend fun thread(address: String, sub: Int?): Int? {
        if (!prefs.getBoolean("telegram_topics_available", topicsAvailable) || !prefs.getBoolean("TELEGRAM_TOPICS", true) || SmsAddress.normalize(address) == null) return null
        store.topic(storageKey, address, sub)?.let { return it }
        val name = MessagesRepository(context).name(address)
        val sim = DeviceStatusProvider.activeSims(context).firstOrNull { it.subscriptionId == sub }
        val title = "$name${sim?.let { " · SIM ${it.slotIndex + 1}" }.orEmpty()}".take(128)
        return try {
            val result = api.request("createForumTopic", JSONObject().put("chat_id", owner).put("name", title)) as JSONObject
            result.getInt("message_thread_id").also { store.saveTopic(storageKey, address, sub, it) }
        } catch (exception: TelegramApiException) {
            if (exception.code != 400 && exception.code != 403) throw exception
            topicsAvailable = false
            prefs.edit().putBoolean("telegram_topics_available", false).apply()
            null
        }
    }

    private suspend fun edit(id: Int, text: String, markup: JSONObject?) {
        api.request("editMessageText", JSONObject().put("chat_id", owner).put("message_id", id)
            .put("text", text).put("parse_mode", "HTML")
            .put("reply_markup", markup ?: JSONObject().put("inline_keyboard", JSONArray())))
    }

    private fun markupFor(event: JSONObject, address: String): JSONObject? {
        val rows = JSONArray()
        if (event.optString("kind") == "call") {
            val id = event.optString("call_id")
            val state = event.optString("state")
            if (state == "Incoming call") rows.put(JSONArray().put(button("Decline", "call:decline:$id")))
            if (state in listOf("Call connected", "Call on hold", "Outgoing call")) {
                rows.put(JSONArray().put(button("End call", "call:end:$id")))
            }
        }
        val smsActions = JSONArray()
        if (SendPolicy.canReply(address)) smsActions.put(button("Reply by SMS", "reply"))
        if (event.optString("kind") == "sms") smsActions.put(button("Conversation", "history"))
        if (smsActions.length() > 0) rows.put(smsActions)
        return if (rows.length() == 0) null else JSONObject().put("inline_keyboard", rows)
    }

    fun close() = api.close()

    companion object {
        fun button(label: String, action: String) = JSONObject().put("text", label).put("callback_data", action)
        fun homeMarkup(): JSONObject = JSONObject().put("inline_keyboard", JSONArray()
            .put(JSONArray().put(button("Inbox", "inbox")).put(button("Calls", "calls")))
            .put(JSONArray().put(button("New SMS", "compose")).put(button("Spam", "spam")))
            .put(JSONArray().put(button("Device", "status"))))
    }
}
