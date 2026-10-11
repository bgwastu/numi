package net.wastu.numi

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import net.wastu.numi.contracts.NumiHooks
import net.wastu.numi.messaging.AppStore
import net.wastu.numi.messaging.MessagesRepository
import net.wastu.numi.messaging.SendPolicy
import net.wastu.numi.queue.OutboxService
import net.wastu.numi.telephony.CallBridge
import net.wastu.numi.network.TelegramEvents
import net.wastu.numi.ui.attachNavigation
import org.fossify.commons.extensions.baseConfig
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages
import java.util.concurrent.Executors

class NumiApplication : org.fossify.messages.App() {
    override val initializeMessaging: Boolean get() = !java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000').endsWith(":spam")
    private val synchronization = Executors.newSingleThreadExecutor()
    override fun onCreate() {
        super.onCreate()
        if (!initializeMessaging) return
        val prefs = getSharedPreferences("numi_settings", MODE_PRIVATE)
        if (android.os.Build.VERSION.SDK_INT >= 31 && !prefs.getBoolean("numi_monet_default_v1", false)) {
            baseConfig.isSystemThemeEnabled = true
            prefs.edit().putBoolean("numi_monet_default_v1", true).apply()
        }
        if (!prefs.contains("numi_legacy_bot_identity")) {
            prefs.edit().putString("numi_legacy_bot_identity", prefs.getString("BOT_TOKEN", "").orEmpty().substringBefore(':'))
                .putString("numi_legacy_owner", prefs.getString("TELEGRAM_OWNER_ID", "").orEmpty()).apply()
        }
        NumiHooks.openConnector = { it.startActivity(Intent(it, ConnectorSettingsActivity::class.java)) }
        NumiHooks.openCalls = { it.startActivity(Intent(it, org.fossify.phone.activities.SplashActivity::class.java)) }
        NumiHooks.openMessages = { it.startActivity(Intent(it, MainActivity::class.java)) }
        NumiHooks.openSpam = { it.startActivity(Intent(it, net.wastu.numi.spam.SpamActivity::class.java)) }
        NumiHooks.bindNavigation = ::attachNavigation
        NumiHooks.spamIds = { net.wastu.numi.spam.SpamStore(it).hiddenIds() }
        NumiHooks.inboxSummary = net.wastu.numi.spam.SpamCoordinator::summary
        NumiHooks.messagesOpened = { context -> synchronizeHistory(context) }
        NumiHooks.smsReceived = net.wastu.numi.spam.SpamCoordinator::receive
        NumiHooks.smsSending = { context, uri, parts ->
            val store = AppStore(context)
            val id = registerSend(context, uri, store, refresh = true)
            val generation = store.beginNativeSend(id, parts)
            OutboxService.wake(context)
            generation
        }
        NumiHooks.smsSent = { context, uri, part, _, generation, result ->
            val store = AppStore(context)
            val id = registerSend(context, uri, store)
            val row = store.partResult(id, part, "sent", result, generation)
            if (row != null) {
                MessagesRepository(context).synchronize(row)
                if (row.state != "SENDING") TelegramEvents.result(context, row)
            }
            when (row?.state) {
                "SENT" -> Telephony.Sms.MESSAGE_TYPE_SENT
                "FAILED" -> Telephony.Sms.MESSAGE_TYPE_FAILED
                else -> 0
            }
        }
        NumiHooks.smsFailed = { context, uri, error ->
            val store = AppStore(context)
            val id = registerSend(context, uri, store)
            store.fail(id, error)
            store.message(id)?.let { TelegramEvents.result(context, it) }
        }
        NumiHooks.smsDelivered = delivered@{ context, uri, part, _, generation, status ->
            val store = AppStore(context)
            val id = registerSend(context, uri, store)
            val current = store.message(id) ?: return@delivered Int.MIN_VALUE
            if (current.attempts != generation || part !in 0 until current.partCount) return@delivered Int.MIN_VALUE
            val row = if (status == Telephony.Sms.STATUS_COMPLETE || status >= Telephony.Sms.STATUS_FAILED) {
                store.partResult(id, part, "delivered", if (status == 0) SendPolicy.SUCCESS else status, generation)
            } else current
            if (row?.delivered == true) {
                MessagesRepository(context).synchronize(row)
                TelegramEvents.result(context, row)
            }
            when {
                row?.delivered == true -> Telephony.Sms.STATUS_COMPLETE
                store.deliveryFailed(id) -> Telephony.Sms.STATUS_FAILED
                else -> Telephony.Sms.STATUS_PENDING
            }
        }
        NumiHooks.callChanged = CallBridge::changed
        NumiHooks.callRemoved = CallBridge::removed
        synchronizeHistory(this)
        synchronization.execute {
            try {
                if (TelegramEvents.enabled(this)) OutboxService.wake(this)
            } catch (exception: Exception) {
                Log.e("Numi", "Startup synchronization failed: ${exception.javaClass.simpleName}")
            }
        }
    }

    private fun synchronizeHistory(context: Context) {
        synchronization.execute {
            try {
                if (MessagesRepository(context.applicationContext).migrateLegacyHistory() > 0) {
                    refreshMessages(); refreshConversations()
                }
                net.wastu.numi.spam.SpamCoordinator.scanHistory(context.applicationContext)
            } catch (exception: Exception) {
                Log.e("Numi", "History synchronization failed: ${exception.javaClass.simpleName}")
            }
        }
    }

    private fun registerSend(context: Context, uri: Uri, store: AppStore, refresh: Boolean = false): Long {
        val providerId = uri.lastPathSegment!!.toLong()
        val existing = store.db.rawQuery("SELECT id FROM message_outbox WHERE message_type='SMS' AND provider_id=?", arrayOf(providerId.toString()))
            .use { if (it.moveToFirst()) it.getLong(0) else null }
        if (existing != null && !refresh) return existing
        return context.contentResolver.query(uri, arrayOf("address", "body", "sub_id", "date"), null, null, null)!!.use { cursor ->
            if (!cursor.moveToFirst()) return existing ?: error("The SMS no longer exists.")
            store.db.beginTransaction()
            try {
                val id = existing ?: store.legacy.enqueueMessage("SMS", cursor.getString(0), cursor.getString(1), "native-sms:$providerId", cursor.getInt(2))
                val actualId = if (id != -1L) id else store.db.rawQuery("SELECT id FROM message_outbox WHERE idempotency_key=?", arrayOf("native-sms:$providerId"))
                    .use { check(it.moveToFirst()); it.getLong(0) }
                store.update(actualId, ContentValues().apply {
                    put("provider_id", providerId); put("created_at", cursor.getLong(3))
                    put("recipient", cursor.getString(0)); put("body", cursor.getString(1)); put("subscription_id", cursor.getInt(2))
                    // A locally composed message is already being sent by the native sender.
                    // It must never be visible as PENDING to the remote SMS worker.
                    if (existing == null) { put("status", "SENDING"); put("updated_at", System.currentTimeMillis()) }
                })
                store.db.setTransactionSuccessful()
                actualId
            } finally { store.db.endTransaction() }
        }
    }
}
