package net.wastu.numi.messaging

enum class SendState { SENDING, SENT, FAILED }

object SendPolicy {
    const val SUCCESS = -1
    const val COMMAND_TTL_MS = 10 * 60 * 1000L
    const val SEND_TIMEOUT_MS = 2 * 60 * 1000L

    fun aggregate(expectedParts: Int, results: List<Int>): SendState = when {
        results.any { it != SUCCESS } -> SendState.FAILED
        expectedParts > 0 && results.size == expectedParts -> SendState.SENT
        else -> SendState.SENDING
    }

    fun isFresh(createdAt: Long, now: Long): Boolean =
        createdAt > 0 && createdAt <= now + 60_000 && now - createdAt <= COMMAND_TTL_MS

    fun canReply(address: String): Boolean =
        address.trim().matches(Regex("\\+?[0-9][0-9 ()-]{1,24}[0-9]")) &&
            address.count(Char::isDigit) in 3..15
}
