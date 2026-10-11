package net.wastu.numi.messaging

import org.junit.Assert.*
import org.junit.Test

class SendPolicyTest {
    @Test fun multipartWaitsForEveryResult() {
        assertEquals(SendState.SENDING, SendPolicy.aggregate(3, listOf(-1, -1)))
        assertEquals(SendState.SENT, SendPolicy.aggregate(3, listOf(-1, -1, -1)))
    }
    @Test fun oneFailedPartFailsTheWholeMessage() {
        assertEquals(SendState.FAILED, SendPolicy.aggregate(3, listOf(-1, 1)))
    }
    @Test fun absentPartCountCannotBecomeSent() {
        assertEquals(SendState.SENDING, SendPolicy.aggregate(0, emptyList()))
    }
    @Test fun oldCommandsAndImplausibleFutureDatesExpire() {
        val now = 2_000_000L
        assertTrue(SendPolicy.isFresh(now - 60_000, now))
        assertFalse(SendPolicy.isFresh(now - SendPolicy.COMMAND_TTL_MS - 1, now))
        assertFalse(SendPolicy.isFresh(now + 60_001, now))
        assertFalse(SendPolicy.isFresh(0, now))
    }
    @Test fun namedSendersAreReceiveOnly() {
        assertTrue(SendPolicy.canReply("+62 812-3456-7890"))
        assertTrue(SendPolicy.canReply("3636"))
        assertFalse(SendPolicy.canReply("TELKOMSEL"))
        assertFalse(SendPolicy.canReply("Name123"))
        assertFalse(SendPolicy.canReply("+1"))
    }
}
