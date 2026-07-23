package com.bgwastu.numi.device

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log

sealed class SmsSendResult {
    data object Sent : SmsSendResult()
    data object PermissionDenied : SmsSendResult()
    data object NoSim : SmsSendResult()
    data class Error(val reason: String) : SmsSendResult()
}

class SmsSender(private val context: Context) {

    fun sendSms(
        recipient: String,
        body: String,
        subscriptionId: Int? = null
    ): SmsSendResult {
        if (context.checkSelfPermission(android.Manifest.permission.SEND_SMS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return SmsSendResult.PermissionDenied
        }

        return try {
            val smsManager: SmsManager = if (subscriptionId != null &&
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
            ) {
                val subManager = context.getSystemService(SubscriptionManager::class.java)
                val subInfo = subManager.activeSubscriptionInfoList
                    ?.find { it.subscriptionId == subscriptionId }
                if (subInfo != null) {
                    SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
                } else {
                    SmsManager.getDefault()
                }
            } else {
                SmsManager.getDefault()
            }

            val messages = smsManager.divideMessage(body)
            val sentIntent = PendingIntent.getBroadcast(
                context, 0,
                Intent("com.bgwastu.numi.SMS_SENT"),
                PendingIntent.FLAG_IMMUTABLE
            )

            if (messages.size == 1) {
                smsManager.sendTextMessage(recipient, null, messages[0], sentIntent, null)
            } else {
                val sentIntents = ArrayList<PendingIntent>(messages.size)
                for (i in messages.indices) sentIntents.add(sentIntent)
                smsManager.sendMultipartTextMessage(recipient, null, messages, sentIntents, null)
            }

            Log.d("SmsSender", "SMS queued to $recipient (${messages.size} parts)")
            SmsSendResult.Sent
        } catch (e: SecurityException) {
            SmsSendResult.PermissionDenied
        } catch (e: IllegalArgumentException) {
            SmsSendResult.Error("invalid recipient: ${e.message}")
        } catch (e: Exception) {
            SmsSendResult.Error(e.message ?: "unknown error")
        }
    }
}
