package com.bgwastu.numi.device

import android.content.Context

object TelkomselCheck {
    const val KUOTA_DEST = "3636"
    const val KUOTA_BODY = "CEK"
    const val MASA_AKTIF_USSD = "*888#"

    fun preferredSubscriptionId(context: Context): Int? {
        val sims = DeviceStatusProvider.activeSims(context)
        return sims.firstOrNull { sim ->
            sim.carrier?.contains("telkomsel", ignoreCase = true) == true
        }?.subscriptionId ?: sims.singleOrNull()?.subscriptionId
    }

    fun requestKuota(context: Context): SmsSendResult {
        val sender = SmsSender(context)
        return sender.sendSms(
            recipient = KUOTA_DEST,
            body = KUOTA_BODY,
            subscriptionId = preferredSubscriptionId(context)
        )
    }

    fun requestMasaAktifUssd(context: Context, timeoutSec: Long = 25): Result<String> =
        UssdRunner.run(context, MASA_AKTIF_USSD, timeoutSec)
}
