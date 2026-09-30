package net.wastu.numi.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "numi.db"
        private const val DATABASE_VERSION = 2

        // Table Messages
        const val TABLE_OUTBOX = "message_outbox"
        const val COL_ID = "id"
        const val COL_TYPE = "message_type"
        const val COL_RECIPIENT = "recipient"
        const val COL_BODY = "body"
        const val COL_CREATED_AT = "created_at"
        const val COL_STATUS = "status"
        const val COL_ATTEMPTS = "attempt_count"
        const val COL_NEXT_ATTEMPT = "next_attempt_at"
        const val COL_IDEMPOTENCY_KEY = "idempotency_key"
        const val COL_SUBSCRIPTION_ID = "subscription_id"
        const val COL_LAST_ERROR = "last_error"

        // Table Nonces
        const val TABLE_NONCES = "command_nonces"
        const val COL_NONCE = "nonce"
        const val COL_ACTOR = "actor_id"

        // Table Audit Log
        const val TABLE_AUDIT = "audit_log"
        const val COL_AUDIT_ID = "id"
        const val COL_TIMESTAMP = "timestamp"
        const val COL_AUDIT_ACTOR = "actor_id"
        const val COL_ACTION = "action"
        const val COL_ARGS = "arguments_json"
        const val COL_OUTCOME = "outcome"
        const val COL_DETAILS = "details"

        const val TABLE_INBOX = "sms_inbox"
        const val COL_INBOX_ID = "id"
        const val COL_INBOX_SENDER = "sender"
        const val COL_INBOX_BODY = "body"
        const val COL_INBOX_RECEIVED_AT = "received_at"
        const val COL_INBOX_SUBSCRIPTION_ID = "subscription_id"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createOutbox = """
            CREATE TABLE $TABLE_OUTBOX (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TYPE TEXT NOT NULL CHECK($COL_TYPE IN ('SMS', 'TELEGRAM')),
                $COL_RECIPIENT TEXT NOT NULL,
                $COL_BODY TEXT NOT NULL,
                $COL_CREATED_AT INTEGER NOT NULL,
                $COL_STATUS TEXT NOT NULL CHECK($COL_STATUS IN ('PENDING', 'SENDING', 'SENT', 'FAILED')),
                $COL_ATTEMPTS INTEGER DEFAULT 0,
                $COL_NEXT_ATTEMPT INTEGER DEFAULT 0,
                $COL_IDEMPOTENCY_KEY TEXT UNIQUE,
                $COL_SUBSCRIPTION_ID INTEGER,
                $COL_LAST_ERROR TEXT
            )
        """.trimIndent()

        val createNonces = """
            CREATE TABLE $TABLE_NONCES (
                $COL_NONCE TEXT PRIMARY KEY,
                $COL_ACTOR TEXT NOT NULL,
                $COL_CREATED_AT INTEGER NOT NULL
            )
        """.trimIndent()

        val createAudit = """
            CREATE TABLE $TABLE_AUDIT (
                $COL_AUDIT_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_AUDIT_ACTOR TEXT NOT NULL,
                $COL_ACTION TEXT NOT NULL,
                $COL_ARGS TEXT NOT NULL,
                $COL_OUTCOME TEXT NOT NULL CHECK($COL_OUTCOME IN ('SUCCESS', 'FAILED', 'REJECTED')),
                $COL_DETAILS TEXT
            )
        """.trimIndent()

        db.execSQL(createOutbox)
        db.execSQL(createNonces)
        db.execSQL(createAudit)
        db.execSQL("""
            CREATE TABLE $TABLE_INBOX (
                $COL_INBOX_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_INBOX_SENDER TEXT NOT NULL,
                $COL_INBOX_BODY TEXT NOT NULL,
                $COL_INBOX_RECEIVED_AT INTEGER NOT NULL,
                $COL_INBOX_SUBSCRIPTION_ID INTEGER
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE_OUTBOX ADD COLUMN $COL_SUBSCRIPTION_ID INTEGER")
            db.execSQL("ALTER TABLE $TABLE_OUTBOX ADD COLUMN $COL_LAST_ERROR TEXT")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $TABLE_INBOX (
                    $COL_INBOX_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_INBOX_SENDER TEXT NOT NULL,
                    $COL_INBOX_BODY TEXT NOT NULL,
                    $COL_INBOX_RECEIVED_AT INTEGER NOT NULL,
                    $COL_INBOX_SUBSCRIPTION_ID INTEGER
                )
            """.trimIndent())
        }
    }

    // Helper to insert message
    fun enqueueMessage(
        type: String,
        recipient: String,
        body: String,
        idempotencyKey: String? = null,
        subscriptionId: Int? = null
    ): Long {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COL_TYPE, type)
            put(COL_RECIPIENT, recipient)
            put(COL_BODY, body)
            put(COL_CREATED_AT, System.currentTimeMillis())
            put(COL_STATUS, "PENDING")
            put(COL_ATTEMPTS, 0)
            put(COL_NEXT_ATTEMPT, 0)
            if (idempotencyKey != null) {
                put(COL_IDEMPOTENCY_KEY, idempotencyKey)
            }
            if (subscriptionId != null) put(COL_SUBSCRIPTION_ID, subscriptionId)
        }
        return db.insertWithOnConflict(TABLE_OUTBOX, null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    // Helper to log audit events
    fun logAudit(actorId: String, action: String, argsJson: String, outcome: String, details: String? = null): Long {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COL_TIMESTAMP, System.currentTimeMillis())
            put(COL_AUDIT_ACTOR, actorId)
            put(COL_ACTION, action)
            put(COL_ARGS, argsJson)
            put(COL_OUTCOME, outcome)
            put(COL_DETAILS, details)
        }
        return db.insert(TABLE_AUDIT, null, values)
    }

    // Check and save nonce (returns false if already exists). Uses atomic INSERT OR IGNORE.
    fun useNonce(nonce: String, actorId: String): Boolean {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COL_NONCE, nonce)
            put(COL_ACTOR, actorId)
            put(COL_CREATED_AT, System.currentTimeMillis())
        }
        val result = db.insertWithOnConflict(TABLE_NONCES, null, values, SQLiteDatabase.CONFLICT_IGNORE)
        return result != -1L
    }

    fun recordIncomingSms(sender: String, body: String, receivedAt: Long, subscriptionId: Int? = null): Long {
        val values = ContentValues().apply {
            put(COL_INBOX_SENDER, sender)
            put(COL_INBOX_BODY, body)
            put(COL_INBOX_RECEIVED_AT, receivedAt)
            if (subscriptionId != null) put(COL_INBOX_SUBSCRIPTION_ID, subscriptionId)
        }
        return writableDatabase.insert(TABLE_INBOX, null, values)
    }

    fun recordAcceptedSms(recipient: String, body: String, subscriptionId: Int?): Long {
        val values = ContentValues().apply {
            put(COL_TYPE, "SMS")
            put(COL_RECIPIENT, recipient)
            put(COL_BODY, body)
            put(COL_CREATED_AT, System.currentTimeMillis())
            put(COL_STATUS, "SENT")
            put(COL_ATTEMPTS, 0)
            put(COL_NEXT_ATTEMPT, 0)
            if (subscriptionId != null) put(COL_SUBSCRIPTION_ID, subscriptionId)
        }
        return writableDatabase.insert(TABLE_OUTBOX, null, values)
    }

    fun deleteIncomingSms(id: Long): Int = writableDatabase.delete(
        TABLE_INBOX,
        "$COL_INBOX_ID = ?",
        arrayOf(id.toString())
    )

    fun incomingSmsCount(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM $TABLE_INBOX",
        null
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) else 0
    }

    fun listOutbox(type: String = "SMS", limit: Int = 100, offset: Int = 0): List<OutboxMessage> {
        val messages = mutableListOf<OutboxMessage>()
        val page = if (offset > 0) "${limit.coerceAtLeast(0)} OFFSET ${offset.coerceAtLeast(0)}" else limit.coerceAtLeast(0).toString()
        readableDatabase.query(
            TABLE_OUTBOX,
            arrayOf(COL_ID, COL_RECIPIENT, COL_BODY, COL_CREATED_AT, COL_STATUS, COL_ATTEMPTS,
                COL_NEXT_ATTEMPT, COL_SUBSCRIPTION_ID, COL_LAST_ERROR),
            "$COL_TYPE = ?",
            arrayOf(type), null, null, "$COL_CREATED_AT DESC, $COL_ID DESC", page
        ).use { cursor ->
            while (cursor.moveToNext()) {
                messages += OutboxMessage(
                    id = cursor.getLong(0),
                    recipient = cursor.getString(1),
                    body = cursor.getString(2),
                    createdAt = cursor.getLong(3),
                    status = cursor.getString(4),
                    attempts = cursor.getInt(5),
                    nextAttemptAt = cursor.getLong(6),
                    subscriptionId = if (cursor.isNull(7)) null else cursor.getInt(7),
                    lastError = if (cursor.isNull(8)) null else cursor.getString(8)
                )
            }
        }
        return messages
    }

    fun listInbox(limit: Int = 100, offset: Int = 0): List<IncomingSms> {
        val messages = mutableListOf<IncomingSms>()
        val page = if (offset > 0) "${limit.coerceAtLeast(0)} OFFSET ${offset.coerceAtLeast(0)}" else limit.coerceAtLeast(0).toString()
        readableDatabase.query(
            TABLE_INBOX,
            arrayOf(COL_INBOX_ID, COL_INBOX_SENDER, COL_INBOX_BODY, COL_INBOX_RECEIVED_AT,
                COL_INBOX_SUBSCRIPTION_ID),
            null, null, null, null, "$COL_INBOX_RECEIVED_AT DESC, $COL_INBOX_ID DESC", page
        ).use { cursor ->
            while (cursor.moveToNext()) {
                messages += IncomingSms(
                    id = cursor.getLong(0),
                    sender = cursor.getString(1),
                    body = cursor.getString(2),
                    receivedAt = cursor.getLong(3),
                    subscriptionId = if (cursor.isNull(4)) null else cursor.getInt(4)
                )
            }
        }
        return messages
    }

    fun getIncomingSms(id: Long): IncomingSms? = readableDatabase.query(
        TABLE_INBOX,
        arrayOf(COL_INBOX_ID, COL_INBOX_SENDER, COL_INBOX_BODY, COL_INBOX_RECEIVED_AT,
            COL_INBOX_SUBSCRIPTION_ID),
        "$COL_INBOX_ID = ?",
        arrayOf(id.toString()), null, null, null, "1"
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        IncomingSms(
            id = cursor.getLong(0),
            sender = cursor.getString(1),
            body = cursor.getString(2),
            receivedAt = cursor.getLong(3),
            subscriptionId = if (cursor.isNull(4)) null else cursor.getInt(4)
        )
    }

    fun outboxCounts(type: String = "SMS"): Map<String, Int> {
        val counts = mutableMapOf("PENDING" to 0, "SENDING" to 0, "SENT" to 0, "FAILED" to 0)
        readableDatabase.rawQuery(
            "SELECT $COL_STATUS, COUNT(*) FROM $TABLE_OUTBOX WHERE $COL_TYPE = ? GROUP BY $COL_STATUS",
            arrayOf(type)
        ).use { cursor ->
            while (cursor.moveToNext()) counts[cursor.getString(0)] = cursor.getInt(1)
        }
        return counts
    }

    fun retryMessage(id: Long): Int = writableDatabase.update(
        TABLE_OUTBOX,
        ContentValues().apply {
            put(COL_STATUS, "PENDING")
            put(COL_ATTEMPTS, 0)
            put(COL_NEXT_ATTEMPT, 0)
            putNull(COL_LAST_ERROR)
        },
        "$COL_ID = ? AND $COL_TYPE = 'SMS' AND $COL_STATUS = 'FAILED'",
        arrayOf(id.toString())
    )

    fun deleteMessage(id: Long): Int = writableDatabase.delete(
        TABLE_OUTBOX,
        "$COL_ID = ? AND $COL_TYPE = 'SMS' AND $COL_STATUS != 'SENDING'",
        arrayOf(id.toString())
    )

    fun deleteFilteredIncomingSms(id: Long): Int = writableDatabase.delete(
        TABLE_OUTBOX,
        "$COL_ID = ? AND $COL_TYPE = 'TELEGRAM' AND $COL_STATUS IN ('PENDING', 'FAILED')",
        arrayOf(id.toString())
    )
}

data class OutboxMessage(
    val id: Long,
    val recipient: String,
    val body: String,
    val createdAt: Long,
    val status: String,
    val attempts: Int,
    val nextAttemptAt: Long,
    val subscriptionId: Int?,
    val lastError: String?
)

data class IncomingSms(
    val id: Long,
    val sender: String,
    val body: String,
    val receivedAt: Long,
    val subscriptionId: Int?
)
