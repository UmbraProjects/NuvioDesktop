package com.nuvio.app.features.music

import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.trailer.TrailerPlaybackSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MusicPlayerTest {
    private fun album(n: Int) = MusicAlbum(
        "MPREb_x", "Album", listOf(MusicArtistRef("Artist")), "2020", "Album", null,
        (1..n).map { MusicTrack("id$it", "Track $it", emptyList(), 200, it, true) }, null,
    )

    private class Fake(
        val noVideo: Set<String> = emptySet(),
        val fallbackFor: Set<String> = emptySet(),
    ) {
        val calls = ArrayList<String>()
        val resolver: MusicTrackResolver = { track, _, mode ->
            calls += "${track.videoId}|$mode"
            when {
                mode == MusicPlayMode.Video && track.videoId in noVideo -> MusicResolution.NoVideo(track)
                else -> MusicResolution.Playable(
                    track, mode, track.videoId,
                    TrailerPlaybackSource(
                        videoUrl = "v-${track.videoId}-$mode",
                        fallback = if (track.videoId in fallbackFor) TrailerPlaybackSource(videoUrl = "fb-${track.videoId}") else null,
                    ),
                    viaCounterpart = mode == MusicPlayMode.Video,
                )
            }
        }
    }

    private fun player(fake: Fake): MusicPlayer =
        MusicPlayer(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), fake.resolver, now = { 0L })

    private fun MusicPlayer.playing(positionMs: Long, durationMs: Long = 200_000L) =
        onSnapshot(PlayerPlaybackSnapshot(isLoading = false, isPlaying = true, durationMs = durationMs, positionMs = positionMs))

    @Test
    fun playStartsTheTrackAndResolvesTheNextOneAhead() {
        val fake = Fake()
        val p = player(fake)
        p.play(album(3), trackIndex = 0)
        val s = p.state.value
        assertEquals("id1", s.track?.videoId)
        assertEquals("v-id1-Audio", s.source?.videoUrl)
        assertTrue(s.hasNext)
        assertTrue(s.isLoading)
        // The second track was resolved before anyone asked for it.
        assertTrue("id2|Audio" in fake.calls)
        assertFalse("id3|Audio" in fake.calls)
    }

    @Test
    fun endingMovesOnUsingTheResolutionAlreadyMade() {
        val fake = Fake()
        val p = player(fake)
        p.play(album(3))
        val firstLoad = p.state.value.loadId
        p.playing(positionMs = 199_000L)
        p.onSurfaceEnded()
        val s = p.state.value
        assertEquals("id2", s.track?.videoId)
        assertNotEquals(firstLoad, s.loadId)
        assertEquals(0L, s.startPositionMs)
        assertEquals(1, fake.calls.count { it == "id2|Audio" })
        // And the one after it is now being resolved.
        assertTrue("id3|Audio" in fake.calls)
    }

    @Test
    fun theLastTrackEndingStopsThePlayer() {
        val p = player(Fake())
        p.play(album(2), trackIndex = 1)
        p.playing(199_000L)
        p.onSurfaceEnded()
        assertFalse(p.state.value.isActive)
        assertNull(p.state.value.source)
    }

    @Test
    fun videoModeSkipsTracksWithNoVideoAndSaysSo() {
        val fake = Fake(noVideo = setOf("id2"))
        val p = player(fake)
        p.play(album(3), mode = MusicPlayMode.Video)
        p.playing(199_000L)
        p.onSurfaceEnded()
        val s = p.state.value
        assertEquals("id3", s.track?.videoId)
        assertEquals(2, s.trackIndex)
        assertNotNull(s.notice)
        assertTrue(s.notice!!.contains("Skipped 1"))
    }

    @Test
    fun switchingModeKeepsTheSongAndThePosition() {
        val p = player(Fake())
        p.play(album(3))
        p.playing(positionMs = 61_000L)
        p.switchMode(MusicPlayMode.Video)
        val s = p.state.value
        assertEquals(MusicPlayMode.Video, s.mode)
        assertEquals("id1", s.track?.videoId)
        assertEquals("v-id1-Video", s.source?.videoUrl)
        assertEquals(61_000L, s.startPositionMs)
        // The next track is now looked up for video, so the following advance stays in video mode.
        p.playing(199_000L)
        p.onSurfaceEnded()
        assertEquals("v-id2-Video", p.state.value.source?.videoUrl)
    }

    @Test
    fun switchingToVideoWithNoVideoLeavesTheSongPlaying() {
        val p = player(Fake(noVideo = setOf("id1")))
        p.play(album(2))
        val before = p.state.value
        p.playing(30_000L)
        p.switchMode(MusicPlayMode.Video)
        val s = p.state.value
        assertEquals(MusicPlayMode.Audio, s.mode)
        assertEquals(before.loadId, s.loadId)
        assertEquals(before.source, s.source)
        assertEquals("No video for this track", s.notice)
    }

    @Test
    fun aStreamThatFailsToOpenTriesItsFallbackThenSkips() {
        val p = player(Fake(fallbackFor = setOf("id1")))
        p.play(album(3))
        p.playing(0L)
        p.onSurfaceError()
        assertEquals("fb-id1", p.state.value.source?.videoUrl)
        assertEquals("id1", p.state.value.track?.videoId)
        // No fallback left: the album moves on rather than stalling.
        p.onSurfaceError()
        assertEquals("id2", p.state.value.track?.videoId)
        assertNotNull(p.state.value.notice)
    }

    @Test
    fun aStreamCutOffEarlyContinuesOnItsFallbackFromTheSamePlace() {
        val p = player(Fake(fallbackFor = setOf("id1")))
        p.play(album(3))
        p.playing(positionMs = 40_000L)
        p.onSurfaceEnded()
        val s = p.state.value
        assertEquals("id1", s.track?.videoId)
        assertEquals("fb-id1", s.source?.videoUrl)
        assertEquals(40_000L, s.startPositionMs)
    }

    @Test
    fun previousRestartsASongThatHasBeenPlayingAndOtherwiseGoesBack() {
        val p = player(Fake())
        p.play(album(3), trackIndex = 1)
        p.playing(positionMs = 30_000L)
        p.previous()
        assertEquals("id2", p.state.value.track?.videoId)
        assertEquals(0L, p.seekRequests.value?.positionMs)
        p.playing(positionMs = 1_000L)
        p.previous()
        assertEquals("id1", p.state.value.track?.videoId)
    }

    @Test
    fun pauseAndResumeOnlyChangeWhatIsWanted() {
        val p = player(Fake())
        p.play(album(2))
        val load = p.state.value.loadId
        p.togglePlayback()
        assertFalse(p.state.value.playWhenReady)
        assertEquals(load, p.state.value.loadId)
        p.togglePlayback()
        assertTrue(p.state.value.playWhenReady)
    }

    @Test
    fun anAlbumNothingInItCanPlayStartsNothing() {
        val fake = Fake(noVideo = setOf("id1", "id2"))
        val p = player(fake)
        p.play(album(2), mode = MusicPlayMode.Video)
        assertFalse(p.state.value.isActive)
        assertNotNull(p.state.value.notice)
    }

    @Test
    fun formatsTimes() {
        assertEquals("0:00", formatMusicTime(-5))
        assertEquals("3:07", formatMusicTime(187_000))
        assertEquals("1:02:09", formatMusicTime(3_729_000))
    }
}
