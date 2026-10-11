package net.wastu.numi.spam

enum class SpamVerdict { SAFE, SPAM, UNKNOWN }
data class SpamDecision(val verdict: SpamVerdict, val reason: String, val source: String)

/** Protect essential notices before applying advertising and fraud screening. */
object SpamPolicy {
    private fun matches(pattern: String, text: String) = Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text)

    fun evaluate(body: String, trustedSender: Boolean = false): SpamDecision {
        if (trustedSender) return SpamDecision(SpamVerdict.SAFE, "Sender is in your contacts or allowed list", "trusted")
        if (matches("\\b(otp|verification code|kode (verifikasi|login|autentikasi)|one.time password)\\b", body) &&
            matches("\\b\\d{4,8}\\b", body) && !matches("\\b(kirim(kan)?|balas dengan|reply with|send us|share with us)\\b", body)) {
            return SpamDecision(SpamVerdict.SAFE, "Verification code", "rules")
        }
        if (matches("\\b(transaksi|pembayaran|pembelian|transfer|transaction|payment|purchase)\\b.{0,55}\\b(berhasil|sukses|diterima|successful|completed|received)\\b", body) ||
            matches("\\b(paket|kuota|subscription|plan)\\b.{0,65}\\b(berakhir|habis|telah aktif|sudah aktif|expired|expires|activated)\\b", body)) {
            return SpamDecision(SpamVerdict.SAFE, "Transaction or service notice", "rules")
        }
        if (matches("\\b(slot|gacor|judi|casino|jackpot)\\b", body) &&
            matches("\\b(deposit|bonus|jackpot|daftar|register|menang|spin)\\b", body)) {
            return SpamDecision(SpamVerdict.SPAM, "Gambling promotion", "rules")
        }
        if (matches("\\b(pinjaman|pinjol|loan)\\b", body) &&
            matches("\\b(cair cepat|langsung cair|tanpa jaminan|tanpa bi checking|instant approval|apply now)\\b", body)) {
            return SpamDecision(SpamVerdict.SPAM, "Unsolicited loan offer", "rules")
        }
        if (matches("\\b(menang|hadiah|you won|prize|reward)\\b", body) &&
            matches("\\b(kirim(kan)?|bayar|send|pay|reply with|balas dengan)\\b.{0,65}\\b(otp|pin|biaya admin|processing fee)\\b", body) &&
            !matches("\\b(jangan|never|do not|don't)\\b", body)) {
            return SpamDecision(SpamVerdict.SPAM, "Prize scam requesting a secret or fee", "rules")
        }
        val salesAction = matches("\\b(beli|belanja|buruan|nikmati|daftar|aktifkan|klaim|claim|buy|shop|subscribe|order now)\\b", body)
        val offer = matches("\\b(promo|diskon|cashback|discount|sale|special offer)\\b", body) ||
            (matches("\\b(paket|kuota|internet|pulsa|data)\\b", body) && matches("\\brp\\s*\\d|\\b\\d+\\s*(ribu|rb)\\b|\\b(hanya|murah)\\b", body))
        if (salesAction && offer) return SpamDecision(SpamVerdict.SPAM, "Bulk advertising or mobile plan offer", "rules")
        return SpamDecision(SpamVerdict.UNKNOWN, "Needs SMS classification", "rules")
    }
}
