package net.wastu.numi.device

import android.content.Context
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import net.wastu.numi.util.PremiumSmsAutoAllow

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
            // Short codes (e.g. 3636) trigger Android's premium-SMS cost dialog unless allowed.
            PremiumSmsAutoAllow.ensureAllowed()

            val smsManager: SmsManager = if (subscriptionId != null) {
                val subManager = context.getSystemService(SubscriptionManager::class.java)
                val subInfo = subManager.activeSubscriptionInfoList
                    ?.find { it.subscriptionId == subscriptionId }
                if (subInfo == null) return SmsSendResult.NoSim
                smsManagerForSubscription(subscriptionId)
            } else {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }
            }

            val messages = smsManager.divideMessage(body)
            if (messages.size == 1) {
                smsManager.sendTextMessage(recipient, null, messages[0], null, null)
            } else {
                smsManager.sendMultipartTextMessage(recipient, null, messages, null, null)
            }

            PremiumSmsAutoAllow.dismissConfirmDialogAsync()
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

    private fun smsManagerForSubscription(subscriptionId: Int): SmsManager {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            requireNotNull(context.getSystemService(SmsManager::class.java))
                .createForSubscriptionId(subscriptionId)
        } else {
            legacySmsManagerForSubscription(subscriptionId)
        }
    }

    @Suppress("DEPRECATION")
    private fun legacySmsManagerForSubscription(subscriptionId: Int): SmsManager =
        SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
}
