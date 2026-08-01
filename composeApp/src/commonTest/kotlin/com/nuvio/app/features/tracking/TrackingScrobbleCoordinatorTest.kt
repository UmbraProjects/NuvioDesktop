package com.nuvio.app.features.tracking

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackingScrobbleCoordinatorTest {
    @Test
    fun `fanout isolates one provider failure`() = runBlocking {
        val successful = FakeScrobbler(TrackingProviderId.TRAKT)
        val failing = FakeScrobbler(TrackingProviderId.SIMKL, failure = IllegalStateException("offline"))

        val dispatch = dispatchTrackingScrobble(
            scrobblers = listOf(successful, failing),
            profileId = 2,
            action = TrackingScrobbleAction.PAUSE,
            event = movieEvent(),
        )

        assertEquals(1, successful.callCount)
        assertEquals(1, failing.callCount)
        assertEquals(
            listOf(TrackingProviderId.SIMKL),
            dispatch.failures.map(TrackingScrobbleFailure::providerId),
        )
        assertEquals(1, dispatch.sentCount)
        assertFalse(dispatch.handledNothing)
    }

    @Test
    fun `seek fanout targets only providers that restart scrobbles`() = runBlocking {
        val trakt = FakeScrobbler(
            providerId = TrackingProviderId.TRAKT,
            seekScrobblePolicy = TrackingSeekScrobblePolicy.STOP_AND_RESTART,
        )
        val simkl = FakeScrobbler(TrackingProviderId.SIMKL)

        dispatchTrackingSeekScrobble(
            scrobblers = listOf(trakt, simkl),
            profileId = 2,
            action = TrackingScrobbleAction.STOP,
            event = movieEvent(),
        )

        assertEquals(1, trakt.callCount)
        assertEquals(0, simkl.callCount)
    }

    @Test
    fun `a restart-only provider takes the seek restart but not the seek stop`() = runBlocking {
        // Regression: this provider was NONE, so nothing re-sent a position after a seek and the
        // server kept extrapolating from wherever playback began.
        fun scrobblers() = listOf(
            FakeScrobbler(TrackingProviderId.TRAKT, TrackingSeekScrobblePolicy.STOP_AND_RESTART),
            FakeScrobbler(TrackingProviderId.MDBLIST, TrackingSeekScrobblePolicy.RESTART_ONLY),
        )

        val onStop = scrobblers()
        dispatchTrackingSeekScrobble(onStop, profileId = 2, action = TrackingScrobbleAction.STOP, event = movieEvent())
        assertEquals(1, onStop[0].callCount)
        assertEquals(0, onStop[1].callCount)

        val onStart = scrobblers()
        dispatchTrackingSeekScrobble(onStart, profileId = 2, action = TrackingScrobbleAction.START, event = movieEvent())
        assertEquals(1, onStart[0].callCount)
        assertEquals(1, onStart[1].callCount)
    }

    @Test
    fun `a seek-indifferent provider receives neither half`() = runBlocking {
        val ignoring = FakeScrobbler(TrackingProviderId.SIMKL, TrackingSeekScrobblePolicy.NONE)

        TrackingScrobbleAction.entries.forEach { action ->
            dispatchTrackingSeekScrobble(listOf(ignoring), profileId = 2, action = action, event = movieEvent())
        }

        assertEquals(0, ignoring.callCount)
    }

    @Test
    fun `a seek near the end is dispatched like any other position`() = runBlocking {
        // There is no completion guard: seeking to the end is a deliberate act, and a provider that
        // records it as watched is agreeing with the user rather than jumping the gun. Withholding
        // it instead left every provider stale for the rest of the file.
        val stopAndRestart = FakeScrobbler(
            TrackingProviderId.SIMKL,
            TrackingSeekScrobblePolicy.STOP_AND_RESTART,
        )
        val restartOnly = FakeScrobbler(
            TrackingProviderId.MDBLIST,
            TrackingSeekScrobblePolicy.RESTART_ONLY,
        )

        dispatchTrackingSeekScrobble(
            scrobblers = listOf(stopAndRestart, restartOnly),
            profileId = 2,
            action = TrackingScrobbleAction.START,
            event = movieEvent(progressPercent = 96.7),
        )

        assertEquals(1, stopAndRestart.callCount)
        assertEquals(1, restartOnly.callCount)
    }

    @Test
    fun `declining every provider is reported as unhandled, not as failure`() = runBlocking {
        val dispatch = dispatchTrackingScrobble(
            scrobblers = listOf(
                FakeScrobbler(TrackingProviderId.TRAKT, sends = false),
                FakeScrobbler(TrackingProviderId.SIMKL, sends = false),
            ),
            profileId = 2,
            action = TrackingScrobbleAction.START,
            event = movieEvent(),
        )

        assertEquals(0, dispatch.sentCount)
        assertTrue(dispatch.failures.isEmpty())
        assertTrue(dispatch.handledNothing)
    }

    @Test
    fun `a provider that declines does not mask one that sent`() = runBlocking {
        val dispatch = dispatchTrackingScrobble(
            scrobblers = listOf(
                FakeScrobbler(TrackingProviderId.TRAKT, sends = false),
                FakeScrobbler(TrackingProviderId.SIMKL, sends = true),
            ),
            profileId = 2,
            action = TrackingScrobbleAction.START,
            event = movieEvent(),
        )

        assertEquals(1, dispatch.sentCount)
        assertFalse(dispatch.handledNothing)
    }

    @Test
    fun `a failure alone is not unhandled — the item was addressable`() = runBlocking {
        val dispatch = dispatchTrackingScrobble(
            scrobblers = listOf(
                FakeScrobbler(TrackingProviderId.TRAKT, failure = IllegalStateException("500")),
            ),
            profileId = 2,
            action = TrackingScrobbleAction.STOP,
            event = movieEvent(),
        )

        assertEquals(0, dispatch.sentCount)
        assertEquals(1, dispatch.failures.size)
        assertFalse(dispatch.handledNothing)
    }

    @Test
    fun `no connected providers is unhandled`() = runBlocking {
        val dispatch = dispatchTrackingScrobble(
            scrobblers = emptyList(),
            profileId = 2,
            action = TrackingScrobbleAction.START,
            event = movieEvent(),
        )

        assertTrue(dispatch.handledNothing)
    }

    @Test
    fun `only providers that opted in receive a progress refresh`() = runBlocking {
        val optedIn = FakeScrobbler(TrackingProviderId.MDBLIST, progressRefreshIntervalMs = 300_000L)
        val optedOut = FakeScrobbler(TrackingProviderId.SIMKL)

        val dispatch = dispatchTrackingScrobble(
            scrobblers = listOf(optedIn, optedOut).filter { it.progressRefreshIntervalMs != null },
            profileId = 2,
            action = TrackingScrobbleAction.START,
            event = movieEvent(),
        )

        assertEquals(1, optedIn.callCount)
        assertEquals(0, optedOut.callCount)
        assertEquals(1, dispatch.sentCount)
    }

    private fun movieEvent(progressPercent: Double = 42.5) = TrackingScrobbleEvent(
        media = TrackingMediaReference(
            kind = TrackingMediaKind.MOVIE,
            ids = TrackingExternalIds(imdb = "tt0111161"),
        ),
        progressPercent = progressPercent,
    )

    private class FakeScrobbler(
        override val providerId: TrackingProviderId,
        override val seekScrobblePolicy: TrackingSeekScrobblePolicy = TrackingSeekScrobblePolicy.NONE,
        private val failure: Throwable? = null,
        private val sends: Boolean = true,
        override val progressRefreshIntervalMs: Long? = null,
    ) : TrackingScrobbler {
        var callCount: Int = 0

        override suspend fun scrobble(
            profileId: Int,
            action: TrackingScrobbleAction,
            event: TrackingScrobbleEvent,
        ): Boolean {
            callCount += 1
            failure?.let { throw it }
            return sends
        }
    }
}
