package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a scrobble `start` reports.
 *
 * Wrong answers here are silent and destructive: providers accept any plausible percentage, and one
 * near 100 on a fresh item is read as "watched".
 */
class ScrobbleStartProgressTest {
    private fun progress(
        currentPercent: Float,
        positionMs: Long = 0L,
        durationMs: Long = 1_000_000L,
        initialPositionMs: Long = 0L,
        initialProgressFraction: Float? = null,
        initialSeekApplied: Boolean = true,
        snapshotBelongsToCurrentAttempt: Boolean = true,
    ) = scrobbleStartProgressPercent(
        currentPercent = currentPercent,
        positionMs = positionMs,
        durationMs = durationMs,
        initialPositionMs = initialPositionMs,
        initialProgressFraction = initialProgressFraction,
        initialSeekApplied = initialSeekApplied,
        snapshotBelongsToCurrentAttempt = snapshotBelongsToCurrentAttempt,
    )

    @Test
    fun `a fresh item never inherits the previous episode's position`() {
        // The next-episode advance: the episode fields have moved on, the snapshot has not.
        assertEquals(
            0f,
            progress(currentPercent = 99.3f, snapshotBelongsToCurrentAttempt = false),
        )
    }

    @Test
    fun `a stale snapshot still reports a pending resume point`() {
        // Resuming into a new attempt: the intended position is known even though the player has
        // not sampled yet, and it is more useful than 0.
        assertEquals(
            25f,
            progress(
                currentPercent = 99.3f,
                durationMs = 1_000L,
                initialPositionMs = 250L,
                snapshotBelongsToCurrentAttempt = false,
            ),
        )
    }

    @Test
    fun `once the resume seek has landed the live position wins`() {
        // Including a deliberate seek back to before the resume point.
        assertEquals(
            11.4f,
            progress(
                currentPercent = 11.4f,
                positionMs = 114_000L,
                durationMs = 1_000_000L,
                initialPositionMs = 218_000L,
                initialSeekApplied = true,
            ),
        )
    }

    @Test
    fun `before the resume seek lands the pending position is reported instead of zero`() {
        assertEquals(
            21.8f,
            progress(
                currentPercent = 0f,
                positionMs = 0L,
                durationMs = 1_000_000L,
                initialPositionMs = 218_000L,
                initialSeekApplied = false,
            ),
        )
    }

    @Test
    fun `a resume expressed as a fraction is honoured the same way`() {
        assertEquals(
            40f,
            progress(
                currentPercent = 0f,
                initialProgressFraction = 0.4f,
                initialSeekApplied = false,
            ),
        )
    }

    @Test
    fun `an ordinary start from the beginning reports zero`() {
        assertEquals(0f, progress(currentPercent = 0f))
    }

    @Test
    fun `a live position past the resume point is not dragged backwards`() {
        assertEquals(
            60f,
            progress(
                currentPercent = 60f,
                positionMs = 600_000L,
                durationMs = 1_000_000L,
                initialPositionMs = 218_000L,
                initialSeekApplied = false,
            ),
        )
    }
}
