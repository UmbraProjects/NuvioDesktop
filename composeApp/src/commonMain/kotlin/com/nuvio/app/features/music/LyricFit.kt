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

/** Loud: the band at the pane's foot kept clear for the sync strip, and the room the next line takes under the words. */
internal const val LOUD_STRIP_BAND_DP = 72f
internal const val LOUD_NEXT_LINE_DP = 40f

/** The height, in dp, the loud lyric stack may use: the pane less the sync strip band and, when shown, the next line. */
internal fun loudStackHeightDp(paneHeightDp: Float, hasNext: Boolean, stripBandDp: Float = LOUD_STRIP_BAND_DP): Float =
    (paneHeightDp - stripBandDp - (if (hasNext) LOUD_NEXT_LINE_DP else 0f)).coerceAtLeast(80f)

/** The text with an ellipsis after its last word, for a line that had to be cut. */
internal fun withEllipsis(text: String): String = text.trimEnd(',', ';', ':', '-', ' ', '.') + "…"

/** Stage panels: three side by side only when there is room for the lyrics (or the video) between the player and the booklet; a playing video does not matter. */
internal const val THREE_PANEL_MIN_WIDTH_DP = 1400f

internal fun stageUsesThreePanels(widthDp: Float): Boolean = widthDp >= THREE_PANEL_MIN_WIDTH_DP
