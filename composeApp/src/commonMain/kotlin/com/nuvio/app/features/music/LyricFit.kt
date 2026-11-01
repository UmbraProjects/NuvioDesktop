package com.nuvio.app.features.music

/*
 * fork:stage - lyrics are set to fit the panel they sit in: shrink first, cut words only as a last resort.
 * The composables supply `fits` by measuring the real text (a TextMeasurer); these helpers only choose.
 */

/**
 * The largest size from [idealSp] down to [minSp], in steps of [stepSp], for which [fits] is true. When nothing fits the
 * result is the floor: [minSp], or [idealSp] when that is smaller (a size is never raised above the ideal).
 */
internal fun fitFontSizeSp(idealSp: Float, minSp: Float, stepSp: Float = 2f, fits: (Float) -> Boolean): Float {
    val floor = minOf(minSp, idealSp)
    val step = stepSp.coerceAtLeast(0.1f)
    var size = idealSp
    while (size > floor && !fits(size)) size = (size - step).coerceAtLeast(floor)
    return size
}

/** The most words (at least 1, at most [count]) for which [fits] is true; [count] itself when all of them fit. */
internal fun fitWordCount(count: Int, fits: (Int) -> Boolean): Int {
    if (count <= 1) return count.coerceAtLeast(0)
    var keep = count
    while (keep > 1 && !fits(keep)) keep--
    return keep
}

/**
 * How many rows of a stack set at sizes [rawSizesPx] (each as wide as the panel allows) to keep so that, shrunk to fit
 * [heightPx], none is smaller than [minPx]. Rows are dropped from the end; at least one is always kept.
 */
internal fun fitRowCount(rawSizesPx: List<Float>, heightPx: Float, minPx: Float, lineFactor: Float = 0.98f): Int {
    var keep = rawSizesPx.size
    while (keep > 1) {
        val rows = rawSizesPx.take(keep)
        val total = rows.sumOf { (it * lineFactor).toDouble() }.toFloat().coerceAtLeast(1f)
        val shrink = minOf(1f, heightPx / total)
        if (rows.minOrNull()!! * shrink >= minPx) break
        keep--
    }
    return keep
}

/** The text with an ellipsis after its last word, for a line that had to be cut. */
internal fun withEllipsis(text: String): String = text.trimEnd(',', ';', ':', '-', ' ', '.') + "…"

/** Stage panels: three side by side only when there is room for the lyrics between the player and the booklet. */
internal const val THREE_PANEL_MIN_WIDTH_DP = 1400f

internal fun stageUsesThreePanels(widthDp: Float, realVideo: Boolean): Boolean =
    !realVideo && widthDp >= THREE_PANEL_MIN_WIDTH_DP
