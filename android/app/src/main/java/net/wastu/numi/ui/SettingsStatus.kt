package net.wastu.numi.ui

import android.content.Context
import net.wastu.numi.bus.TelegramOwnerConfig
import net.wastu.numi.spam.GemmaModel
import net.wastu.numi.spam.GemmaSpamFilter
import net.wastu.numi.spam.SpamStore

fun telegramStatus(context: Context): String {
    val prefs = context.getSharedPreferences("numi_settings", Context.MODE_PRIVATE)
    if (prefs.getString("BOT_TOKEN", "").isNullOrBlank() || TelegramOwnerConfig.normalize(prefs.getString("TELEGRAM_OWNER_ID", null)).isBlank()) return "Not connected"
    if (!prefs.getBoolean("TELEGRAM_ENABLED", true)) return "Paused"
    val last = prefs.getLong("telegram_last_poll_success", 0)
    return when {
        last == 0L -> "Connecting"
        !prefs.getString("telegram_last_poll_error", null).isNullOrBlank() -> "Connection issue"
        System.currentTimeMillis() - last < 120_000 -> "Connected"
        else -> "Reconnecting"
    }
}

data class FilterStatus(
    val enabled: Boolean,
    val present: Boolean,
    val pending: Int,
    val spam: Int,
    val issue: String?,
    val download: String?,
    val ready: Boolean
) {
    val downloading: Boolean get() = download != null && !download.contains("failed") && !download.contains("retry")
    val title: String get() = when {
        !enabled -> "Off"
        downloading -> if (download!!.startsWith("Verifying")) "Verifying" else "Downloading"
        issue != null || download != null -> "Needs attention"
        !present -> "Needs download"
        pending > 0 -> "Checking $pending messages"
        ready -> "Ready"
        else -> "Starting"
    }

    companion object {
        fun read(context: Context): FilterStatus {
            val prefs = context.getSharedPreferences("numi_settings", Context.MODE_PRIVATE)
            val store = SpamStore(context)
            return FilterStatus(
                GemmaSpamFilter.enabled(context), GemmaModel.present(context), store.pendingCount(), store.spam().size,
                prefs.getString("spam_screening_error", null) ?: prefs.getString("spam_classifier_error", null) ?: prefs.getString("spam_notification_error", null),
                GemmaModel.downloadStatus(context),
                prefs.getString("gemma_success_profile", "") == GemmaModel.SHA256 + GemmaSpamFilter.HEAD_SHA256
            )
        }
    }
}
