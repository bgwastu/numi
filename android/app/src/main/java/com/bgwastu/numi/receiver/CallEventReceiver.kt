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
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class CallEventReceiver : Service() {

    private val isRunning = AtomicBoolean(false)
    private var telephonyListener: Any? = null
    private var lastState: Int = TelephonyManager.CALL_STATE_IDLE
    private var ringingNumber: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning.compareAndSet(false, true)) return START_STICKY

        val prefs = getSharedPreferences("numi_settings", Context.MODE_PRIVATE)
        val token = prefs.getString("BOT_TOKEN", null)
        if (token.isNullOrBlank()) {
            Log.e("CallEventReceiver", "Cannot start without bot_token")
            stopSelf()
            return START_NOT_STICKY
        }

        val sender = TelegramSender(token)
        val actionStore = TelegramActionStore(this)
        val telephony = getSystemService(TelephonyManager::class.java)
        val handler = CallHandler(this, sender, actionStore)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handler.onCallStateChanged(state)
                }
            }
            telephony.registerTelephonyCallback(mainExecutor, callback)
            telephonyListener = callback
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handler.onCallStateChanged(state)
                }
            }
            telephony.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            telephonyListener = listener
        }

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning.set(false)
        val telephony = getSystemService(TelephonyManager::class.java)
        when (val listener = telephonyListener) {
            is TelephonyCallback -> telephony.unregisterTelephonyCallback(listener)
            is PhoneStateListener -> {
                @Suppress("DEPRECATION")
                telephony.listen(listener, PhoneStateListener.LISTEN_NONE)
            }
        }
        super.onDestroy()
    }

    private inner class CallHandler(
        private val service: CallEventReceiver,
        private val sender: TelegramSender,
        private val actionStore: TelegramActionStore
    ) {
        fun onCallStateChanged(state: Int) {
            if (ContextCompat.checkSelfPermission(
                    service, Manifest.permission.READ_PHONE_STATE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            if (lastState == TelephonyManager.CALL_STATE_RINGING &&
                state == TelephonyManager.CALL_STATE_IDLE
            ) {
                val prefs = service.getSharedPreferences("numi_settings", Context.MODE_PRIVATE)
                val chatId = prefs.getString("TARGET_CHAT_ID", null)
                if (chatId != null) {
                    GlobalScope.launch {
                        sender.sendMessage(chatId, "📞 Call ended — ring followed by idle")
                    }
                }
            }
            lastState = state
        }
    }
}
