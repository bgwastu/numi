package net.wastu.numi.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import net.wastu.numi.database.DbHelper
import net.wastu.numi.device.DeviceStatusProvider
import net.wastu.numi.network.TelegramHtml
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        if (intent.action == Telephony.Sms.Intents.SMS_DELIVER_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isNullOrEmpty()) return

            val dbHelper = DbHelper(context)
            val sender = messages[0].originatingAddress ?: "Unknown"

            val bodyBuilder = StringBuilder()
            for (msg in messages) {
                bodyBuilder.append(msg.messageBody)
            }
            val body = bodyBuilder.toString()
            val timestamp = messages[0].timestampMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
            val subscriptionId = intent.getIntExtra(
                SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID
            ).takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }

            dbHelper.recordIncomingSms(sender, body, timestamp, subscriptionId)

            val sim = subscriptionId?.let { id ->
                runCatching { DeviceStatusProvider.activeSims(context).firstOrNull { it.subscriptionId == id } }
                    .getOrNull()
            }
            val simTag = sim?.let { "SIM ${it.slotIndex + 1}" }
            val timeLabel = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
            val compactBody = body.replace(Regex("\\s+"), " ").trim()
            val excerpt = compactBody.takeCodePoints(180)

            val formattedTelegramMessage = buildString {
                append("📩 ${TelegramHtml.bold("SMS")} · ${TelegramHtml.code(sender)}")
                if (simTag != null) append(" · ${TelegramHtml.bold(simTag)}")
                append(" · ${TelegramHtml.code(timeLabel)}\n")
                append(TelegramHtml.escape(excerpt))
                if (excerpt.length < compactBody.length) append("…")
            }

            // Remote forwarding is optional; the local inbox works without a bot configured.
            val prefs = context.getSharedPreferences("numi_settings", Context.MODE_PRIVATE)
            val remoteReady = !prefs.getString("BOT_TOKEN", "").isNullOrBlank() &&
                !prefs.getString("ALLOWED_USER_IDS", "").isNullOrBlank()
            if (remoteReady) {
                val idempotencyKey = "sms:${sender.hashCode()}:$timestamp:${subscriptionId ?: "unknown"}"
                val rowId = dbHelper.enqueueMessage(
                    type = "TELEGRAM",
                    recipient = sender,
                    body = formattedTelegramMessage,
                    idempotencyKey = idempotencyKey,
                    subscriptionId = subscriptionId
                )
                if (rowId != -1L) {
                    Log.d("NumiSmsReceiver", "Enqueued incoming SMS from $sender for forwarding. Row ID: $rowId")
                } else {
                    Log.d("NumiSmsReceiver", "Duplicate SMS forwarding event from $sender at $timestamp")
                }
            }
        }
    }

    private fun String.takeCodePoints(limit: Int): String {
        if (codePointCount(0, length) <= limit) return this
        return substring(0, offsetByCodePoints(0, limit))
    }
}
