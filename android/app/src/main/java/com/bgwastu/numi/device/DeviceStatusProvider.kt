package com.bgwastu.numi.device

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.bgwastu.numi.network.TelegramHtml
import java.net.Inet4Address
import java.net.NetworkInterface

object DeviceStatusProvider {
    fun summary(context: Context): String = buildString {
        appendLine("📟 ${TelegramHtml.bold("Numi device status")}")
        appendLine("${TelegramHtml.bold("Battery:")} ${TelegramHtml.code(batterySummary(context))}")
        appendLine(TelegramHtml.bold("Network:"))
        append(networkSummaryHtml())
        appendLine()
        appendLine(TelegramHtml.bold("Cellular:"))
        append(NetworkMeter.snapshot(context).cellularBlockHtml())
        appendLine()
        append(DevicePermissionFixer.health(context).summaryBlockHtml())
    }.trimEnd()

    fun activeSims(context: Context): List<SimInfo> {
        val subManager = context.getSystemService(SubscriptionManager::class.java)
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val hasPhone = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        return try {
            subManager.activeSubscriptionInfoList.orEmpty()
                .sortedBy { it.simSlotIndex }
                .map { subscription ->
                    val number: String? = if (hasPhone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        try {
                            telephony.createForSubscriptionId(subscription.subscriptionId).line1Number
                                ?.takeIf { it.isNotBlank() }
                        } catch (_: Exception) {
                            null
                        }
                    } else if (hasPhone) {
                        @Suppress("DEPRECATION")
                        try {
                            if (subscription.simSlotIndex == 0) {
                                telephony.line1Number?.takeIf { it.isNotBlank() }
                            } else {
                                null
                            }
                        } catch (_: Exception) {
                            null
                        }
                    } else {
                        null
                    }

                    val normalized = number?.let { if (it.startsWith("+")) it else "+$it" }
                    val carrier = subscription.carrierName?.toString()?.takeIf { it.isNotBlank() }
                        ?: subscription.displayName?.toString()?.takeIf { it.isNotBlank() }

                    SimInfo(
                        subscriptionId = subscription.subscriptionId,
                        slotIndex = subscription.simSlotIndex,
                        label = listOfNotNull(normalized, carrier).joinToString(" - ")
                            .ifBlank { "SIM ${subscription.simSlotIndex + 1}" },
                        number = normalized,
                        carrier = carrier
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
        val chargeLabel = if (charging) "Charging" else "Not Charging"
        return if (percent != null) "$percent% - $chargeLabel" else chargeLabel
    }

    private fun networkSummaryHtml(): String {
        val interfaces = interfaceAddresses()
        val publicIp = fetchPublicIpv4()
        return buildString {
            appendLine("- Public IP: ${TelegramHtml.code(publicIp ?: "unavailable")}")
            if (interfaces.isEmpty()) {
                append("- (no interfaces)")
            } else {
                interfaces.forEachIndexed { index, (name, ip) ->
                    val label = friendlyInterfaceName(name)
                    val line = "- $label: ${TelegramHtml.code(ip)}"
                    if (index == interfaces.lastIndex) append(line) else appendLine(line)
                }
            }
        }
    }

    private fun fetchPublicIpv4(): String? {
        val urls = listOf(
            "https://api.ipify.org",
            "https://ifconfig.me/ip",
            "https://icanhazip.com"
        )
        for (url in urls) {
            try {
                val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 3_000
                    readTimeout = 3_000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "numi")
                    instanceFollowRedirects = true
                }
                connection.inputStream.bufferedReader().use { reader ->
                    val body = reader.readText().trim()
                    if (body.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) return body
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun interfaceAddresses(): List<Pair<String, String>> {
        return try {
            NetworkInterface.getNetworkInterfaces()
                ?.toList()
                .orEmpty()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { nif ->
                    nif.inetAddresses.toList()
                        .asSequence()
                        .filterIsInstance<Inet4Address>()
                        .filter { !it.isLoopbackAddress }
                        .mapNotNull { addr ->
                            val host = addr.hostAddress ?: return@mapNotNull null
                            nif.name to host
                        }
                }
                .distinctBy { it.second }
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun friendlyInterfaceName(name: String): String = when {
        name.startsWith("wlan", ignoreCase = true) ||
            name.startsWith("wifi", ignoreCase = true) -> "Wi‑Fi"
        name.startsWith("rmnet", ignoreCase = true) ||
            name.startsWith("ccmni", ignoreCase = true) ||
            name.startsWith("mobile", ignoreCase = true) ||
            name.startsWith("wwan", ignoreCase = true) ||
            name.startsWith("usb", ignoreCase = true) && name.contains("rndis", ignoreCase = true) ->
            "Mobile"
        name.startsWith("tun", ignoreCase = true) ||
            name.startsWith("wg", ignoreCase = true) ||
            name.startsWith("ppp", ignoreCase = true) -> "VPN"
        name.startsWith("eth", ignoreCase = true) -> "Ethernet"
        else -> name
    }
}

data class SimInfo(
    val subscriptionId: Int,
    val slotIndex: Int,
    val label: String,
    val number: String? = null,
    val carrier: String? = null
)
