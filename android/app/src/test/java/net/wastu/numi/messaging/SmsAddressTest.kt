package net.wastu.numi.messaging

import net.wastu.numi.utils.SmsAddress
import org.junit.Assert.*
import org.junit.Test

class SmsAddressTest {
    @Test fun phoneFormattingDoesNotSplitConversations() {
        assertEquals(SmsAddress.normalize("+62 812-3456-7890"), SmsAddress.normalize("6281234567890"))
    }
    @Test fun namedSendersWithDigitsCannotCollideWithPhoneNumbers() {
        assertNotEquals(SmsAddress.normalize("BANK123"), SmsAddress.normalize("123"))
        assertEquals("BANK123", SmsAddress.normalize("Bank123"))
    }
    @Test fun unknownAddressCannotBecomeAReplyTopic() {
        assertNull(SmsAddress.normalize("Unknown"))
        assertNull(SmsAddress.normalize("  "))
    }
}
