package com.bgwastu.numi.device

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

object DeviceStatusProvider {
    fun summary(context: Context): String = buildString {
        appendLine("📟 Numi device status")
        appendLine("Battery: ${batterySummary(context)}")
        appendLine("Network: ${networkSummary(context)}")
        appendLine("SIMs: ${simSummary(context)}")
    }.trimEnd()

    fun activeSims(context: Context): List<SimInfo> {
        val subManager = context.getSystemService(SubscriptionManager::class.java)
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val hasPhone = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        return try {
            subManager.activeSubscriptionInfoList.orEmpty().map { subscription ->
                val number: String? = if (hasPhone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        telephony.createForSubscriptionId(subscription.subscriptionId).line1Number
                            ?.takeIf { it.isNotBlank() }
                    } catch (_: Exception) { null }
                } else if (hasPhone) {
                    @Suppress("DEPRECATION")
                    try {
                        // On older Android, line1Number is per-device, not per-subscription
                        if (subscription.simSlotIndex == 0) telephony.line1Number?.takeIf { it.isNotBlank() }
                        else null
                    } catch (_: Exception) { null }
                } else null

                val label = listOfNotNull(
                    number?.let { "+$it" },
                    subscription.displayName?.toString()?.takeIf { it.isNotBlank() },
                    subscription.carrierName?.toString()?.takeIf { it.isNotBlank() }
                ).distinct().joinToString(" · ").ifBlank { "SIM ${subscription.simSlotIndex + 1}" }

                SimInfo(
                    subscriptionId = subscription.subscriptionId,
                    slotIndex = subscription.simSlotIndex,
                    label = label,
                    number = number
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
            val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            if (caps == null) return "offline"
            val transport = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi‑Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "connected"
            }
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
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
    val label: String,
    val number: String? = null
)
