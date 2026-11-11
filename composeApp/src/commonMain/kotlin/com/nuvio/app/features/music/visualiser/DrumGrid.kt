package com.nuvio.app.features.music.visualiser
// FORK UI (Spyder): Nick's Now Playing stage, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import kotlin.math.floor
import kotlin.math.min

/*
 * fork:visualiser - kick, snare and hats read on the beat grid. Picking hits frame by frame with thresholds let a loud bass
 * note or synth stab pass for a kick or a snare. With a beat grid that can be trusted (the Beat This! network's), each
 * sixteenth-note step of each bar asks how strongly each drum's onset strength (the struck part's flux, TrackMapBuilder) rose
 * right there, and a drum part repeats: the same step of the bars around it says what the pattern is. A step is a hit when it
 * is part of the pattern there and something did sound this time, or when it is as strong as the drum's real hits anyway (a
 * fill, a crash into the chorus). A bass note that wanders across the bar, or one stab, is neither.
 *
 * Steps are measured where the flux peaks within a little of the step, and the hit is put on that frame, so a slightly
 * early or swung hit stays where it was played. Melody, and anything before the first beat or after the last, is left as
 * the frame-by-frame picker found it.
 */

/** The drums read on the grid; melody is not. */
private val GRID_DRUMS = intArrayOf(Onset.KICK, Onset.SNARE, Onset.HATS)

/** How far either side of a step (as a share of the step) a hit still belongs to it. */
private const val STEP_REACH = 0.4

/** Bars either side whose same step makes up the pattern. */
private const val PATTERN_BARS = 4

/**
 * Per drum: a step is in the pattern when its typical strength there reaches this share of the pattern's strongest step,
 * and a pattern step lights when this bar's strength reaches this share of the typical one.
 */
private val PATTERN_SHARE = floatArrayOf(0.6f, 0.45f, 0.35f)
private val PRESENT_SHARE = floatArrayOf(0.4f, 0.4f, 0.35f)

/**
 * A step this strong (for the drum's real hits) is a hit even off the pattern: a fill. The kick's bar is higher and its pattern
 * share too: a bass line leaks into the kick's range and repeats every bar just as a kick does, but a little weaker.
 */
private val FILL_STRENGTH = floatArrayOf(1.0f, 0.85f, 0.85f)

/** Below this (for the drum's real hits) nothing is a hit at all: the drum is not playing. */
private const val QUIET = 0.12f

/**
 * [onsets] with kick, snare and hats re-read on [grid] from their onset strength [kick], [snare] and [hats] (one value a
 * frame, the first [frames] of each). Returns a new array.
 */
internal fun drumsOnGrid(
    onsets: ByteArray,
    frames: Int,
    kick: FloatArray,
    snare: FloatArray,
    hats: FloatArray,
    grid: BeatGrid,
): ByteArray {
    val out = onsets.copyOf()
    if (frames <= 0) return out
    val flux = arrayOf(kick, snare, hats)
    val endMs = frames.toDouble() * MAP_FRAME_MS
    val firstStep = grid.stepAt(maxOf(0.0, grid.beatsMs.first()))
    val lastStep = grid.stepAt(minOf(endMs, grid.beatsMs.last() + grid.periodMs))
    if (lastStep - firstStep < 32) return out
    val count = (lastStep - firstStep + 1).toInt()

    // Each step's strength per drum and the frame where it peaked; -1 for a step the bar does not have.
    val strength = Array(3) { FloatArray(count) }
    val at = Array(3) { IntArray(count) { -1 } }
    for (i in 0 until count) {
        val step = firstStep + i
        if (!grid.hasStep(step)) continue
        val start = grid.stepStartMs(step)
        val length = grid.stepStartMs(step + 1) - start
        if (length <= 0.0) continue
        val f0 = maxOf(0, floor((start - STEP_REACH * length) / MAP_FRAME_MS).toInt())
        val f1 = minOf(frames - 1, floor((start + STEP_REACH * length) / MAP_FRAME_MS).toInt())
        for (d in 0 until 3) {
            var best = 0f
            var where = -1
            for (f in f0..f1) if (flux[d][f] > best) { best = flux[d][f]; where = f }
            strength[d][i] = best
            at[d][i] = where
        }
    }
    // Strengths as a share of each drum's real hits (its 95th percentile over the steps), so the shares above mean the same in
    // every song however loud its drums are.
    for (d in 0 until 3) {
        val sorted = strength[d].filter { it > 0f }.sorted()
        val top = if (sorted.isEmpty()) 0f else sorted[min(sorted.size - 1, (sorted.size * 0.95).toInt())]
        if (top <= 0f) { strength[d].fill(0f); continue }
        for (i in 0 until count) strength[d][i] = min(2f, strength[d][i] / top)
    }

    // Bars, and the typical strength of each of the sixteen steps over the bars around each bar.
    val firstBar = firstStep.floorDiv(16L)
    val bars = (lastStep.floorDiv(16L) - firstBar + 1).toInt()
    fun indexOf(bar: Int, slot: Int): Int = ((firstBar + bar) * 16 + slot - firstStep).toInt()
    val column = FloatArray(2 * PATTERN_BARS + 1)
    val typical = Array(3) { FloatArray(16) }
    // Hits are written fresh for the drums, only where the grid covers.
    val coveredFrom = at.minOf { a -> a.filter { it >= 0 }.minOrNull() ?: Int.MAX_VALUE }
    val coveredTo = at.maxOf { a -> a.maxOrNull() ?: -1 }
    val clearFrom = maxOf(0, floor(grid.stepStartMs(firstStep) / MAP_FRAME_MS).toInt())
    val clearTo = minOf(frames - 1, floor(grid.stepStartMs(lastStep + 1) / MAP_FRAME_MS).toInt())
    if (coveredTo < 0 || coveredFrom == Int.MAX_VALUE) return out
    val drumBits = GRID_DRUMS.fold(0) { acc, k -> acc or k }
    for (f in clearFrom..clearTo) out[f] = (out[f].toInt() and drumBits.inv()).toByte()

    for (bar in 0 until bars) {
        for (d in 0 until 3) {
            for (slot in 0 until 16) {
                var n = 0
                for (b in maxOf(0, bar - PATTERN_BARS)..minOf(bars - 1, bar + PATTERN_BARS)) {
                    val i = indexOf(b, slot)
                    if (i in 0 until count && at[d][i] >= 0) column[n++] = strength[d][i]
                }
                typical[d][slot] = if (n == 0) 0f else medianOf(column, n)
            }
        }
        for (d in 0 until 3) {
            val peak = typical[d].max()
            for (slot in 0 until 16) {
                val i = indexOf(bar, slot)
                if (i !in 0 until count || at[d][i] < 0) continue
                val s = strength[d][i]
                if (s < QUIET) continue
                val inPattern = peak >= QUIET && typical[d][slot] >= peak * PATTERN_SHARE[d]
                val hit = (inPattern && s >= typical[d][slot] * PRESENT_SHARE[d]) || s >= FILL_STRENGTH[d]
                if (!hit) continue
                // A kick's beater click reaches the snare's range: where the kick is the stronger part of the pattern at this
                // step, a snare there needs to be a fill. (Hats leaking into the snare range are weak and fall below the pattern.)
                if (d == 1 && s < FILL_STRENGTH[1] && typical[1][slot] < typical[0][slot]) continue
                val f = at[d][i]
                out[f] = (out[f].toInt() or GRID_DRUMS[d]).toByte()
            }
        }
    }
    return out
}

/** The median of the first [n] values of [a] (reorders them). */
private fun medianOf(a: FloatArray, n: Int): Float {
    for (i in 1 until n) {
        val v = a[i]
        var j = i - 1
        while (j >= 0 && a[j] > v) { a[j + 1] = a[j]; j-- }
        a[j + 1] = v
    }
    return if (n % 2 == 1) a[n / 2] else (a[n / 2 - 1] + a[n / 2]) * 0.5f
}
