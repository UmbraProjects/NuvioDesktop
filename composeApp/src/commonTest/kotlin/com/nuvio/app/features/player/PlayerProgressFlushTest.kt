package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
