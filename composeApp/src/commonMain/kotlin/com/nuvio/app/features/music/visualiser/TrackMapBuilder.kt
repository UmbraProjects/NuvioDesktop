package com.nuvio.app.features.music.visualiser
// FORK UI (Spyder): Nick's Now Playing stage, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

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
 * loudness measurement's windows). Each 25 ms frame gets an FFT of 2048 samples centred on it, which gives the 16 band
 * levels drawn by the styles.
 *
 * Hits are not read off the raw spectrum: in most music the 40-130 Hz range is the bass guitar as much as the kick, and
 * every bass note would count as a kick. The spectrum is first split into what is sustained and what is struck
 * (harmonic/percussive separation by median filtering, Fitzgerald 2010): a note holds steady across time, so the median
 * over 17 frames keeps it; a drum hit is a short burst across many frequencies, so the median over 13 neighbouring
 * bands keeps it. Kick, snare and hats are picked from the struck part's spectral flux, melody from the sustained part's,
 * each with an adaptive threshold. The beat grid is the period whose comb over the whole track lands on the most kick and
 * snare, and the downbeat the beat with the kick on 1 and 3 and the backbeat on 2 and 4 (see [estimateBeat]).
 */

private const val FFT_SIZE = 2048
private const val MIN_HZ = 40.0
private const val MAX_HZ = 11_000.0

/** Band levels: -66 dB maps to 0 and -6 dB to 1 (referenced to a full-scale sine). */
private const val BAND_FLOOR_DB = -66.0
private const val BAND_RANGE_DB = 60.0

/** Frame levels (RMS): -48 dBFS maps to 0 and 0 dBFS to 1. */
internal const val LEVEL_FLOOR_DBFS = -48.0

/**
 * Half the median filters: 17 frames (425 ms) across time, so a kick's 100 ms body is a minority of the window and stays a
 * hit while a bass note of a quarter second or more holds it; 13 fine bands across frequency.
 */
private const val HALF_TIME = 8
private const val HALF_FREQ = 6

/** A frame-band counts as struck only where the burst clearly beats the sustained level (a soft mask with this margin). */
private const val SPLIT_MARGIN = 2f

/** Frames a hit decision waits for after the split (it compares a frame with the ones just after it). */
private const val LOOKAHEAD = 3

/** The beat grid needs this many seconds of track. */
private const val BEAT_MIN_FRAMES = 8_000 / MAP_FRAME_MS

/** Single FFT bins are fine bands up to here; above it, twelfth-octave groups. */
private const val FINE_SINGLE_BIN_HZ = 375.0

private class Group(
    val loHz: Double,
    val hiHz: Double,
    val kind: Int,
    val minGap: Int,
    val sensitivity: Float,
    /** A hit must reach this share of the strongest recent one. */
    val peakShare: Float,
    val struck: Boolean,
    /**
     * The least flux a hit can have at all. The flux is a rise in log magnitude, so it hardly depends on how loud the track
     * is: a kick in a full mix rises by 1.5 or more, a bass note leaking into the struck part by about 1, which is what keeps
     * a bass line without drums off the kick row.
     */
    val floor: Float = 0f,
)

private val GROUPS = listOf(
    Group(40.0, 150.0, Onset.KICK, minGap = 6, sensitivity = 1.5f, peakShare = 0.45f, struck = true, floor = 1.1f),
    Group(1_500.0, 5_000.0, Onset.SNARE, minGap = 6, sensitivity = 1.5f, peakShare = 0.45f, struck = true),
    Group(7_000.0, 11_500.0, Onset.HATS, minGap = 3, sensitivity = 1.4f, peakShare = 0.3f, struck = true),
    Group(250.0, 2_000.0, Onset.MELODY, minGap = 5, sensitivity = 1.6f, peakShare = 0.25f, struck = false),
)
/** How strong (for its usual level) the snare range must rise to count as a snare when a kick lands at the same moment. */
private const val SNARE_ON_KICK_STRENGTH = 0.8f
private const val KICK = 0
private const val SNARE = 1
private const val HATS = 2

internal class TrackMapBuilder(private val sampleRate: Int) {
    private val hop = sampleRate * MAP_FRAME_MS / 1_000
    private val fft = RealFft(FFT_SIZE)
    private val window = FloatArray(FFT_SIZE) { (0.5 - 0.5 * cos(2.0 * PI * it / FFT_SIZE)).toFloat() }
    private val binHz = sampleRate.toDouble() / FFT_SIZE
    private val bandBins: List<IntRange>
    private val fineBins: List<IntRange>
    private val groupFine: List<IntRange>
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

    /** Frames with an FFT; frames split into sustained and struck (their flux is known); frames with their hits picked. */
    private var analysed = 0
    private var separated = 0
    private var decided = 0

    // Fine-band magnitudes of the frames the split still needs; spectraStart is the frame index of spectra[0].
    private val spectra = ArrayDeque<FloatArray>()
    private var spectraStart = 0
    private var previousStruck: FloatArray? = null
    private var previousHeld: FloatArray? = null
    private val re = DoubleArray(FFT_SIZE)
    private val im = DoubleArray(FFT_SIZE)

    init {
        val edges = DoubleArray(MAP_BANDS + 1) { MIN_HZ * (MAX_HZ / MIN_HZ).pow(it.toDouble() / MAP_BANDS) }
        bandBins = List(MAP_BANDS) { b ->
            val lo = kotlin.math.ceil(edges[b] / binHz).toInt()
            val hi = max(lo, kotlin.math.floor(edges[b + 1] / binHz).toInt())
            lo..min(hi, FFT_SIZE / 2)
        }
        val fine = mutableListOf<IntRange>()
        val firstBin = kotlin.math.ceil(35.0 / binHz).toInt()
        val singleTo = kotlin.math.floor(FINE_SINGLE_BIN_HZ / binHz).toInt()
        for (k in firstBin..singleTo) fine += k..k
        var lowHz = (singleTo + 1) * binHz
        while (lowHz < 11_500.0) {
            val highHz = lowHz * 2.0.pow(1.0 / 12)
            val lo = kotlin.math.ceil(lowHz / binHz).toInt()
            val hi = max(lo, min(FFT_SIZE / 2, kotlin.math.floor(highHz / binHz).toInt()))
            if (fine.isEmpty() || lo > fine.last().last) fine += lo..hi
            lowHz = highHz
        }
        fineBins = fine
        groupFine = GROUPS.map { g ->
            val centres = fineBins.map { (it.first + it.last) / 2.0 * binHz }
            val lo = centres.indexOfFirst { it >= g.loHz }.coerceAtLeast(0)
            val hi = centres.indexOfLast { it <= g.hiHz }.coerceAtLeast(lo)
            lo..hi
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
        separate(flush = false)
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
        separate(flush = true)
        decide(flush = true)
        return build(decided, complete = true)
    }

    private fun build(frames: Int, complete: Boolean): TrackMap {
        val n = minOf(frames, level.size, analysed)
        val beat = if (n >= BEAT_MIN_FRAMES) {
            estimateBeat(kick = flux[0].data, snare = flux[1].data, hats = flux[2].data, frames = n)
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
        val power = DoubleArray(bins) { re[it] * re[it] + im[it] * im[it] }
        for (b in 0 until MAP_BANDS) {
            var sum = 0.0
            for (k in bandBins[b]) sum += power[k]
            val db = 10.0 * log10(sum / reference + 1e-12)
            bands.add(unit((db - BAND_FLOOR_DB) / BAND_RANGE_DB))
        }
        spectra.addLast(FloatArray(fineBins.size) { f ->
            var sum = 0.0
            for (k in fineBins[f]) sum += power[k]
            (sqrt(sum) / (FFT_SIZE / 4.0)).toFloat()
        })
    }

    /** Splits each frame whose nine-frame neighbourhood is analysed into its sustained and struck parts, and takes their flux. */
    private fun separate(flush: Boolean) {
        val fine = fineBins.size
        val column = FloatArray(2 * HALF_TIME + 1)
        val row = FloatArray(2 * HALF_FREQ + 1)
        while (separated < analysed && (flush || separated + HALF_TIME < analysed)) {
            val t = separated
            val now = spectra[t - spectraStart]
            val struck = FloatArray(fine)
            val held = FloatArray(fine)
            val from = maxOf(spectraStart, t - HALF_TIME)
            val to = minOf(analysed - 1, t + HALF_TIME)
            for (f in 0 until fine) {
                var n = 0
                for (j in from..to) column[n++] = spectra[j - spectraStart][f]
                val sustained = median(column, n)
                var m = 0
                for (g in maxOf(0, f - HALF_FREQ)..minOf(fine - 1, f + HALF_FREQ)) row[m++] = now[g]
                val burst = median(row, m)
                val ratio = burst / (sustained + 1e-9f)
                val r4 = ratio * ratio * ratio * ratio
                val m4 = SPLIT_MARGIN * SPLIT_MARGIN * SPLIT_MARGIN * SPLIT_MARGIN
                val share = r4 / (r4 + m4)
                struck[f] = ln(1f + 1_000f * now[f] * share)
                held[f] = ln(1f + 1_000f * now[f] * (1f - share))
            }
            GROUPS.forEachIndexed { g, group ->
                val previous = if (group.struck) previousStruck else previousHeld
                val current = if (group.struck) struck else held
                var sum = 0f
                var peak = 0f
                val range = groupFine[g]
                if (previous != null) for (f in range) {
                    val rise = max(0f, current[f] - previous[f])
                    sum += rise
                    peak = max(peak, rise)
                }
                val width = range.last - range.first + 1
                var value = sum / width
                if (group.struck && peak > 0f && previous != null) {
                    // A drum rises across its whole range at once; a note leaking into the struck part rises in a few bands
                    // (its partials). Weighting by how much of the range rose keeps the first and fades the second.
                    var broad = 0
                    for (f in range) if (current[f] - previous[f] > peak * 0.25f) broad++
                    value *= broad.toFloat() / width
                }
                flux[g].add(value)
            }
            previousStruck = struck
            previousHeld = held
            separated++
            // Keep the frames a later split still looks back at.
            while (spectraStart < separated - HALF_TIME && spectra.isNotEmpty()) {
                spectra.removeFirst()
                spectraStart++
            }
        }
    }

    /** Picks hits for frames whose look-ahead has been split. */
    private fun decide(flush: Boolean) {
        val limit = if (flush) separated else separated - LOOKAHEAD
        val candidate = BooleanArray(GROUPS.size)
        val strength = FloatArray(GROUPS.size)
        while (decided < limit) {
            GROUPS.forEachIndexed { g, group ->
                candidate[g] = false
                val f = flux[g]
                val v = f[decided]
                // The strongest recent hit, fading over about ten seconds: a bass note that leaks into the struck part is a
                // fraction of a real kick, so a hit must reach a share of it as well as stand out from its neighbours.
                recentMax[g] = max(v, recentMax[g] * 0.9975f)
                strength[g] = if (recentMax[g] > 0f) v / recentMax[g] else 0f
                if (v <= 0f || decided - lastHit[g] < group.minGap) return@forEachIndexed
                for (j in decided - 2..decided + 2) if (j != decided && j in 0 until f.size && f[j] > v) return@forEachIndexed
                var sum = 0f
                var count = 0
                for (j in maxOf(0, decided - 20)..minOf(f.size - 1, decided + LOOKAHEAD)) { sum += f[j]; count++ }
                candidate[g] = v > group.floor && v > max(sum / count * group.sensitivity, recentMax[g] * group.peakShare)
            }
            // A kick's beater click and a hi-hat both reach the snare's range. With a kick at the same moment, the snare range
            // must rise strongly for a snare (a click is a small rise there, a clap on a four-on-the-floor kick a big one);
            // and where the hats rose harder than the snare range, it was the hats.
            if (candidate[SNARE] && (candidate[KICK] && strength[SNARE] < SNARE_ON_KICK_STRENGTH || strength[SNARE] < strength[HATS] * 0.9f)) {
                candidate[SNARE] = false
            }
            var bits = 0
            GROUPS.forEachIndexed { g, group ->
                if (candidate[g]) {
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

/** The median of the first [n] values of [a] (reorders them). */
private fun median(a: FloatArray, n: Int): Float {
    for (i in 1 until n) {
        val v = a[i]
        var j = i - 1
        while (j >= 0 && a[j] > v) { a[j + 1] = a[j]; j-- }
        a[j + 1] = v
    }
    return if (n % 2 == 1) a[n / 2] else (a[n / 2 - 1] + a[n / 2]) * 0.5f
}

/**
 * The steady beat in the first [frames] of the drum flux, or null when there is none worth drawing a grid on. Public to
 * the package for tests.
 *
 * Every beat period from 185 to 67 BPM is tried as a comb over the whole track: the period whose beats land on the most
 * kick and snare wins, with a gentle preference for tempos near 120 so that double or half time (which fits the same hits)
 * loses. Hats are left out: off-beat and sixteenth hats (garage, house, funk) made an autocorrelation find a beat and a half
 * as steady as one beat, and a four-on-the-floor track at 130 BPM read as 87.
 */
internal fun estimateBeat(kick: FloatArray, snare: FloatArray, @Suppress("UNUSED_PARAMETER") hats: FloatArray, frames: Int): BeatGrid? {
    if (frames < BEAT_MIN_FRAMES) return null
    fun meanOf(a: FloatArray): Float { var s = 0f; for (i in 0 until frames) s += a[i]; return (s / frames).takeIf { it > 1e-6f } ?: 1f }
    val mk = meanOf(kick); val ms = meanOf(snare)
    val raw = FloatArray(frames) { kick[it] / mk + snare[it] / ms }
    // Take away the local mean so loud passages do not look like beats.
    val env = FloatArray(frames)
    var run = 0f
    val span = 32
    for (i in 0 until frames) {
        run += raw[i]
        if (i >= span) run -= raw[i - span]
        env[i] = max(0f, raw[i] - run / min(i + 1, span))
    }
    // A hit counts for the frame either side too, so a comb a fraction of a frame off the grid still meets it.
    val wide = FloatArray(frames) { i -> max(env[i], max(env.getOrElse(i - 1) { 0f }, env.getOrElse(i + 1) { 0f })) }
    var meanWide = 0.0
    for (v in wide) meanWide += v
    meanWide /= frames
    if (meanWide <= 1e-9) return null

    fun comb(period: Double): Pair<Double, Double> {
        var best = -1.0
        var bestPhase = 0.0
        var phase = 0.0
        while (phase < period) {
            var s = 0.0
            var k = 0
            var at = phase
            while (at < frames - 1) { s += wide[at.roundToInt()]; k++; at += period }
            val fit = s / k
            if (fit > best) { best = fit; bestPhase = phase }
            phase += 0.5
        }
        return best to bestPhase
    }
    fun prior(period: Double): Double {
        val bpm = 60_000.0 / (period * MAP_FRAME_MS)
        return exp(-0.5 * (log2(bpm / 120.0) / 0.55).pow(2))
    }

    var period = -1.0
    var top = -1.0
    var fitAtTop = 0.0
    var p = 60_000.0 / 185.0 / MAP_FRAME_MS
    val longest = 60_000.0 / 67.0 / MAP_FRAME_MS
    while (p <= longest) {
        val fit = comb(p).first
        val score = fit * prior(p)
        if (score > top) { top = score; period = p; fitAtTop = fit }
        p += 0.25
    }
    // A beat's hits must stand well clear of the track's average, or there is no steady beat to draw.
    if (period < 0 || fitAtTop / meanWide < 1.6) return null
    // Settle the period and phase finely around the winner.
    var phase = 0.0
    var bestFit = -1.0
    val coarse = period
    p = coarse * 0.98
    while (p <= coarse * 1.02) {
        val (fit, ph) = comb(p)
        if (fit > bestFit) { bestFit = fit; period = p; phase = ph }
        p += 0.02
    }
    // The downbeat: of the four beats in a bar, the one where the kick falls on 1 and 3 and the snare (or clap) on 2 and 4.
    // A four-on-the-floor kick is the same on every beat, so there the backbeat decides.
    fun at(a: FloatArray, beat: Double): Float = a[beat.roundToInt().coerceIn(0, frames - 1)] / max(1e-6f, if (a === kick) mk else ms)
    var bar = 0
    var barScore = -1.0
    for (q in 0 until 4) {
        var s = 0.0
        var b = phase + q * period
        while (b < frames) {
            s += at(kick, b) + at(snare, b + period) + at(kick, b + 2 * period) * 0.5 + at(snare, b + 3 * period)
            b += 4 * period
        }
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
