package com.bgwastu.numi.queue

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.IBinder
import android.telephony.SmsManager
import android.util.Log
import com.bgwastu.numi.database.DbHelper
import com.bgwastu.numi.network.TelegramSender
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class OutboxService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    
    private lateinit var dbHelper: DbHelper
    private lateinit var telegramSender: TelegramSender
    private var commandReceiver: com.bgwastu.numi.bus.CommandReceiver? = null
    private val isRunning = AtomicBoolean(false)
    private var pollJob: Job? = null

    companion object {
        private const val CHANNEL_ID = "NumiOutboxChannel"
        private const val NOTIFICATION_ID = 2002
        private var BOT_TOKEN = "" // Stored or set via settings
        private var TARGET_CHAT_ID = "" // Stored or set via settings

        fun updateConfig(token: String, chat: String) {
            BOT_TOKEN = token
            TARGET_CHAT_ID = chat
        }
    }

    override fun onCreate() {
        super.onCreate()
        dbHelper = DbHelper(this)

        // Read persistent config FIRST, then create the sender
        val prefs = getSharedPreferences("numi_settings", Context.MODE_PRIVATE)
        val token = prefs.getString("BOT_TOKEN", "") ?: ""
        val targetChat = prefs.getString("TARGET_CHAT_ID", "") ?: ""
        updateConfig(token, targetChat)
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
        if (BOT_TOKEN.isNotBlank()) {
            commandReceiver = com.bgwastu.numi.bus.CommandReceiver(this, BOT_TOKEN, telegramSender)
            commandReceiver?.start()
        }

        // Start incoming call monitoring
        startService(Intent(this, com.bgwastu.numi.receiver.CallEventReceiver::class.java))
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

            // Update state to SENDING
            db.execSQL(
                "UPDATE ${DbHelper.TABLE_OUTBOX} SET ${DbHelper.COL_STATUS} = 'SENDING' WHERE ${DbHelper.COL_ID} = ?",
                arrayOf(id)
            )

            serviceScope.launch {
                val success = when (type) {
                    "TELEGRAM" -> sendTelegram(recipient, body)
                    "SMS" -> sendSmsDirect(recipient, body)
                    else -> false
                }

                if (success) {
                    db.execSQL(
                        "UPDATE ${DbHelper.TABLE_OUTBOX} SET ${DbHelper.COL_STATUS} = 'SENT' WHERE ${DbHelper.COL_ID} = ?",
                        arrayOf(id)
                    )
                } else {
                    val nextAttempts = attempts + 1
                    // Exponential backoff: 5s, 10s, 20s, 40s...
                    val delayMs = (5000 * Math.pow(2.0, attempts.toDouble())).toLong().coerceAtMost(300000)
                    val nextAttemptAt = System.currentTimeMillis() + delayMs
                    db.execSQL(
                        "UPDATE ${DbHelper.TABLE_OUTBOX} SET ${DbHelper.COL_STATUS} = 'FAILED', ${DbHelper.COL_ATTEMPTS} = ?, ${DbHelper.COL_NEXT_ATTEMPT} = ? WHERE ${DbHelper.COL_ID} = ?",
                        arrayOf(nextAttempts, nextAttemptAt, id)
                    )
                    Log.e("OutboxService", "Message $id failed. Retrying in ${delayMs / 1000}s")
                }
            }
        }
        cursor.close()
    }

    private suspend fun sendTelegram(chatId: String, text: String): Boolean {
        // If config changed during runtime, re-init
        val activeChat = if (chatId.isBlank()) TARGET_CHAT_ID else chatId
        return telegramSender.sendMessage(activeChat, text)
    }

    private fun sendSmsDirect(recipient: String, body: String): Boolean {
        return try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                this.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            smsManager.sendTextMessage(recipient, null, body, null, null)
            Log.d("OutboxService", "SMS sent successfully to $recipient")
            true
        } catch (e: Exception) {
            Log.e("OutboxService", "Failed to send SMS to $recipient", e)
            false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        val token = intent?.getStringExtra("BOT_TOKEN")
        val chat = intent?.getStringExtra("TARGET_CHAT_ID")
        if (token != null && chat != null) {
            updateConfig(token, chat)
            telegramSender = TelegramSender(BOT_TOKEN)

            // Persist config
            getSharedPreferences("numi_settings", Context.MODE_PRIVATE)
                .edit()
                .putString("BOT_TOKEN", token)
                .putString("TARGET_CHAT_ID", chat)
                .apply()

            // Create/recreate CommandReceiver with new token
            commandReceiver?.stop()
            commandReceiver = com.bgwastu.numi.bus.CommandReceiver(this, BOT_TOKEN, telegramSender)
            commandReceiver?.start()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
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
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Numi Outbox Daemon")
            .setContentText("Gateway is running...")
            .setOngoing(true)
            .build()
    }
}
