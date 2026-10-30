package com.nuvio.app.features.preroll

import com.nuvio.app.features.player.skip.SkipInterval
import com.nuvio.app.features.player.skip.isOutroKind
import com.nuvio.app.features.player.skip.isPostCreditsScene

/** The stretch of playback during which the post-show may be up. */
data class PostShowWindow(val startMs: Long, val endMs: Long) {
    operator fun contains(positionMs: Long): Boolean = positionMs >= startMs && positionMs < endMs
}

/** An outro starting before this share of the runtime is a mislabelled chapter, not the credits. */
private const val MIN_CREDITS_START_FRACTION = 0.85

/** With no usable marker, credits are assumed to start this far in. */
private const val FALLBACK_CREDITS_START_FRACTION = 0.96

/** Not worth shrinking the video for less than this. */
private const val MIN_WINDOW_MS = 20_000L

/** Cards start prefetching at this share of the runtime, so they are ready when the credits roll. */
private const val PREFETCH_FRACTION = 0.5

/** Largest forward step between two position reports that still counts as playing, not seeking. */
private const val MAX_PLAYING_STEP_MS = 10_000L

/**
 * Where the post-show runs for a film of [durationMs], or null when it cannot be told.
 *
 * Starts at the credits marker Nuvio already tracks (the skip-credits interval), provided it sits
 * late enough to be the credits; otherwise falls back to the last few percent of the runtime. Ends
 * where a known post-credits scene begins, so the scene is watched at full size, or at the end of
 * the file.
 */
fun postShowWindow(intervals: List<SkipInterval>, durationMs: Long): PostShowWindow? {
    if (durationMs <= 0L) return null
    val marker = intervals
        .filter { it.isOutroKind() }
        .map { (it.startTime * 1000.0).toLong() }
        .filter { it >= durationMs * MIN_CREDITS_START_FRACTION && it < durationMs }
        .minOrNull()
    val startMs = marker ?: (durationMs * FALLBACK_CREDITS_START_FRACTION).toLong()
    val sceneMs = intervals
        .filter { it.isPostCreditsScene() }
        .map { (it.startTime * 1000.0).toLong() }
        .filter { it > startMs && it < durationMs }
        .minOrNull()
    val endMs = sceneMs ?: durationMs
    if (endMs - startMs < MIN_WINDOW_MS) return null
    return PostShowWindow(startMs, endMs)
}

/**
 * True on the position report that carries playback across the window's start. A report that
 * lands inside the window from far behind is a seek or a resume, and the post-show stays away: it
 * is for people who watched their way to the credits.
 */
fun postShowArrivedByPlaying(previousMs: Long, currentMs: Long, window: PostShowWindow): Boolean =
    previousMs < window.startMs &&
        currentMs >= window.startMs &&
        currentMs < window.endMs &&
        currentMs - previousMs in 0..MAX_PLAYING_STEP_MS

/** Whether it is time to fetch the cards. */
fun shouldPrefetchPostShow(positionMs: Long, durationMs: Long): Boolean =
    durationMs > 0L && positionMs >= (durationMs * PREFETCH_FRACTION).toLong()
