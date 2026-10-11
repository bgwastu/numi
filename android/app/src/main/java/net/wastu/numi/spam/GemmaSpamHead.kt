package net.wastu.numi.spam

import java.io.DataInputStream
import java.io.InputStream
import kotlin.math.exp

/** A small decision layer; its inputs must come from the pinned Gemma encoder. */
class GemmaSpamHead private constructor(private val weights: DoubleArray, private val bias: Double, val threshold: Double) {
    fun probability(embedding: FloatArray): Double {
        require(embedding.size == weights.size && embedding.all { it.isFinite() }) { "Invalid Gemma vector" }
        val norm = embedding.sumOf { it.toDouble() * it }
        require(norm in 0.99..1.01) { "Gemma vector is not normalized" }
        var score = bias
        for (index in weights.indices) score += embedding[index] * weights[index]
        return 1.0 / (1.0 + exp(-score.coerceIn(-40.0, 40.0)))
    }

    fun classify(embedding: FloatArray): SpamDecision = if (probability(embedding) >= threshold)
        SpamDecision(SpamVerdict.SPAM, "Gemma detected advertising or a suspicious message", "gemma")
    else SpamDecision(SpamVerdict.SAFE, "Checked by Gemma on this phone", "gemma")

    companion object {
        fun load(input: InputStream): GemmaSpamHead = DataInputStream(input).use { stream ->
            val magic = ByteArray(8); stream.readFully(magic)
            require(magic.contentEquals("NUMIGEM1".toByteArray(Charsets.US_ASCII))) { "Invalid Gemma decision layer" }
            val dimension = stream.readInt(); require(dimension == 768) { "Wrong Gemma dimension" }
            val bias = stream.readDouble(); val threshold = stream.readDouble()
            require(bias.isFinite() && threshold.isFinite() && threshold > 0 && threshold < 1)
            val weights = DoubleArray(dimension) { stream.readDouble().also { require(it.isFinite()) } }
            require(stream.read() == -1) { "Unexpected Gemma decision layer data" }
            GemmaSpamHead(weights, bias, threshold)
        }
    }
}
