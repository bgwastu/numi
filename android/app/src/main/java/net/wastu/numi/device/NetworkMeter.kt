package net.wastu.numi.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CellInfoLte
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import net.wastu.numi.network.TelegramHtml

data class SimCellularLine(
    val slotIndex: Int,
    val level: Int?,
    val carrier: String?,
    val inService: Boolean
) {
    fun toHtml(): String {
        val service = if (inService) "IN SERVICE" else "NO SERVICE"
        val signal = level?.let { "$it/4" } ?: "signal unavailable"
        return "- SIM ${slotIndex + 1} - ${TelegramHtml.code(service)} - ${TelegramHtml.code(signal)} - ${TelegramHtml.escape(carrier ?: "unknown carrier")}"
    }
}

data class NetworkMeterSnapshot(
    val inService: Boolean,
    val lines: List<SimCellularLine> = emptyList()
) {
    fun cellularBlockHtml(): String = if (lines.isEmpty()) {
        "- unavailable (grant Phone permission)"
    } else {
        lines.joinToString("\n") { it.toHtml() }
    }
}

object NetworkMeter {
    fun snapshot(context: Context): NetworkMeterSnapshot {
        val sims = DeviceStatusProvider.activeSims(context)
        if (sims.isEmpty()) return NetworkMeterSnapshot(inService = false)

        val baseManager = context.getSystemService(TelephonyManager::class.java)
        val hasPhonePermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        val lines = sims.map { sim ->
            val manager = baseManager?.let { base ->
                runCatching { base.createForSubscriptionId(sim.subscriptionId) }.getOrElse { base }
            }
            val state = readState(manager, hasPhonePermission)
            SimCellularLine(
                slotIndex = sim.slotIndex,
                level = state.level,
                carrier = sim.carrier ?: state.carrier,
                inService = state.inService
            )
        }
        return NetworkMeterSnapshot(lines.any { it.inService }, lines)
    }

    private data class SimState(
        val inService: Boolean,
        val carrier: String?,
        val level: Int?
    )

    private fun readState(manager: TelephonyManager?, hasPhonePermission: Boolean): SimState {
        if (manager == null || !hasPhonePermission) return SimState(false, null, null)

        val serviceState = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) manager.serviceState else null
        } catch (_: Exception) {
            null
        }
        val registered = serviceState?.state == ServiceState.STATE_IN_SERVICE ||
            (serviceState == null && manager.simState == TelephonyManager.SIM_STATE_READY)
        val carrier = manager.networkOperatorName?.takeIf { it.isNotBlank() }
            ?: manager.simOperatorName?.takeIf { it.isNotBlank() }

        var level: Int? = null
        try {
            val signalStrength: SignalStrength? = manager.signalStrength
            if (signalStrength != null) {
                level = signalStrength.level.coerceIn(0, 4)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && level == 0) {
                    level = signalStrength.cellSignalStrengths.firstOrNull()?.level?.coerceIn(0, 4) ?: level
                }
            }
        } catch (_: Exception) {
            // Signal readings can disappear while Android switches radio states.
        }

        if (level == null) {
            try {
                @Suppress("MissingPermission")
                val registeredLte = manager.allCellInfo
                    ?.filterIsInstance<CellInfoLte>()
                    ?.firstOrNull { it.isRegistered }
                level = registeredLte?.cellSignalStrength?.level?.coerceIn(0, 4)
            } catch (_: Exception) {
                // Older radios may not expose cell info.
            }
        }

        return SimState(registered && (level == null || level > 0), carrier, level)
    }
}
