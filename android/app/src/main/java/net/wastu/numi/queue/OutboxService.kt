package net.wastu.numi.queue

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.wastu.numi.ConnectorSettingsActivity
import net.wastu.numi.R
import net.wastu.numi.bus.CommandReceiver
import net.wastu.numi.bus.TelegramPollHealth
import net.wastu.numi.device.DeviceStatusProvider
import net.wastu.numi.messaging.AppStore
import net.wastu.numi.messaging.MessagesRepository
import net.wastu.numi.messaging.QueuedMessage
import net.wastu.numi.messaging.SendPolicy
import net.wastu.numi.messaging.SimRouting
import net.wastu.numi.network.TelegramApiException
import net.wastu.numi.network.TelegramConnector
import net.wastu.numi.network.TelegramEvents
import net.wastu.numi.utils.SmsRoleManager
import net.wastu.numi.spam.SpamStore
import net.wastu.numi.spam.SpamCoordinator
import org.fossify.messages.extensions.messagingUtils
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages

/** Only Telegram retries automatically. A cellular send with an uncertain result needs review. */
class OutboxService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var store: AppStore
    private var connector: TelegramConnector? = null
    private var commands: CommandReceiver? = null
    private var smsJob: Job? = null
    private var telegramJob: Job? = null
    private var filterJob: Job? = null
    private var filterEffectsJob: Job? = null
    private var configuredToken = ""
    private var configuredOwner = ""
    private val configurationLock = Mutex()

    override fun onCreate() {
        super.onCreate()
        store = AppStore(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Telegram connection", NotificationManager.IMPORTANCE_LOW))
        val openSettings = PendingIntent.getActivity(this, 2002, Intent(this, ConnectorSettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_stat_numi)
            .setContentTitle("Numi").setContentText("SMS delivery and Telegram connection")
            .setContentIntent(openSettings).setOngoing(true).build()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0)
            serviceActive = true
        } catch (exception: Exception) {
            Log.e("Numi", "Background connection could not start: ${exception.javaClass.simpleName}")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        scope.launch {
            configurationLock.withLock {
                val prefs = getSharedPreferences("numi_settings", MODE_PRIVATE)
                val enabled = TelegramEvents.enabled(this@OutboxService)
                val token = if (enabled) prefs.getString("BOT_TOKEN", "").orEmpty() else ""
                val owner = if (enabled) prefs.getString("TELEGRAM_OWNER_ID", "").orEmpty() else ""
                if (token != configuredToken || owner != configuredOwner || (enabled && connector == null)) {
                    telegramJob?.cancelAndJoin()
                    commands?.stop()
                    connector?.close()
                    commands = null; connector = null
                    configuredToken = token; configuredOwner = owner
                    if (enabled) {
                        val active = TelegramConnector(this@OutboxService, token)
                        connector = active
                        commands = CommandReceiver(this@OutboxService, active).also { it.start(scope) }
                        // Telegram has no idempotency token. Resume persisted multipart progress after a restart.
                        store.sending().filter { it.type == "TELEGRAM" }.forEach {
                            store.update(it.id, ContentValues().apply { put("status", "PENDING") })
                        }
                        telegramJob = scope.launch {
                            while (isActive) {
                                store.due(System.currentTimeMillis(), "TELEGRAM").forEach { sendTelegram(it, active) }
                                delay(1500)
                            }
                        }
                    }
                }
                if (smsJob?.isActive != true) smsJob = scope.launch {
                    while (isActive) {
                        checkUncertainSends()
                        store.due(System.currentTimeMillis(), "SMS").forEach(::sendSms)
                        if (!TelegramEvents.enabled(this@OutboxService) && !store.hasSmsWork() && !SpamStore(this@OutboxService).hasWork()) { stopSelf(); break }
                        delay(1500)
                    }
                }
                if (filterJob?.isActive != true) filterJob = scope.launch {
                    while (isActive) {
                        try {
                            SpamCoordinator.process(this@OutboxService)
                            getSharedPreferences("numi_settings", MODE_PRIVATE).edit().remove("spam_screening_error").apply()
                        } catch (exception: CancellationException) { throw exception }
                        catch (exception: Exception) {
                            getSharedPreferences("numi_settings", MODE_PRIVATE).edit()
                                .putString("spam_screening_error", "SMS screening needs attention (${exception.javaClass.simpleName}). Open the Spam folder.").apply()
                        }
                        delay(1500)
                    }
                }
                if (filterEffectsJob?.isActive != true) filterEffectsJob = scope.launch {
                    while (isActive) {
                        try {
                            SpamCoordinator.deliverReady(this@OutboxService)
                            getSharedPreferences("numi_settings", MODE_PRIVATE).edit().remove("spam_notification_error").apply()
                        }
                        catch (exception: Exception) {
                            getSharedPreferences("numi_settings", MODE_PRIVATE).edit()
                                .putString("spam_notification_error", "SMS notifications need attention (${exception.javaClass.simpleName}).").apply()
                        }
                        delay(500)
                    }
                }
            }
        }
        return if (TelegramEvents.enabled(this)) START_STICKY else START_NOT_STICKY
    }

    private fun sendSms(row: QueuedMessage) {
        try {
            require(SendPolicy.isFresh(row.createdAt, System.currentTimeMillis())) { "This queued SMS expired before it was sent. Send it again from Numi." }
            require(SmsRoleManager.isDefaultSmsApp(this)) { "Numi is no longer the default SMS app." }
            require(checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) { "SMS permission is missing." }
            val sub = SimRouting.resolve(row.subscriptionId, DeviceStatusProvider.activeSims(this).map { it.subscriptionId }, SubscriptionManager.getDefaultSmsSubscriptionId())
            val id = MessagesRepository(this).ensureOutgoing(row) ?: error("Could not save the SMS to the phone inbox.")
            if (!store.start(row.id, 0)) return
            messagingUtils.sendSmsMessage(row.body, setOf(row.address), sub, requireDeliveryReport = true, messageId = id)
            refreshMessages(); refreshConversations()
        } catch (exception: Exception) {
            val reason = if (exception is IllegalArgumentException || exception is IllegalStateException) exception.message.orEmpty()
                else "Android rejected the SMS request. Open Numi to review it."
            store.fail(row.id, reason)
            store.message(row.id)?.let { failed ->
                MessagesRepository(this).synchronize(failed)
                TelegramEvents.result(this, failed)
            }
            refreshMessages(); refreshConversations()
        }
    }

    private fun checkUncertainSends() {
        val now = System.currentTimeMillis()
        store.sending().filter { it.type == "SMS" && now - it.updatedAt > SendPolicy.SEND_TIMEOUT_MS }.forEach { row ->
            store.fail(row.id, "Send result unknown. Check with the recipient before retrying; the SMS may have been sent.")
            store.message(row.id)?.let { failed ->
                MessagesRepository(this).synchronize(failed)
                TelegramEvents.result(this, failed)
            }
            refreshMessages(); refreshConversations()
        }
    }

    private suspend fun sendTelegram(row: QueuedMessage, active: TelegramConnector) {
        val event = row.event?.let { org.json.JSONObject(it) }
        if (event?.optString("kind") == "sms" && event.optString("direction") == "incoming") {
            val filtered = SpamStore(this).message(event.optLong("source_id"))
            if (filtered?.state == "PENDING") return
            if (filtered?.state == "SPAM") {
                store.update(row.id, ContentValues().apply { put("status", "SENT"); put("last_error", "Suppressed: stored in Spam") })
                return
            }
        }
        store.update(row.id, ContentValues().apply { put("status", "SENDING"); put("updated_at", System.currentTimeMillis()) })
        try {
            active.deliver(row)
            store.update(row.id, ContentValues().apply { put("status", "SENT"); putNull("last_error") })
        } catch (exception: CancellationException) {
            store.update(row.id, ContentValues().apply { put("status", "PENDING") })
            throw exception
        } catch (exception: Exception) {
            val backoff = (5000L shl row.attempts.coerceIn(0, 6)).coerceAtMost(300000)
            val serverDelay = (exception as? TelegramApiException)?.retryAfter?.toLong()?.times(1000) ?: 0
            store.update(row.id, ContentValues().apply {
                put("status", "FAILED"); put("attempt_count", row.attempts + 1)
                put("next_attempt_at", System.currentTimeMillis() + maxOf(backoff, serverDelay))
                put("last_error", if (exception is TelegramApiException) "Telegram API error ${exception.code}" else "Connection unavailable")
            })
        }
    }

    override fun onDestroy() {
        serviceActive = false
        commands?.stop(); scope.cancel(); connector?.close()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "NumiOutboxChannel"
        private const val NOTIFICATION_ID = 2002
        @Volatile private var serviceActive = false
        fun isServiceRunning(): Boolean = serviceActive

        fun wake(context: Context) {
            if (!TelegramEvents.enabled(context) && !AppStore(context).hasSmsWork() && !SpamStore(context).hasWork() && !serviceActive) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, OutboxService::class.java))
            } catch (exception: Exception) {
                TelegramPollHealth.recordError(context, "Open Numi to resume its background connection.")
                Log.e("Numi", "Could not wake connection: ${exception.javaClass.simpleName}")
            }
        }
    }
}
