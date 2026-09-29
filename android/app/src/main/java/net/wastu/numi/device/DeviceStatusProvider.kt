package net.wastu.numi.device

import android.content.Context
import android.telephony.SubscriptionManager

object DeviceStatusProvider {
    fun activeSims(context: Context): List<SimInfo> {
        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
        return try {
            subscriptionManager.activeSubscriptionInfoList.orEmpty()
                .sortedBy { it.simSlotIndex }
                .map { subscription ->
                    val carrier = subscription.carrierName?.toString()?.takeIf { it.isNotBlank() }
                        ?: subscription.displayName?.toString()?.takeIf { it.isNotBlank() }
                    SimInfo(
                        subscriptionId = subscription.subscriptionId,
                        slotIndex = subscription.simSlotIndex,
                        label = carrier ?: "SIM ${subscription.simSlotIndex + 1}",
                        carrier = carrier
                    )
                }
        } catch (_: SecurityException) {
            emptyList()
        }
    }
}

data class SimInfo(
    val subscriptionId: Int,
    val slotIndex: Int,
    val label: String,
    val carrier: String? = null
)
