package com.nuvio.app.features.music

import com.nuvio.app.features.music.home.TopMatchKind
import com.nuvio.app.features.music.home.topMatchKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** fork:music-radio - reading radios, playlists and stations, on answers shaped as YouTube Music sends them. */
class MusicRadioParseTest {
    private fun artistRun(name: String, id: String) =
        """{"text":"$name","navigationEndpoint":{"browseEndpoint":{"browseId":"$id","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}}"""

    private fun albumRun(name: String, id: String) =
        """{"text":"$name","navigationEndpoint":{"browseEndpoint":{"browseId":"$id","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}}}"""

    private val dot = """{"text":" • "}"""

    private fun queueItem(videoId: String, title: String, byline: String, length: String) = """
        {"playlistPanelVideoRenderer":{
          "title":{"runs":[{"text":"$title"}]},
          "longBylineText":{"runs":[$byline]},
          "thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/$videoId/small.jpg","width":60,"height":60},{"url":"https://i.ytimg.com/vi/$videoId/big.jpg","width":544,"height":544}]},
          "lengthText":{"runs":[{"text":"$length"}]},
          "videoId":"$videoId"}}
    """

    private val radioPage = """
        {"contents":{"singleColumnMusicWatchNextResultsRenderer":{"tabbedRenderer":{"watchNextTabbedResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"musicQueueRenderer":{"content":{"playlistPanelRenderer":{
          "title":"Radio",
          "contents":[
            ${queueItem("Z85lxckrtzg", "Billie Jean", "${artistRun("Michael Jackson", "UCoIOOL7QKuBhQHVKL8y7BEQ")},$dot,${albumRun("Thriller", "MPREb_dqWTncCjkSp")},$dot,{\"text\":\"1982\"}", "4:55")},
            ${queueItem("nb8CnIo_-_A", "A Video", "{\"text\":\"Some Channel\"},$dot,{\"text\":\"55M views\"}", "3:10")}
          ],
          "continuations":[{"nextRadioContinuationData":{"continuation":"PAGE2"}}]
        }}}}}}]}}}}}
    """

    @Test
    fun aRadioPageReadsItsTracksArtistsAlbumAndNextPage() {
        val page = assertNotNull(parseRadioPage(radioPage))
        assertEquals("PAGE2", page.continuation)
        assertEquals(listOf("Z85lxckrtzg", "nb8CnIo_-_A"), page.tracks.map { it.videoId })
        val first = page.tracks[0]
        assertEquals("Billie Jean", first.title)
        assertEquals(listOf(MusicArtistRef("Michael Jackson", "UCoIOOL7QKuBhQHVKL8y7BEQ")), first.artists)
        assertEquals("MPREb_dqWTncCjkSp", first.albumBrowseId)
        assertEquals("Thriller", first.albumTitle)
        assertEquals(295, first.durationSeconds)
        assertEquals(1, first.trackNumber)
        assertTrue(first.coverUrl?.contains("big.jpg") == true || first.coverUrl?.contains("small.jpg") == true)
        // A video names its channel without a link.
        assertEquals(listOf(MusicArtistRef("Some Channel")), page.tracks[1].artists)
    }

    @Test
    fun aContinuationPageNumbersOnFromWhereTheLastStopped() {
        val cont = """{"continuationContents":{"playlistPanelContinuation":{"contents":[${queueItem("aaaaaaaaaaa", "Next One", artistRun("X", "UCx"), "2:00")}],
            "continuations":[{"nextRadioContinuationData":{"continuation":"PAGE3"}}]}}}"""
        val page = assertNotNull(parseRadioPage(cont, firstNumber = 26))
        assertEquals(26, page.tracks.single().trackNumber)
        assertEquals("PAGE3", page.continuation)
    }

    @Test
    fun nothingReadableIsNull() {
        assertNull(parseRadioPage("""{"contents":{}}"""))
        assertNull(parseRadioPage("not json"))
    }

    private fun overlay(playlistId: String?): String = if (playlistId == null) "" else
        "\"overlay\":{\"musicItemThumbnailOverlayRenderer\":{\"content\":{\"musicPlayButtonRenderer\":{\"playNavigationEndpoint\":" +
            "{\"watchPlaylistEndpoint\":{\"playlistId\":\"$playlistId\",\"params\":\"wAEB\"}}}}}},"

    private fun searchRow(title: String, line: String, browseId: String, pageType: String, playlistId: String?) = """
        {"musicResponsiveListItemRenderer":{
          "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://example.com/$browseId.jpg","width":120,"height":120}]}}},
          "flexColumns":[
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$title"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$line"}]}}}
          ],
          ${overlay(playlistId)}
          "navigationEndpoint":{"browseEndpoint":{"browseId":"$browseId","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"$pageType"}}}}
        }}
    """

    @Test
    fun searchKeepsPlaylistsAndYouTubeMusicListsButNotPodcasts() {
        val body = """{"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
            {"musicShelfRenderer":{"contents":[
              ${searchRow("TOP 20 Travis best songs", "Playlist • Alessandro Paldo • 1.5M views", "VLPLBZ4_XQHhH71mTvF", "MUSIC_PAGE_TYPE_PLAYLIST", "PLBZ4_XQHhH71mTvF")},
              ${searchRow("Presenting Travis Scott", "Playlist • YouTube Music • 47 songs", "VLRDCLAK5uy_mDOuBN9g8h", "MUSIC_PAGE_TYPE_PLAYLIST", "RDCLAK5uy_mDOuBN9g8h")},
              ${searchRow("Travis Take Two", "Podcast • Travis Take Two", "MPSPPLLmz4M_ibUVy15", "MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE", "PLLmz4M_ibUVy15")}
            ]}}
        ]}}}}]}}}"""
        val found = parseSearchEverything(body)
        assertEquals(listOf("PLBZ4_XQHhH71mTvF", "RDCLAK5uy_mDOuBN9g8h"), found.playlists.map { it.playlistId })
        assertEquals("Playlist • YouTube Music • 47 songs", found.playlists[1].subtitle)
        assertFalse(found.playlists[1].radio, "a YouTube Music list is fixed, read as a page")
    }

    @Test
    fun radioIdsAreMadeAsTheyPlayAndFixedListsAreNot() {
        assertTrue(isRadioPlaylistId("RDAMVMZ85lxckrtzg"))
        assertTrue(isRadioPlaylistId("RDEMabc"))
        assertTrue(isRadioPlaylistId("VLRDAMPLxyz"))
        assertTrue(isRadioPlaylistId("RDTMAK5uy_discover"))
        assertFalse(isRadioPlaylistId("RDCLAK5uy_mDOuBN9g8h"))
        assertFalse(isRadioPlaylistId("PLBZ4_XQHhH71mTvF"))
        assertFalse(isRadioPlaylistId("OLAK5uy_kRLVufSy1X"))
    }

    @Test
    fun aStationTopMatchIsAPlaylistNotAnArtist() {
        val card = """{"contents":{"sectionListRenderer":{"contents":[{"musicCardShelfRenderer":{
            "title":{"runs":[{"text":"Feel-Good Hip Hop","navigationEndpoint":{"browseEndpoint":{"browseId":"VLRDCLAK5uy_mkEwQ","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_PLAYLIST"}}}}}]},
            "subtitle":{"runs":[{"text":"Playlist"},$dot,{"text":"YouTube Music"}]}
        }}]}}}"""
        val top = assertNotNull(parseTopResult(card))
        assertEquals("RDCLAK5uy_mkEwQ", top.playlistId)
        assertEquals(TopMatchKind.Playlist, topMatchKind(top))
    }

    @Test
    fun anArtistTopMatchStaysAnArtistThoughItsButtonsStartLists() {
        val card = """{"contents":{"sectionListRenderer":{"contents":[{"musicCardShelfRenderer":{
            "title":{"runs":[${artistRun("Travis", "UCowh5WX6b70SpXxZvfMQE7Q")}]},
            "subtitle":{"runs":[{"text":"Artist"},$dot,{"text":"1.24M monthly audience"}]},
            "buttons":[{"buttonRenderer":{"command":{"watchPlaylistEndpoint":{"playlistId":"RDAOxyz"}}}}]
        }}]}}}"""
        val top = assertNotNull(parseTopResult(card))
        assertNull(top.playlistId)
        assertEquals(TopMatchKind.Artist, topMatchKind(top))
    }

    @Test
    fun aQueueIsNumberedInOrderWithoutRepeatsOrGreyedOutTracks() {
        fun t(id: String, ok: Boolean = true) = MusicTrack(id, id, emptyList(), 0, 9, ok)
        val queue = listQueue("list:x", "X", "Radio", null, emptyList(), listOf(t("a"), t("b", ok = false), t("a"), t("c")))
        assertEquals(listOf("a", "c"), queue.tracks.map { it.videoId })
        assertEquals(listOf(1, 2), queue.tracks.map { it.trackNumber })
    }
}
