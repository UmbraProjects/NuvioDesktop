package com.nuvio.app.features.player

import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleDispatch
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import com.nuvio.app.features.tracking.buildTrackingMediaReference
import com.nuvio.app.features.watchprogress.WatchProgressPlaybackSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExternalPlaybackTrackingSessionTest {
    private data class Scrobble(val action: TrackingScrobbleAction, val percent: Double, val paused: Boolean, val viaSeek: Boolean)

    private class FakeSink(private val knownDuration: Long? = null) : ExternalPlaybackSink {
        val scrobbles = mutableListOf<Scrobble>()
        val writes = mutableListOf<PlayerPlaybackSnapshot>()

        override suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent, viaSeek: Boolean): TrackingScrobbleDispatch {
            scrobbles += Scrobble(action, event.progressPercent, event.isPauseRatherThanStop, viaSeek)
            return TrackingScrobbleDispatch(sentCount = 1)
        }

        override suspend fun refresh(event: TrackingScrobbleEvent) = Unit

        override fun writeProgress(session: WatchProgressPlaybackSession, snapshot: PlayerPlaybackSnapshot, syncRemote: Boolean) {
            writes += snapshot
        }

        override fun knownDurationMs(videoId: String): Long? = knownDuration
    }

    private var now = 1_000_000L

    private fun session(sink: FakeSink, resumeMs: Long = 0L) = ExternalPlaybackTrackingSession(
        tracking = ExternalPlaybackTracking(
            session = WatchProgressPlaybackSession(
                contentType = "movie",
                parentMetaId = "tt0111161",
                parentMetaType = "movie",
                videoId = "tt0111161",
                title = "The Shawshank Redemption",
            ),
            media = buildTrackingMediaReference(
                contentType = "movie",
                parentMetaId = "tt0111161",
                videoId = "tt0111161",
                title = "The Shawshank Redemption",
            ),
            resumePositionMs = resumeMs,
        ),
        sink = sink,
        // Unconfined runs each queued event on the feeding thread, so assertions see it at once.
        scope = CoroutineScope(Dispatchers.Unconfined),
        clock = { now },
    )

    private fun ExternalPlaybackTrackingSession.feed(positionMs: Long, paused: Boolean = false, advanceMs: Long = 2_000L) {
        now += advanceMs
        onSample(ExternalPlaybackSample(positionMs = positionMs, durationMs = 100_000L * 60, isPaused = paused))
    }

    @Test
    fun `playing then pausing sends a start and a pause-flavoured stop`() {
        val sink = FakeSink()
        val session = session(sink)
        session.feed(60_000L)
        session.feed(62_000L)
        session.feed(64_000L, paused = true)

        assertEquals(listOf(TrackingScrobbleAction.START, TrackingScrobbleAction.STOP), sink.scrobbles.map { it.action })
        assertTrue(sink.scrobbles[1].paused)
    }

    @Test
    fun `waits for the resume seek before starting`() {
        val sink = FakeSink()
        val session = session(sink, resumeMs = 3_000_000L)
        session.feed(0L)
        session.feed(1_000L)
        assertTrue(sink.scrobbles.isEmpty())

        session.feed(3_000_000L)
        assertEquals(TrackingScrobbleAction.START, sink.scrobbles.single().action)
        assertEquals(50.0, sink.scrobbles.single().percent, 0.01)
    }

    @Test
    fun `a jump far from the clock is reported as a seek stop and restart`() {
        val sink = FakeSink()
        val session = session(sink)
        session.feed(60_000L)
        session.feed(1_800_000L)

        assertEquals(
            listOf(
                Triple(TrackingScrobbleAction.START, 1L, false),
                Triple(TrackingScrobbleAction.STOP, 30L, true),
                Triple(TrackingScrobbleAction.START, 30L, true),
            ),
            sink.scrobbles.map { Triple(it.action, kotlin.math.round(it.percent).toLong(), it.viaSeek) },
        )
    }

    @Test
    fun `closing near the end sends a completion and records the end`() {
        val sink = FakeSink()
        val session = session(sink)
        session.feed(5_700_000L)
        session.feed(5_960_000L, advanceMs = 260_000L)
        session.onEnded()

        val stop = sink.scrobbles.last()
        assertEquals(TrackingScrobbleAction.STOP, stop.action)
        assertTrue(!stop.paused && stop.percent > 99.0)
        assertTrue(sink.writes.last().isEnded)
    }

    @Test
    fun `unobserved playback estimates the stop from elapsed time`() {
        val sink = FakeSink(knownDuration = 6_000_000L)
        val session = session(sink, resumeMs = 600_000L)
        session.onUnobservedPlaybackStarted()
        session.onUnobservedPlaybackEnded(elapsedMs = 2_400_000L)

        assertEquals(TrackingScrobbleAction.START, sink.scrobbles.first().action)
        assertEquals(10.0, sink.scrobbles.first().percent, 0.01)
        assertEquals(TrackingScrobbleAction.STOP, sink.scrobbles.last().action)
        assertEquals(50.0, sink.scrobbles.last().percent, 0.01)
        assertEquals(3_000_000L, sink.writes.last().positionMs)
    }
}
