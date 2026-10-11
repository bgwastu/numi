package net.wastu.numi.spam

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.wastu.numi.database.DbHelper
import net.wastu.numi.messaging.AppStore
import org.junit.After
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpamStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = SpamStore(context)
    private val id = -9_999L
    private val address = "+19995550199"
    private fun register(live: Boolean = true) = store.register(id, address, "PROMO! Buy now", 1000, 800, 1, live)
    @Before fun requireIsolatedPackage() { assumeTrue(context.packageName.endsWith(".preview")) }
    @After fun cleanup() {
        if (!context.packageName.endsWith(".preview")) return
        val db = AppStore(context).db
        db.delete("sms_filter", "provider_id<0", null)
        db.delete("sms_allowed_senders", "address_key=?", arrayOf(address))
    }
    @Test fun quarantinedMessagesRetainTheirFullContents() {
        register()
        store.decide(id, SpamDecision(SpamVerdict.SPAM, "Promotion", "rules"))
        store.finish(id)
        assertEquals("PROMO! Buy now", store.spam().first { it.id == id }.body)
        assertTrue(id in store.hiddenIds())
        assertFalse(store.work().any { it.id == id })
    }
    @Test fun duplicateReceiptsCannotResetAUserDecision() {
        register(); store.restore(id)
        register()
        assertEquals("SAFE", store.message(id)!!.state)
        assertEquals("Restored by you", store.message(id)!!.reason)
    }
    @Test fun aLateClassifierCannotUndoRestoration() {
        register(); store.restore(id)
        assertFalse(store.decide(id, SpamDecision(SpamVerdict.SPAM, "Late classifier verdict", "classifier")))
        assertFalse(id in store.hiddenIds())
    }
    @Test fun allowedSendersPersistAcrossStoreInstances() {
        register(); store.restore(id, true)
        assertTrue(SpamStore(context).allowed(address))
    }
    @Test fun unavailableModelKeepsTheSmsAndCanBeRechecked() {
        register()
        assertFalse(store.decide(id, SpamDecision(SpamVerdict.UNKNOWN, "Model missing", "unavailable")))
        store.finish(id)
        assertEquals("PENDING", store.message(id)!!.state)
        assertFalse(store.message(id)!!.effectsDone)
        assertFalse(store.ready().any { it.id == id })
        store.retryUnavailable()
        assertEquals("PENDING", store.message(id)!!.state)
        assertTrue(store.message(id)!!.live)
    }
    @Test fun recheckingCannotUndoAnExplicitUserRestore() {
        register(); store.decide(id, SpamDecision(SpamVerdict.UNKNOWN, "Model missing", "unavailable"))
        store.restore(id); store.retryUnavailable()
        assertEquals("SAFE", store.message(id)!!.state)
        assertTrue(store.message(id)!!.effectsDone)
    }
    @Test fun liveMessagesTakePriorityOverHistoricalReview() {
        store.register(id - 1, address, "Old message", 1, 800, 1, false)
        register()
        assertEquals(id, store.work().first { it.id < 0 }.id)
    }
    @Test fun v7UpgradeKeepsExistingSmsAndCreatesEmptyFilteringState() {
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE sms_inbox (id INTEGER PRIMARY KEY,body TEXT)")
            db.insert("sms_inbox", null, ContentValues().apply { put("id", 12); put("body", "Original incoming SMS") })
            DbHelper(context).onUpgrade(db, 7, 8)
            db.rawQuery("SELECT body FROM sms_inbox WHERE id=12", null).use { assertTrue(it.moveToFirst()); assertEquals("Original incoming SMS", it.getString(0)) }
            db.rawQuery("SELECT count(*) FROM sms_filter", null).use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        }
    }
    @Test fun readyReceiptsAreNotStarvedByHistoricalInference() {
        repeat(45) { store.register(id - it - 1, address, "History $it", it.toLong(), 800, 1, false) }
        register(false)
        store.decide(id, SpamDecision(SpamVerdict.SAFE, "OTP", "rules"))
        assertTrue(store.ready().any { it.id == id })
        assertEquals(45, store.pendingCount())
    }
    @Test fun aReusedProviderIdCannotHideANewMessage() {
        register(); store.decide(id, SpamDecision(SpamVerdict.SPAM, "Promotion", "rules")); store.finish(id)
        store.register(id, address, "Your verification code is 123456", 2000, 800, 1, true)
        assertEquals("PENDING", store.message(id)!!.state)
        assertFalse(id in store.hiddenIds())
    }
    @Test fun deletedProviderMessagesAreRemovedFromTheSpamFolder() {
        register(); store.decide(id, SpamDecision(SpamVerdict.SPAM, "Promotion", "rules"))
        store.prune(emptySet())
        assertNull(store.message(id))
    }
    @Test fun newBlockingRulesApplyToPreviouslySafeMessages() {
        register(); store.decide(id, SpamDecision(SpamVerdict.SAFE, "No spam signals", "classifier")); store.finish(id)
        store.register(id, address, "PROMO! Buy now", 1000, 800, 1, false, "Blocked sender")
        assertEquals("SPAM", store.message(id)!!.state)
        store.register(id, address, "PROMO! Buy now", 1000, 800, 1, false)
        assertEquals("PENDING", store.message(id)!!.state)
        assertFalse(store.message(id)!!.live)
    }
    @Test fun aRecheckPreservesUserRestoresAndDoesNotAlertForHistory() {
        register(); store.restore(id)
        store.register(id - 1, address, "Another message", 2000, 800, 1, true)
        store.decide(id - 1, SpamDecision(SpamVerdict.SPAM, "Promotion", "classifier"))
        store.finish(id - 1)
        store.register(id - 2, address, "New message awaiting screening", 3000, 800, 1, true)
        store.recheck()
        assertEquals("SAFE", store.message(id)!!.state)
        assertEquals("PENDING", store.message(id - 1)!!.state)
        assertFalse(store.message(id - 1)!!.live)
        assertTrue(store.message(id - 2)!!.live)
        store.register(id, address, "PROMO! Buy now", 1000, 800, 1, false, "Blocked sender")
        assertEquals("SAFE", store.message(id)!!.state)
    }
}
