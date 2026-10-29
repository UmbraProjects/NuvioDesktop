package com.nuvio.app.features.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * These fixtures are hand-built to the shape YouTube Music's responses have (as ytmusicapi reads
 * them), not captured from the live service. Phase 0 should add real saved responses beside them;
 * until then they pin the parsers' behaviour, including what happens when the shape is wrong.
 */
class MusicParsersTest {
    private val albumSearch = """
    {"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
      {"musicShelfRenderer":{"contents":[
        {"musicResponsiveListItemRenderer":{
          "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img/small","width":60},{"url":"https://img/large","width":226}]}}},
          "navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_abc123"}},
          "flexColumns":[
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Random Access Memories"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
              {"text":"Album"},{"text":" • "},
              {"text":"Daft Punk","navigationEndpoint":{"browseEndpoint":{"browseId":"UCartist1"}}},
              {"text":" • "},{"text":"2013"}]}}}
          ]}},
        {"musicResponsiveListItemRenderer":{
          "flexColumns":[
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Some Song"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song"}]}}}
          ]}}
      ]}}
    ]}}}}]}}}
    """

    @Test
    fun albumSearchReadsTitleArtistYearAndLargestThumbnail() {
        val albums = parseAlbumSearch(albumSearch)
        assertEquals(1, albums.size)
        val a = albums.single()
        assertEquals("MPREb_abc123", a.browseId)
        assertEquals("Random Access Memories", a.title)
        assertEquals("Album", a.kind)
        assertEquals("2013", a.year)
        assertEquals(listOf(MusicArtistRef("Daft Punk", "UCartist1")), a.artists)
        assertEquals("https://img/large", a.thumbnailUrl)
    }

    @Test
    fun albumSearchSkipsNonAlbumsAndSurvivesGarbage() {
        assertTrue(parseAlbumSearch("not json").isEmpty())
        assertTrue(parseAlbumSearch("{}").isEmpty())
        assertTrue(parseAlbumSearch("[1,2,3]").isEmpty())
    }

    @Test
    fun artistSearchNeedsAChannelId() {
        val body = """
        {"a":{"musicResponsiveListItemRenderer":{"navigationEndpoint":{"browseEndpoint":{"browseId":"UC123"}},
          "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Daft Punk"}]}}}]}},
         "b":{"musicResponsiveListItemRenderer":{"navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_x"}},
          "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"An Album"}]}}}]}}}
        """
        assertEquals(listOf(MusicArtistSummary("UC123", "Daft Punk", null)), parseArtistSearch(body))
    }

    private fun trackItem(index: Int, id: String, title: String, duration: String, greyed: Boolean = false) = """
        {"musicResponsiveListItemRenderer":{
          ${if (greyed) "\"musicItemRendererDisplayPolicy\":\"MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT\"," else ""}
          "playlistItemData":{"videoId":"$id"},
          "index":{"runs":[{"text":"$index"}]},
          "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$title"}]}}}],
          "fixedColumns":[{"musicResponsiveListItemFixedColumnRenderer":{"text":{"runs":[{"text":"$duration"}]}}}]}}
    """

    private val albumPage = """
    {"contents":{"twoColumnBrowseResultsRenderer":{
      "tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"musicResponsiveHeaderRenderer":{
        "title":{"runs":[{"text":"Random Access Memories"}]},
        "subtitle":{"runs":[{"text":"Album"},{"text":" • "},{"text":"2013"}]},
        "straplineTextOne":{"runs":[{"text":"Daft Punk","navigationEndpoint":{"browseEndpoint":{"browseId":"UCartist1"}}}]},
        "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img/cover"}]}}}
      }}]}}}}],
      "secondaryContents":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"contents":[
        ${trackItem(1, "AAAAAAAAAAA", "Give Life Back to Music", "4:34")},
        ${trackItem(2, "BBBBBBBBBBB", "The Game of Love", "5:21")},
        ${trackItem(3, "CCCCCCCCCCC", "Removed Track", "3:00", greyed = true)}
      ]}}]}},
      "x":{"watchEndpoint":{"videoId":"AAAAAAAAAAA","playlistId":"OLAK5uy_example"}}
    }}}
    """

    @Test
    fun albumPageReadsHeaderTracksAndGreyedOutOnes() {
        val album = assertNotNull(parseAlbum(albumPage, "MPREb_abc123"))
        assertEquals("Random Access Memories", album.title)
        assertEquals("Album", album.kind)
        assertEquals("2013", album.year)
        assertEquals("Daft Punk", album.artists.single().name)
        assertEquals("https://img/cover", album.thumbnailUrl)
        assertEquals("OLAK5uy_example", album.audioPlaylistId)
        assertEquals(listOf("AAAAAAAAAAA", "BBBBBBBBBBB", "CCCCCCCCCCC"), album.tracks.map { it.videoId })
        assertEquals(listOf(274, 321, 180), album.tracks.map { it.durationSeconds })
        assertEquals(listOf(1, 2, 3), album.tracks.map { it.trackNumber })
        assertEquals(listOf(true, true, false), album.tracks.map { it.isAvailable })
        // A track with no artists of its own takes the album's.
        assertEquals("Daft Punk", album.tracks.first().artists.single().name)
    }

    @Test
    fun albumPageListsItsOtherVersions() {
        val shelf = """{"musicCarouselShelfRenderer":{
          "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Other versions"}]}}},
          "contents":[
            {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Random Access Memories","navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_long"}}}]}}},
            {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Itself","navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_abc123"}}}]}}}
          ]}}"""
        val unrelated = """{"musicCarouselShelfRenderer":{
          "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"You might also like"}]}}},
          "contents":[{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Other","navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_other"}}}]}}}]}}"""
        val body = albumPage.replace("\"x\":", "\"shelves\":[$shelf,$unrelated],\"x\":")
        val album = assertNotNull(parseAlbum(body, "MPREb_abc123"))
        assertEquals(listOf("MPREb_long"), album.otherVersionIds)
    }

    @Test
    fun albumWithoutTitleOrTracksIsNull() {
        assertNull(parseAlbum("{}", "MPREb_x"))
        assertNull(parseAlbum("nope", "MPREb_x"))
        val noTracks = """{"musicResponsiveHeaderRenderer":{"title":{"runs":[{"text":"Empty"}]}}}"""
        assertNull(parseAlbum(noTracks, "MPREb_x"))
    }

    @Test
    fun artistPageListsItsMostPlayedSongsInOrder() {
        fun song(id: String, title: String, album: String) = """
            {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"$id"},
              "flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$title"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Daft Punk"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Album","navigationEndpoint":{"browseEndpoint":{"browseId":"$album"}}}]}}}]}}"""
        val body = """
        {"header":{"musicImmersiveHeaderRenderer":{"title":{"runs":[{"text":"Daft Punk"}]}}},
         "contents":[{"musicShelfRenderer":{"contents":[${song("v1", "One More Time", "MPREb_disc")},${song("v2", "Get Lucky", "MPREb_ram")}]}}]}"""
        val artist = assertNotNull(parseArtist(body, "UC123"))
        assertEquals(listOf("One More Time", "Get Lucky"), artist.topSongs.map { it.title })
        assertEquals(listOf("v1", "v2"), artist.topSongs.map { it.videoId })
        assertEquals(listOf("MPREb_disc", "MPREb_ram"), artist.topSongs.map { it.albumBrowseId })
    }

    @Test
    fun artistPageListsAlbumsOnly() {
        val body = """
        {"header":{"musicImmersiveHeaderRenderer":{"title":{"runs":[{"text":"Daft Punk"}]}}},
         "contents":[
          {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Discovery","navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_disc"}}}]},
            "subtitle":{"runs":[{"text":"Album"},{"text":" • "},{"text":"2001"}]},
            "thumbnailRenderer":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img/d"}]}}}}},
          {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"A Playlist","navigationEndpoint":{"browseEndpoint":{"browseId":"VLPL123"}}}]}}}
         ]}
        """
        val artist = assertNotNull(parseArtist(body, "UC123"))
        assertEquals("Daft Punk", artist.name)
        val album = artist.albums.single()
        assertEquals("MPREb_disc", album.browseId)
        assertEquals("2001", album.year)
        assertEquals("Album", album.kind)
        assertEquals("https://img/d", album.thumbnailUrl)
    }

    private fun panel(id: String, type: String, title: String, length: String, counterpart: String = "") = """
        {"playlistPanelVideoRenderer":{"videoId":"$id","title":{"runs":[{"text":"$title"}]},"lengthText":{"runs":[{"text":"$length"}]},
          "navigationEndpoint":{"watchEndpoint":{"watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"musicVideoType":"$type"}}}}
          $counterpart}}
    """

    @Test
    fun counterpartIsTheLinkedVideo() {
        val linked = ""","counterpart":[{"counterpartRenderer":${panel("VVVVVVVVVVV", "MUSIC_VIDEO_TYPE_OMV", "Give Life Back to Music (Official Video)", "4:40")}}]"""
        val body = """{"contents":[${panel("AAAAAAAAAAA", "MUSIC_VIDEO_TYPE_ATV", "Give Life Back to Music", "4:34", linked)}]}"""
        val track = parseWatchNext(body).single()
        assertEquals(MusicVideoType.ArtTrack, track.videoType)
        assertEquals("VVVVVVVVVVV", track.counterpart?.videoId)
        val c = assertNotNull(parseCounterpart(body, "AAAAAAAAAAA"))
        assertEquals("VVVVVVVVVVV", c.videoId)
        assertEquals(MusicVideoType.OfficialVideo, c.videoType)
        assertEquals(280, c.durationSeconds)
    }

    @Test
    fun counterpartOfAnArtTrackWithoutALinkIsNull() {
        val body = """{"contents":[${panel("AAAAAAAAAAA", "MUSIC_VIDEO_TYPE_ATV", "Track", "3:00")}]}"""
        assertNull(parseCounterpart(body, "AAAAAAAAAAA"))
    }

    @Test
    fun anOfficialVideoLinkedToAnArtTrackGivesTheArtTrack() {
        val linked = ""","counterpart":[{"counterpartRenderer":${panel("AAAAAAAAAAA", "MUSIC_VIDEO_TYPE_ATV", "Still D.R.E.", "4:30")}}]"""
        val body = """{"contents":[${panel("VVVVVVVVVVV", "MUSIC_VIDEO_TYPE_OMV", "Still D.R.E.", "4:33", linked)}]}"""
        val c = assertNotNull(parseCounterpart(body, "VVVVVVVVVVV"))
        assertEquals("AAAAAAAAAAA", c.videoId)
        assertEquals(MusicVideoType.ArtTrack, c.videoType)
    }

    @Test
    fun anOfficialVideoIsItsOwnCounterpart() {
        val body = """{"contents":[${panel("VVVVVVVVVVV", "MUSIC_VIDEO_TYPE_OMV", "Video", "4:00")}]}"""
        val c = assertNotNull(parseCounterpart(body, "VVVVVVVVVVV"))
        assertEquals("VVVVVVVVVVV", c.videoId)
        assertEquals(MusicVideoType.OfficialVideo, c.videoType)
        assertEquals(240, c.durationSeconds)
    }

    @Test
    fun unknownTrackIsNull() {
        assertNull(parseCounterpart("""{"contents":[]}""", "AAAAAAAAAAA"))
        assertNull(parseCounterpart("garbage", "AAAAAAAAAAA"))
    }
}

class ArtistShelfParsersTest {
    private fun tile(id: String, title: String, subtitle: String) = """
        {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"$title","navigationEndpoint":{"browseEndpoint":{"browseId":"$id"}}}]},
          "subtitle":{"runs":[$subtitle]},
          "thumbnailRenderer":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img/$id"}]}}}}}"""

    private val self = MusicArtistRef("Daft Punk", "UC123")

    @Test
    fun shelvesCarryTheirItemsAndTheEndpointOfTheFullList() {
        val body = """
        {"contents":[
          {"musicCarouselShelfRenderer":{
            "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Albums"}]},
              "moreContentButton":{"buttonRenderer":{"navigationEndpoint":{"browseEndpoint":{"browseId":"MPAD123","params":"abc"}}}}}},
            "contents":[${tile("MPREb_d", "Discovery", """{"text":"Album"},{"text":" • "},{"text":"2001"}""")}]}},
          {"musicCarouselShelfRenderer":{
            "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Singles & EPs"}]}}},
            "contents":[${tile("MPREb_s", "One More Time", """{"text":"2000"}""")}]}},
          {"musicCarouselShelfRenderer":{
            "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Fans might also like"}]}}},
            "contents":[{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Another Artist","navigationEndpoint":{"browseEndpoint":{"browseId":"UC999"}}}]}}}]}}
        ]}"""
        val shelves = parseArtistShelves(body, self)
        assertEquals(listOf("Albums", "Singles & EPs"), shelves.map { it.title })
        assertEquals("MPAD123", shelves[0].moreBrowseId)
        assertEquals("abc", shelves[0].moreParams)
        assertNull(shelves[1].moreBrowseId)
        assertEquals("Album", shelves[0].items.single().kind)
        // No kind in the tile: the shelf's title supplies it.
        assertEquals("Single", shelves[1].items.single().kind)
        assertEquals("2000", shelves[1].items.single().year)
    }

    @Test
    fun theFullGridListsEveryTile() {
        val body = """{"contents":[${tile("MPREb_1", "One", """{"text":"EP"},{"text":" • "},{"text":"2010"}""")},${tile("MPREb_2", "Two", """{"text":"2012"}""")}]}"""
        val albums = parseAlbumGrid(body, self, "Singles & EPs")
        assertEquals(listOf("EP", "Single"), albums.map { it.kind })
        assertEquals(listOf("MPREb_1", "MPREb_2"), albums.map { it.browseId })
        assertEquals("Daft Punk", albums.first().artists.single().name)
    }

    @Test
    fun gridContinuationIsReadFromTheGridNotTheSectionList() {
        // First page: the token sits in gridRenderer.continuations (shape from First to Eleven, 2026-10-29).
        val first = """
        {"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{
          "contents":[{"gridRenderer":{"items":[],"continuations":[{"nextContinuationData":{"continuation":"GRID1"}}]}}]}}}}]}}}
        """
        assertEquals("GRID1", parseGridContinuation(first))
        // Later pages answer with gridContinuation, carrying the next token the same way; the last has none.
        val middle = """{"continuationContents":{"gridContinuation":{"items":[],"continuations":[{"nextContinuationData":{"continuation":"GRID2"}}]}}}"""
        assertEquals("GRID2", parseGridContinuation(middle))
        assertNull(parseGridContinuation("""{"continuationContents":{"gridContinuation":{"items":[]}}}"""))
    }

    @Test
    fun trackLengthIsReadFromTheTvLayoutsLastFlexColumn() {
        // The layout YouTube Music sends to the app's Android TV user agent (In Our Bones, 2026-10-29): no fixedColumns,
        // the length is the last flex column, after the play count.
        val body = """
        {"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
          {"musicResponsiveHeaderRenderer":{"title":{"runs":[{"text":"In Our Bones"}]},"subtitle":{"runs":[{"text":"Album"},{"text":" • "},{"text":"2016"}]}}},
          {"musicShelfRenderer":{"contents":[
            {"musicResponsiveListItemRenderer":{
              "playlistItemData":{"videoId":"abcdefghijk"},
              "flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Running with the Wild Things"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"6.6M plays"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"3:43"}]}}}
              ]}}
          ]}}
        ]}}}}]}}}
        """
        val album = parseAlbum(body, "MPREb_x")
        assertEquals(223, album?.tracks?.single()?.durationSeconds)
    }
}
