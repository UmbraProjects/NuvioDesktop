package com.nuvio.app.features.music

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * Integrated loudness (ITU-R BS.1770-4 / EBU R128) of PCM that arrives in pieces: K-weighting, 400 ms blocks every
 * 100 ms, the absolute gate at -70 LUFS and the relative gate 10 LU below the gated mean. Checked against ffmpeg's
 * `ebur128` on test signals (within 1 LU at 24 kHz; the remainder is energy above 12 kHz that the decode leaves out).
 *
 * Samples are interleaved float32 with [channels] channels, every channel weighted 1.0 (stereo; no surround).
 */
internal class LoudnessMeter(private val sampleRate: Int, private val channels: Int) {
    private class Biquad(
        val b0: Double, val b1: Double, val b2: Double,
        val a1: Double, val a2: Double,
    ) {
        private var z1 = 0.0
        private var z2 = 0.0

        fun process(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
    }

    private val shelf = List(channels) { highShelf(sampleRate) }
    private val highPass = List(channels) { highPass(sampleRate) }

    /** Frames in one 100 ms step. */
    private val hop = (sampleRate / 10).coerceAtLeast(1)

    private val steps = ArrayList<Double>()
    private var stepSum = 0.0
    private var stepFrames = 0

    /** Seconds of audio taken in so far. */
    var seconds: Double = 0.0
        private set

    fun add(samples: FloatArray) {
        val frames = samples.size / channels
        for (frame in 0 until frames) {
            var energy = 0.0
            for (channel in 0 until channels) {
                val x = samples[frame * channels + channel].toDouble()
                val y = highPass[channel].process(shelf[channel].process(x))
                energy += y * y
            }
            stepSum += energy
            stepFrames++
            if (stepFrames == hop) {
                steps += stepSum
                stepSum = 0.0
                stepFrames = 0
            }
        }
        seconds += frames.toDouble() / sampleRate
    }

    /** The gated integrated loudness in LUFS, or null when nothing above the absolute gate has been heard. */
    fun integratedLufs(): Double? {
        if (steps.size < 4) return null
        val blockFrames = 4.0 * hop
        val blocks = DoubleArray(steps.size - 3) { i ->
            (steps[i] + steps[i + 1] + steps[i + 2] + steps[i + 3]) / blockFrames
        }
        val aboveAbsolute = blocks.filter { lufsOf(it) > ABSOLUTE_GATE_LUFS }
        if (aboveAbsolute.isEmpty()) return null
        val relativeGate = lufsOf(aboveAbsolute.average()) - 10.0
        val gated = aboveAbsolute.filter { lufsOf(it) > relativeGate }
        if (gated.isEmpty()) return null
        return lufsOf(gated.average())
    }

    private fun lufsOf(meanSquare: Double): Double =
        if (meanSquare <= 0.0) Double.NEGATIVE_INFINITY else -0.691 + 10.0 * log10(meanSquare)

    private companion object {
        const val ABSOLUTE_GATE_LUFS = -70.0

        /** BS.1770 stage 1: the head-related high shelf. */
        private fun highShelf(sampleRate: Int): Biquad {
            val f0 = 1681.974450955533
            val gainDb = 3.999843853973347
            val q = 0.7071752369554196
            val k = tan(PI * f0 / sampleRate)
            val vh = 10.0.pow(gainDb / 20.0)
            val vb = vh.pow(0.4996667741545416)
            val a0 = 1.0 + k / q + k * k
            return Biquad(
                b0 = (vh + vb * k / q + k * k) / a0,
                b1 = 2.0 * (k * k - vh) / a0,
                b2 = (vh - vb * k / q + k * k) / a0,
                a1 = 2.0 * (k * k - 1.0) / a0,
                a2 = (1.0 - k / q + k * k) / a0,
            )
        }

        /** BS.1770 stage 2: the revised low-frequency B-curve (a high-pass). */
        private fun highPass(sampleRate: Int): Biquad {
            val f0 = 38.13547087613982
            val q = 0.5003270373253953
            val k = tan(PI * f0 / sampleRate)
            val a0 = 1.0 + k / q + k * k
            return Biquad(
                b0 = 1.0, b1 = -2.0, b2 = 1.0,
                a1 = 2.0 * (k * k - 1.0) / a0,
                a2 = (1.0 - k / q + k * k) / a0,
            )
        }
    }
}

/**
 * Added to what the meter reads from the 24 kHz decode, which leaves out the energy above 12 kHz. Small: real music has
 * little there (pink noise, which has a lot, read 0.85 LU low).
 */
internal const val MEASURED_LOUDNESS_COMPENSATION_LU = 0.5

/** A track's loudness as measured, with when. */
internal data class MeasuredLoudness(val lufs: Double, val atEpochMs: Long)

/** The text kept in the loudness cache for [measured]: `<lufs to 0.1>|<epoch ms>`. */
internal fun encodeMeasuredLoudness(measured: MeasuredLoudness): String {
    val tenths = kotlin.math.round(measured.lufs * 10).toLong()
    val sign = if (tenths < 0) "-" else ""
    val magnitude = kotlin.math.abs(tenths)
    return "$sign${magnitude / 10}.${magnitude % 10}|${measured.atEpochMs}"
}

/** The inverse of [encodeMeasuredLoudness]; null for text that is missing, malformed or not a believable loudness. */
internal fun decodeMeasuredLoudness(text: String?): MeasuredLoudness? {
    if (text == null) return null
    val parts = text.split('|')
    if (parts.size != 2) return null
    val lufs = parts[0].toDoubleOrNull() ?: return null
    val at = parts[1].toLongOrNull() ?: return null
    if (!lufs.isFinite() || lufs !in -70.0..0.0) return null
    return MeasuredLoudness(lufs, at)
}

/** The keys to drop so no more than [capacity] remain: the oldest first. [ages] maps each key to when it was measured. */
internal fun loudnessKeysToEvict(ages: Map<String, Long>, capacity: Int): List<String> {
    if (ages.size <= capacity) return emptyList()
    return ages.entries.sortedBy { it.value }.take(ages.size - capacity).map { it.key }
}
