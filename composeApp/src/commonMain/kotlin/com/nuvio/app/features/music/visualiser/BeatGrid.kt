package com.nuvio.app.features.music.visualiser
// FORK UI (Spyder): Nick's Now Playing stage, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * fork:visualiser - the beat grid: the song's own beats (not one tempo laid over it, which drifts on anything played by people)
 * and where each beat falls in its bar. Made by the Beat This! network on desktop (BeatTracker.desktop.kt), or, when that cannot
 * run, by laying the signal-processing estimate's one tempo over the track ([uniform]).
 *
 * The Beat Grid draws sixteen steps a bar: four per beat. Steps are counted on a "virtual" 4/4 count, where a beat's virtual
 * number is the next one after the previous beat's with this beat's bar position: in 4/4 it is just the beat's number, and a bar
 * of three skips the fourth beat's steps ([hasStep] is false for them), so the bar line stays on the left of the grid.
 */

/** Beats in the longest bar the grid draws. */
internal const val GRID_BEATS = 4

internal class BeatGrid(
    /** Every beat's time, ascending. At least two. */
    val beatsMs: DoubleArray,
    /** Each beat's place in its bar: 0 is the downbeat (beat one). */
    val barPos: ByteArray,
    /** Beats the bar line has been moved by hand (0 until [GRID_BEATS]); added to every bar position. */
    val barShift: Int = 0,
) {
    init {
        require(beatsMs.size >= 2 && beatsMs.size == barPos.size)
    }

    private val n = beatsMs.size

    /** Beats in a bar: the most a bar position reaches, plus one (three for a waltz, else four). */
    val beatsPerBar: Int = (barPos.maxOrNull()?.toInt() ?: 3).plus(1).coerceIn(2, GRID_BEATS)

    /** The virtual 4/4 beat number of each beat (see the file comment), with [barShift] applied. */
    private val virtual = LongArray(n).also { v ->
        fun pos(i: Int) = (barPos[i] + barShift).mod(beatsPerBar)
        v[0] = pos(0).toLong()
        for (i in 1 until n) {
            var d = (pos(i) - pos(i - 1)).mod(GRID_BEATS)
            if (d == 0) d = GRID_BEATS
            v[i] = v[i - 1] + d
        }
        // Counted from the first downbeat, so the beats before it are the count-in (negative steps).
        val first = (0 until n).firstOrNull { pos(it) == 0 } ?: 0
        val base = v[first]
        for (i in 0 until n) v[i] -= base
    }

    /** The median time between beats. */
    val periodMs: Double = run {
        val gaps = DoubleArray(n - 1) { beatsMs[it + 1] - beatsMs[it] }
        gaps.sort()
        gaps[gaps.size / 2]
    }

    val bpm: Double get() = 60_000.0 / periodMs

    /** The same beats with the bar line moved by [beats]. */
    fun shifted(beats: Int): BeatGrid = BeatGrid(beatsMs, barPos, (barShift + beats).mod(GRID_BEATS))

    /** The same beats with the bar line moved to [shift]. */
    fun withShift(shift: Int): BeatGrid = if (shift.mod(GRID_BEATS) == barShift) this else BeatGrid(beatsMs, barPos, shift.mod(GRID_BEATS))

    /** The time of the first downbeat. */
    val downbeatMs: Double get() = (0 until n).firstOrNull { (barPos[it] + barShift).mod(beatsPerBar) == 0 }?.let { beatsMs[it] } ?: beatsMs[0]

    /** The index of the last beat at or before [atMs], or -1 before the first. */
    private fun beatAt(atMs: Double): Int {
        var lo = 0
        var hi = n - 1
        if (atMs < beatsMs[0]) return -1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (beatsMs[mid] <= atMs) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** How long beat [i] lasts: to the next beat, or for the last beat as long as the one before it. */
    private fun lengthOf(i: Int): Double = if (i + 1 < n) beatsMs[i + 1] - beatsMs[i] else beatsMs[i] - beatsMs[i - 1]

    /** The length of a sixteenth-note step at [atMs]. */
    fun stepMsAt(atMs: Double): Double = lengthOf(beatAt(atMs).coerceIn(0, n - 1)) / 4.0

    /** The length of a sixteenth-note step at the median tempo. */
    val stepMs: Double get() = periodMs / 4.0

    /** The sixteenth-note step [atMs] falls in on the virtual count (negative well before the first beat). */
    fun stepAt(atMs: Double): Long {
        val i = beatAt(atMs)
        if (i < 0) {
            val beats = (atMs - beatsMs[0]) / lengthOf(0)
            return floor((virtual[0] + beats) * 4.0).toLong()
        }
        val within = ((atMs - beatsMs[i]) / lengthOf(i))
        // Past the last beat the grid carries on at the last beat's length.
        val beats = if (i == n - 1) within else within.coerceAtMost(0.999999)
        return virtual[i] * 4 + floor(beats * 4.0).toLong()
    }

    /** False for the steps of a virtual beat the song does not have (the fourth beat of a bar of three). */
    fun hasStep(step: Long): Boolean {
        val v = step.floorDiv(4L)
        if (v < virtual[0] || v > virtual[n - 1]) return true
        return indexOfVirtual(v) >= 0
    }

    /** When step [step] (on the virtual count) starts. */
    fun stepStartMs(step: Long): Double {
        val v = step.floorDiv(4L)
        val sub = step.mod(4L) / 4.0
        if (v < virtual[0]) return beatsMs[0] + ((v - virtual[0]) + sub) * lengthOf(0)
        if (v > virtual[n - 1]) return beatsMs[n - 1] + ((v - virtual[n - 1]) + sub) * lengthOf(n - 1)
        val i = indexOfVirtual(v)
        if (i < 0) {
            // A beat the bar does not have: the next real beat (the grid shows nothing lit there).
            val next = (0 until n).first { virtual[it] > v }
            return beatsMs[next]
        }
        return beatsMs[i] + sub * lengthOf(i)
    }

    private fun indexOfVirtual(v: Long): Int {
        var lo = 0
        var hi = n - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                virtual[mid] == v -> return mid
                virtual[mid] < v -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        return -1
    }

    override fun equals(other: Any?): Boolean =
        other is BeatGrid && other.barShift == barShift && other.beatsMs.contentEquals(beatsMs) && other.barPos.contentEquals(barPos)

    override fun hashCode(): Int = (beatsMs.contentHashCode() * 31 + barPos.contentHashCode()) * 31 + barShift

    companion object {
        /** One tempo laid over the track from [fromMs] to [toMs], with a downbeat at [downbeatMs] (the signal-processing estimate). */
        fun uniform(periodMs: Double, downbeatMs: Double, fromMs: Double, toMs: Double): BeatGrid? {
            if (periodMs <= 0.0 || toMs - fromMs < periodMs * 2) return null
            val first = kotlin.math.ceil((fromMs - downbeatMs) / periodMs).toLong()
            val last = floor((toMs - downbeatMs) / periodMs).toLong()
            val count = (last - first + 1).toInt()
            if (count < 2) return null
            return BeatGrid(
                DoubleArray(count) { downbeatMs + (first + it) * periodMs },
                ByteArray(count) { (first + it).mod(GRID_BEATS.toLong()).toByte() },
            )
        }
    }
}

/** At least this many beats before there is a grid to draw. */
internal const val GRID_MIN_BEATS = 8

/**
 * The grid for [beats] and [downbeats] (seconds, ascending, as the Beat This! network finds them), or null with too few beats.
 *
 * The bar lines come from the downbeats, which the network holds steady, while its beats can flip between the beat and the half
 * beat for a few bars (a hip-hop loop read at 68 and then 136). So:
 * - The bar length is the median gap between downbeats, a gap of two or three bars counting as that many bars.
 * - A downbeat less than three quarters of a bar after the last one kept is a stray and is dropped.
 * - Three or four beats a bar is whichever beat length (a third or a quarter of the bar) the network's beat gaps fit best as
 *   whole multiples or halves of it; four on a tie.
 * - A bar of two (the beats only ever on half bars) is paired with the next into one bar of four.
 * - The beats are spread evenly across each bar, so the grid follows a song that speeds up or drags, bar by bar. Before the first
 *   downbeat and after the last, the grid carries on at the nearest bar's beat, as far as the network heard beats.
 *
 * With fewer than two downbeats there are no bar lines to go by: the beats themselves are used, holes filled, counted in fours.
 */
internal fun beatGridFrom(beats: List<Double>, downbeats: List<Double>): BeatGrid? {
    if (beats.size < GRID_MIN_BEATS) return null
    if (downbeats.size < 2) return countedFromBeats(beats)
    val rawGaps = DoubleArray(downbeats.size - 1) { downbeats[it + 1] - downbeats[it] }.sortedArray()
    var bar = rawGaps[rawGaps.size / 2]
    if (bar <= 0.0) return countedFromBeats(beats)
    // Refine on whole bars: a gap of two bars is two of them.
    run {
        val perBar = rawGaps.map { g -> g / maxOf(1, (g / bar).roundToInt()) }.sorted()
        bar = perBar[perBar.size / 2]
    }
    var downs = ArrayList<Double>(downbeats.size)
    for (d in downbeats) if (downs.isEmpty() || d - downs.last() >= bar * 0.75) downs += d
    if (downs.size < 2) return countedFromBeats(beats)
    // A bar of two (a polka, a country two-step): the beats only ever land on half bars. Two of its bars make one of four,
    // so the grid shows the beat that was heard rather than twice it.
    if (twoBeatBars(beats, bar)) {
        bar *= 2
        downs = ArrayList(downs.filterIndexed { i, _ -> i % 2 == 0 })
        if (downs.size < 2) return countedFromBeats(beats)
    }

    val perBar = beatsPerBarFor(beats, bar)
    val times = ArrayList<Double>(beats.size + 32)
    val pos = ArrayList<Byte>(beats.size + 32)
    // Before the first downbeat, back to the first beat heard.
    val lead = (downs[1] - downs[0]) / maxOf(1, ((downs[1] - downs[0]) / bar).roundToInt()) / perBar
    var k = 1
    while (downs[0] - k * lead >= beats.first() - lead * 0.5) k++
    for (j in k - 1 downTo 1) { times += downs[0] - j * lead; pos += (perBar - j).mod(perBar).toByte() }
    // Each stretch between downbeats: as many bars as fit, the beats evenly across them.
    for (i in 0 until downs.size - 1) {
        val gap = downs[i + 1] - downs[i]
        val bars = maxOf(1, (gap / bar).roundToInt())
        val count = bars * perBar
        for (j in 0 until count) { times += downs[i] + gap * j / count; pos += (j % perBar).toByte() }
    }
    // After the last downbeat, on to the last beat heard.
    val tail = (downs[downs.size - 1] - downs[downs.size - 2]).let { it / maxOf(1, (it / bar).roundToInt()) } / perBar
    var j = 0
    while (downs.last() + j * tail <= beats.last() + tail * 0.5) { times += downs.last() + j * tail; pos += (j % perBar).toByte(); j++ }
    if (times.size < 2) return null
    return BeatGrid(DoubleArray(times.size) { times[it] * 1_000.0 }, pos.toByteArray())
}

/** True when the gaps between [beats] are whole halves of a [bar]-second bar and an odd quarter of it far less often (under 1 in 2.8). */
private fun twoBeatBars(beats: List<Double>, bar: Double): Boolean {
    var odd = 0
    var even = 0
    val quarter = bar / 4
    for (i in 1 until beats.size) {
        val r = (beats[i] - beats[i - 1]) / quarter
        val q = r.roundToInt()
        if (q !in 1..4 || abs(r - q) > 0.25) continue
        if (q % 2 == 1) odd++ else even++
    }
    return even >= 8 && even > odd * 1.8
}

/**
 * Three or four beats in a bar of [bar] seconds: the beat length whose whole multiples and halves the gaps between [beats]
 * match best. The network may hear a waltz's beat or its half and a 4/4 song's beat or its half, so a half counts too.
 */
private fun beatsPerBarFor(beats: List<Double>, bar: Double): Int {
    fun fit(perBar: Int): Int {
        val beat = bar / perBar
        var n = 0
        for (i in 1 until beats.size) {
            val r = (beats[i] - beats[i - 1]) / beat
            val near = if (r < 0.75) abs(r - 0.5) / 0.5 else abs(r - r.roundToInt()) / r.roundToInt()
            if (r <= 4.5 && near < 0.08) n++
        }
        return n
    }
    return if (fit(3) > fit(4) * 1.2) 3 else 4
}

/** The grid from the beats alone (no bar lines): holes filled, counted in fours from the first. */
private fun countedFromBeats(beats: List<Double>): BeatGrid? {
    val filled = fillHoles(beats)
    if (filled.size < 2) return null
    return BeatGrid(filled, ByteArray(filled.size) { (it % GRID_BEATS).toByte() })
}

/** [beats] in milliseconds with the holes in them (a gap over one and a half beats, up to 64) filled at the beat either side. */
private fun fillHoles(beats: List<Double>): DoubleArray {
    val out = ArrayList<Double>(beats.size + 16)
    val gaps = DoubleArray(beats.size - 1) { beats[it + 1] - beats[it] }
    fun localBeat(i: Int): Double {
        // The median of the gaps around gap i, leaving out i itself (it is the hole).
        val near = ArrayList<Double>(16)
        for (j in maxOf(0, i - 8)..minOf(gaps.size - 1, i + 8)) if (j != i) near += gaps[j]
        if (near.isEmpty()) return gaps[i]
        near.sort()
        return near[near.size / 2]
    }
    for (i in beats.indices) {
        out += beats[i] * 1_000.0
        if (i == gaps.size) break
        val beat = localBeat(i)
        if (gaps[i] > beat * 1.5) {
            val parts = (gaps[i] / beat).roundToInt()
            if (parts in 2..64) for (k in 1 until parts) out += (beats[i] + gaps[i] * k / parts) * 1_000.0
        }
    }
    return out.toDoubleArray()
}

/**
 * The beats (or downbeats) in a frame-wise logit track at [fps]: frames above 0 (a probability over a half) that are the highest
 * within three frames either side, with neighbouring peaks merged into their mean. Seconds. As Beat This!'s minimal postprocessor.
 */
internal fun pickPeaks(logits: FloatArray, length: Int = logits.size, fps: Double = BEAT_NET_FPS): List<Double> {
    val peaks = ArrayList<Int>()
    for (i in 0 until length) {
        val v = logits[i]
        if (v <= 0f) continue
        var top = true
        for (j in maxOf(0, i - 3) until minOf(length, i + 4)) if (logits[j] > v) { top = false; break }
        if (top) peaks += i
    }
    val out = ArrayList<Double>()
    if (peaks.isEmpty()) return out
    var p = peaks[0].toDouble()
    var c = 1.0
    for (k in 1 until peaks.size) {
        val q = peaks[k].toDouble()
        if (q - p <= 1.0) { c += 1.0; p += (q - p) / c } else { out += p / fps; p = q; c = 1.0 }
    }
    out += p / fps
    return out
}

/** Frames a second of the Beat This! network's spectrogram and output. */
internal const val BEAT_NET_FPS = 50.0
