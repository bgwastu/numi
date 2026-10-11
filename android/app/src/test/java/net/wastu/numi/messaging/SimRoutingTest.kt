package net.wastu.numi.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SimRoutingTest {
    @Test fun repliesKeepTheirOriginalSim() { assertEquals(2, SimRouting.resolve(2, listOf(1, 2), 1)) }
    @Test fun unavailableRequestedSimNeverFallsBack() {
        assertThrows(IllegalArgumentException::class.java) { SimRouting.resolve(2, listOf(1), 1) }
    }
    @Test fun newMessagesUseAndroidDefault() { assertEquals(2, SimRouting.resolve(null, listOf(1, 2), 2)) }
    @Test fun aSingleSimCanBeUsedWithoutAndroidDefault() { assertEquals(1, SimRouting.resolve(null, listOf(1), -1)) }
    @Test fun ambiguousSimChoiceCannotSend() {
        assertThrows(IllegalStateException::class.java) { SimRouting.resolve(null, listOf(1, 2), -1) }
    }
    @Test fun noSimCannotSend() {
        assertThrows(IllegalArgumentException::class.java) { SimRouting.resolve(null, emptyList(), -1) }
    }
}
