package com.nuvio.app.core.ui

import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/**
 * Animations that run with no input behind them (the hero backdrop crossfade) mark themselves here,
 * so work that would pause the UI - the native build's idle-time garbage collection - can wait
 * until nothing is moving. Anything the user drives is already covered by the input tracking there.
 */
object UiMotion {
    private val origin = TimeSource.Monotonic.markNow()

    @Volatile
    private var busyUntilMs = 0L

    private fun nowMs(): Long = origin.elapsedNow().inWholeMilliseconds

    /** Something is animating for the next [durationMs]. */
    fun markBusy(durationMs: Long) {
        val until = nowMs() + durationMs
        if (until > busyUntilMs) busyUntilMs = until
    }

    /** Milliseconds since the last marked animation ended; 0 while one is running. */
    fun quietForMs(): Long = (nowMs() - busyUntilMs).coerceAtLeast(0L)
}
