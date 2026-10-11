package net.wastu.numi.bus

/** Normalizes the single Telegram owner ID entered in Numi settings. */
object TelegramOwnerConfig {
    fun normalize(raw: String?): String = raw.orEmpty().trim()
        .takeIf { it.matches(Regex("[0-9]+")) }
        .orEmpty()

    fun isAuthorized(owner: String, actor: String, chat: String, type: String): Boolean =
        owner.isNotEmpty() && normalize(owner) == owner && type == "private" && actor == owner && chat == owner
}
