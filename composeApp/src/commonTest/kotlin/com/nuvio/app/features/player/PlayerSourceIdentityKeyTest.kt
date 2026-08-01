package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * The source identity key decides which row is marked "Playing" and which sources failover has
 * already tried.
 *
 * It has to survive a re-fetch of the same source list. It previously did not: it mixed in formatter
 * output and the playback URL, both of which change between fetches for a proxying addon, so the
 * playing row simply stopped being recognised.
 */
class PlayerSourceIdentityKeyTest {

    private fun stream(
        name: String = "2160p",
        subtitle: String? = null,
        filename: String? = "Mulholland.Dr.2001.UHD.BluRay.2160p.DTS-HD.MA.5.1.DV.HEVC.REMUX-FraMeSToR.mkv",
        videoSize: Long? = 93_500_000_000L,
        url: String? = "https://proxy.example/stream?token=abc123",
    ) = StreamItem(
        name = name,
        title = subtitle,
        addonName = "AIOUmbra",
        addonId = "aioumbra",
        url = url,
        behaviorHints = StreamBehaviorHints(filename = filename, videoSize = videoSize),
    )

    @Test
    fun theKeySurvivesAReformattedLabel() {
        // Seeder counts and release age live in the formatted label and drift between fetches.
        val atPlaybackStart = stream(name = "2160p", subtitle = "17651 | 84.8 Mbps | 1706d | Ninja")
        val afterRefetch = stream(name = "2160p", subtitle = "17652 | 84.8 Mbps | 1707d | Ninja")
        assertEquals(atPlaybackStart.playerSourceIdentityKey(), afterRefetch.playerSourceIdentityKey())
    }

    @Test
    fun theKeySurvivesARegeneratedProxyUrl() {
        // AIOStreams-style addons sign the playback URL per request.
        val first = stream(url = "https://proxy.example/stream?token=abc123")
        val second = stream(url = "https://proxy.example/stream?token=zzz999")
        assertEquals(first.playerSourceIdentityKey(), second.playerSourceIdentityKey())
    }

    @Test
    fun differentReleasesStillGetDifferentKeys() {
        val framestor = stream(filename = "Movie.2160p.REMUX-FraMeSToR.mkv", videoSize = 93_500_000_000L)
        val zq = stream(filename = "Movie.2160p.REMUX-ZQ.mkv", videoSize = 96_200_000_000L)
        assertNotEquals(framestor.playerSourceIdentityKey(), zq.playerSourceIdentityKey())
    }

    @Test
    fun theSameReleaseAtADifferentSizeIsADifferentSource() {
        // Two encodes of one release are genuinely different files and must stay distinguishable,
        // or failover would skip the second after the first failed.
        val bigger = stream(videoSize = 103_000_000_000L)
        val smaller = stream(videoSize = 93_500_000_000L)
        assertNotEquals(bigger.playerSourceIdentityKey(), smaller.playerSourceIdentityKey())
    }

    @Test
    fun aStreamWithNoFilenameStillProducesAKey() {
        val bare = stream(filename = null, videoSize = null)
        assertNotNull(bare.playerSourceIdentityKey())
    }

    @Test
    fun filenameMatchingIsCaseInsensitive() {
        val lower = stream(filename = "movie.2160p.remux-framestor.mkv")
        val upper = stream(filename = "Movie.2160p.REMUX-FraMeSToR.mkv")
        assertEquals(lower.playerSourceIdentityKey(), upper.playerSourceIdentityKey())
    }
}
