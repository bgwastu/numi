package net.wastu.numi.messaging

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import net.wastu.numi.database.DbHelper
import net.wastu.numi.utils.SmsAddress
import org.json.JSONObject
import java.util.UUID

data class QueuedMessage(
    val id: Long,
    val type: String,
    val address: String,
    val body: String,
    val createdAt: Long,
    val state: String,
    val attempts: Int,
    val subscriptionId: Int?,
    val providerId: Long?,
    val partCount: Int,
    val updatedAt: Long,
    val delivered: Boolean,
    val error: String?,
    val telegramPart: Int,
    val telegramMessageId: Int?,
    val event: String?
)

data class MessageTarget(val address: String, val subscriptionId: Int?)

class AppStore(context: Context) {
    val legacy = synchronized(AppStore::class.java) {
        helper ?: DbHelper(context.applicationContext).also { helper = it }
    }
    val db: SQLiteDatabase get() = legacy.writableDatabase

    companion object {
        @Volatile private var helper: DbHelper? = null
    }

    fun message(id: Long): QueuedMessage? = db.rawQuery(
        "SELECT * FROM message_outbox WHERE id=?", arrayOf(id.toString())
    ).use { if (it.moveToFirst()) it.message() else null }

    fun queueSms(address: String, body: String, subscriptionId: Int?, key: String = UUID.randomUUID().toString()): Long {
        require(SendPolicy.canReply(address)) { "Enter a phone number or short code." }
        require(body.isNotBlank()) { "Enter a message." }
        val id = legacy.enqueueMessage("SMS", address.trim(), body, key, subscriptionId)
        if (id != -1L) return id
        return db.rawQuery("SELECT id FROM message_outbox WHERE idempotency_key=?", arrayOf(key))
            .use { check(it.moveToFirst()); it.getLong(0) }
    }

    fun queueEvent(address: String, body: String, subscriptionId: Int?, key: String, event: JSONObject): Long {
        db.beginTransaction()
        try {
            val id = legacy.enqueueMessage("TELEGRAM", address, body, key, subscriptionId)
            if (id != -1L) update(id, ContentValues().apply { put("event_json", event.toString()) })
            db.setTransactionSuccessful()
            return id
        } finally { db.endTransaction() }
    }

    fun due(now: Long, type: String): List<QueuedMessage> = db.rawQuery(
        """SELECT * FROM message_outbox WHERE
            message_type=? AND (status='PENDING' OR
            (message_type='TELEGRAM' AND status='FAILED' AND next_attempt_at<=?))
            ORDER BY created_at,id LIMIT 20""".trimIndent(), arrayOf(type, now.toString())
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.message()) } }

    fun sending(): List<QueuedMessage> = db.rawQuery(
        "SELECT * FROM message_outbox WHERE status='SENDING'", null
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.message()) } }

    fun hasSmsWork(): Boolean = db.rawQuery(
        "SELECT 1 FROM message_outbox WHERE message_type='SMS' AND status IN ('PENDING','SENDING') LIMIT 1", null
    ).use { it.moveToFirst() }

    fun beginNativeSend(id: Long, parts: Int): Int {
        db.beginTransaction()
        try {
            db.delete("sms_parts", "outbox_id=?", arrayOf(id.toString()))
            db.execSQL("""UPDATE message_outbox SET status='SENDING',part_count=?,updated_at=?,
                attempt_count=attempt_count+1,delivered=0,last_error=NULL WHERE id=?""",
                arrayOf<Any>(parts, System.currentTimeMillis(), id))
            db.setTransactionSuccessful()
            return message(id)!!.attempts
        } finally { db.endTransaction() }
    }

    fun smsHistory(): List<QueuedMessage> = db.rawQuery(
        "SELECT * FROM message_outbox WHERE message_type='SMS' ORDER BY created_at DESC", null
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.message()) } }

    fun update(id: Long, values: ContentValues): Int = db.update("message_outbox", values, "id=?", arrayOf(id.toString()))

    fun start(id: Long, partCount: Int): Boolean = db.update("message_outbox", ContentValues().apply {
        put("status", "SENDING")
        put("updated_at", System.currentTimeMillis())
        put("part_count", partCount)
    }, "id=? AND status='PENDING'", arrayOf(id.toString())) == 1

    fun fail(id: Long, reason: String) = update(id, ContentValues().apply {
        put("status", "FAILED")
        put("last_error", reason)
        put("updated_at", System.currentTimeMillis())
        put("next_attempt_at", Long.MAX_VALUE)
    })

    fun partResult(id: Long, part: Int, phase: String, result: Int, generation: Int? = null): QueuedMessage? {
        db.beginTransaction()
        try {
            val row = message(id) ?: return null
            if (generation != null && generation != row.attempts) return null
            if (part !in 0 until row.partCount) return null
            db.insertWithOnConflict("sms_parts", null, ContentValues().apply {
                put("outbox_id", id); put("part_index", part); put("phase", phase); put("result", result)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            val results = db.rawQuery("SELECT result FROM sms_parts WHERE outbox_id=? AND phase=?", arrayOf(id.toString(), phase))
                .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getInt(0)) } }
            val state = SendPolicy.aggregate(row.partCount, results)
            if (phase == "sent" && !row.delivered) {
                when (state) {
                    SendState.FAILED -> fail(id, "Android could not send all message parts (result ${results.first { it != SendPolicy.SUCCESS }}).")
                    SendState.SENT -> update(id, ContentValues().apply {
                        put("status", "SENT"); putNull("last_error"); put("updated_at", System.currentTimeMillis())
                    })
                    SendState.SENDING -> Unit
                }
            } else if (state == SendState.SENT) {
                update(id, ContentValues().apply {
                    put("delivered", 1); put("status", "SENT"); putNull("last_error"); put("updated_at", System.currentTimeMillis())
                })
            }
            db.setTransactionSuccessful()
            return message(id)
        } finally {
            db.endTransaction()
        }
    }

    fun deliveryFailed(id: Long): Boolean = db.rawQuery(
        "SELECT 1 FROM sms_parts WHERE outbox_id=? AND phase='delivered' AND result!=? LIMIT 1",
        arrayOf(id.toString(), SendPolicy.SUCCESS.toString())
    ).use { it.moveToFirst() }

    fun saveTarget(chat: String, messageId: Int, address: String, subscriptionId: Int?) {
        db.insertWithOnConflict("telegram_targets", null, ContentValues().apply {
            put("chat_id", chat); put("message_id", messageId); put("address", address)
            if (subscriptionId != null) put("subscription_id", subscriptionId)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun target(chat: String, messageId: Int): MessageTarget? = db.rawQuery(
        "SELECT address,subscription_id FROM telegram_targets WHERE chat_id=? AND message_id=?",
        arrayOf(chat, messageId.toString())
    ).use { if (it.moveToFirst()) MessageTarget(it.getString(0), if (it.isNull(1)) null else it.getInt(1)) else null }

    fun topic(chat: String, address: String, subscriptionId: Int?): Int? = db.rawQuery(
        "SELECT thread_id FROM telegram_topics WHERE chat_id=? AND address_key=? AND subscription_id=?",
        arrayOf(chat, SmsAddress.normalize(address), (subscriptionId ?: -1).toString())
    ).use { if (it.moveToFirst()) it.getInt(0) else null }

    fun topicTarget(chat: String, thread: Int): MessageTarget? = db.rawQuery(
        "SELECT address,subscription_id FROM telegram_topics WHERE chat_id=? AND thread_id=?",
        arrayOf(chat, thread.toString())
    ).use { if (it.moveToFirst()) MessageTarget(it.getString(0), it.getInt(1).takeIf { sub -> sub >= 0 }) else null }

    fun saveTopic(chat: String, address: String, subscriptionId: Int?, thread: Int) {
        db.insertWithOnConflict("telegram_topics", null, ContentValues().apply {
            put("chat_id", chat); put("address_key", SmsAddress.normalize(address)); put("address", address)
            put("subscription_id", subscriptionId ?: -1); put("thread_id", thread)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun forgetTopic(chat: String, thread: Int) {
        db.delete("telegram_topics", "chat_id=? AND thread_id=?", arrayOf(chat, thread.toString()))
    }

    fun receipt(chat: String, key: String): Int? = db.rawQuery(
        "SELECT message_id FROM telegram_receipts WHERE chat_id=? AND event_key=?", arrayOf(chat, key)
    ).use { if (it.moveToFirst()) it.getInt(0) else null }

    fun saveReceipt(chat: String, key: String, messageId: Int, thread: Int?) {
        db.insertWithOnConflict("telegram_receipts", null, ContentValues().apply {
            put("chat_id", chat); put("event_key", key); put("message_id", messageId); put("thread_id", thread)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun Cursor.message() = QueuedMessage(
        id = getLong(getColumnIndexOrThrow("id")), type = getString(getColumnIndexOrThrow("message_type")),
        address = getString(getColumnIndexOrThrow("recipient")), body = getString(getColumnIndexOrThrow("body")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")), state = getString(getColumnIndexOrThrow("status")),
        attempts = getInt(getColumnIndexOrThrow("attempt_count")), subscriptionId = intOrNull("subscription_id"),
        providerId = getColumnIndexOrThrow("provider_id").let { if (isNull(it)) null else getLong(it) },
        partCount = getInt(getColumnIndexOrThrow("part_count")), updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
        delivered = getInt(getColumnIndexOrThrow("delivered")) == 1, error = getString(getColumnIndexOrThrow("last_error")),
        telegramPart = getInt(getColumnIndexOrThrow("telegram_part_index")), telegramMessageId = intOrNull("telegram_last_message_id"),
        event = getString(getColumnIndexOrThrow("event_json"))
    )

    private fun Cursor.intOrNull(column: String): Int? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getInt(it) }
}
