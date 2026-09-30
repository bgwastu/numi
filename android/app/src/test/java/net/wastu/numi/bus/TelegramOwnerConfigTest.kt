package net.wastu.numi.bus

import org.junit.Assert.assertEquals
import org.junit.Test

class TelegramOwnerConfigTest {
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
