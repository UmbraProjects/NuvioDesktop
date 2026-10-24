package com.nuvio.app.features.music

import com.nuvio.app.features.trailer.TrailerPlaybackSource
import com.nuvio.app.features.trailer.YouTubeSearchHit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MusicResolutionTest {
    private fun hit(id: String, title: String, channel: String = "Daft Punk", seconds: Int = 248, verified: Boolean = false, views: Long = 1_000_000) =
        YouTubeSearchHit(id, title, channel, verified, seconds, views)

    private fun rank(hits: List<YouTubeSearchHit>, title: String = "Get Lucky (feat. Pharrell Williams)", seconds: Int = 248) =
        rankMusicVideoHits(hits, title, "Daft Punk", seconds).map { it.videoId }

    @Test
    fun coreTitleDropsFeaturesAndRemasterSuffixes() {
        assertEquals("Get Lucky", coreTrackTitle("Get Lucky (feat. Pharrell Williams & Nile Rodgers)"))
        assertEquals("Instant Crush", coreTrackTitle("Instant Crush - 2013 Remaster"))
        assertEquals("One More Time", coreTrackTitle("One More Time [Radio Edit]"))
    }

    @Test
    fun officialVideoBeatsLyricsLiveAndAudio() {
        val ranked = rank(
            listOf(
                hit("lyrics", "Daft Punk - Get Lucky (Lyrics)"),
                hit("live", "Daft Punk - Get Lucky (Live at Coachella)"),
                hit("audio", "Daft Punk - Get Lucky (Official Audio)"),
                hit("topic", "Get Lucky", channel = "Daft Punk - Topic"),
                hit("video", "Daft Punk - Get Lucky (Official Video) ft. Pharrell Williams", channel = "Daft Punk", verified = true),
            ),
        )
        assertEquals(listOf("video", "topic"), ranked)
    }

    @Test
    fun wrongLengthWrongArtistAndWrongSongAreRejected() {
        val ranked = rank(
            listOf(
                hit("long", "Daft Punk - Get Lucky (Official Video)", seconds = 600),
                hit("other-artist", "Get Lucky (Official Video)", channel = "Someone Else"),
                hit("other-song", "Daft Punk - Lose Yourself to Dance (Official Video)"),
                hit("ok", "Daft Punk - Get Lucky (Official Video)", seconds = 252),
            ),
        )
        assertEquals(listOf("ok"), ranked)
    }

    @Test
    fun vevoChannelsMatchTheArtistWithoutSpaces() {
        val ranked = rankMusicVideoHits(
            listOf(hit("v", "Get Lucky (Official Video)", channel = "DaftPunkVEVO")),
            "Get Lucky", "Daft Punk", 248,
        )
        assertEquals(listOf("v"), ranked.map { it.videoId })
    }

    @Test
    fun aWordInTheTracksOwnTitleIsNotUnwanted() {
        val ranked = rankMusicVideoHits(
            listOf(hit("v", "Daft Punk - Live Forever (Official Video)", seconds = 200)),
            "Live Forever", "Daft Punk", 200,
        )
        assertEquals(listOf("v"), ranked.map { it.videoId })
    }

    private fun track(n: Int, available: Boolean = true) =
        MusicTrack("id$n", "Track $n", emptyList(), 200, n, available)

    private fun playable(t: MusicTrack) =
        MusicResolution.Playable(t, MusicPlayMode.Video, t.videoId, TrailerPlaybackSource(videoUrl = "u"), viaCounterpart = true)

    private fun <T> runSuspend(block: suspend () -> T): T {
        var out: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
        return out!!.getOrThrow()
    }

    @Test
    fun albumSkipsTracksWithoutAVideoAndGreyedOnesWithoutAsking() {
        val tracks = listOf(track(1), track(2, available = false), track(3), track(4))
        val asked = ArrayList<String>()
        val next = runSuspend {
            findNextPlayable(tracks, from = 0) { t ->
                asked += t.videoId
                if (t.videoId == "id1") MusicResolution.NoVideo(t) else playable(t)
            }
        }
        val found = assertNotNull(next)
        assertEquals(2, found.index)
        assertEquals(listOf("id1", "id2"), found.skipped.map { it.videoId })
        assertEquals(listOf("id1", "id3"), asked)
    }

    @Test
    fun failedStreamsAreSkippedToo() {
        val tracks = listOf(track(1), track(2))
        val found = runSuspend {
            findNextPlayable(tracks, from = 0) { t ->
                if (t.videoId == "id1") MusicResolution.Failed(t, "x") else playable(t)
            }
        }
        assertEquals(1, assertNotNull(found).index)
    }

    @Test
    fun nothingLeftGivesNull() {
        val tracks = listOf(track(1), track(2))
        assertNull(runSuspend { findNextPlayable(tracks, from = 0) { MusicResolution.NoVideo(it) } })
        assertNull(runSuspend { findNextPlayable(tracks, from = 5) { playable(it) } })
        assertTrue(tracks.isNotEmpty())
    }
}
