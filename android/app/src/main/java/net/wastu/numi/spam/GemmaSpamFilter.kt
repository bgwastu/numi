package net.wastu.numi.spam

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

object GemmaSpamFilter {
    const val ENABLED = "LOCAL_SPAM_FILTER_ENABLED"
    const val HEAD_SHA256 = "bc163438b6f325163f97acf345993050ebada95e95fa380fa17774a2831e4c6f"
    private val mutex = Mutex()
    // The client retains only applicationContext, including for direct callers.
    @android.annotation.SuppressLint("StaticFieldLeak")
    @Volatile private var client: GemmaClassifierClient? = null

    fun enabled(context: Context) = context.getSharedPreferences("numi_settings", Context.MODE_PRIVATE).getBoolean(ENABLED, true)

    fun loadHead(context: Context): GemmaSpamHead {
        val bytes = context.assets.open("spam/gemma-spam-head-v1.bin").use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(digest == HEAD_SHA256) { "Gemma decision layer needs an app update" }
        return GemmaSpamHead.load(bytes.inputStream())
    }

    suspend fun classify(context: Context, body: String): SpamDecision = mutex.withLock {
        val active = client ?: GemmaClassifierClient(context.applicationContext).also { client = it }
        val result = active.classify(body)
        val prefs = context.getSharedPreferences("numi_settings", Context.MODE_PRIVATE)
        if (result.verdict == SpamVerdict.UNKNOWN) prefs.edit().putString("spam_classifier_error", result.reason).apply()
        else prefs.edit().remove("spam_classifier_error").putString("gemma_success_profile", GemmaModel.SHA256 + HEAD_SHA256)
            .putLong("gemma_peak_rss_kb", active.peakRssKb).apply()
        result
    }

    fun prepare(context: Context) {
        if (GemmaModel.finishDownload(context)) requestRetry(context)
        if (!GemmaModel.present(context)) return
        GemmaModel.verify(context)
        val prefs = context.getSharedPreferences("numi_settings", Context.MODE_PRIVATE)
        val profile = GemmaModel.SHA256 + HEAD_SHA256
        if (prefs.getString("spam_classifier_profile", "") != profile) {
            SpamStore(context).recheckClassifier()
            prefs.edit().putString("spam_classifier_profile", profile).remove("spam_classifier_error").apply()
        }
    }

    fun requestRetry(context: Context) {
        client?.close()
        context.getSharedPreferences("numi_settings", Context.MODE_PRIVATE).edit().putLong("spam_model_retry", System.currentTimeMillis())
            .remove("spam_classifier_error").remove("spam_screening_error").remove("spam_notification_error").apply()
    }

    fun status(context: Context): String {
        if (!enabled(context)) return "Automatic filtering is off."
        val prefs = context.getSharedPreferences("numi_settings", Context.MODE_PRIVATE)
        val error = prefs.getString("spam_screening_error", null) ?: prefs.getString("spam_classifier_error", null) ?: prefs.getString("spam_notification_error", null)
        if (error != null) return error
        GemmaModel.downloadStatus(context)?.let { return it }
        if (!GemmaModel.present(context)) return "Download Gemma (165 MB) to finish checking messages. Local protection rules are active."
        return if (prefs.getString("gemma_success_profile", "") == GemmaModel.SHA256 + HEAD_SHA256)
            "Gemma is ready. Messages are checked on this phone."
        else "Gemma is downloaded. Waiting for the first message check."
    }
}
