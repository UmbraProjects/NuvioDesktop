package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerProgressFlushTest {
    @Test
    fun quickCloseBeforeFirstSampleDoesNotCreateZeroProgress() {
        assertNull(
            PlayerPlaybackSnapshot().progressSnapshotForFlush(
                initialPositionMs = 420_000L,
                initialProgressFraction = null,
            ),
        )
    }

    @Test
    fun requestedResumePositionWinsOverInitialZeroSample() {
        val snapshot = PlayerPlaybackSnapshot(durationMs = 1_200_000L)
            .progressSnapshotForFlush(
                initialPositionMs = 420_000L,
                initialProgressFraction = null,
            )

        assertEquals(420_000L, snapshot?.positionMs)
    }

    @Test
    fun placeholderDurationCannotTurnResumeIntoCompletion() {
        assertNull(
            PlayerPlaybackSnapshot(durationMs = 500L).progressSnapshotForFlush(
                initialPositionMs = 420_000L,
                initialProgressFraction = null,
            ),
        )
    }

    @Test
    fun realPlaybackPositionWinsAfterResume() {
        val snapshot = PlayerPlaybackSnapshot(
            positionMs = 480_000L,
            durationMs = 1_200_000L,
        ).progressSnapshotForFlush(
            initialPositionMs = 420_000L,
            initialProgressFraction = null,
        )

        assertEquals(480_000L, snapshot?.positionMs)
    }

    @Test
    fun syntheticLastFrameAfterEarlyFailureIsNotATrustworthyEnd() {
        assertEquals(
            false,
            isTrustworthyPlaybackEnd(
                isEnded = true,
                durationMs = 1_244_243L,
                positionMs = 1_244_243L,
                lastTrustedPositionMs = 5_599L,
            ),
        )
    }

    @Test
    fun lastFrameAfterObservedNearEndPlaybackIsTrustworthy() {
        assertEquals(
            true,
            isTrustworthyPlaybackEnd(
                isEnded = true,
                durationMs = 1_244_243L,
                positionMs = 1_244_243L,
                lastTrustedPositionMs = 1_242_000L,
            ),
        )
    }

    @Test
    fun rewindAfterResumeLandedRecordsTheLivePosition() {
        // Resumed at 85%, rewound to 20%, paused: the flush must not report the old resume point.
        val snapshot = PlayerPlaybackSnapshot(
            positionMs = 240_000L,
            durationMs = 1_200_000L,
        ).progressSnapshotForFlush(
            initialPositionMs = 1_020_000L,
            initialProgressFraction = null,
            resumeReached = true,
        )

        assertEquals(240_000L, snapshot?.positionMs)
    }

    @Test
    fun fractionResumeIsNotAFloorOnceReached() {
        val snapshot = PlayerPlaybackSnapshot(
            positionMs = 240_000L,
            durationMs = 1_200_000L,
        ).progressSnapshotForFlush(
            initialPositionMs = 0L,
            initialProgressFraction = 0.85f,
            resumeReached = true,
        )

        assertEquals(240_000L, snapshot?.positionMs)
    }

    @Test
    fun resumeIsReachedOnlyOnceATrustedSampleArrivesNearTheTarget() {
        fun reached(trustedPositionMs: Long) = isResumeReached(
            trustedPositionMs = trustedPositionMs,
            durationMs = 1_200_000L,
            initialPositionMs = 1_020_000L,
            initialProgressFraction = null,
        )
        assertFalse(reached(0L))
        // An early sample before the seek lands keeps the floor in place.
        assertFalse(reached(2_000L))
        // Keyframe snapping can land a few seconds short.
        assertTrue(reached(1_014_000L))
        assertTrue(reached(1_020_500L))
    }

    @Test
    fun noResumeIsReachedByTheFirstTrustedSample() {
        assertTrue(
            isResumeReached(
                trustedPositionMs = 1_500L,
                durationMs = 1_200_000L,
                initialPositionMs = 0L,
                initialProgressFraction = null,
            ),
        )
    }

    @Test
    fun pauseBetweenProviderAndAppThresholdsIsResumableProgress() {
        assertEquals(
            FlushScrobbleStopKind.PROGRESS,
            flushScrobbleStopKind(
                effectivePercent = 85f,
                paused = true,
                sessionRunning = true,
                completionSent = false,
            ),
        )
    }

    @Test
    fun pauseBelowEightyIsResumableProgress() {
        assertEquals(
            FlushScrobbleStopKind.PROGRESS,
            flushScrobbleStopKind(effectivePercent = 20f, paused = true, sessionRunning = true, completionSent = false),
        )
    }

    @Test
    fun pausePastAppCompletionWithoutAMarkSendsTheCompletion() {
        assertEquals(
            FlushScrobbleStopKind.COMPLETION,
            flushScrobbleStopKind(effectivePercent = 92f, paused = true, sessionRunning = true, completionSent = false),
        )
        assertEquals(
            FlushScrobbleStopKind.NONE,
            flushScrobbleStopKind(effectivePercent = 92f, paused = true, sessionRunning = false, completionSent = true),
        )
    }

    @Test
    fun repeatedFlushAtTheEndSendsTheCompletionOnce() {
        assertEquals(
            FlushScrobbleStopKind.NONE,
            flushScrobbleStopKind(effectivePercent = 100f, paused = false, sessionRunning = false, completionSent = true),
        )
    }

    @Test
    fun sessionRestartedAfterTheCompletionIsClosedAgain() {
        assertEquals(
            FlushScrobbleStopKind.COMPLETION,
            flushScrobbleStopKind(effectivePercent = 99.5f, paused = false, sessionRunning = true, completionSent = true),
        )
    }

    @Test
    fun exitPastProviderThresholdIsStillACompletion() {
        assertEquals(
            FlushScrobbleStopKind.COMPLETION,
            flushScrobbleStopKind(effectivePercent = 85f, paused = false, sessionRunning = true, completionSent = false),
        )
    }

    @Test
    fun pauseWithoutARunningSessionSendsNothing() {
        assertEquals(
            FlushScrobbleStopKind.NONE,
            flushScrobbleStopKind(effectivePercent = 40f, paused = true, sessionRunning = false, completionSent = false),
        )
    }

    @Test
    fun aShortVideoEndsForRealOnlyWhenItCannotBeAPlaceholder() {
        // The 1:44 YouTube video autoplay sat at the end of (Nov 3): under two minutes reads as a placeholder clip.
        val end = { allowShort: Boolean ->
            isTrustworthyPlaybackEnd(
                isEnded = true,
                durationMs = 104_025L,
                positionMs = 104_025L,
                lastTrustedPositionMs = 103_320L,
                allowShortContent = allowShort,
            )
        }
        assertEquals(false, end(false))
        assertEquals(true, end(true))
    }
}
