package com.bgwastu.numi.receiver

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.bgwastu.numi.bus.ReplyTarget
import com.bgwastu.numi.bus.TelegramActionStore
import com.bgwastu.numi.network.TelegramSender
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background service that detects incoming calls and notifies Telegram.
 * Runs call-state monitoring only while the foreground service is active;
 * closes when Numi stops (the foreground notification shields it).
 */
class CallEventReceiver : Service() {

    private val isRunning = AtomicBoolean(false)
    private var phoneStateListener: Any? = null
    private var lastState: Int = TelephonyManager.CALL_STATE_IDLE
    private var ringingNumber: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning.compareAndSet(false, true)) return START_STICKY

        val prefs = getSharedPreferences("numi_settings", Context.MODE_PRIVATE)
        val token = prefs.getString("bot_token", null)
        if (token.isNullOrBlank()) {
            Log.e("CallEventReceiver", "Cannot start without bot_token")
            stopSelf()
            return START_NOT_STICKY
        }

        val sender = TelegramSender(token)
        val actionStore = TelegramActionStore(this)
        val telephony = getSystemService(TelephonyManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleCallState(state, telephony, sender, actionStore)
                }
            }
            telephony.registerTelephonyCallback(mainExecutor, callback)
            phoneStateListener = callback
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleCallState(state, telephony, sender, actionStore)
                }
            }
            telephony.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            phoneStateListener = listener
        }

        return START_STICKY
    }

    private fun handleCallState(
        state: Int,
        telephony: TelephonyManager,
        sender: TelegramSender,
        actionStore: TelegramActionStore
    ) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("CallEventReceiver", "Cannot read phone state without permission")
            return
        }

        val wasIdle = lastState == TelephonyManager.CALL_STATE_IDLE
        lastState = state

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                if (wasIdle) {
                    // Incoming or outgoing? On Android we can't distinguish without
                    // additional call-log heuristics, so we report conservatively.
                    ringingNumber = System.currentTimeMillis().toString()
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (ringingNumber != null) {
                    val prefs = getSharedPreferences("numi_settings", Context.MODE_PRIVATE)
                    val chatId = prefs.getString("TARGET_CHAT_ID", null)
                    if (chatId != null) {
                        val msgId = sender.sendTelegram(
                            "sendMessage",
                            org.json.JSONObject().apply {
                                put("chat_id", chatId)
                                put("text", "📞 Call ended — ring followed by idle")
                            }
                        )
                        // Store a reply target so the owner can reply with SMS text
                        if (msgId > 0) {
                            actionStore.saveReplyTarget(
                                msgId,
                                ReplyTarget(
                                    phoneNumber = "", // unknown caller — reply manual
                                    subscriptionId = null,
                                    source = "incoming_call"
                                )
                            )
                        }
                    }
                    ringingNumber = null
                }
            }
        }
    }

    override fun onDestroy() {
        isRunning.set(false)
        val telephony = getSystemService(TelephonyManager::class.java)
        when (val listener = phoneStateListener) {
            is TelephonyCallback -> telephony.unregisterTelephonyCallback(listener)
            is PhoneStateListener -> {
                @Suppress("DEPRECATION")
                telephony.listen(listener, PhoneStateListener.LISTEN_NONE)
            }
        }
        super.onDestroy()
    }
}
