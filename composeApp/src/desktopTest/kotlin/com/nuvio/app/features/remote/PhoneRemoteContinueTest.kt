package com.nuvio.app.features.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhoneRemoteContinueTest {
    @Test
    fun continueWatchingTravelsAsOneLine() {
        val line = PhoneRemoteLibraryLines.continueLine(
            listOf(
                RemoteResume(
                    type = "series", parentId = "tt0903747", videoId = "tt0903747:2:5", title = "Breaking, Bad; Show",
                    episode = "S2 E5 · Breakage", poster = "https://img/p.jpg", still = null, positionMs = 1_200_000L,
                    durationMs = 2_820_000L, lastSource = "Torrentio 1080p", progressPermille = 425,
                ),
                RemoteResume(
                    type = "movie", parentId = "tt1856101", videoId = "tt1856101", title = "Blade Runner 2049",
                    episode = null, poster = null, still = null, positionMs = 0L, durationMs = 0L, lastSource = null,
                    progressPermille = 300,
                ),
            ),
        )
        assertEquals(
            "CONTINUE series,tt0903747,tt0903747%3A2%3A5,Breaking%2C%20Bad%3B%20Show,S2%20E5%20%C2%B7%20Breakage," +
                "https%3A%2F%2Fimg%2Fp.jpg,-,1200000,2820000,Torrentio%201080p,425;" +
                "movie,tt1856101,tt1856101,Blade%20Runner%202049,-,-,-,0,0,-,300",
            line,
        )
        assertEquals("CONTINUE -", PhoneRemoteLibraryLines.continueLine(emptyList()))
    }

    @Test
    fun theRequestsParse() {
        assertEquals(RemoteMessage.Continue, PhoneRemoteProtocol.parse("CONTINUE"))
        assertEquals(RemoteMessage.Resume("tt0903747", "tt0903747:2:5"), PhoneRemoteProtocol.parse("RESUME tt0903747 tt0903747%3A2%3A5"))
        assertNull(PhoneRemoteProtocol.parse("RESUME tt0903747"))
    }
}

class PhoneRemoteSourcesTest {
    @Test
    fun theSourceListTravelsAsOneLine() {
        val line = PhoneRemoteLibraryLines.sourcesLine(
            RemoteSourceList(
                title = "Severance · S2 E5",
                loading = true,
                sources = listOf(
                    RemoteSource("Torrentio 4K", "Severance.S02E05.2160p.mkv\n💾 12.3 GB 👤 41", "Torrentio", 13_200_000_000L, instant = true, playable = true),
                    RemoteSource("P2P 1080p", null, "Comet", null, instant = false, playable = false),
                ),
            ),
        )
        assertEquals(
            "SOURCES 1 1 Severance%20%C2%B7%20S2%20E5 " +
                "Torrentio%204K,Severance.S02E05.2160p.mkv%0A%F0%9F%92%BE%2012.3%20GB%20%F0%9F%91%A4%2041,Torrentio,13200000000,1,1;" +
                "P2P%201080p,-,Comet,-,0,0",
            line,
        )
        assertEquals("SOURCES 0 0 - -", PhoneRemoteLibraryLines.sourcesLine(null))
        assertEquals(RemoteMessage.PickSource(3), PhoneRemoteProtocol.parse("PICK_SOURCE 3"))
        assertNull(PhoneRemoteProtocol.parse("PICK_SOURCE 0"))
    }
}

class PhoneRemoteSearchTest {
    @Test
    fun searchResultsTravelAsOneLine() {
        val line = PhoneRemoteLibraryLines.searchLine(
            "blade runner",
            loading = false,
            titles = listOf(RemoteTitle("Movies · Cinemeta", "movie", "tt1856101", "Blade Runner 2049", "2017", "https://img/p.jpg", null, watched = true)),
        )
        assertEquals(
            "SEARCH_RESULTS 0 blade%20runner Movies%20%C2%B7%20Cinemeta,movie,tt1856101,Blade%20Runner%202049,2017,https%3A%2F%2Fimg%2Fp.jpg,-,1",
            line,
        )
        assertEquals("SEARCH_RESULTS 1 x -", PhoneRemoteLibraryLines.searchLine("x", loading = true, titles = emptyList()))
        assertEquals(RemoteMessage.Search("blade runner"), PhoneRemoteProtocol.parse("SEARCH blade%20runner"))
        assertNull(PhoneRemoteProtocol.parse("SEARCH %20"))
    }
}

class PhoneRemoteWakeTest {
    @Test
    fun theWakeLineCarriesTheCardsAndBroadcasts() {
        assertEquals(
            "WAKE 001A2B3C4D5E,A0B1C2D3E4F5 192.168.1.255",
            PhoneRemoteProtocol.wakeLine(listOf("001A2B3C4D5E", "A0B1C2D3E4F5"), listOf("192.168.1.255")),
        )
        assertEquals("WAKE - -", PhoneRemoteProtocol.wakeLine(emptyList(), emptyList()))
    }
}

class PhoneRemoteMusicLinesTest {
    @Test
    fun theQueueAndLyricsTravelAsOneLineEach() {
        assertEquals(
            "QUEUE 0 abc,Billie%20Jean,Michael%20Jackson,294,https%3A%2F%2Fimg%2Fc.jpg,0;def,Beat%20It,Michael%20Jackson,258,-,1",
            PhoneRemoteLibraryLines.queueLine(
                0,
                listOf(
                    RemoteQueueTrack("abc", "Billie Jean", "Michael Jackson", 294, "https://img/c.jpg", upcoming = false),
                    RemoteQueueTrack("def", "Beat It", "Michael Jackson", 258, null, upcoming = true),
                ),
            ),
        )
        assertEquals(
            "LYRICS synced Billie%20Jean 1200,She%20was%20more%20like%20a%20beauty%20queen,-;5400,-,-;" +
                "9000,Billie%20Jean,Billie%3A9000%3A9400%7CJean%3A9400%3A10100",
            PhoneRemoteLibraryLines.lyricsLine(
                "synced",
                "Billie Jean",
                listOf(
                    RemoteLyricLine(1200L, "She was more like a beauty queen"),
                    RemoteLyricLine(5400L, ""),
                    RemoteLyricLine(9000L, "Billie Jean", listOf(Triple("Billie", 9000L, 9400L), Triple("Jean", 9400L, 10100L))),
                ),
            ),
        )
        assertEquals("LYRICS none - -", PhoneRemoteLibraryLines.lyricsLine("none", null, emptyList()))
        assertEquals(RemoteMessage.QueueAction("remove", "def"), PhoneRemoteProtocol.parse("QUEUE_REMOVE def"))
        assertEquals(RemoteMessage.Queue, PhoneRemoteProtocol.parse("QUEUE"))
        assertEquals(RemoteMessage.LyricsRequest, PhoneRemoteProtocol.parse("LYRICS"))
    }
}

class PhoneRemoteAlbumTracksTest {
    @Test
    fun anAlbumsSongsTravelAsOneLine() {
        assertEquals(
            "ALBUM_TRACKS MPREb_1 abc,Billie%20Jean,Michael%20Jackson,294,1;def,Gone,Michael%20Jackson,0,0",
            PhoneRemoteLibraryLines.albumTracksLine(
                "MPREb_1",
                listOf(
                    RemoteQueueTrack("abc", "Billie Jean", "Michael Jackson", 294, null, upcoming = true),
                    RemoteQueueTrack("def", "Gone", "Michael Jackson", 0, null, upcoming = false),
                ),
            ),
        )
        assertEquals(RemoteMessage.AlbumTracks("MPREb_1"), PhoneRemoteProtocol.parse("ALBUM_TRACKS MPREb_1"))
        assertEquals(RemoteMessage.PlayTrack("MPREb_1", 3), PhoneRemoteProtocol.parse("PLAY_TRACK MPREb_1 3"))
        assertNull(PhoneRemoteProtocol.parse("PLAY_TRACK MPREb_1 -1"))
    }
}

class PhoneRemoteEpisodesTest {
    @Test
    fun aShowsEpisodesTravelAsOneLine() {
        assertEquals(
            "EPISODES series tt0903747 1 1,1,Pilot,tt0903747%3A1%3A1,https%3A%2F%2Fimg%2Fe.jpg,2008-01-20,1000;2,-,Untitled,x,-,-,250",
            PhoneRemoteLibraryLines.episodesLine(
                "series", "tt0903747", found = true,
                episodes = listOf(
                    RemoteEpisode(1, 1, "Pilot", "tt0903747:1:1", "https://img/e.jpg", "2008-01-20T00:00:00.000Z", 1000),
                    RemoteEpisode(2, null, "Untitled", "x", null, null, 250),
                ),
            ),
        )
        assertEquals(RemoteMessage.Episodes("series", "tt0903747"), PhoneRemoteProtocol.parse("EPISODES series tt0903747"))
        assertEquals(
            RemoteMessage.PlayEpisode("series", "tt0903747", "tt0903747:1:1"),
            PhoneRemoteProtocol.parse("PLAY_EPISODE series tt0903747 tt0903747%3A1%3A1"),
        )
    }
}
