package net.wastu.numi.messaging

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import net.wastu.numi.utils.SmsAddress
import net.wastu.numi.utils.SmsRoleManager

data class SmsEntry(
    val id: Long, val address: String, val body: String, val date: Long,
    val incoming: Boolean, val read: Boolean, val subscriptionId: Int?,
    val status: String, val error: String? = null, val outboxId: Long? = null
)

data class Conversation(val address: String, val name: String, val messages: List<SmsEntry>) {
    val latest: SmsEntry get() = messages.last()
    val unread: Int get() = messages.count { it.incoming && !it.read }
}

class MessagesRepository(private val context: Context) {
    companion object { private val migrationLock = Any() }
    private val resolver = context.contentResolver
    private val store = AppStore(context)
    private val contactNames = mutableMapOf<String, String>()

    fun name(address: String): String {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return address
        return contactNames.getOrPut(address) {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
            runCatching { resolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else address
            } }.getOrNull() ?: address
        }
    }

    fun conversations(): List<Conversation> {
        val hidden = net.wastu.numi.spam.SpamStore(context).hiddenIds()
        val history = store.smsHistory()
        val jobs = history.filter { it.providerId != null }.associateBy { it.providerId!! }
        val messages = mutableListOf<SmsEntry>()
        if (context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            resolver.query(Telephony.Sms.CONTENT_URI,
                arrayOf("_id", "address", "body", "date", "type", "read", "sub_id", "status"),
                "type!=?", arrayOf(Telephony.Sms.MESSAGE_TYPE_DRAFT.toString()), "date ASC, _id ASC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    if (id in hidden) continue
                    val type = cursor.getInt(4)
                    val job = jobs[id]
                    messages += SmsEntry(id, cursor.getString(1) ?: "Unknown", cursor.getString(2).orEmpty(),
                        cursor.getLong(3), type == Telephony.Sms.MESSAGE_TYPE_INBOX, cursor.getInt(5) == 1,
                        cursor.getInt(6).takeIf { it >= 0 }, job?.let(::status) ?: when (type) {
                            Telephony.Sms.MESSAGE_TYPE_INBOX -> "Received"
                            Telephony.Sms.MESSAGE_TYPE_FAILED -> "Failed"
                            Telephony.Sms.MESSAGE_TYPE_QUEUED -> "Queued"
                            Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "Sending"
                            else -> if (cursor.getInt(7) == Telephony.Sms.STATUS_COMPLETE) "Delivered" else "Sent"
                        }, job?.error, job?.id)
                }
            }
        }
        val ids = messages.map { it.id }.toSet()
        store.legacy.listInbox(Int.MAX_VALUE).forEach { row ->
            val providerId = store.db.rawQuery("SELECT provider_id FROM sms_inbox WHERE id=?", arrayOf(row.id.toString()))
                .use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
            if (providerId == null && messages.none { it.incoming && it.address == row.sender && it.date == row.receivedAt && it.body == row.body }) {
                messages += SmsEntry(-row.id, row.sender, row.body, row.receivedAt, true, false, row.subscriptionId, "Received")
            }
        }
        history.filter { it.providerId == null || it.providerId !in ids }.forEach { row ->
            // Deleted provider messages stay deleted; only queued or unmigrated legacy rows need a fallback.
            if (row.providerId == null) messages += SmsEntry(Long.MIN_VALUE + row.id, row.address, row.body,
                row.createdAt, false, true, row.subscriptionId, status(row), row.error, row.id)
        }
        return messages.groupBy { SmsAddress.normalize(it.address) }.values.map { entries ->
            val sorted = entries.sortedWith(compareBy<SmsEntry> { it.date }.thenBy { it.id })
            Conversation(sorted.last().address, name(sorted.last().address), sorted)
        }.sortedByDescending { it.latest.date }
    }

    fun migrateLegacyHistory(): Int = synchronized(migrationLock) {
        if (!SmsRoleManager.isDefaultSmsApp(context)) return@synchronized 0
        val pending = store.db.rawQuery("SELECT 1 FROM sms_inbox WHERE provider_id IS NULL UNION ALL SELECT 1 FROM message_outbox WHERE message_type='SMS' AND provider_id IS NULL LIMIT 1", null)
            .use { it.moveToFirst() }
        if (!pending) return@synchronized 0
        var migrated = 0
        store.legacy.listInbox(Int.MAX_VALUE).forEach { row ->
            val pending = store.db.rawQuery("SELECT provider_id FROM sms_inbox WHERE id=?", arrayOf(row.id.toString()))
                .use { it.moveToFirst() && it.isNull(0) }
            if (pending) {
                val uri = findExisting(row.sender, row.body, row.receivedAt, true, row.subscriptionId)
                    ?: insert(row.sender, row.body, row.receivedAt, Telephony.Sms.MESSAGE_TYPE_INBOX, row.subscriptionId, true)
                if (uri != null) {
                    store.db.update("sms_inbox", ContentValues().apply { put("provider_id", ContentUris.parseId(uri)) }, "id=?", arrayOf(row.id.toString()))
                    migrated++
                }
            }
        }
        store.smsHistory().filter { it.providerId == null }.forEach { ensureOutgoing(it); migrated++ }
        migrated
    }

    fun recordIncoming(address: String, body: String, date: Long, subscriptionId: Int?): Long {
        val id = store.legacy.recordIncomingSms(address, body, date, subscriptionId)
        val uri = insert(address, body, date, Telephony.Sms.MESSAGE_TYPE_INBOX, subscriptionId, false)
        if (uri != null) store.db.update("sms_inbox", ContentValues().apply { put("provider_id", ContentUris.parseId(uri)) }, "id=?", arrayOf(id.toString()))
        return uri?.let(ContentUris::parseId) ?: -id
    }

    fun ensureOutgoing(row: QueuedMessage): Long? = synchronized(migrationLock) {
        (store.message(row.id)?.providerId ?: row.providerId)?.let { return@synchronized it }
        if (!SmsRoleManager.isDefaultSmsApp(context)) return@synchronized null
        val type = when (row.state) {
            "SENT" -> Telephony.Sms.MESSAGE_TYPE_SENT
            "FAILED" -> Telephony.Sms.MESSAGE_TYPE_FAILED
            "SENDING" -> Telephony.Sms.MESSAGE_TYPE_OUTBOX
            else -> Telephony.Sms.MESSAGE_TYPE_QUEUED
        }
        val uri = findExisting(row.address, row.body, row.createdAt, false, row.subscriptionId)
            ?: insert(row.address, row.body, row.createdAt, type, row.subscriptionId, true)
        uri?.let { ContentUris.parseId(it) }?.also { id ->
            store.update(row.id, ContentValues().apply { put("provider_id", id) })
        }
    }

    fun synchronize(row: QueuedMessage) {
        val id = ensureOutgoing(row) ?: return
        if (!SmsRoleManager.isDefaultSmsApp(context)) return
        resolver.update(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id), ContentValues().apply {
            put("type", when (row.state) {
                "SENT" -> Telephony.Sms.MESSAGE_TYPE_SENT
                "FAILED" -> Telephony.Sms.MESSAGE_TYPE_FAILED
                "SENDING" -> Telephony.Sms.MESSAGE_TYPE_OUTBOX
                else -> Telephony.Sms.MESSAGE_TYPE_QUEUED
            })
            put("status", if (row.delivered) Telephony.Sms.STATUS_COMPLETE else Telephony.Sms.STATUS_NONE)
        }, null, null)
    }

    fun markRead(address: String) {
        if (SmsRoleManager.isDefaultSmsApp(context)) resolver.update(Telephony.Sms.CONTENT_URI,
            ContentValues().apply { put("read", 1); put("seen", 1) }, "address=? AND read=0", arrayOf(address))
    }

    private fun findExisting(address: String, body: String, date: Long, incoming: Boolean, subscriptionId: Int?): Uri? =
        resolver.query(Telephony.Sms.CONTENT_URI, arrayOf("_id", "sub_id"),
            "address=? AND body=? AND date=? AND ${if (incoming) "type=1" else "type!=1"}",
            arrayOf(address, body, date.toString()), null)?.use { cursor ->
                var found: Uri? = null
                while (cursor.moveToNext()) if (subscriptionId == null || cursor.getInt(1) == subscriptionId) {
                    found = ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, cursor.getLong(0)); break
                }
                found
            }

    private fun insert(address: String, body: String, date: Long, type: Int, subscriptionId: Int?, read: Boolean): Uri? {
        if (!SmsRoleManager.isDefaultSmsApp(context)) return null
        return resolver.insert(Telephony.Sms.CONTENT_URI, ContentValues().apply {
            put("address", address); put("body", body); put("date", date); put("type", type)
            put("read", if (read) 1 else 0); put("seen", if (read) 1 else 0)
            put("thread_id", Telephony.Threads.getOrCreateThreadId(context, address))
            if (subscriptionId != null) put("sub_id", subscriptionId)
        })
    }

    private fun status(row: QueuedMessage): String = if (row.delivered) "Delivered" else when (row.state) {
        "PENDING" -> "Queued"
        "SENDING" -> "Sending"
        "FAILED" -> "Failed"
        else -> "Sent"
    }
}
