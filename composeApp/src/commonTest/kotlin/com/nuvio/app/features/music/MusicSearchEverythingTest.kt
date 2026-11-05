package com.nuvio.app.features.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * fork:music-home - fixtures cut down from YouTube Music answers captured on 2026-11-05: the newer flat layout (each
 * row in its own section, the kind as the first word) for "yelawolf", a classic titled-shelf layout, and the
 * suggestions answer for "yela".
 */
class MusicSearchEverythingTest {
    private fun col(vararg runs: String) =
        """{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[${runs.joinToString(",")}]}}}"""

    private fun run(text: String) = """{"text":"$text"}"""
    private fun dot() = run(" • ")
    private fun link(text: String, browseId: String, pageType: String? = null): String {
        val config = pageType?.let {
            ""","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"$it"}}"""
        }.orEmpty()
        return """{"text":"$text","navigationEndpoint":{"browseEndpoint":{"browseId":"$browseId"$config}}}"""
    }

    private fun section(item: String) = """{"itemSectionRenderer":{"contents":[{"musicResponsiveListItemRenderer":$item}]}}"""

    private val flat = """
    {"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
      {"musicCardShelfRenderer":{
        "title":{"runs":[${link("Yelawolf", "UCyela", "MUSIC_PAGE_TYPE_ARTIST")}]},
        "subtitle":{"runs":[${run("Artist")},${dot()},${run("3.16M monthly audience")}]}}},
      ${section("""{"navigationEndpoint":{"browseEndpoint":{"browseId":"UCrittz","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}},
        "flexColumns":[${col(run("Rittz"))},${col(run("Artist"), dot(), run("908K monthly audience"))}]}""")},
      ${section("""{"playlistItemData":{"videoId":"78ZD05_wbqk"},
        "flexColumns":[${col(run("Been a Problem"))},${col(run("Video"), dot(), link("Yelawolf", "UCyela"), run(" & "), link("Caskey", "UCcaskey"), dot(), run("19M views"), dot(), run("4:12"))}]}""")},
      ${section("""{"playlistItemData":{"videoId":"dr1GOJKo-P8"},
        "flexColumns":[${col(run("YelaWolf Elvis Messy Freestyle"))},${col(run("Video"), dot(), run("Slumerican"), dot(), run("21M views"))}]}""")},
      ${section("""{"playlistItemData":{"videoId":"rpQjv6RMFSQ"},
        "flexColumns":[${col(run("Cowboy talks about the studio"))},${col(run("Episode"), dot(), run("Nov 7, 2024"), dot(), run("Respect The Connect"))}]}""")},
      ${section("""{"navigationEndpoint":{"browseEndpoint":{"browseId":"VLPLx","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_PLAYLIST"}}}},
        "flexColumns":[${col(run("YELAWOLF HITS"))},${col(run("Playlist"), dot(), run("Henry Harper"), dot(), run("355 songs"))}]}""")},
      ${section("""{"navigationEndpoint":{"browseEndpoint":{"browseId":"UCfan","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_USER_CHANNEL"}}}},
        "flexColumns":[${col(run("yelawolf 9010"))},${col(run("Profile"), dot(), run("@yelawolf-z9q"))}]}""")},
      ${section("""{"playlistItemData":{"videoId":"songA"},
        "flexColumns":[${col(run("Till It's Gone"))},${col(run("Song"), dot(), link("Yelawolf", "UCyela"), dot(), link("Love Story", "MPREb_love"), dot(), run("4:00"))}]}""")},
      ${section("""{"navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_love"}},
        "flexColumns":[${col(run("Love Story"))},${col(run("Album"), dot(), link("Yelawolf", "UCyela"), dot(), run("2015"))}]}""")}
    ]}}}}]}}}
    """

    private val classic = """
    {"contents":{"sectionListRenderer":{"contents":[
      {"musicShelfRenderer":{"title":{"runs":[{"text":"Songs"}]},"contents":[
        {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"s1"},
          "flexColumns":[${col(run("Daddy's Lambo"))},${col(link("Yelawolf", "UCyela"), dot(), link("Trunk Muzik 0-60", "MPREb_tm"), dot(), run("3:58"))}]}}
      ]}},
      {"musicShelfRenderer":{"title":{"runs":[{"text":"Albums"}]},"contents":[
        {"musicResponsiveListItemRenderer":{"navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_tm"}},
          "flexColumns":[${col(run("Trunk Muzik 0-60"))},${col(run("Album"), dot(), link("Yelawolf", "UCyela"), dot(), run("2011"))}]}}
      ]}},
      {"musicShelfRenderer":{"title":{"runs":[{"text":"Videos"}]},"contents":[
        {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"v1"},
          "flexColumns":[${col(run("Pop the Trunk (Official Video)"))},${col(link("Yelawolf", "UCyela"), dot(), run("12M views"), dot(), run("4:06"))}]}}
      ]}},
      {"musicShelfRenderer":{"title":{"runs":[{"text":"Community playlists"}]},"contents":[
        {"musicResponsiveListItemRenderer":{"navigationEndpoint":{"browseEndpoint":{"browseId":"VLPLy"}},
          "flexColumns":[${col(run("Southern rap"))},${col(run("Someone"), dot(), run("80 songs"))}]}}
      ]}}
    ]}}}
    """

    @Test
    fun flatLayoutSortsRowsByTheirOwnKindWord() {
        val found = parseSearchEverything(flat)
        assertEquals("Yelawolf", found.top?.title)
        assertEquals(listOf("Rittz"), found.artists.map { it.name })
        assertEquals(listOf("78ZD05_wbqk", "dr1GOJKo-P8"), found.videos.map { it.videoId })
        assertEquals(listOf("songA"), found.songs.map { it.videoId })
        assertEquals(listOf("MPREb_love"), found.albums.map { it.browseId })
        assertEquals("Album", found.albums.single().kind)
    }

    @Test
    fun flatLayoutLeavesOutEpisodesPlaylistsAndProfiles() {
        val found = parseSearchEverything(flat)
        val ids = found.songs.map { it.videoId } + found.videos.map { it.videoId }
        assertTrue("rpQjv6RMFSQ" !in ids)
        assertTrue(found.artists.none { it.browseId == "UCfan" })
    }

    @Test
    fun anUnlinkedChannelIsTheSegmentAfterTheKindWord() {
        val freestyle = parseSearchEverything(flat).videos.first { it.videoId == "dr1GOJKo-P8" }
        assertEquals(listOf("Slumerican"), freestyle.artists.map { it.name })
        val linked = parseSearchEverything(flat).videos.first { it.videoId == "78ZD05_wbqk" }
        assertEquals(listOf("Yelawolf", "Caskey"), linked.artists.map { it.name })
        assertEquals(252, linked.durationSeconds)
    }

    @Test
    fun classicLayoutTakesTheKindFromTheShelfTitle() {
        val found = parseSearchEverything(classic)
        assertNull(found.top)
        assertEquals(listOf("s1"), found.songs.map { it.videoId })
        assertEquals("Trunk Muzik 0-60", found.songs.single().albumTitle)
        assertEquals(listOf("MPREb_tm"), found.albums.map { it.browseId })
        assertEquals(listOf("v1"), found.videos.map { it.videoId })
        assertTrue(found.artists.isEmpty())
    }

    @Test
    fun garbageIsEmptyNotAnError() {
        assertTrue(parseSearchEverything("not json").isEmpty)
        assertTrue(parseSearchEverything("{}").isEmpty)
    }

    @Test
    fun suggestionsComeBackAsTheQueriesTheyRun() {
        val body = """
        {"contents":[{"searchSuggestionsSectionRenderer":{"contents":[
          {"searchSuggestionRenderer":{"suggestion":{"runs":[{"text":"yela","bold":true},{"text":"wolf best friend"}]},
            "navigationEndpoint":{"searchEndpoint":{"query":"yelawolf best friend"}}}},
          {"searchSuggestionRenderer":{"suggestion":{"runs":[{"text":"yela","bold":true},{"text":"wolf"}]},
            "navigationEndpoint":{"searchEndpoint":{"query":"yelawolf"}}}},
          {"searchSuggestionRenderer":{"suggestion":{"runs":[{"text":"yela","bold":true},{"text":"wolf"}]}}}
        ]}}]}
        """
        assertEquals(listOf("yelawolf best friend", "yelawolf"), parseSearchSuggestions(body))
        assertTrue(parseSearchSuggestions("nope").isEmpty())
    }
}
