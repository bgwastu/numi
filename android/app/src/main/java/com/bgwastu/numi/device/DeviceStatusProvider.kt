package com.bgwastu.numi.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

object DeviceStatusProvider {
    fun summary(context: Context): String = buildString {
        appendLine("📟 Numi device status")
        appendLine("Battery: ${batterySummary(context)}")
        appendLine("Network: ${networkSummary(context)}")
        appendLine("SIMs: ${simSummary(context)}")
    }.trimEnd()

    fun activeSims(context: Context): List<SimInfo> {
        val manager = context.getSystemService(SubscriptionManager::class.java)
        return try {
            manager.activeSubscriptionInfoList.orEmpty().map { subscription ->
                val label = listOfNotNull(
                    subscription.displayName?.toString()?.takeIf { it.isNotBlank() },
                    subscription.carrierName?.toString()?.takeIf { it.isNotBlank() }
                ).distinct().joinToString(" · ").ifBlank { "SIM ${subscription.simSlotIndex + 1}" }
                SimInfo(
                    subscriptionId = subscription.subscriptionId,
                    slotIndex = subscription.simSlotIndex,
                    label = label
                )
            }
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    private fun batterySummary(context: Context): String {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return "unavailable"
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return listOfNotNull(percent?.let { "$it%" }, if (charging) "charging" else "not charging")
            .joinToString(" · ")
    }

    private fun networkSummary(context: Context): String {
        return try {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                ?: return@try "offline"
            val transport = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi‑Fi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "connected"
            }
            val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (validated) "$transport · internet reachable" else "$transport · no validated internet"
        } catch (_: SecurityException) {
            "unavailable (grant Network permission)"
        }
    }

    private fun simSummary(context: Context): String {
        val sims = activeSims(context)
        if (sims.isEmpty()) return "unavailable (grant Phone permission)"
        val defaultSmsSubId = SubscriptionManager.getDefaultSmsSubscriptionId()
        return sims.joinToString("; ") { sim ->
            "SIM ${sim.slotIndex + 1}: ${sim.label}" +
                if (sim.subscriptionId == defaultSmsSubId) " (default SMS)" else ""
        }
    }
}

data class SimInfo(
    val subscriptionId: Int,
    val slotIndex: Int,
    val label: String
)
