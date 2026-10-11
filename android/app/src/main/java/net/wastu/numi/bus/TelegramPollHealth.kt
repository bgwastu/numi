package net.wastu.numi.bus

import android.content.Context
import net.wastu.numi.utils.RecentActivity

object TelegramPollHealth {
    private const val PREFS = "numi_settings"
    private const val KEY_LAST_SUCCESS = "telegram_last_poll_success"
    private const val KEY_LAST_ERROR = "telegram_last_poll_error"

    fun recordSuccess(context: Context, at: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_SUCCESS, at)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun recordError(context: Context, error: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_ERROR, error.take(100))
            .apply()
    }

    fun summary(context: Context, configured: Boolean): String {
        if (!configured) return "Telegram: not configured"
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastSuccess = prefs.getLong(KEY_LAST_SUCCESS, 0L)
        val lastError = prefs.getString(KEY_LAST_ERROR, null)
        val now = System.currentTimeMillis()
        return when {
            lastSuccess == 0L -> "Telegram: connecting"
            lastError.isNullOrBlank() && now - lastSuccess < HEALTHY_WINDOW_MS ->
                "Telegram: polling, checked ${RecentActivity.relativeTime(lastSuccess)}"
            !lastError.isNullOrBlank() -> buildString {
                append("Telegram: issue, ")
                append(lastError)
                if (lastSuccess > 0L) {
                    append(", last connected ")
                    append(RecentActivity.relativeTime(lastSuccess))
                }
            }
            else -> "Telegram: reconnecting, last connected ${RecentActivity.relativeTime(lastSuccess)}"
        }
    }

    private const val HEALTHY_WINDOW_MS = 2 * 60 * 1000L
}
