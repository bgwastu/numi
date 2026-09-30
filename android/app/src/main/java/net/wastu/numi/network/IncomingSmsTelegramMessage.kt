package net.wastu.numi.network

import android.content.Context
import net.wastu.numi.device.DeviceStatusProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object IncomingSmsTelegramMessage {
    fun format(
        context: Context,
        sender: String,
        body: String,
        receivedAt: Long,
        subscriptionId: Int?
    ): String {
        val simTag = subscriptionId?.let { id ->
            runCatching { DeviceStatusProvider.activeSims(context).firstOrNull { it.subscriptionId == id } }
                .getOrNull()
                ?.let { "SIM ${it.slotIndex + 1}" }
        }
        val compactBody = body.replace(Regex("\\s+"), " ").trim()
        val excerpt = compactBody.takeCodePoints(140)
        val timeLabel = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(receivedAt))

        return buildString {
            append("📩 ${TelegramHtml.code(sender)}")
            if (simTag != null) append(" · ${TelegramHtml.escape(simTag)}")
            append(" · ${TelegramHtml.code(timeLabel)}\n")
            append(TelegramHtml.escape(excerpt))
            if (excerpt.codePointCount(0, excerpt.length) < compactBody.codePointCount(0, compactBody.length)) {
                append("…")
            }
        }
    }

    private fun String.takeCodePoints(limit: Int): String {
        if (codePointCount(0, length) <= limit) return this
        return substring(0, offsetByCodePoints(0, limit))
    }
}
