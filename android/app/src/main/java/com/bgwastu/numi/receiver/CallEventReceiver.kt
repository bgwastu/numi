package com.bgwastu.numi.receiver

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.provider.CallLog
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.bgwastu.numi.bus.ReplyTarget
import com.bgwastu.numi.bus.TelegramActionStore
import com.bgwastu.numi.network.TelegramHtml
import com.bgwastu.numi.network.TelegramSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background service that detects incoming calls and notifies Telegram while ringing
 * (and again for missed calls). Started from [com.bgwastu.numi.queue.OutboxService].
 */
class CallEventReceiver : Service() {

    private val isRunning = AtomicBoolean(false)
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var telephonyListener: Any? = null
    private var telegramSender: TelegramSender? = null
    private var lastState: Int = TelephonyManager.CALL_STATE_IDLE
    private var ringingNumber: String? = null
    private var answeredDuringRing: Boolean = false
    private var notifiedRinging: Boolean = false

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
        telegramSender = sender
        val actionStore = TelegramActionStore(this)
        val telephony = getSystemService(TelephonyManager::class.java)
        val handler = CallHandler(this, sender, actionStore)

        val hasPhoneState = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPhoneState) {
            Log.w("CallEventReceiver", "READ_PHONE_STATE missing; skipping call-state listener")
            return START_STICKY
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        handler.onCallStateChanged(state, phoneNumber = null)
                    }
                }
                telephony.registerTelephonyCallback(mainExecutor, callback)
                telephonyListener = callback
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        handler.onCallStateChanged(state, phoneNumber)
                    }
                }
                telephony.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                telephonyListener = listener
            }
            Log.d("CallEventReceiver", "Call-state listener registered")
        } catch (e: SecurityException) {
            Log.e("CallEventReceiver", "Failed to register call-state listener", e)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning.set(false)
        serviceJob.cancel()
        val telephony = getSystemService(TelephonyManager::class.java)
        when (val listener = telephonyListener) {
            is TelephonyCallback -> telephony.unregisterTelephonyCallback(listener)
            is PhoneStateListener -> {
                @Suppress("DEPRECATION")
                telephony.listen(listener, PhoneStateListener.LISTEN_NONE)
            }
        }
        telegramSender?.close()
        telegramSender = null
        super.onDestroy()
    }

    private inner class CallHandler(
        private val service: CallEventReceiver,
        private val sender: TelegramSender,
        private val actionStore: TelegramActionStore
    ) {
        fun onCallStateChanged(state: Int, phoneNumber: String?) {
            if (ContextCompat.checkSelfPermission(
                    service, Manifest.permission.READ_PHONE_STATE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            val previous = lastState
            lastState = state
            Log.d("CallEventReceiver", "Call state $previous -> $state number=${phoneNumber ?: "-"}")

            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> {
                    if (notifiedRinging) return
                    answeredDuringRing = false
                    notifiedRinging = true
                    val initialNumber = phoneNumber?.takeIf { it.isNotBlank() }
                        ?: readLatestIncomingNumber()
                        ?: "Unknown"
                    ringingNumber = initialNumber
                    serviceScope.launch {
                        notifyTelegram(
                            title = "Incoming call",
                            number = initialNumber,
                            emoji = "📞"
                        )
                        // Call log often appears slightly after RINGING; refresh number for later.
                        if (initialNumber == "Unknown") {
                            val resolved = resolveCallerNumber(null)
                            if (resolved != "Unknown") {
                                ringingNumber = resolved
                            }
                        }
                    }
                }

                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    if (previous == TelephonyManager.CALL_STATE_RINGING || notifiedRinging) {
                        answeredDuringRing = true
                    }
                }

                TelephonyManager.CALL_STATE_IDLE -> {
                    if (!notifiedRinging && previous != TelephonyManager.CALL_STATE_RINGING) {
                        resetRingState()
                        return
                    }
                    val numberHint = ringingNumber
                    val wasAnswered = answeredDuringRing
                    resetRingState()
                    serviceScope.launch {
                        val resolved = resolveCallerNumber(numberHint?.takeIf { it != "Unknown" })
                        if (wasAnswered) {
                            notifyTelegram(
                                title = "Call ended",
                                number = resolved,
                                emoji = "☎️"
                            )
                        } else {
                            notifyTelegram(
                                title = "Missed call",
                                number = resolved,
                                emoji = "📵"
                            )
                        }
                    }
                }
            }
        }

        private fun resetRingState() {
            ringingNumber = null
            answeredDuringRing = false
            notifiedRinging = false
        }

        private suspend fun resolveCallerNumber(hint: String?): String {
            if (!hint.isNullOrBlank()) return hint
            // Call log often lags a beat behind RINGING on modern Android.
            repeat(4) { attempt ->
                if (attempt > 0) delay(400)
                val fromLog = readLatestIncomingNumber()
                if (!fromLog.isNullOrBlank()) return fromLog
            }
            return "Unknown"
        }

        private fun readLatestIncomingNumber(): String? {
            if (ContextCompat.checkSelfPermission(
                    service, Manifest.permission.READ_CALL_LOG
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return null
            }
            return try {
                val cutoff = System.currentTimeMillis() - 60_000
                service.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(
                        CallLog.Calls.NUMBER,
                        CallLog.Calls.TYPE,
                        CallLog.Calls.DATE
                    ),
                    "${CallLog.Calls.DATE} > ?",
                    arrayOf(cutoff.toString()),
                    "${CallLog.Calls.DATE} DESC"
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val type = cursor.getInt(cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                        if (type != CallLog.Calls.INCOMING_TYPE &&
                            type != CallLog.Calls.MISSED_TYPE &&
                            type != CallLog.Calls.REJECTED_TYPE
                        ) {
                            continue
                        }
                        val number = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
                        if (!number.isNullOrBlank()) return number
                    }
                    null
                }
            } catch (e: Exception) {
                Log.e("CallEventReceiver", "Failed to read call log", e)
                null
            }
        }

        private suspend fun notifyTelegram(title: String, number: String, emoji: String) {
            val prefs = service.getSharedPreferences("numi_settings", Context.MODE_PRIVATE)
            val chatId = prefs.getString("TARGET_CHAT_ID", null)
            if (chatId.isNullOrBlank()) {
                Log.e("CallEventReceiver", "TARGET_CHAT_ID missing; cannot notify")
                return
            }

            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date())
            val text = buildString {
                appendLine("$emoji ${TelegramHtml.bold(title)}")
                appendLine("${TelegramHtml.bold("From:")} ${TelegramHtml.code(number)}")
                append("${TelegramHtml.bold("Time:")} ${TelegramHtml.code(time)}")
            }

            val messageId = sender.sendMessageForId(chatId, text)
            if (messageId == null) {
                Log.e("CallEventReceiver", "Failed to notify Telegram for $title")
                return
            }
            Log.d("CallEventReceiver", "Notified Telegram: $title from $number id=$messageId")
            if (number != "Unknown") {
                // Reply to this Telegram message with text → SMS confirmation flow.
                actionStore.saveReplyTarget(
                    messageId,
                    ReplyTarget(phoneNumber = number, source = "incoming_call")
                )
            }
        }
    }
}
