package com.nuvio.app.features.preroll

/*
 * P0 of the post-show trivia plan: a resize spike, not the feature.
 *
 * It answers one question: can the desktop player's video shrink into a corner box while Compose
 * draws around it, and come back? Nothing here knows about trivia, AI or credits yet. The timed
 * window below stands in for "the credits started" so the resize can be tried on any film.
 */

/** Playback position, in ms, at which the spike boxes the video. */
const val POST_SHOW_SPIKE_START_MS = 45_000L

/** How long the video stays boxed, in ms of playback. */
const val POST_SHOW_SPIKE_HOLD_MS = 20_000L

/**
 * Whether the video should be in the corner box at [positionMs].
 *
 * Driven by the playhead rather than a timer so the box follows the film: seeking back out of the
 * window restores full screen at once, and pausing inside it keeps the box up.
 */
fun postShowSpikeActive(
    positionMs: Long,
    startMs: Long = POST_SHOW_SPIKE_START_MS,
    holdMs: Long = POST_SHOW_SPIKE_HOLD_MS,
): Boolean = positionMs >= startMs && positionMs < startMs + holdMs
