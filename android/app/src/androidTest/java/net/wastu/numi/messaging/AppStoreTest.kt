package net.wastu.numi.messaging

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.wastu.numi.database.DbHelper
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Exercises persistence only: these tests never submit messages to SmsManager. */
@RunWith(AndroidJUnit4::class)
class AppStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = AppStore(context)
    private val prefix = "test:${UUID.randomUUID()}:"
    private fun sms(key: String = "sms") = store.queueSms("+19995550123", "Persistence test", 1, prefix + key)
    @Before fun requireIsolatedPackage() { assumeTrue(context.packageName.endsWith(".preview")) }

    @After fun cleanUp() {
        store.db.execSQL("DELETE FROM sms_parts WHERE outbox_id IN (SELECT id FROM message_outbox WHERE idempotency_key LIKE ?)", arrayOf("$prefix%"))
        store.db.delete("message_outbox", "idempotency_key LIKE ?", arrayOf("$prefix%"))
        store.db.delete("telegram_targets", "chat_id LIKE ?", arrayOf("$prefix%"))
    }

    @Test fun duplicateUpdatesEnqueueOnce() {
        val id = sms()
        assertEquals(id, sms())
    }
    @Test fun botAndOwnerScopesCannotShareReplyTargets() {
        store.saveTarget(prefix + "bot1:123", 1, "+19995550123", 1)
        store.saveTarget(prefix + "bot2:123", 1, "+19995550124", 2)
        assertEquals("+19995550123", store.target(prefix + "bot1:123", 1)!!.address)
        assertEquals("+19995550124", store.target(prefix + "bot2:123", 1)!!.address)
        assertNull(store.target(prefix + "bot1:456", 1))
    }
    @Test fun aMessageCanOnlyBeClaimedOnce() {
        val id = sms()
        assertTrue(store.start(id, 2))
        assertFalse(store.start(id, 2))
    }
    @Test fun duplicateAndOutOfOrderCallbacksDoNotPrematurelyReportSent() {
        val id = sms()
        val generation = store.beginNativeSend(id, 3)
        assertEquals("SENDING", store.partResult(id, 2, "sent", -1, generation)!!.state)
        assertEquals("SENDING", store.partResult(id, 2, "sent", -1, generation)!!.state)
        assertEquals("SENDING", store.partResult(id, 0, "sent", -1, generation)!!.state)
        assertEquals("SENT", store.partResult(id, 1, "sent", -1, generation)!!.state)
    }
    @Test fun aFailedPartCannotBeOverwrittenByLaterSuccess() {
        val id = sms()
        val generation = store.beginNativeSend(id, 2)
        assertEquals("FAILED", store.partResult(id, 1, "sent", 1, generation)!!.state)
        assertEquals("FAILED", store.partResult(id, 0, "sent", -1, generation)!!.state)
        assertFalse(store.due(System.currentTimeMillis(), "SMS").any { it.id == id })
    }
    @Test fun callbacksFromPreviousSendAttemptsAreIgnored() {
        val id = sms()
        val old = store.beginNativeSend(id, 1)
        store.fail(id, "Retry test")
        val current = store.beginNativeSend(id, 1)
        assertNotEquals(old, current)
        assertNull(store.partResult(id, 0, "sent", -1, old))
        assertEquals("SENDING", store.message(id)!!.state)
        assertEquals("SENT", store.partResult(id, 0, "sent", -1, current)!!.state)
    }
    @Test fun deliveryWaitsForEveryMultipartReport() {
        val id = sms()
        val generation = store.beginNativeSend(id, 2)
        store.partResult(id, 0, "sent", -1, generation)
        store.partResult(id, 1, "sent", -1, generation)
        assertFalse(store.partResult(id, 1, "delivered", -1, generation)!!.delivered)
        assertTrue(store.partResult(id, 0, "delivered", -1, generation)!!.delivered)
    }
    @Test fun confirmedDeliveryResolvesAnUncertainSend() {
        val id = sms()
        val generation = store.beginNativeSend(id, 1)
        store.fail(id, "Send callback did not arrive")
        val confirmed = store.partResult(id, 0, "delivered", -1, generation)!!
        assertEquals("SENT", confirmed.state)
        assertTrue(confirmed.delivered)
        assertNull(confirmed.error)
    }
    @Test fun invalidPartCannotAffectSendingState() {
        val id = sms()
        val generation = store.beginNativeSend(id, 1)
        assertNull(store.partResult(id, 5, "sent", -1, generation))
        assertEquals("SENDING", store.message(id)!!.state)
    }
    @Test fun telegramEventsRetainTheirPayloadAcrossDatabaseReads() {
        val key = prefix + "event"
        val id = store.queueEvent("+19995550123", "Incoming", 1, key, JSONObject().put("kind", "sms"))
        assertTrue(id > 0)
        assertEquals("sms", JSONObject(store.message(id)!!.event!!).getString("kind"))
        assertEquals(-1L, store.queueEvent("+19995550123", "Incoming", 1, key, JSONObject().put("kind", "sms")))
    }
    @Test fun v5UpgradePreservesExistingPrivateHistory() {
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE message_outbox (id INTEGER PRIMARY KEY,message_type TEXT,status TEXT,next_attempt_at INTEGER,body TEXT)")
            db.execSQL("CREATE TABLE sms_inbox (id INTEGER PRIMARY KEY,body TEXT)")
            db.insert("message_outbox", null, ContentValues().apply { put("id", 42); put("message_type", "SMS"); put("status", "SENT"); put("body", "Original outgoing history") })
            db.insert("sms_inbox", null, ContentValues().apply { put("id", 12); put("body", "Original incoming history") })
            DbHelper(context).onUpgrade(db, 5, 8)
            db.rawQuery("SELECT body,provider_id FROM sms_inbox WHERE id=12", null).use {
                assertTrue(it.moveToFirst()); assertEquals("Original incoming history", it.getString(0)); assertTrue(it.isNull(1))
            }
            db.rawQuery("SELECT body,part_count FROM message_outbox WHERE id=42", null).use {
                assertTrue(it.moveToFirst()); assertEquals("Original outgoing history", it.getString(0)); assertEquals(0, it.getInt(1))
            }
            assertNotEquals(-1L, db.insert("telegram_receipts", null, ContentValues().apply { put("chat_id", "123"); put("event_key", "new"); put("message_id", 1) }))
        }
    }
}
