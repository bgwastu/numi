package com.bgwastu.numi.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "numi.db"
        private const val DATABASE_VERSION = 1

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
                $COL_IDEMPOTENCY_KEY TEXT UNIQUE
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_OUTBOX")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NONCES")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_AUDIT")
        onCreate(db)
    }

    // Helper to insert message
    fun enqueueMessage(type: String, recipient: String, body: String, idempotencyKey: String? = null): Long {
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

    // Check and save nonce (returns false if already exists)
    fun useNonce(nonce: String, actorId: String): Boolean {
        val db = this.writableDatabase
        val checkQuery = "SELECT 1 FROM $TABLE_NONCES WHERE $COL_NONCE = ?"
        val cursor = db.rawQuery(checkQuery, arrayOf(nonce))
        val exists = cursor.moveToFirst()
        cursor.close()
        if (exists) return false

        val values = ContentValues().apply {
            put(COL_NONCE, nonce)
            put(COL_ACTOR, actorId)
            put(COL_CREATED_AT, System.currentTimeMillis())
        }
        val result = db.insert(TABLE_NONCES, null, values)
        return result != -1L
    }
}
