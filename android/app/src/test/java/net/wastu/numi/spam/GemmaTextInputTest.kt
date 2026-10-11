package net.wastu.numi.spam

import org.junit.Assert.*
import org.junit.Test

class GemmaTextInputTest {
    @Test fun fullMessagesAreSplitWithinTheEncoderBudget() {
        val body = "Congratulations on your delivery. ".repeat(30) + "Final segment still matters."
        val parts = GemmaTextInput.chunks(body)
        assertTrue(parts.size > 1)
        assertTrue(parts.last().endsWith("Final segment still matters."))
        assertEquals(body.trim(), parts.joinToString(" ") { it.removePrefix(GemmaTextInput.PREFIX) })
        assertTrue(parts.all { it.removePrefix(GemmaTextInput.PREFIX).toByteArray(Charsets.UTF_8).size <= GemmaTextInput.CHUNK_BYTES })
    }

    @Test fun unicodeAndEmojiDoNotOverflowOrSplitSurrogatePairs() {
        val body = "🚀你好é".repeat(90)
        val parts = GemmaTextInput.chunks(body)
        assertEquals(body, parts.joinToString("") { it.removePrefix(GemmaTextInput.PREFIX) })
        for (part in parts) {
            val content = part.removePrefix(GemmaTextInput.PREFIX)
            assertTrue(content.toByteArray(Charsets.UTF_8).size <= GemmaTextInput.CHUNK_BYTES)
            assertFalse(Character.isLowSurrogate(content.first()))
            assertFalse(Character.isHighSurrogate(content.last()))
        }
    }

    @Test fun emptyLongAndInvalidVectorsAreHandledExplicitly() {
        assertEquals(listOf(GemmaTextInput.PREFIX), GemmaTextInput.chunks(" \n "))
        assertEquals(GemmaTextInput.MAX_CODE_POINTS, GemmaTextInput.chunks("x".repeat(4000)).sumOf { it.removePrefix(GemmaTextInput.PREFIX).length })
        val vector = FloatArray(768).apply { this[0] = 1f }
        assertArrayEquals(vector, GemmaTextInput.pool(listOf(vector, vector)), 0f)
        for (invalid in listOf(emptyList(), listOf(FloatArray(1)), listOf(FloatArray(768)), listOf(vector.copyOf().apply { this[0] = Float.NaN }))) {
            try { GemmaTextInput.pool(invalid); fail("Invalid vector was accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
