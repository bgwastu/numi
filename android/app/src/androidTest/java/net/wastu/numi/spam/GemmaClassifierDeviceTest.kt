package net.wastu.numi.spam

import android.app.ActivityManager
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, isolated inference. No sends, provider writes or Telegram credentials. */
@RunWith(AndroidJUnit4::class)
class GemmaClassifierDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun requirePreview() {
        assumeTrue(context.packageName.endsWith(".preview") && InstrumentationRegistry.getArguments().getString("run_gemma") == "true")
    }

    @Test fun gemmaActuallyRunsInASeparateProcessWithinTheMemoryBudget() = runBlocking {
        val parent = Process.myPid()
        val started = System.currentTimeMillis()
        val result = GemmaSpamFilter.classify(context, "Nikmati paket internet 30GB mulai Rp50.000. Aktifkan sekarang.")
        context.filesDir.resolve("gemma-device-probe.json").writeText(JSONObject().put("verdict", result.verdict.name)
            .put("reason", result.reason).put("source", result.source).put("elapsed_ms", System.currentTimeMillis() - started).toString())
        assertEquals(result.reason, SpamVerdict.SPAM, result.verdict)
        assertEquals("gemma", result.source)
        assertTrue("Gemma cold check exceeded 15 seconds", System.currentTimeMillis() - started < 15_000)
        assertEquals(SpamVerdict.SAFE, GemmaSpamFilter.classify(context, "Besok ketemu jam tiga ya.").verdict)
        val child = context.getSystemService(ActivityManager::class.java).runningAppProcesses?.firstOrNull { it.processName == "${context.packageName}:spam" }
        assertNotNull("Gemma did not run in its worker", child)
        assertNotEquals(parent, child!!.pid)
        val prefs = context.getSharedPreferences("numi_settings", 0)
        assertTrue(prefs.getLong("gemma_peak_rss_kb", 0) in 1..512 * 1024)
        context.filesDir.resolve("gemma-device-metrics.json").writeText(JSONObject().put("peak_rss_kb", prefs.getLong("gemma_peak_rss_kb", 0))
            .put("cold_and_warm_checks_ms", System.currentTimeMillis() - started).put("separate_process", true).toString())
        GemmaSpamFilter.requestRetry(context)
    }

    @Test fun missingModelKeepsTheFilterEnabledAndReturnsUnknown() = runBlocking {
        GemmaSpamFilter.requestRetry(context); delay(300)
        val model = GemmaModel.file(context); val hidden = model.resolveSibling("${model.name}.test-hidden")
        assertTrue(model.renameTo(hidden))
        try {
            context.getSharedPreferences("numi_settings", 0).edit().putBoolean(GemmaSpamFilter.ENABLED, true).commit()
            assertEquals(SpamVerdict.UNKNOWN, GemmaSpamFilter.classify(context, "A message awaiting review.").verdict)
            assertTrue(GemmaSpamFilter.enabled(context))
            assertTrue(GemmaSpamFilter.status(context).contains("Download Gemma"))
        } finally { assertTrue(hidden.renameTo(model)); GemmaSpamFilter.requestRetry(context) }
    }

    @Test fun killedWorkerCannotKillTheInboxOrPretendTheMessageIsSafe() = runBlocking {
        GemmaSpamFilter.requestRetry(context); delay(300)
        val parent = Process.myPid(); val client = GemmaClassifierClient(context)
        try {
            val pending = async { client.classify("Ordinary message requiring a Gemma decision.") }
            var child = 0
            repeat(50) {
                if (child == 0) {
                    child = context.getSystemService(ActivityManager::class.java).runningAppProcesses
                        ?.firstOrNull { it.processName == "${context.packageName}:spam" }?.pid ?: 0
                    if (child == 0) delay(20)
                }
            }
            assertTrue(child > 0 && child != parent)
            Process.killProcess(child)
            assertEquals(SpamVerdict.UNKNOWN, pending.await().verdict)
            assertEquals(parent, Process.myPid())
        } finally { client.close() }
    }

    @Test fun originalSmsCanBeEvaluatedLocallyWithoutTrainingOrAlerts() = runBlocking {
        val corpus = context.filesDir.resolve("private-spam-corpus.json")
        assumeTrue(corpus.isFile)
        GemmaSpamFilter.requestRetry(context); delay(300)
        val rows = JSONArray(corpus.readText()); val results = JSONArray()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index); val body = row.getString("body")
            val local = SpamPolicy.evaluate(body)
            val result = if (local.verdict == SpamVerdict.UNKNOWN) GemmaSpamFilter.classify(context, body) else local
            assertNotEquals("A message was left without a decision", SpamVerdict.UNKNOWN, result.verdict)
            results.put(JSONObject().put("id", row.getLong("id")).put("verdict", result.verdict.name).put("source", result.source))
        }
        context.filesDir.resolve("gemma-private-evaluation.json").writeText(JSONObject().put("cases", results).toString())
        GemmaSpamFilter.requestRetry(context)
    }
}
