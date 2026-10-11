package net.wastu.numi.messaging

/** A requested SIM must never silently fall back to a different subscription. */
object SimRouting {
    fun resolve(requested: Int?, active: List<Int>, androidDefault: Int): Int {
        if (requested != null && requested >= 0) {
            require(requested in active) { "The selected SIM is unavailable. Choose an active SIM and try again." }
            return requested
        }
        if (androidDefault in active) return androidDefault
        if (active.size == 1) return active.single()
        require(active.isNotEmpty()) { "No active SIM is available." }
        error("Choose a default SMS SIM in Android settings or use /sim in Telegram.")
    }
}
