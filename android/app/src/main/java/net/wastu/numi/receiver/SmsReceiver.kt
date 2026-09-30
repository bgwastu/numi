package net.wastu.numi.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import net.wastu.numi.database.DbHelper
import net.wastu.numi.bus.TelegramOwnerConfig
import net.wastu.numi.utils.RecentActivity

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
            RecentActivity.record(context, "SMS received", timestamp)

            // Remote forwarding is optional; the local inbox works without a bot configured.
            val prefs = context.getSharedPreferences("numi_settings", Context.MODE_PRIVATE)
            val remoteReady = !prefs.getString("BOT_TOKEN", "").isNullOrBlank() &&
                TelegramOwnerConfig.normalize(prefs.getString("TELEGRAM_OWNER_ID", null)).isNotBlank()
            if (remoteReady) {
                val idempotencyKey = "sms:${sender.hashCode()}:$timestamp:${subscriptionId ?: "unknown"}"
                val rowId = dbHelper.enqueueMessage(
                    type = "TELEGRAM",
                    recipient = sender,
                    body = body,
                    idempotencyKey = idempotencyKey,
                    subscriptionId = subscriptionId
                )
                if (rowId != -1L) {
                    Log.d("NumiSmsReceiver", "Incoming SMS queued for Telegram forwarding")
                } else {
                    Log.d("NumiSmsReceiver", "Duplicate incoming SMS forwarding event ignored")
                }
            }
        }
    }
}
