package com.nuvio.app.features.iptv

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** fork:live-tv-on-now - a merged channel's other copies as fallbacks, and Start over's recording before the live URLs. */
class IptvMergedPlaybackTest {

    private val playlist = IptvPlaylist(
        id = "merged-p", type = IptvPlaylistType.Xtream, name = "Home",
        server = "http://host:8080", username = "user", password = "pass",
    )
    private val fhd = IptvChannel(uid = 1, key = "11", num = 1, name = "BBC One FHD", categoryId = "c", streamId = "11", archiveDays = 2)
    private val sd = IptvChannel(uid = 2, key = "12", num = 2, name = "BBC One SD", categoryId = "c", streamId = "12")
    private val merged = IptvMergedChannel(listOf(fhd, sd))
    private val alternates = mapOf(fhd.uid to listOf(sd))

    @AfterTest
    fun clear() = IptvPlaybackSession.clear()

    @Test
    fun `a copy that fails hands over to the next copy`() {
        val launch = assertNotNull(IptvPlaybackSession.launchFor(playlist, fhd, alternates = alternates))
        assertEquals("http://host:8080/live/user/pass/11.ts", launch.sourceUrl)
        assertTrue(IptvPlaybackSession.hasAlternates())
        val urls = generateSequence(launch.sourceUrl) { IptvPlaybackSession.nextCandidateAfter(it)?.url }.toList()
        assertEquals(
            listOf(
                "http://host:8080/live/user/pass/11.ts",
                "http://host:8080/live/user/pass/11.m3u8",
                "http://host:8080/live/user/pass/12.ts",
                "http://host:8080/live/user/pass/12.m3u8",
            ),
            urls,
        )
    }

    @Test
    fun `a channel with one copy has nothing to hand over to`() {
        assertNotNull(IptvPlaybackSession.launchFor(playlist, sd))
        assertFalse(IptvPlaybackSession.hasAlternates())
    }

    @Test
    fun `start over plays the recording, then joins the channel live`() {
        val start = 1_800_000_000_000L
        val programme = IptvEpgEntry("Paddington 2", start, start + 105 * 60_000L)
        val launch = assertNotNull(IptvPlaybackSession.launchCatchup(playlist, merged, programme, alternates = alternates))
        assertTrue(launch.sourceUrl.startsWith("http://host:8080/timeshift/user/pass/105/"), launch.sourceUrl)
        assertTrue(launch.sourceUrl.endsWith("/11.ts"), launch.sourceUrl)
        assertEquals("Paddington 2", launch.title)
        assertTrue(IptvPlaybackSession.isIptvUrl(launch.sourceUrl))
        assertEquals(programme, IptvPlaybackSession.active.value?.catchup)

        assertEquals("http://host:8080/live/user/pass/11.ts", IptvPlaybackSession.finishCatchup())
        assertNull(IptvPlaybackSession.active.value?.catchup)
        assertNull(IptvPlaybackSession.finishCatchup(), "only once")
    }

    @Test
    fun `a recording that will not open falls back to live`() {
        val start = 1_800_000_000_000L
        val launch = assertNotNull(IptvPlaybackSession.launchCatchup(playlist, merged, IptvEpgEntry("News", start, start + 30 * 60_000L)))
        val next = assertNotNull(IptvPlaybackSession.nextCandidateAfter(launch.sourceUrl))
        assertEquals("http://host:8080/live/user/pass/11.ts", next.url)
        assertNull(IptvPlaybackSession.active.value?.catchup)
    }

    @Test
    fun `no recording, no start over`() {
        assertNull(IptvPlaybackSession.launchCatchup(playlist, IptvMergedChannel(listOf(sd)), IptvEpgEntry("News", 1L, 2L)))
        assertNull(IptvPlaybackSession.launchCatchup(playlist.copy(type = IptvPlaylistType.M3u), merged, IptvEpgEntry("News", 1L, 2L)))
    }
}
