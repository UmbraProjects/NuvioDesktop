package com.nuvio.app.features.music.visualiser
// FORK UI (Spyder): Nick's Now Playing stage, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/* fork:visualiser - the plain-Kotlin parts of the Beat This! pipeline (BeatTracker.desktop.kt), here so they can be tested. */

/** Frames of spectrogram the Beat This! network takes at once, and how many at each edge of a chunk are thrown away. */
internal const val BEAT_NET_CHUNK = 1_500
internal const val BEAT_NET_BORDER = 6

/**
 * Where Beat This! starts each 1500-frame chunk of a [frames]-frame spectrogram: every 1488 frames from -6, the last one moved
 * back to end on the spectrogram's end. While the track is still being read ([final] false) the last one is not moved, so the
 * chunks before it stay the same chunks and their answers can be kept.
 */
internal fun chunkStarts(frames: Int, final: Boolean = true): List<Int> {
    val stride = BEAT_NET_CHUNK - 2 * BEAT_NET_BORDER
    val starts = ArrayList<Int>()
    var pos = -BEAT_NET_BORDER
    while (pos < frames - BEAT_NET_BORDER) { starts += pos; pos += stride }
    if (final && frames > stride && starts.isNotEmpty()) starts[starts.size - 1] = frames - (BEAT_NET_CHUNK - BEAT_NET_BORDER)
    return starts
}

/**
 * A streaming windowed-sinc resampler (64 taps, Hann-windowed, cut off at 95 % of the lower Nyquist). The rates' ratio is
 * reduced to whole numbers, so the tap weights for every output phase are worked out once (147 phases for 48 kHz to 22.05 kHz).
 */
internal class Resampler(private val from: Int, private val to: Int) {
    private val half = 32
    private val up: Int
    private val down: Int
    private val weights: Array<FloatArray>

    init {
        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
        val g = gcd(from, to)
        up = to / g
        down = from / g
        val ratio = to.toDouble() / from
        val fc = 0.95 * minOf(1.0, ratio) * 0.5
        weights = Array(up) { phase ->
            val frac = phase.toDouble() / up
            FloatArray(2 * half) { j ->
                val k = j - half + 1
                val d = frac - k
                val x = 2.0 * fc * d
                val sinc = if (abs(x) < 1e-12) 1.0 else sin(PI * x) / (PI * x)
                (2.0 * fc * sinc * (0.5 + 0.5 * cos(PI * d / half))).toFloat()
            }
        }
    }

    // Input samples not yet past every output that needs them; inStart is the absolute index of input[0].
    private var input = FloatArray(1 shl 16)
    private var inLen = 0
    private var inStart = 0L
    private var received = 0L
    /** The next output sample's index. */
    private var next = 0L

    fun push(samples: FloatArray): FloatArray {
        if (inLen + samples.size > input.size) input = input.copyOf(maxOf(input.size * 2, inLen + samples.size))
        samples.copyInto(input, inLen)
        inLen += samples.size
        received += samples.size
        return produce(flush = false)
    }

    /** The outputs still owed, as if silence followed. */
    fun finish(): FloatArray = produce(flush = true)

    private fun produce(flush: Boolean): FloatArray {
        val last = if (flush) received * up / down else Long.MAX_VALUE
        // Outputs whose taps are all received (or, flushing, every output the input covers).
        val ready = if (flush) last else ((received - half) * up + up - 1) / down
        val count = (minOf(last, ready) - next).coerceAtLeast(0L).toInt()
        val out = FloatArray(count)
        var made = 0
        while (next < last && made < count) {
            val pos = next * down
            val centre = pos / up
            val phase = (pos % up).toInt()
            if (!flush && centre + half >= received) break
            val w = weights[phase]
            var s = 0.0f
            for (j in 0 until 2 * half) {
                val idx = centre + j - half + 1
                val at = idx - inStart
                if (at >= 0 && at < inLen) s += input[at.toInt()] * w[j]
            }
            out[made++] = s
            next++
        }
        // Drop input no later output reaches.
        val keepFrom = next * down / up - half
        val drop = (keepFrom - inStart).coerceIn(0L, inLen.toLong()).toInt()
        if (drop > 0) {
            input.copyInto(input, 0, drop, inLen)
            inLen -= drop
            inStart += drop
        }
        return if (made == count) out else out.copyOf(made)
    }
}
