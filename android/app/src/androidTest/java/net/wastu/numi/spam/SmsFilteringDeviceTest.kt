package net.wastu.numi.spam

import android.content.ContentUris
import android.content.Context
import android.provider.Telephony
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.wastu.numi.messaging.MessagesRepository
import net.wastu.numi.messaging.AppStore
import net.wastu.numi.network.TelegramEvents
import org.fossify.messages.extensions.filterNumiSpamConversations
import org.fossify.messages.extensions.getConversations
import org.fossify.messages.extensions.getMessages
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses three caller-created local provider fixtures. Never sends an SMS or calls Telegram. */
@RunWith(AndroidJUnit4::class)
class SmsFilteringDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val store = SpamStore(context)
    private val spamId = arguments.getString("fixture_spam")?.toLongOrNull() ?: -1L
    private val safeId = arguments.getString("fixture_safe")?.toLongOrNull() ?: -1L
    private val onlySpamId = arguments.getString("fixture_only_spam")?.toLongOrNull() ?: -1L
    private var thread = 0L
    private var spamThread = 0L
    private fun register(id: Long): FilteredSms {
        context.contentResolver.query(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id),
            arrayOf("address", "body", "date", "thread_id", "sub_id"), null, null, null)!!.use { c ->
            check(c.moveToFirst())
            store.register(id, c.getString(0), c.getString(1), c.getLong(2), c.getLong(3), c.getInt(4), false)
        }
        return store.message(id)!!
    }
    @Before fun prepare() {
        assumeTrue(context.packageName.endsWith(".preview") && spamId > 0 && safeId > 0 && onlySpamId > 0)
        listOf(spamId, safeId, onlySpamId).forEach(store::forget)
        thread = register(spamId).threadId
        assertEquals(thread, register(safeId).threadId)
        spamThread = register(onlySpamId).threadId
        store.decide(spamId, SpamDecision(SpamVerdict.SPAM, "Fixture promotion", "rules"))
        store.decide(onlySpamId, SpamDecision(SpamVerdict.SPAM, "Fixture promotion", "rules"))
        store.decide(safeId, SpamDecision(SpamVerdict.SAFE, "Fixture OTP", "rules"))
        listOf(spamId, safeId, onlySpamId).forEach(store::finish)
    }
    @After fun cleanup() { if (context.packageName.endsWith(".preview")) listOf(spamId, safeId, onlySpamId).filter { it > 0 }.forEach(store::forget) }

    @Test fun providerAndCachedInboxHideSpamButPreserveTheOlderSafeSms() {
        val messages = context.getMessages(thread, includeScheduledMessages = false, limit = 1)
        assertEquals(listOf(safeId), messages.filterNot { it.isMMS }.map { it.id })
        val visible = context.getConversations(thread).single()
        assertEquals(store.message(safeId)!!.body, visible.snippet)
        val cached = visible.copy(snippet = store.message(spamId)!!.body, unreadCount = 1)
        assertEquals(visible.snippet, context.filterNumiSpamConversations(listOf(cached)).single().snippet)
        assertTrue(context.getConversations(spamThread).isEmpty())
        assertFalse(MessagesRepository(context).conversations().flatMap { it.messages }.any { it.id == spamId || it.id == onlySpamId })
        assertFalse(SpamCoordinator.summary(context, thread)!!.hidden)
        assertEquals(0, SpamCoordinator.summary(context, thread)!!.unread)
    }
    @Test fun restorationShowsTheOriginalProviderRowWithoutCreatingACopy() {
        assertTrue(store.restore(spamId))
        assertTrue(context.getMessages(thread, includeScheduledMessages = false).any { it.id == spamId })
        assertTrue(MessagesRepository(context).conversations().flatMap { it.messages }.any { it.id == spamId })
        assertTrue(store.message(spamId)!!.effectsDone)
    }
    @Test fun spamAndPendingSmsCannotCreateTelegramAlerts() {
        assertFalse(TelegramEvents.allowsIncomingAlert(context, spamId))
        assertTrue(TelegramEvents.allowsIncomingAlert(context, safeId))
        store.forget(spamId); register(spamId)
        assertFalse(TelegramEvents.allowsIncomingAlert(context, spamId))
    }
    @Test fun receivedPromotionIsScreenedBeforeNotificationDelivery() {
        val original = store.message(spamId)!!
        val prefs = context.getSharedPreferences("numi_settings", Context.MODE_PRIVATE)
        val enabled = GemmaSpamFilter.enabled(context)
        val queuedBefore = AppStore(context).db.rawQuery("SELECT count(*) FROM message_outbox WHERE message_type='TELEGRAM'", null)
            .use { it.moveToFirst(); it.getInt(0) }
        try {
            prefs.edit().putBoolean(GemmaSpamFilter.ENABLED, true).commit()
            store.forget(spamId)
            assertTrue(SpamCoordinator.receive(context, spamId, original.address, original.body, -1, null))
            assertEquals("SPAM", store.message(spamId)!!.state)
            SpamCoordinator.deliverReady(context)
            assertTrue(store.message(spamId)!!.effectsDone)
            assertFalse(TelegramEvents.allowsIncomingAlert(context, spamId))
            val queuedAfter = AppStore(context).db.rawQuery("SELECT count(*) FROM message_outbox WHERE message_type='TELEGRAM'", null)
                .use { it.moveToFirst(); it.getInt(0) }
            assertEquals(queuedBefore, queuedAfter)
        } finally { prefs.edit().putBoolean(GemmaSpamFilter.ENABLED, enabled).commit() }
    }
}
