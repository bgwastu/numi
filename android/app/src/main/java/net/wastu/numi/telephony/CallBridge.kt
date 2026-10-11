@file:Suppress("DEPRECATION")
package net.wastu.numi.telephony

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.Call
import android.telecom.DisconnectCause
import android.telephony.TelephonyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.wastu.numi.network.TelegramEvents
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object CallBridge {
    private data class Tracked(val id: String, val call: Call, var incoming: Boolean, var connected: Boolean = false,
        var lastState: Int? = null, var sequence: Int = 0)
    private val calls = ConcurrentHashMap<Call, Tracked>()

    fun changed(context: Context, call: Call, state: Int) {
        val tracked = calls.getOrPut(call) { Tracked(UUID.randomUUID().toString().take(16), call, state == Call.STATE_RINGING) }
        if (tracked.lastState == state) return
        tracked.lastState = state
        if (state == Call.STATE_RINGING) tracked.incoming = true
        if (state == Call.STATE_ACTIVE) tracked.connected = true
        val title = when (state) {
            Call.STATE_RINGING -> "Incoming call"
            Call.STATE_DIALING, Call.STATE_CONNECTING -> "Outgoing call"
            Call.STATE_ACTIVE -> "Call connected"
            Call.STATE_HOLDING -> "Call on hold"
            else -> return
        }
        emit(context, tracked, title, "${tracked.id}:${++tracked.sequence}:$state", state)
    }

    fun removed(context: Context, call: Call) {
        val tracked = calls.remove(call) ?: return
        val cause = call.details.disconnectCause?.code
        val title = when {
            cause == DisconnectCause.REJECTED -> "Call declined"
            tracked.incoming && !tracked.connected -> "Missed call"
            else -> "Call ended"
        }
        emit(context, tracked, title, "${tracked.id}:ended", Call.STATE_DISCONNECTED)
    }

    private fun emit(context: Context, tracked: Tracked, title: String, key: String, state: Int) {
        val address = tracked.call.details.handle?.schemeSpecificPart ?: "Unknown caller"
        val sub = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) runCatching {
            context.getSystemService(TelephonyManager::class.java).getSubscriptionId(tracked.call.details.accountHandle)
        }.getOrNull()?.takeIf { it >= 0 } else null
        val connectedAt = tracked.call.details.connectTimeMillis
        val body = if (state == Call.STATE_DISCONNECTED && connectedAt > 0) "$title · ${(System.currentTimeMillis() - connectedAt).coerceAtLeast(0) / 1000}s" else title
        TelegramEvents.call(context, address, sub, key, body, JSONObject().apply {
            put("kind", "call"); put("call_id", tracked.id); put("call_state", state)
            put("state", title)
        })
    }

    suspend fun control(id: String, action: String): Boolean = withContext(Dispatchers.Main) {
        val call = calls.values.firstOrNull { it.id == id }?.call ?: return@withContext false
        val state = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) call.details.state else call.state
        when {
            action == "decline" && state == Call.STATE_RINGING -> call.reject(false, null)
            action == "end" && state in listOf(Call.STATE_ACTIVE, Call.STATE_HOLDING, Call.STATE_DIALING, Call.STATE_CONNECTING) -> call.disconnect()
            else -> return@withContext false
        }
        true
    }
}
