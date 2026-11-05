package com.nuvio.app.features.music

import com.nuvio.app.features.trailer.TrailerPlaybackSource
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MusicQueueTest {
    private fun album(n: Int) = MusicAlbum(
        "MPREb_x", "Album", emptyList(), "2020", "Album", null,
        (1..n).map { MusicTrack("id$it", "Track $it", emptyList(), 200, it, true) }, null,
    )

    private fun playable(t: MusicTrack, mode: MusicPlayMode = MusicPlayMode.Video) =
        MusicResolution.Playable(t, mode, t.videoId, TrailerPlaybackSource(videoUrl = "u"), viaCounterpart = true)

    private fun <T> runSuspend(block: suspend () -> T): T {
        var out: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
        return out!!.getOrThrow()
    }

    private val all: suspend (MusicTrack) -> MusicResolution = { playable(it) }

    @Test
    fun playsInAlbumOrderThenEnds() {
        val q = MusicQueue(album(3), MusicPlayMode.Video)
        assertEquals("id1", q.current?.videoId)
        assertEquals(listOf("id2", "id3"), q.upNext.map { it.videoId })
        assertEquals("id2", runSuspend { q.advance(all) }?.playable?.videoId)
        assertEquals("id3", runSuspend { q.advance(all) }?.playable?.videoId)
        assertNull(runSuspend { q.advance(all) })
        assertEquals("id3", q.current?.videoId)
    }

    @Test
    fun skipsTracksWithoutAVideoAndReportsThem() {
        val q = MusicQueue(album(4), MusicPlayMode.Video)
        val next = assertNotNull(runSuspend {
            q.advance { t -> if (t.videoId == "id2") MusicResolution.NoVideo(t) else playable(t) }
        })
        assertEquals(2, next.index)
        assertEquals(listOf("id2"), next.skipped.map { it.videoId })
        assertEquals("id3", q.current?.videoId)
    }

    @Test
    fun retreatGoesBackAndSkipsToo() {
        val q = MusicQueue(album(4), MusicPlayMode.Video, startIndex = 3)
        val back = assertNotNull(runSuspend {
            q.retreat { t -> if (t.videoId == "id3") MusicResolution.NoVideo(t) else playable(t) }
        })
        assertEquals("id2", back.playable.videoId)
        assertEquals(1, back.index)
        assertNull(runSuspend { MusicQueue(album(2), MusicPlayMode.Audio).retreat(all) })
    }

    @Test
    fun jumpToPlaysThatTrack() {
        val q = MusicQueue(album(5), MusicPlayMode.Audio)
        assertEquals("id4", runSuspend { q.jumpTo(3, all) }?.playable?.videoId)
        assertEquals("id4", q.current?.videoId)
        assertEquals(listOf("id5"), q.upNext.map { it.videoId })
    }

    @Test
    fun shuffleKeepsTheCurrentSongAndPlaysEveryTrackOnce() {
        val q = MusicQueue(album(6), MusicPlayMode.Audio, startIndex = 2)
        q.setShuffled(true)
        assertEquals("id3", q.current?.videoId)
        val played = ArrayList<String>()
        played += q.current!!.videoId
        while (true) played += runSuspend { q.advance(all) }?.playable?.videoId ?: break
        assertEquals(6, played.size)
        assertEquals(6, played.toSet().size)
        // Turning shuffle off keeps whatever is playing at that moment (here, the last one played).
        val last = q.current!!.videoId
        q.setShuffled(false)
        assertEquals(last, q.current?.videoId)
    }

    @Test
    fun sameSeedGivesTheSameShuffle() {
        fun order(seed: Long): List<String> {
            val q = MusicQueue(album(8), MusicPlayMode.Audio, shuffled = true, seed = seed)
            return listOf(q.current!!.videoId) + q.upNext.map { it.videoId }
        }
        assertEquals(order(7), order(7))
        assertTrue(order(7) != order(8))
    }

    @Test
    fun modeSwitchKeepsThePositionAndFailsSoftly() {
        val q = MusicQueue(album(3), MusicPlayMode.Audio)
        val ok = runSuspend { q.switchMode(MusicPlayMode.Video, 61_500L) { t, m -> playable(t, m) } }
        val switched = ok as MusicModeSwitch.Switched
        assertEquals(61_500L, switched.startPositionMs)
        assertEquals(MusicPlayMode.Video, q.mode)

        val none = runSuspend { q.switchMode(MusicPlayMode.Audio, 10_000L) { t, _ -> MusicResolution.NoVideo(t) } }
        assertEquals(MusicModeSwitch.Unavailable, none)
        assertEquals(MusicPlayMode.Video, q.mode)
        assertEquals(MusicModeSwitch.Unavailable, runSuspend { q.switchMode(MusicPlayMode.Video, 0L) { t, m -> playable(t, m) } })
    }

    @Test
    fun emptyAlbumHasNothing() {
        val q = MusicQueue(album(0), MusicPlayMode.Audio)
        assertNull(q.current)
        assertNull(runSuspend { q.advance(all) })
    }

    @Test
    fun dropUpNextEndsTheQueueAfterTheTrackPlaying() {
        val q = MusicQueue(album(5), MusicPlayMode.Audio, startIndex = 1)
        assertEquals(listOf("id3", "id4", "id5"), q.dropUpNext().map { it.videoId })
        assertTrue(q.upNext.isEmpty())
        assertEquals("id2", q.current?.videoId)
        assertNull(runSuspend { q.advance(all) })
        // Back still reaches what was played before.
        assertEquals("id1", runSuspend { q.retreat(all) }?.playable?.videoId)
    }

    @Test
    fun shuffleAfterDropUpNextNeverBringsTheDroppedTracksBack() {
        val q = MusicQueue(album(6), MusicPlayMode.Audio, startIndex = 2)
        q.dropUpNext()
        q.setShuffled(true)
        assertEquals("id3", q.current?.videoId)
        assertTrue(q.ordered.all { it.videoId in setOf("id1", "id2", "id3") })
        q.setShuffled(false)
        assertEquals(listOf("id1", "id2", "id3"), q.ordered.map { it.videoId })
        assertEquals("id3", q.current?.videoId)
    }
    /** The album with every track but those in [missing] pointed at the addon (ids "nebula:..."), as NebulaMusicSource.prepare does. */
    private fun addonCopy(n: Int, missing: Set<Int> = emptySet()) = album(n).let { a ->
        a.copy(tracks = a.tracks.mapIndexed { i, t -> if (i in missing) t.copy(isAvailable = false) else t.copy(videoId = "nebula:${t.videoId}") })
    }

    @Test
    fun swapUpcomingLeavesTheTrackPlayingAndSwapsTheRest() {
        val q = MusicQueue(album(4), MusicPlayMode.Audio, startIndex = 1)
        assertEquals(2, q.swapUpcoming(addonCopy(4)))
        assertEquals("id2", q.current?.videoId)
        assertEquals(listOf("nebula:id3", "nebula:id4"), q.upNext.map { it.videoId })
        // What was already played is as it was, and the album keeps its length and order.
        assertEquals(listOf("id1", "id2", "nebula:id3", "nebula:id4"), q.album.tracks.map { it.videoId })
        assertEquals("nebula:id3", runSuspend { q.advance(all) }?.playable?.videoId)
    }

    @Test
    fun swapUpcomingKeepsATrackTheAddonLacks() {
        val q = MusicQueue(album(4), MusicPlayMode.Audio)
        assertEquals(2, q.swapUpcoming(addonCopy(4, missing = setOf(2))))
        assertEquals(listOf("nebula:id2", "id3", "nebula:id4"), q.upNext.map { it.videoId })
        // Swapped once: the same copy again changes nothing.
        assertEquals(0, q.swapUpcoming(addonCopy(4, missing = setOf(2))))
    }

    @Test
    fun swapUpcomingFollowsTheShuffledOrderAndLeavesDroppedTracksAlone() {
        val q = MusicQueue(album(6), MusicPlayMode.Audio, shuffled = true, seed = 7L, startIndex = 2)
        val coming = q.upNext.map { it.videoId }
        assertEquals(coming.size, q.swapUpcoming(addonCopy(6)))
        assertEquals(coming.map { "nebula:$it" }, q.upNext.map { it.videoId })
        assertEquals("id3", q.current?.videoId)

        val dropped = MusicQueue(album(5), MusicPlayMode.Audio, startIndex = 1)
        dropped.dropUpNext()
        assertEquals(0, dropped.swapUpcoming(addonCopy(5)))
    }
}
