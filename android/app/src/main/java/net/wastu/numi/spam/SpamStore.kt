package net.wastu.numi.spam

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import net.wastu.numi.messaging.AppStore
import net.wastu.numi.utils.SmsAddress

data class FilteredSms(val id: Long, val address: String, val body: String, val date: Long, val threadId: Long,
    val subscriptionId: Int?, val state: String, val reason: String, val live: Boolean, val effectsDone: Boolean)

class SpamStore(context: Context) {
    private val db = AppStore(context).db

    companion object {
        fun install(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS sms_filter (
                provider_id INTEGER PRIMARY KEY, address TEXT NOT NULL, body TEXT NOT NULL,
                received_at INTEGER NOT NULL, thread_id INTEGER NOT NULL, subscription_id INTEGER,
                state TEXT NOT NULL CHECK(state IN ('PENDING','SAFE','SPAM')),
                reason TEXT NOT NULL DEFAULT '', source TEXT NOT NULL DEFAULT '',
                live INTEGER NOT NULL DEFAULT 0, effects_done INTEGER NOT NULL DEFAULT 0)""")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_filter_work ON sms_filter(state,effects_done)")
            db.execSQL("CREATE TABLE IF NOT EXISTS sms_allowed_senders (address_key TEXT PRIMARY KEY)")
        }
    }

    fun register(id: Long, address: String, body: String, date: Long, thread: Long, sub: Int?, live: Boolean, blockedReason: String? = null) {
        db.beginTransaction()
        try {
        // Android can reuse a deleted provider ID. A verdict belongs to the original SMS.
        val previous = message(id)
        if (previous != null && (previous.address != address || previous.body != body || previous.date != date)) forget(id)
        db.insertWithOnConflict("sms_filter", null, ContentValues().apply {
            put("provider_id", id); put("address", address); put("body", body); put("received_at", date)
            put("thread_id", thread); if (sub != null) put("subscription_id", sub)
            put("live", if (live) 1 else 0); put("state", if (blockedReason == null) "PENDING" else "SPAM")
            put("reason", blockedReason.orEmpty()); put("source", if (blockedReason == null) "" else "blocked")
        }, SQLiteDatabase.CONFLICT_IGNORE)
        if (blockedReason != null) {
            db.execSQL("UPDATE sms_filter SET state='SPAM',reason=?,source='blocked' WHERE provider_id=? AND source!='user'", arrayOf<Any>(blockedReason, id))
        } else {
            db.execSQL("UPDATE sms_filter SET state='PENDING',live=0,effects_done=0,source='',reason='' WHERE provider_id=? AND source='blocked'", arrayOf(id))
        }
        db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun message(id: Long): FilteredSms? = db.rawQuery("SELECT * FROM sms_filter WHERE provider_id=?", arrayOf(id.toString()))
        .use { if (it.moveToFirst()) it.sms() else null }

    fun work(): List<FilteredSms> = db.rawQuery("SELECT * FROM sms_filter WHERE state='PENDING' OR effects_done=0 ORDER BY live DESC,received_at,provider_id LIMIT 40", null)
        .use { c -> buildList { while (c.moveToNext()) add(c.sms()) } }
    fun pending(): FilteredSms? = db.rawQuery("SELECT * FROM sms_filter WHERE state='PENDING' ORDER BY live DESC,received_at,provider_id LIMIT 1", null)
        .use { if (it.moveToFirst()) it.sms() else null }
    fun ready(): List<FilteredSms> = db.rawQuery("SELECT * FROM sms_filter WHERE state!='PENDING' AND effects_done=0 ORDER BY live DESC,received_at,provider_id LIMIT 40", null)
        .use { c -> buildList { while (c.moveToNext()) add(c.sms()) } }
    fun pendingCount(): Int = db.rawQuery("SELECT count(*) FROM sms_filter WHERE state='PENDING'", null)
        .use { it.moveToFirst(); it.getInt(0) }
    fun trackedIds(): Set<Long> = db.rawQuery("SELECT provider_id FROM sms_filter", null)
        .use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } }
    fun prune(existingIds: Set<Long>, candidates: Set<Long> = trackedIds()) {
        val stale = db.rawQuery("SELECT provider_id FROM sms_filter", null).use { c ->
            buildList { while (c.moveToNext()) if (c.getLong(0) in candidates && c.getLong(0) !in existingIds) add(c.getLong(0)) }
        }
        stale.forEach(::forget)
    }

    fun hasWork() = db.rawQuery("SELECT 1 FROM sms_filter WHERE state='PENDING' OR effects_done=0 LIMIT 1", null).use { it.moveToFirst() }

    fun decide(id: Long, decision: SpamDecision): Boolean {
        val updated = db.update("sms_filter", ContentValues().apply {
            put("state", when (decision.verdict) {
                SpamVerdict.SPAM -> "SPAM"
                SpamVerdict.SAFE -> "SAFE"
                SpamVerdict.UNKNOWN -> "PENDING"
            })
            put("reason", decision.reason); put("source", decision.source)
        }, "provider_id=? AND state='PENDING'", arrayOf(id.toString())) == 1
        return updated && decision.verdict != SpamVerdict.UNKNOWN
    }

    fun finish(id: Long) { db.execSQL("UPDATE sms_filter SET effects_done=1 WHERE provider_id=? AND state!='PENDING'", arrayOf(id)) }
    fun forget(id: Long) { db.delete("sms_filter", "provider_id=?", arrayOf(id.toString())) }
    fun spam(): List<FilteredSms> = db.rawQuery("SELECT * FROM sms_filter WHERE state='SPAM' ORDER BY received_at DESC", null)
        .use { c -> buildList { while (c.moveToNext()) add(c.sms()) } }
    fun hiddenIds(): Set<Long> = db.rawQuery("SELECT provider_id FROM sms_filter WHERE state='SPAM'", null)
        .use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } }
    fun hasSpamInThread(thread: Long) = db.rawQuery("SELECT 1 FROM sms_filter WHERE state='SPAM' AND thread_id=? LIMIT 1", arrayOf(thread.toString())).use { it.moveToFirst() }

    fun restore(id: Long, allowSender: Boolean = false): Boolean {
        db.beginTransaction()
        try {
            val row = message(id) ?: return false
            if (allowSender) db.insertWithOnConflict("sms_allowed_senders", null, ContentValues().apply {
                put("address_key", SmsAddress.normalize(row.address))
            }, SQLiteDatabase.CONFLICT_IGNORE)
            val changed = db.update("sms_filter", ContentValues().apply {
                put("state", "SAFE"); put("reason", "Restored by you"); put("source", "user"); put("effects_done", 1)
            }, "provider_id=?", arrayOf(id.toString())) == 1
            db.setTransactionSuccessful()
            return changed
        } finally { db.endTransaction() }
    }

    fun allowed(address: String) = db.rawQuery("SELECT 1 FROM sms_allowed_senders WHERE address_key=?", arrayOf(SmsAddress.normalize(address)))
        .use { it.moveToFirst() }

    fun retryUnavailable() {
        // Previously forwarded failures are historical reviews. New pending receipts
        // keep their live flag so a successful retry can deliver the original alert.
        db.execSQL("UPDATE sms_filter SET state='PENDING',live=0,effects_done=0 WHERE state!='PENDING' AND (source='unavailable' OR (source IN ('gemma','ai') AND state='SAFE' AND reason='AI returned an invalid verdict'))")
    }

    fun recheck() {
        db.execSQL("UPDATE sms_filter SET state='PENDING',live=0,effects_done=0,source='',reason='' WHERE source NOT IN ('user','blocked') AND state!='PENDING' AND (effects_done=1 OR live=0)")
    }

    fun recheckClassifier() {
        db.execSQL("UPDATE sms_filter SET state='PENDING',live=0,effects_done=0,source='',reason='' WHERE source IN ('classifier','gemma','ai','unavailable') AND state!='PENDING' AND (effects_done=1 OR live=0)")
    }

    private fun Cursor.sms() = FilteredSms(getLong(getColumnIndexOrThrow("provider_id")), getString(getColumnIndexOrThrow("address")),
        getString(getColumnIndexOrThrow("body")), getLong(getColumnIndexOrThrow("received_at")), getLong(getColumnIndexOrThrow("thread_id")),
        getColumnIndexOrThrow("subscription_id").let { if (isNull(it)) null else getInt(it) }, getString(getColumnIndexOrThrow("state")),
        getString(getColumnIndexOrThrow("reason")), getInt(getColumnIndexOrThrow("live")) == 1, getInt(getColumnIndexOrThrow("effects_done")) == 1)
}
