package net.wastu.numi.network

import android.content.Context
import net.wastu.numi.device.DeviceStatusProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object IncomingSmsTelegramMessage {
    private const val TELEGRAM_TEXT_LIMIT = 4096
    private const val PART_LABEL_RESERVE = 64
    private const val SENDER_METADATA_LIMIT = 256

    /** Formats the complete SMS as one Telegram message or a sequence of numbered parts. */
    fun format(
        context: Context,
        sender: String,
        body: String,
        receivedAt: Long,
        subscriptionId: Int?,
        state: String? = null
    ): List<String> {
        val simTag = subscriptionId?.let { id ->
            runCatching { DeviceStatusProvider.activeSims(context).firstOrNull { it.subscriptionId == id } }
                .getOrNull()
                ?.let { "SIM ${it.slotIndex + 1}" }
        }
        val timeLabel = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(receivedAt))
        val safeSender = sender.takeCodePoints(SENDER_METADATA_LIMIT)
        val visibleFooter = buildString {
            append(safeSender)
            if (simTag != null) append(", $simTag")
            append(", $timeLabel")
            if (state != null) append(", $state")
        }
        val footer = buildString {
            append(TelegramHtml.code(safeSender))
            if (simTag != null) append(", ${TelegramHtml.escape(simTag)}")
            append(", ${TelegramHtml.code(timeLabel)}")
            if (state != null) append(", ${TelegramHtml.escape(state)}")
        }
        val footerLength = visibleFooter.length + 2
        if (body.length <= 3000 && body.length + footerLength <= TELEGRAM_TEXT_LIMIT) {
            return listOf(renderPart(body, footer, partLabel = null))
        }

        // A fixed budget keeps status edits from shifting text between multipart messages.
        val bodyBudget = minOf(3000, TELEGRAM_TEXT_LIMIT - footerLength - PART_LABEL_RESERVE).coerceAtLeast(1)
        val chunks = splitPreservingText(body, bodyBudget)
        return chunks.mapIndexed { index, chunk ->
            renderPart(chunk, footer.takeIf { index == chunks.lastIndex }, "(${index + 1}/${chunks.size})")
        }
    }

    private fun renderPart(body: String, footer: String?, partLabel: String?): String = buildString {
        append(TelegramHtml.escape(body))
        if (partLabel != null) {
            if (body.isNotEmpty()) append("\n\n")
            append(partLabel)
        }
        if (footer != null) {
            if (body.isNotEmpty() || partLabel != null) append("\n\n")
            append(footer)
        }
    }

    private fun splitPreservingText(text: String, limit: Int): List<String> {
        if (text.isEmpty()) return listOf("")
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = start
            while (end < text.length) {
                val codePoint = text.codePointAt(end)
                val width = Character.charCount(codePoint)
                if (end > start && end - start + width > limit) break
                end += width
            }
            if (end < text.length) {
                val whitespaceOffset = text.substring(start, end).indexOfLast { it.isWhitespace() }
                if (whitespaceOffset > 0) end = start + whitespaceOffset + 1
            }
            chunks += text.substring(start, end)
            start = end
        }
        return chunks
    }

    private fun String.takeCodePoints(limit: Int): String {
        val count = codePointCount(0, length)
        return if (count <= limit) this else substring(0, offsetByCodePoints(0, limit))
    }
}
