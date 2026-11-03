package com.nuvio.app.features.music.visualiser

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * fork:visualiser - builds a TrackMap from interleaved stereo float samples, fed in the order they were decoded (the
 * loudness measurement's windows). Each 25 ms frame gets an FFT of 2048 samples centred on it: 16 log-spaced band levels,
 * and the spectral flux of four ranges (kick 40-130 Hz, melody 300-1500 Hz, snare 1.5-5 kHz, hats 7-11.5 kHz), from which
 * hits are picked with an adaptive threshold. The beat grid comes from the autocorrelation of the drum flux, refined by
 * a comb over the whole track; the downbeat is the beat of four with the most kick.
 */

private const val FFT_SIZE = 2048
private const val MIN_HZ = 40.0
private const val MAX_HZ = 11_000.0

/** Band levels: -66 dB maps to 0 and -6 dB to 1 (referenced to a full-scale sine). */
private const val BAND_FLOOR_DB = -66.0
private const val BAND_RANGE_DB = 60.0

/** Frame levels (RMS): -48 dBFS maps to 0 and 0 dBFS to 1. */
internal const val LEVEL_FLOOR_DBFS = -48.0

/** Frames a hit decision waits for (it compares a frame with the ones just after it). */
private const val LOOKAHEAD = 3

/** The beat grid needs this many seconds of track. */
private const val BEAT_MIN_FRAMES = 8_000 / MAP_FRAME_MS

private class Group(val loHz: Double, val hiHz: Double, val kind: Int, val minGap: Int, val sensitivity: Float)

private val GROUPS = listOf(
    Group(40.0, 130.0, Onset.KICK, minGap = 5, sensitivity = 1.45f),
    Group(1_500.0, 5_000.0, Onset.SNARE, minGap = 5, sensitivity = 1.5f),
    Group(7_000.0, 11_500.0, Onset.HATS, minGap = 3, sensitivity = 1.4f),
    Group(300.0, 1_500.0, Onset.MELODY, minGap = 5, sensitivity = 1.6f),
)

internal class TrackMapBuilder(private val sampleRate: Int) {
    private val hop = sampleRate * MAP_FRAME_MS / 1_000
    private val fft = RealFft(FFT_SIZE)
    private val window = FloatArray(FFT_SIZE) { (0.5 - 0.5 * cos(2.0 * PI * it / FFT_SIZE)).toFloat() }
    private val binHz = sampleRate.toDouble() / FFT_SIZE
    private val bandBins: List<IntRange>
    private val groupBins: List<IntRange>
    private val reference = (FFT_SIZE / 4.0).pow(2)

    // Mono samples still needed by a frame not yet analysed; monoStart is the absolute index of mono[0].
    private var mono = FloatArray(1 shl 15)
    private var monoStart = 0L
    private var monoLen = 0
    private var received = 0L

    // Left/right/mix energy of the hop being filled.
    private var hopL = 0.0
    private var hopR = 0.0
    private var hopM = 0.0
    private var hopCount = 0

    private val bands = Bytes()
    private val level = Bytes()
    private val left = Bytes()
    private val right = Bytes()
    private val onsets = Bytes()
    private val flux = List(GROUPS.size) { Floats() }
    private val recentMax = FloatArray(GROUPS.size)
    private val lastHit = IntArray(GROUPS.size) { -1_000 }
    private var analysed = 0
    private var decided = 0
    private var previous = FloatArray(FFT_SIZE / 2 + 1)
    private val re = DoubleArray(FFT_SIZE)
    private val im = DoubleArray(FFT_SIZE)

    init {
        val edges = DoubleArray(MAP_BANDS + 1) { MIN_HZ * (MAX_HZ / MIN_HZ).pow(it.toDouble() / MAP_BANDS) }
        bandBins = List(MAP_BANDS) { b ->
            val lo = kotlin.math.ceil(edges[b] / binHz).toInt()
            val hi = max(lo, kotlin.math.floor(edges[b + 1] / binHz).toInt())
            lo..min(hi, FFT_SIZE / 2)
        }
        groupBins = GROUPS.map { g ->
            val lo = kotlin.math.ceil(g.loHz / binHz).toInt()
            lo..max(lo, min(FFT_SIZE / 2, kotlin.math.floor(g.hiHz / binHz).toInt()))
        }
    }

    /** Seconds of audio taken in. */
    val seconds: Double get() = received.toDouble() / sampleRate

    /** Takes in interleaved stereo [samples]. */
    fun add(samples: FloatArray) {
        var i = 0
        while (i + 1 < samples.size) {
            val l = samples[i]
            val r = samples[i + 1]
            val m = (l + r) * 0.5f
            pushMono(m)
            hopL += l * l; hopR += r * r; hopM += m * m
            if (++hopCount == hop) {
                level.add(levelByte(hopM / hop)); left.add(levelByte(hopL / hop)); right.add(levelByte(hopR / hop))
                hopL = 0.0; hopR = 0.0; hopM = 0.0; hopCount = 0
            }
            i += 2
        }
        analyseReady(flush = false)
        decide(flush = false)
    }

    /** The map as far as it is known, for drawing while the rest is read. */
    fun snapshot(): TrackMap = build(decided, complete = false)

    /** The finished map: the tail is analysed as if silence followed. */
    fun finish(): TrackMap {
        if (hopCount > 0) {
            level.add(levelByte(hopM / hopCount)); left.add(levelByte(hopL / hopCount)); right.add(levelByte(hopR / hopCount))
            hopCount = 0
        }
        analyseReady(flush = true)
        decide(flush = true)
        return build(decided, complete = true)
    }

    private fun build(frames: Int, complete: Boolean): TrackMap {
        val n = minOf(frames, level.size, analysed)
        val beat = if (n >= BEAT_MIN_FRAMES) {
            estimateBeat(
                kick = flux[0].data, snare = flux[1].data, hats = flux[2].data, frames = n,
            )
        } else null
        return TrackMap(
            frames = n,
            bands = bands.data.copyOf(n * MAP_BANDS),
            level = level.data.copyOf(n),
            left = left.data.copyOf(n),
            right = right.data.copyOf(n),
            onsets = onsets.data.copyOf(n),
            beat = beat,
            complete = complete,
        )
    }

    private fun pushMono(v: Float) {
        if (monoLen == mono.size) mono = mono.copyOf(mono.size * 2)
        mono[monoLen++] = v
        received++
    }

    /** Analyses every frame whose window is fully received (or, with [flush], every frame that has started). */
    private fun analyseReady(flush: Boolean) {
        val half = FFT_SIZE / 2
        while (true) {
            val centre = analysed.toLong() * hop + hop / 2
            if (flush) {
                if (centre >= received) break
            } else if (centre + half > received) break
            analyseFrame(centre - half)
            analysed++
        }
        // Drop samples no later frame needs.
        val keepFrom = analysed.toLong() * hop + hop / 2 - half
        val drop = (keepFrom - monoStart).coerceIn(0L, monoLen.toLong()).toInt()
        if (drop > 0) {
            mono.copyInto(mono, 0, drop, monoLen)
            monoLen -= drop
            monoStart += drop
        }
    }

    private fun analyseFrame(start: Long) {
        for (k in 0 until FFT_SIZE) {
            val at = start + k - monoStart
            val v = if (at >= 0 && at < monoLen) mono[at.toInt()] else 0f
            re[k] = (v * window[k]).toDouble()
            im[k] = 0.0
        }
        fft.transform(re, im)
        val bins = FFT_SIZE / 2 + 1
        val mag = FloatArray(bins)
        for (k in 0 until bins) mag[k] = sqrt(re[k] * re[k] + im[k] * im[k]).toFloat()
        for (b in 0 until MAP_BANDS) {
            var power = 0.0
            for (k in bandBins[b]) power += mag[k].toDouble() * mag[k]
            val db = 10.0 * log10(power / reference + 1e-12)
            bands.add(unit((db - BAND_FLOOR_DB) / BAND_RANGE_DB))
        }
        val compressed = FloatArray(bins) { ln(1f + 1_000f * mag[it] / (FFT_SIZE / 4f)) }
        GROUPS.indices.forEach { g ->
            var sum = 0f
            val range = groupBins[g]
            for (k in range) sum += max(0f, compressed[k] - previous[k])
            flux[g].add(sum / (range.last - range.first + 1))
        }
        previous = compressed
    }

    /** Picks hits for frames whose look-ahead is analysed. */
    private fun decide(flush: Boolean) {
        val limit = if (flush) analysed else analysed - LOOKAHEAD
        while (decided < limit) {
            var bits = 0
            GROUPS.forEachIndexed { g, group ->
                val f = flux[g]
                val v = f[decided]
                recentMax[g] = max(v, recentMax[g] * 0.9995f)
                if (v <= 0f || decided - lastHit[g] < group.minGap) return@forEachIndexed
                for (j in decided - 2..decided + 2) if (j != decided && j in 0 until f.size && f[j] > v) return@forEachIndexed
                var sum = 0f
                var count = 0
                for (j in maxOf(0, decided - 20)..minOf(f.size - 1, decided + LOOKAHEAD)) { sum += f[j]; count++ }
                val threshold = sum / count * group.sensitivity + recentMax[g] * 0.08f
                if (v > threshold) {
                    bits = bits or group.kind
                    lastHit[g] = decided
                }
            }
            onsets.add(bits.toByte())
            decided++
        }
    }

    private fun levelByte(meanSquare: Double): Byte {
        val db = 10.0 * log10(meanSquare + 1e-12)
        return unit((db - LEVEL_FLOOR_DBFS) / -LEVEL_FLOOR_DBFS)
    }

    private fun unit(v: Double): Byte = (v.coerceIn(0.0, 1.0) * 255.0).roundToInt().toByte()
}

/**
 * The steady beat in the first [frames] of the drum flux, or null when there is none worth drawing a grid on. Public to
 * the package for tests.
 */
internal fun estimateBeat(kick: FloatArray, snare: FloatArray, hats: FloatArray, frames: Int): BeatGrid? {
    if (frames < BEAT_MIN_FRAMES) return null
    fun meanOf(a: FloatArray): Float { var s = 0f; for (i in 0 until frames) s += a[i]; return (s / frames).takeIf { it > 1e-6f } ?: 1f }
    val mk = meanOf(kick); val ms = meanOf(snare); val mh = meanOf(hats)
    val raw = FloatArray(frames) { kick[it] / mk + snare[it] / ms + 0.5f * hats[it] / mh }
    // Take away the local mean so loud passages do not look like beats.
    val env = FloatArray(frames)
    var run = 0f
    val span = 32
    for (i in 0 until frames) {
        run += raw[i]
        if (i >= span) run -= raw[i - span]
        env[i] = max(0f, raw[i] - run / min(i + 1, span))
    }
    val minLag = 13 // 185 BPM
    val maxLag = 36 // 67 BPM
    val acf = DoubleArray(maxLag + 2)
    for (lag in minLag - 1..maxLag + 1) {
        var s = 0.0
        for (i in 0 until frames - lag) s += env[i].toDouble() * env[i + lag]
        acf[lag] = s / (frames - lag)
    }
    var best = -1
    var bestScore = 0.0
    var meanAcf = 0.0
    for (lag in minLag..maxLag) {
        meanAcf += acf[lag]
        val bpm = 60_000.0 / (lag * MAP_FRAME_MS)
        val weight = exp(-0.5 * (log2(bpm / 115.0) / 0.9).pow(2))
        val score = acf[lag] * weight
        if (score > bestScore) { bestScore = score; best = lag }
    }
    meanAcf /= (maxLag - minLag + 1)
    if (best < 0 || meanAcf <= 0.0 || acf[best] / meanAcf < 1.2) return null
    // Sub-frame period from the peak's neighbours, then a comb over the whole track to settle period and phase.
    val a = acf[best - 1]; val b = acf[best]; val c = acf[best + 1]
    val denom = a - 2 * b + c
    val guess = best + if (denom < 0) (0.5 * (a - c) / denom).coerceIn(-0.5, 0.5) else 0.0
    var period = guess
    var phase = 0.0
    var top = -1.0
    var p = guess * 0.97
    while (p <= guess * 1.03) {
        var ph = 0.0
        while (ph < p) {
            var s = 0.0
            var k = 0
            var at = ph
            while (at < frames) { s += env[at.roundToInt().coerceAtMost(frames - 1)]; k++; at += p }
            val score = s / k
            if (score > top) { top = score; period = p; phase = ph }
            ph += 0.5
        }
        p += 0.02
    }
    // The downbeat: of the four beats in a bar, the one with the most kick.
    var bar = 0
    var barScore = -1.0
    for (q in 0 until 4) {
        var s = 0.0
        var at = phase + q * period
        while (at < frames) { s += kick[at.roundToInt().coerceAtMost(frames - 1)]; at += 4 * period }
        if (s > barScore) { barScore = s; bar = q }
    }
    val downbeatFrame = phase + bar * period
    return BeatGrid(periodMs = period * MAP_FRAME_MS, downbeatMs = downbeatFrame * MAP_FRAME_MS + MAP_FRAME_MS / 2.0)
}

/** A growable byte array. */
private class Bytes {
    var data = ByteArray(4_096); private set
    var size = 0; private set
    fun add(v: Byte) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }
}

/** A growable float array. */
private class Floats {
    var data = FloatArray(4_096); private set
    var size = 0; private set
    operator fun get(i: Int): Float = data[i]
    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }
}

/** An iterative radix-2 complex FFT (forward), sized once. */
internal class RealFft(private val size: Int) {
    private val cosT = DoubleArray(size / 2) { cos(2.0 * PI * it / size) }
    private val sinT = DoubleArray(size / 2) { sin(2.0 * PI * it / size) }
    private val rev = IntArray(size).also { r ->
        val bits = log2(size.toDouble()).roundToInt()
        for (i in 0 until size) {
            var x = i; var y = 0
            repeat(bits) { y = (y shl 1) or (x and 1); x = x shr 1 }
            r[i] = y
        }
    }

    fun transform(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until size) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var half = 1
        while (half < size) {
            val step = size / (half * 2)
            var start = 0
            while (start < size) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosT[k]; val wi = -sinT[k]
                    val l = j + half
                    val xr = re[l] * wr - im[l] * wi
                    val xi = re[l] * wi + im[l] * wr
                    re[l] = re[j] - xr; im[l] = im[j] - xi
                    re[j] += xr; im[j] += xi
                    k += step
                }
                start += half * 2
            }
            half *= 2
        }
    }
}
