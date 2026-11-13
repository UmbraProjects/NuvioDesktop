package com.spydermahn.nuvioremote

/**
 * The film screen's "Ends at" clock: the wall-clock time what is playing finishes. Pure, so it can be tested without
 * Android. Null without a timeline.
 *
 * [positionMs] is where the film is now (already moved on from the last STATE line), [nowWallMs] the phone's clock.
 * A speed other than 1x shortens or stretches what is left; while paused the end time keeps moving with the clock,
 * since it is "if you carry on now".
 */
fun filmEndsAt(positionMs: Long, durationMs: Long, speedPercent: Int?, nowWallMs: Long): Long? {
    if (durationMs <= 0L) return null
    val position = positionMs.coerceIn(0L, durationMs)
    val speed = (speedPercent ?: 100).coerceIn(25, 400)
    val left = (durationMs - position) * 100L / speed
    return nowWallMs + left
}

/** "1h 50m", "48m", "under a minute": a length in words for the clock card's quiet line. */
fun runtimeWords(durationMs: Long): String {
    val minutes = (durationMs + 30_000L) / 60_000L
    return when {
        minutes < 1L -> "under a minute"
        minutes < 60L -> "${minutes}m"
        minutes % 60L == 0L -> "${minutes / 60L}h"
        else -> "${minutes / 60L}h ${minutes % 60L}m"
    }
}
