package com.bgwastu.numi.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CellInfoLte
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.bgwastu.numi.network.TelegramHtml

data class SimCellularLine(
    val slotIndex: Int,
    val number: String?,
    val level: Int?,
    val networkType: String,
    val carrier: String?,
    val inService: Boolean
) {
    fun toHtml(): String {
        val num = number ?: "unknown"
        val signal = when {
            !inService -> "NO SIGNAL"
            level != null -> "$level/4 $networkType"
            else -> networkType
        }
        val carrierLabel = carrier ?: "unknown"
        return "- SIM ${slotIndex + 1} - ${TelegramHtml.code(num)} - ${TelegramHtml.code(signal)} - ${TelegramHtml.escape(carrierLabel)}"
    }
}

data class NetworkMeterSnapshot(
    val inService: Boolean,
    val operator: String?,
    val networkType: String,
    val level: Int?,
    val detail: String,
    val lines: List<SimCellularLine> = emptyList()
) {
    fun cellularBlockHtml(): String {
        if (lines.isEmpty()) {
            return "- unavailable (grant Phone permission)"
        }
        return lines.joinToString("\n") { it.toHtml() }
    }
}

object NetworkMeter {
    fun snapshot(context: Context): NetworkMeterSnapshot {
        val sims = DeviceStatusProvider.activeSims(context)
        if (sims.isEmpty()) {
            return NetworkMeterSnapshot(
                inService = false,
                operator = null,
                networkType = "unknown",
                level = null,
                detail = "no SIM",
                lines = emptyList()
            )
        }

        val base = context.getSystemService(TelephonyManager::class.java)
        val hasPhone = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        val lines = sims.map { sim ->
            val tm = if (base != null) {
                try {
                    base.createForSubscriptionId(sim.subscriptionId)
                } catch (_: Exception) {
                    base
                }
            } else {
                null
            }
            val meter = readMeter(tm, hasPhone)
            SimCellularLine(
                slotIndex = sim.slotIndex,
                number = sim.number,
                level = meter.level,
                networkType = meter.networkType,
                carrier = sim.carrier ?: meter.operator,
                inService = meter.inService
            )
        }

        val primary = lines.firstOrNull { it.inService } ?: lines.first()
        return NetworkMeterSnapshot(
            inService = lines.any { it.inService },
            operator = primary.carrier,
            networkType = primary.networkType,
            level = primary.level,
            detail = buildString {
                append(if (primary.inService) "in service" else "no signal")
                append(" · ${primary.networkType}")
                primary.carrier?.let { append(" · $it") }
                primary.level?.let { append(" · $it/4") }
            },
            lines = lines
        )
    }

    private data class MeterRead(
        val inService: Boolean,
        val operator: String?,
        val networkType: String,
        val level: Int?
    )

    private fun readMeter(tm: TelephonyManager?, hasPhone: Boolean): MeterRead {
        if (tm == null || !hasPhone) {
            return MeterRead(false, null, "unknown", null)
        }

        val serviceState = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) tm.serviceState else null
        } catch (_: Exception) {
            null
        }
        val inService = serviceState?.state == ServiceState.STATE_IN_SERVICE ||
            (serviceState == null && tm.simState == TelephonyManager.SIM_STATE_READY)

        val operator = tm.networkOperatorName?.takeIf { it.isNotBlank() }
            ?: tm.simOperatorName?.takeIf { it.isNotBlank() }

        val networkType = networkTypeLabel(
            try {
                @Suppress("DEPRECATION")
                tm.dataNetworkType.takeIf { it != TelephonyManager.NETWORK_TYPE_UNKNOWN }
                    ?: tm.voiceNetworkType
            } catch (_: Exception) {
                TelephonyManager.NETWORK_TYPE_UNKNOWN
            }
        )

        var level: Int? = null
        try {
            val ss: SignalStrength? = tm.signalStrength
            if (ss != null) {
                level = ss.level.coerceIn(0, 4)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val cell = ss.cellSignalStrengths.firstOrNull()
                    if (cell != null && (level == null || level == 0)) {
                        level = cell.level.coerceIn(0, 4)
                    }
                }
            }
        } catch (_: Exception) {
        }

        if (level == null) {
            try {
                @Suppress("MissingPermission")
                val infos = tm.allCellInfo
                val lte = infos?.filterIsInstance<CellInfoLte>()?.firstOrNull { it.isRegistered }
                if (lte != null) {
                    level = lte.cellSignalStrength.level.coerceIn(0, 4)
                }
            } catch (_: Exception) {
            }
        }

        val reallyInService = inService && (level == null || level > 0)
        return MeterRead(reallyInService, operator, networkType, level)
    }

    private fun networkTypeLabel(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_HSPAP,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA -> "HSPA"
        TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
        TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
        TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
        TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT,
        TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_EVDO_B -> "CDMA"
        TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "unknown"
        else -> "type/$type"
    }
}
