package com.nuvio.app.features.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fixtures are cut down from YouTube Music responses captured on 2026-10-29 ("take me to church"
 * and "espresso"): the unfiltered search's top card and a watch-next byline.
 */
class MusicSearchRankingTest {
    private val hozier = MusicArtistRef("Hozier", "UChozier")

    private val topCardSong = """
    {"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
      {"musicCardShelfRenderer":{
        "title":{"runs":[{"text":"Take Me To Church","navigationEndpoint":{"watchEndpoint":{"videoId":"JKlYOUfviXM"}}}]},
        "subtitle":{"runs":[{"text":"Song"},{"text":" • "},
          {"text":"Hozier","navigationEndpoint":{"browseEndpoint":{"browseId":"UChozier"}}},
          {"text":" • "},{"text":"4:02"}]}}}
    ]}}}}]}}}
    """

    private val topCardArtist = """
    {"contents":{"sectionListRenderer":{"contents":[
      {"musicCardShelfRenderer":{
        "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img/hozier"}]}}},
        "title":{"runs":[{"text":"Hozier","navigationEndpoint":{"browseEndpoint":{"browseId":"UChozier"}}}]},
        "subtitle":{"runs":[{"text":"Artist"},{"text":" • "},{"text":"36.4M monthly audience"}]}}}
    ]}}}
    """

    private val watchNext = """
    {"contents":{"singleColumnMusicWatchNextResultsRenderer":{"tabbedRenderer":{"watchNextTabbedResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"musicQueueRenderer":{"content":{"playlistPanelRenderer":{"contents":[
      {"playlistPanelVideoRenderer":{"videoId":"JKlYOUfviXM",
        "thumbnail":{"thumbnails":[{"url":"https://img/hozier-album"}]},
        "longBylineText":{"runs":[
          {"text":"Hozier","navigationEndpoint":{"browseEndpoint":{"browseId":"UChozier"}}},
          {"text":" • "},
          {"text":"Hozier","navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_hozier"}}},
          {"text":" • "},{"text":"2014"}]}}}
    ]}}}}}}]}}}}}
    """

    private fun album(id: String, title: String, artist: String, artistId: String? = null) =
        MusicAlbumSummary(id, title, listOf(MusicArtistRef(artist, artistId)), "2015", "Single", null)

    @Test
    fun topCardReadsASongWithItsArtistAndVideo() {
        val top = parseTopResult(topCardSong)!!
        assertEquals("Song", top.kind)
        assertEquals(listOf(hozier), top.artists)
        assertEquals("JKlYOUfviXM", top.videoId)
        assertNull(top.albumBrowseId)
    }

    @Test
    fun topCardReadsAnArtist() {
        val top = parseTopResult(topCardArtist)!!
        assertEquals("Artist", top.kind)
        assertEquals(listOf(hozier), top.artists)
        assertEquals("https://img/hozier", top.thumbnailUrl)
    }

    @Test
    fun noTopCardIsNull() {
        assertNull(parseTopResult("""{"contents":{}}"""))
    }

    @Test
    fun trackAlbumComesFromTheByline() {
        val found = parseTrackAlbum(watchNext, "JKlYOUfviXM")!!
        assertEquals("MPREb_hozier", found.browseId)
        assertEquals("Hozier", found.title)
        assertEquals("2014", found.year)
        assertEquals(listOf(hozier), found.artists)
        assertNull(parseTrackAlbum(watchNext, "someOtherId"))
    }

    @Test
    fun theTopSongsAlbumLeadsAndCoversSink() {
        val results = listOf(
            album("MPREb_1", "Take Me to Church", "Nenei"),
            album("MPREb_2", "Take me to church (Originally performed by Hozier)", "The Hozier Cover Band"),
            album("MPREb_3", "Take Me to Church: Tribute to Hozier", "Will Luz"),
            album("MPREb_4", "Take Me To Church", "Alhanii"),
        )
        val top = parseTopResult(topCardSong)
        val ranked = rankAlbumResults(results, top, topAlbum = parseTrackAlbum(watchNext, "JKlYOUfviXM"))
        assertEquals(listOf("MPREb_hozier", "MPREb_1", "MPREb_4", "MPREb_2", "MPREb_3"), ranked.map { it.browseId })
        // The ladder offers read the first result's artist: now Hozier, not a tribute act.
        assertEquals("Hozier", ranked.first().artists.first().name)
    }

    @Test
    fun theTopArtistsReleasesComeBeforeOthers() {
        val results = listOf(
            album("MPREb_1", "Espresso", "Sound Tower"),
            album("MPREb_2", "Espresso", "Sabrina Carpenter", "UCsabrina"),
        )
        val top = MusicTopResult("Espresso", "Song", listOf(MusicArtistRef("Sabrina Carpenter", "UCsabrina")), null, null)
        assertEquals(listOf("MPREb_2", "MPREb_1"), rankAlbumResults(results, top).map { it.browseId })
    }

    @Test
    fun withNoTopResultOnlyCoversMove() {
        val results = listOf(album("MPREb_1", "Karaoke Hits", "X"), album("MPREb_2", "Real Album", "Y"))
        assertEquals(listOf("MPREb_2", "MPREb_1"), rankAlbumResults(results, null).map { it.browseId })
    }

    @Test
    fun coverMarkersMatchPhrasesNotRealTitles() {
        assertTrue(looksLikeCover("Take Me to Church (Originally Performed By Hozier) [Karaoke Version]"))
        assertTrue(looksLikeCover("Espresso (sped up)"))
        assertTrue(looksLikeCover("Hits (Cover Version)"))
        assertFalse(looksLikeCover("Cover Me"))
        assertFalse(looksLikeCover("Short n' Sweet"))
    }

    @Test
    fun topArtistLeadsArtistResults() {
        val results = listOf(MusicArtistSummary("UCother", "Take me to church", null))
        val top = parseTopResult(topCardSong)
        val extra = listOf(MusicArtistSummary("UChozier", "Hozier", "https://img/h"))
        assertEquals(listOf("UChozier", "UCother"), rankArtistResults(results, top, extra).map { it.browseId })
        assertEquals("https://img/h", rankArtistResults(results, top, extra).first().thumbnailUrl)
    }
}
