package com.bgwastu.numi.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.bgwastu.numi.database.DbHelper
import com.bgwastu.numi.network.TelegramHtml
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
            val timestamp = System.currentTimeMillis()

            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val dateStr = sdf.format(Date(timestamp))

            val formattedTelegramMessage = buildString {
                appendLine("📬 ${TelegramHtml.bold("Inbound SMS")}")
                appendLine("${TelegramHtml.bold("From:")} ${TelegramHtml.code(sender)}")
                appendLine("${TelegramHtml.bold("Time:")} ${TelegramHtml.code(dateStr)}")
                appendLine()
                append(TelegramHtml.pre(body))
            }

            // recipient = sender phone so OutboxService can attach a reply target
            val idempotencyKey = "sms:${sender.hashCode()}:${timestamp / 1000}"
            val rowId = dbHelper.enqueueMessage("TELEGRAM", sender, formattedTelegramMessage, idempotencyKey)

            if (rowId != -1L) {
                Log.d("NumiSmsReceiver", "Enqueued incoming SMS from $sender for forwarding. Row ID: $rowId")
            } else {
                Log.d("NumiSmsReceiver", "Duplicate or ignored SMS from $sender at $timestamp")
            }
        }
    }
}
