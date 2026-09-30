package net.wastu.numi.utils

import android.content.Context

data class RecentActivityRecord(
    val label: String,
    val timestamp: Long
)

object RecentActivity {
    private const val PREFS_NAME = "numi_settings"
    private const val KEY_LABEL = "LAST_ACTIVITY_LABEL"
    private const val KEY_TIMESTAMP = "LAST_ACTIVITY_TIMESTAMP"

    fun record(context: Context, label: String, timestamp: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LABEL, label.take(80))
            .putLong(KEY_TIMESTAMP, timestamp)
            .apply()
    }

    fun read(context: Context): RecentActivityRecord? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val label = prefs.getString(KEY_LABEL, null)?.takeIf(String::isNotBlank) ?: return null
        val timestamp = prefs.getLong(KEY_TIMESTAMP, 0L).takeIf { it > 0L } ?: return null
        return RecentActivityRecord(label, timestamp)
    }

    fun relativeTime(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        val elapsedSeconds = ((now - timestamp).coerceAtLeast(0L) / 1_000L)
        return when {
            elapsedSeconds < 45 -> "just now"
            elapsedSeconds < 90 -> "1 minute ago"
            elapsedSeconds < 60 * 60 -> "${elapsedSeconds / 60} minutes ago"
            elapsedSeconds < 90 * 60 -> "1 hour ago"
            elapsedSeconds < 24 * 60 * 60 -> "${elapsedSeconds / (60 * 60)} hours ago"
            elapsedSeconds < 48 * 60 * 60 -> "yesterday"
            elapsedSeconds < 7 * 24 * 60 * 60 -> "${elapsedSeconds / (24 * 60 * 60)} days ago"
            elapsedSeconds < 14 * 24 * 60 * 60 -> "1 week ago"
            elapsedSeconds < 30 * 24 * 60 * 60 -> "${elapsedSeconds / (7 * 24 * 60 * 60)} weeks ago"
            elapsedSeconds < 60 * 24 * 60 * 60 -> "1 month ago"
            else -> "${elapsedSeconds / (30 * 24 * 60 * 60)} months ago"
        }
    }
}
