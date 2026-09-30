package net.wastu.numi.queue

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.IBinder
import android.util.Log
import net.wastu.numi.R
import net.wastu.numi.bus.ReplyTarget
import net.wastu.numi.bus.TelegramActionStore
import net.wastu.numi.database.DbHelper
import net.wastu.numi.network.TelegramSender
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class OutboxService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    
    private lateinit var dbHelper: DbHelper
    private lateinit var telegramSender: TelegramSender
    private lateinit var actionStore: TelegramActionStore
    private var commandReceiver: net.wastu.numi.bus.CommandReceiver? = null
    private val isRunning = AtomicBoolean(false)
    private var pollJob: Job? = null

    companion object {
        private const val CHANNEL_ID = "NumiOutboxChannel"
        private const val NOTIFICATION_ID = 2002
        @Volatile private var serviceActive = false
        private var BOT_TOKEN = ""
        private var ALLOWED_USER_IDS = ""
        private val primaryChatId: String get() = ALLOWED_USER_IDS.split(",").firstOrNull { it.isNotBlank() }?.trim() ?: ""

        fun updateConfig(token: String, allowedIds: String) {
            BOT_TOKEN = token
            ALLOWED_USER_IDS = allowedIds
        }

        fun isServiceRunning(): Boolean = serviceActive
    }

    override fun onCreate() {
        super.onCreate()
        serviceActive = true
        dbHelper = DbHelper(this)
        actionStore = TelegramActionStore(this)

        // Read persistent config FIRST, then create the sender
        val prefs = getSharedPreferences("numi_settings", Context.MODE_PRIVATE)
        val token = prefs.getString("BOT_TOKEN", "") ?: ""
        val allowedIds = prefs.getString("ALLOWED_USER_IDS", "") ?: prefs.getString("TARGET_CHAT_ID", "") ?: ""
        updateConfig(token, allowedIds)
        telegramSender = TelegramSender(BOT_TOKEN)

        createNotificationChannel()
        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Log.e("OutboxService", "startForeground failed, retrying with specialUse", e)
            try {
                startForeground(NOTIFICATION_ID, createNotification(), 
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } catch (e2: Exception) {
                Log.e("OutboxService", "Foreground start failed completely", e2)
            }
        }
        startPolling()

        // Create CommandReceiver if we have a token
        if (BOT_TOKEN.isNotBlank() && ALLOWED_USER_IDS.isNotBlank()) {
            commandReceiver = net.wastu.numi.bus.CommandReceiver(this, BOT_TOKEN, telegramSender)
            commandReceiver?.start()
        }

    }

    private fun startPolling() {
        if (isRunning.compareAndSet(false, true)) {
            pollJob = serviceScope.launch {
                while (isActive) {
                    try {
                        processOutbox()
                    } catch (e: Exception) {
                        Log.e("OutboxService", "Error processing outbox", e)
                    }
                    delay(3000) // Poll every 3 seconds
                }
            }
        }
    }

    private fun processOutbox() {
        val db = dbHelper.writableDatabase
        val cursor = db.rawQuery(
            "SELECT * FROM ${DbHelper.TABLE_OUTBOX} WHERE ${DbHelper.COL_STATUS} IN ('PENDING', 'FAILED') AND ${DbHelper.COL_NEXT_ATTEMPT} <= ? LIMIT 5",
            arrayOf(System.currentTimeMillis().toString())
        )

        while (cursor.moveToNext()) {
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(DbHelper.COL_ID))
            val type = cursor.getString(cursor.getColumnIndexOrThrow(DbHelper.COL_TYPE))
            val recipient = cursor.getString(cursor.getColumnIndexOrThrow(DbHelper.COL_RECIPIENT))
            val body = cursor.getString(cursor.getColumnIndexOrThrow(DbHelper.COL_BODY))
            val attempts = cursor.getInt(cursor.getColumnIndexOrThrow(DbHelper.COL_ATTEMPTS))
            val subscriptionColumn = cursor.getColumnIndex(DbHelper.COL_SUBSCRIPTION_ID)
            val subscriptionId = if (subscriptionColumn >= 0 && !cursor.isNull(subscriptionColumn)) {
                cursor.getInt(subscriptionColumn)
            } else null

            // Update state to SENDING
            db.execSQL(
                "UPDATE ${DbHelper.TABLE_OUTBOX} SET ${DbHelper.COL_STATUS} = 'SENDING' WHERE ${DbHelper.COL_ID} = ?",
                arrayOf(id)
            )

            serviceScope.launch {
                val success = when (type) {
                    "TELEGRAM" -> sendTelegram(recipient, body, subscriptionId)
                    "SMS" -> {
                        val result = sendSmsDirect(recipient, body, subscriptionId)
                        if (result !is net.wastu.numi.device.SmsSendResult.Sent) {
                            val reason = when (result) {
                                net.wastu.numi.device.SmsSendResult.PermissionDenied -> "SMS permission is missing"
                                net.wastu.numi.device.SmsSendResult.NoSim -> "Selected SIM is unavailable"
                                is net.wastu.numi.device.SmsSendResult.Error -> result.reason
                            }
                            db.execSQL(
                                "UPDATE ${DbHelper.TABLE_OUTBOX} SET ${DbHelper.COL_LAST_ERROR} = ? WHERE ${DbHelper.COL_ID} = ?",
                                arrayOf<Any>(reason, id)
                            )
                        }
                        result is net.wastu.numi.device.SmsSendResult.Sent
                    }
                    else -> false
                }

                if (success) {
                    db.execSQL(
                        "UPDATE ${DbHelper.TABLE_OUTBOX} SET ${DbHelper.COL_STATUS} = 'SENT', ${DbHelper.COL_LAST_ERROR} = NULL WHERE ${DbHelper.COL_ID} = ?",
                        arrayOf(id)
                    )
                } else {
                    val nextAttempts = attempts + 1
                    // Exponential backoff: 5s, 10s, 20s, 40s...
                    val delayMs = (5000 * Math.pow(2.0, attempts.toDouble())).toLong().coerceAtMost(300000)
                    val nextAttemptAt = System.currentTimeMillis() + delayMs
                    db.execSQL(
                        "UPDATE ${DbHelper.TABLE_OUTBOX} SET ${DbHelper.COL_STATUS} = 'FAILED', ${DbHelper.COL_ATTEMPTS} = ?, ${DbHelper.COL_NEXT_ATTEMPT} = ? WHERE ${DbHelper.COL_ID} = ?",
                        arrayOf<Any>(nextAttempts, nextAttemptAt, id)
                    )
                    Log.e("OutboxService", "Message $id failed. Retrying in ${delayMs / 1000}s")
                }
            }
        }
        cursor.close()
    }

    private suspend fun sendTelegram(recipientOrChat: String, text: String, subscriptionId: Int?): Boolean {
        val activeChat = primaryChatId.ifBlank { return false }
        val replyTo = actionStore.getThreadTip(recipientOrChat)
        val messageId = telegramSender.sendMessageForId(
            activeChat,
            text,
            replyTo,
            includeSmsReplyButton = looksLikeSmsAddress(recipientOrChat)
        )
            ?: if (replyTo != null) {
                // Parent may have been deleted; fall back to a top-level message.
                telegramSender.sendMessageForId(
                    activeChat,
                    text,
                    null,
                    includeSmsReplyButton = looksLikeSmsAddress(recipientOrChat)
                )
            } else {
                null
            }
            ?: return false
        // Keep the thread tip on the newest message so the next inbound replies here.
        actionStore.saveThreadTip(recipientOrChat, messageId)
        // Inbound SMS / short codes store origin in recipient; attach reply target for user replies.
        if (looksLikeSmsAddress(recipientOrChat)) {
            actionStore.saveReplyTarget(
                messageId,
                ReplyTarget(
                    phoneNumber = recipientOrChat,
                    subscriptionId = subscriptionId,
                    source = "inbound_sms"
                )
            )
        }
        return true
    }

    private fun looksLikeSmsAddress(value: String): Boolean {
        val cleaned = value.trim()
        if (cleaned.isEmpty() || cleaned.equals("Unknown", ignoreCase = true)) return false
        val digits = cleaned.filter { it.isDigit() }
        // Include short codes (e.g. 3636) as well as E.164 numbers.
        if (digits.length in 3..15) return true
        // Named senders like TELKOMSEL
        return cleaned.any { it.isLetter() }
    }

    private fun sendSmsDirect(
        recipient: String,
        body: String,
        subscriptionId: Int?
    ): net.wastu.numi.device.SmsSendResult {
        val result = net.wastu.numi.device.SmsSender(this).sendSms(recipient, body, subscriptionId)
        if (result !is net.wastu.numi.device.SmsSendResult.Sent) {
            Log.e("OutboxService", "Failed to send SMS to $recipient via subscription $subscriptionId: $result")
        }
        return result
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        val token = intent?.getStringExtra("BOT_TOKEN")
        val allowedIds = intent?.getStringExtra("ALLOWED_USER_IDS")
        if (token != null && allowedIds != null) {
            commandReceiver?.stop()
            commandReceiver = null
            telegramSender.close()
            updateConfig(token, allowedIds)
            telegramSender = TelegramSender(BOT_TOKEN)

            // Persist config
            getSharedPreferences("numi_settings", Context.MODE_PRIVATE)
                .edit()
                .putString("BOT_TOKEN", token)
                .putString("ALLOWED_USER_IDS", allowedIds)
                .apply()

            if (BOT_TOKEN.isNotBlank() && ALLOWED_USER_IDS.isNotBlank()) {
                commandReceiver = net.wastu.numi.bus.CommandReceiver(this, BOT_TOKEN, telegramSender)
                commandReceiver?.start()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceActive = false
        isRunning.set(false)
        commandReceiver?.stop()
        pollJob?.cancel()
        serviceJob.cancel()
        telegramSender.close()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Numi Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_stat_numi)
            .setContentTitle("Numi SMS Gateway")
            .setContentText("SMS outbox is running")
            .setOngoing(true)
            .build()
    }
}
