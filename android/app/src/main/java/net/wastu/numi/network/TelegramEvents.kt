package net.wastu.numi.network

import android.content.Context
import net.wastu.numi.bus.TelegramOwnerConfig
import net.wastu.numi.messaging.AppStore
import net.wastu.numi.messaging.QueuedMessage
import net.wastu.numi.queue.OutboxService
import org.json.JSONObject

object TelegramEvents {
    fun enabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("numi_settings", Context.MODE_PRIVATE)
        return prefs.getBoolean("TELEGRAM_ENABLED", true) &&
            !prefs.getString("BOT_TOKEN", "").isNullOrBlank() &&
            TelegramOwnerConfig.normalize(prefs.getString("TELEGRAM_OWNER_ID", null)).isNotBlank()
    }

    fun incoming(context: Context, providerId: Long, address: String, body: String, sub: Int?) {
        if (!allowsIncomingAlert(context, providerId)) return
        if (!enabled(context)) return
        AppStore(context).queueEvent(address, body, sub, "received:$providerId", JSONObject().apply {
            put("kind", "sms"); put("direction", "incoming"); put("source_id", providerId)
        })
        OutboxService.wake(context)
    }

    fun allowsIncomingAlert(context: Context, providerId: Long): Boolean =
        net.wastu.numi.spam.SpamStore(context).message(providerId)?.state?.let { it == "SAFE" } ?: true

    fun result(context: Context, row: QueuedMessage) {
        if (!enabled(context)) return
        val state = if (row.delivered) "DELIVERED" else row.state
        AppStore(context).queueEvent(row.address, row.body, row.subscriptionId, "sms-result:${row.id}:${row.attempts}:$state", JSONObject().apply {
            put("kind", "sms"); put("direction", "outgoing"); put("source_id", row.providerId)
            put("state", state); if (row.error != null) put("error", row.error)
            put("outbox_id", row.id)
        })
        OutboxService.wake(context)
    }

    fun call(context: Context, address: String, sub: Int?, key: String, title: String, event: JSONObject) {
        if (!enabled(context)) return
        AppStore(context).queueEvent(address, title, sub, "call:$key", event)
        OutboxService.wake(context)
    }
}
