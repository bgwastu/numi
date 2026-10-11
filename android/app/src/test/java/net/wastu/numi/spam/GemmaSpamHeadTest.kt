package net.wastu.numi.spam

import org.junit.Assert.*
import org.junit.Test

class GemmaSpamHeadTest {
    private fun bytes() = javaClass.classLoader!!.getResourceAsStream("spam/gemma-spam-head-v1.bin")!!.use { it.readBytes() }

    @Test fun scoresMatchIndependentPythonGemmaEvaluation() {
        val head = GemmaSpamHead.load(bytes().inputStream())
        val cases = javaClass.classLoader!!.getResourceAsStream("gemma-head-parity.tsv")!!.bufferedReader().readLines()
        assertTrue(cases.size >= 25)
        for (case in cases) {
            val fields = case.split('\t')
            val vector = fields[2].split(',').map { it.toFloat() }.toFloatArray()
            assertEquals(fields[0], fields[1].toDouble(), head.probability(vector), 1e-10)
        }
    }

    @Test fun corruptOrUnnormalizedInputsCannotBecomeSafeDecisions() {
        for (data in listOf(ByteArray(8), bytes().copyOf(40), bytes() + byteArrayOf(1))) {
            try { GemmaSpamHead.load(data.inputStream()); fail("Corrupt head accepted") } catch (_: Exception) { }
        }
        val head = GemmaSpamHead.load(bytes().inputStream())
        for (vector in listOf(FloatArray(1), FloatArray(768), FloatArray(768) { Float.NaN })) {
            try { head.classify(vector); fail("Invalid vector accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
