package net.wastu.numi.bus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class TelegramOwnerConfigTest {
    @Test fun controlsRequireTheOwnerInTheirPrivateChat() {
        assertTrue(TelegramOwnerConfig.isAuthorized("123", "123", "123", "private"))
        assertFalse(TelegramOwnerConfig.isAuthorized("123", "456", "123", "private"))
        assertFalse(TelegramOwnerConfig.isAuthorized("123", "123", "-123", "supergroup"))
        assertFalse(TelegramOwnerConfig.isAuthorized("", "", "", "private"))
    }
    @Test
    fun acceptsOneNumericOwnerId() {
        assertEquals("123456789", TelegramOwnerConfig.normalize(" 123456789 "))
    }

    @Test
    fun rejectsCommaSeparatedIds() {
        assertEquals("", TelegramOwnerConfig.normalize("123456789,987654321"))
    }

    @Test
    fun rejectsMissingOrNonNumericIds() {
        assertEquals("", TelegramOwnerConfig.normalize(null))
        assertEquals("", TelegramOwnerConfig.normalize("owner"))
        assertEquals("", TelegramOwnerConfig.normalize(""))
    }
}
