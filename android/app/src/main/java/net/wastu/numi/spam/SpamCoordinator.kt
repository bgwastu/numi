package net.wastu.numi.spam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import kotlinx.coroutines.CancellationException
import net.wastu.numi.contracts.InboxSummary
import net.wastu.numi.network.TelegramEvents
import net.wastu.numi.queue.OutboxService
import org.fossify.commons.extensions.isNumberBlocked
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.messages.extensions.getMessages
import org.fossify.messages.extensions.getNotificationBitmap
import org.fossify.messages.extensions.showReceivedMessageNotification
import org.fossify.messages.extensions.shouldUnarchive
import org.fossify.messages.extensions.updateConversationArchivedStatus
import org.fossify.messages.helpers.ReceiverUtils
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages

object SpamCoordinator {
    fun receive(context: Context, id: Long, address: String, body: String, sub: Int, blockedReason: String?): Boolean {
        val uri = Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, id.toString())
        context.contentResolver.query(uri, arrayOf("date", "thread_id"), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val store = SpamStore(context)
                store.register(id, address, body, c.getLong(0), c.getLong(1), sub.takeIf { it >= 0 }, true,
                    blockedReason.takeUnless { store.allowed(address) })
                if (blockedReason == null || store.allowed(address)) {
                    val decision = localDecision(context, store, address, body)
                    if (decision.verdict != SpamVerdict.UNKNOWN) store.decide(id, decision)
                }
            }
        }
        OutboxService.wake(context)
        return true
    }

    fun scanHistory(context: Context) {
        if (context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return
        GemmaSpamFilter.prepare(context)
        val store = SpamStore(context)
        val previouslyTracked = store.trackedIds()
        val existingIds = mutableSetOf<Long>()
        var scanned = false
        context.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf("_id", "address", "body", "date", "thread_id", "sub_id"),
            "type=?", arrayOf(Telephony.Sms.MESSAGE_TYPE_INBOX.toString()), "date ASC")?.use { c ->
            while (c.moveToNext()) {
                existingIds.add(c.getLong(0))
                val address = c.getString(1).orEmpty(); val body = c.getString(2).orEmpty()
                val reason = when {
                    store.allowed(address) -> null
                    ReceiverUtils.isMessageFilteredOut(context, body) -> "Blocked keyword"
                    context.isNumberBlocked(address) -> "Blocked sender"
                    else -> null
                }
                if (GemmaSpamFilter.enabled(context) || reason != null || c.getLong(0) in previouslyTracked)
                    store.register(c.getLong(0), address, body, c.getLong(3), c.getLong(4), c.getInt(5).takeIf { it >= 0 }, false, reason)
            }
            scanned = true
        }
        if (scanned) store.prune(existingIds, previouslyTracked)
        OutboxService.wake(context)
    }

    suspend fun process(context: Context) {
        val store = SpamStore(context)
        repeat(40) {
            // Re-read between classifications so new messages take priority over history.
            val original = store.pending() ?: return
            val uri = Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, original.id.toString())
            val read = context.contentResolver.query(uri, arrayOf("read"), null, null, null)?.use { c -> if (c.moveToFirst()) c.getInt(0) == 1 else null }
            if (read == null) { store.forget(original.id); return@repeat }
            if (original.state == "PENDING") {
                val local = localDecision(context, store, original.address, original.body)
                val decision = if (local.verdict != SpamVerdict.UNKNOWN) local else try {
                    GemmaSpamFilter.classify(context, original.body)
                } catch (exception: CancellationException) { throw exception }
                catch (_: Exception) { SpamDecision(SpamVerdict.UNKNOWN, "SMS classifier unavailable; local rules remain active", "unavailable") }
                store.decide(original.id, decision)
                if (decision.verdict == SpamVerdict.UNKNOWN) return
            }
            refreshMessages(); refreshConversations()
        }
    }

    /** Deliver only messages with a persisted screening decision. */
    fun deliverReady(context: Context) {
        val store = SpamStore(context)
        for (row in store.ready()) {
            val uri = Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, row.id.toString())
            val read = context.contentResolver.query(uri, arrayOf("read"), null, null, null)?.use { c -> if (c.moveToFirst()) c.getInt(0) == 1 else null }
            if (read == null) { store.forget(row.id); continue }
            if (row.state == "SAFE" && row.live && !row.effectsDone) {
                if (context.shouldUnarchive()) context.updateConversationArchivedStatus(row.threadId, false)
                // Persisted forwarding events are idempotent. Notification IDs are the SMS ID.
                TelegramEvents.incoming(context, row.id, row.address, row.body, row.subscriptionId)
                if (!read) {
                    val helper = SimpleContactsHelper(context)
                    context.showReceivedMessageNotification(row.id, false, row.address, row.address, row.body, row.threadId,
                        context.getNotificationBitmap(helper.getPhotoUriFromPhoneNumber(row.address)))
                }
            }
            store.finish(row.id)
            refreshMessages(); refreshConversations()
        }
    }

    private fun localDecision(context: Context, store: SpamStore, address: String, body: String): SpamDecision =
        if (!GemmaSpamFilter.enabled(context)) SpamDecision(SpamVerdict.SAFE, "Automatic filtering is disabled", "disabled")
        else SpamPolicy.evaluate(body, store.allowed(address) || isContact(context, address))

    fun summary(context: Context, thread: Long): InboxSummary? {
        val store = SpamStore(context)
        if (!store.hasSpamInThread(thread)) return null
        val latest = context.getMessages(thread, includeScheduledMessages = false, limit = 1).lastOrNull()
            ?: return InboxSummary(hidden = true)
        val hidden = store.hiddenIds()
        val exclusions = if (hidden.isEmpty()) "" else " AND _id NOT IN (${hidden.joinToString(",")})"
        val smsUnread = context.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf("_id"), "thread_id=? AND read=0$exclusions",
            arrayOf(thread.toString()), null)?.use { it.count } ?: 0
        val mmsUnread = context.contentResolver.query(Telephony.Mms.CONTENT_URI, arrayOf("_id"), "thread_id=? AND read=0 AND msg_box=1",
            arrayOf(thread.toString()), null)?.use { it.count } ?: 0
        return InboxSummary(false, latest.body, latest.date, smsUnread + mmsUnread)
    }

    private fun isContact(context: Context, address: String): Boolean {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED || address.any { it.isLetter() }) return false
        return context.contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)),
            arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { it.moveToFirst() } ?: false
    }
}
