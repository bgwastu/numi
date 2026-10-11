package net.wastu.numi.spam

import org.junit.Assert.assertEquals
import org.junit.Test

class SpamPolicyTest {
    @Test fun indonesianCarrierPromotionsAreSpam() {
        listOf("PROMO kuota 10GB hanya Rp15.000! Beli sekarang di *363#.",
            "Nikmati paket internet 30GB mulai Rp50.000. Aktifkan sekarang.",
            "Beli kuota murah! Promo khusus pelanggan hari ini.").forEach { assertEquals(SpamVerdict.SPAM, SpamPolicy.evaluate(it).verdict) }
    }
    @Test fun issuedOtpsRemainSafeEvenWithAProductOffer() {
        assertEquals(SpamVerdict.SAFE, SpamPolicy.evaluate("Kode verifikasi 123456 untuk membeli paket. Jangan bagikan kode ini.").verdict)
        assertEquals(SpamVerdict.SAFE, SpamPolicy.evaluate("Your OTP is 123456. Never share it.").verdict)
    }
    @Test fun completedTransactionsAndPlanNoticesRemainSafe() {
        listOf("Pembelian paket internet Rp50.000 berhasil. Kuota Anda sudah aktif.",
            "Transfer Rp200.000 berhasil. Saldo Rp500.000.", "Kuota internet Anda akan berakhir besok.").forEach {
            assertEquals(SpamVerdict.SAFE, SpamPolicy.evaluate(it).verdict)
        }
    }
    @Test fun fraudAskingForAnOtpIsNotAutoAllowed() {
        assertEquals(SpamVerdict.SPAM, SpamPolicy.evaluate("Verification code 123456. Reply with your OTP to claim a prize.").verdict)
        assertEquals(SpamVerdict.SPAM, SpamPolicy.evaluate("Kode verifikasi 123456. Kirim OTP ke kami untuk hadiah.").verdict)
    }
    @Test fun gamblingAndLoanOffersAreSpam() {
        assertEquals(SpamVerdict.SPAM, SpamPolicy.evaluate("SLOT GACOR! Bonus deposit 100%, daftar sekarang.").verdict)
        assertEquals(SpamVerdict.SPAM, SpamPolicy.evaluate("Pinjaman langsung cair tanpa jaminan. Daftar sekarang.").verdict)
    }
    @Test fun knownSendersAreProtectedFromAutomaticRules() {
        assertEquals(SpamVerdict.SAFE, SpamPolicy.evaluate("Beli kuota murah! PROMO khusus hari ini.", trustedSender = true).verdict)
    }
    @Test fun ambiguousPersonalMessagesNeedClassification() {
        assertEquals(SpamVerdict.UNKNOWN, SpamPolicy.evaluate("Besok ketemu jam tiga ya.").verdict)
    }
    @Test fun prizeScamsAreSpamButWarningsAreNot() {
        listOf("Selamat Anda menang Rp100 juta! Kirim OTP dan bayar biaya admin untuk mengambil hadiah.",
            "You won a million dollars. Send your bank PIN and a processing fee to claim it.").forEach {
            assertEquals(SpamVerdict.SPAM, SpamPolicy.evaluate(it).verdict)
        }
        assertEquals(SpamVerdict.UNKNOWN, SpamPolicy.evaluate("Jangan kirim OTP kepada orang yang mengaku Anda menang hadiah.").verdict)
    }
}
