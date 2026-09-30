package net.wastu.numi.utils

import java.util.Locale

/** Shared key used to group inbound senders with outgoing SMS recipients. */
object SmsAddress {
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.equals("Unknown", ignoreCase = true)) return null
        val digits = trimmed.filter { it.isDigit() }
        return if (digits.length >= 3) digits else trimmed.uppercase(Locale.US)
    }
}
