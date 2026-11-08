package com.nuvio.app.features.music.library
// FORK UI (Spyder): Nick's album page lenses, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import com.nuvio.app.features.music.MusicAlbumSummary
import com.nuvio.app.features.music.MusicArtistRef
import com.nuvio.app.features.music.parseArtist
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenLineageTest {
    private fun binding(dir: String, artist: String, album: String, date: String, links: Int) =
        """{"dir":{"value":"$dir"},"infName":{"value":"$artist"},"albumName":{"value":"$album"},"date":{"value":"$date"},"links":{"value":"$links"}}"""

    private val answer = """{"head":{},"results":{"bindings":[
        ${binding("before", "Prince", "Purple Rain", "1984-06-25T00:00:00Z", 60)},
        ${binding("before", "Prince", "Sign o' the Times", "1987-03-30T00:00:00Z", 40)},
        ${binding("before", "Prince", "Musicology", "2004-04-20T00:00:00Z", 30)},
        ${binding("before", "Nina Simone", "Little Girl Blue", "1959-02-01T00:00:00Z", 20)},
        ${binding("before", "Alicia Keys", "The Diary of Alicia Keys", "2003-12-02T00:00:00Z", 50)},
        ${binding("after", "H.E.R.", "H.E.R.", "2017-10-20T00:00:00Z", 15)},
        ${binding("after", "Prince", "Purple Rain", "1984-06-25T00:00:00Z", 60)},
        {"dir":{"value":"before"},"infName":{"value":"No Date"},"albumName":{"value":"Undated"},"links":{"value":"3"}}
    ]}}"""

    @Test
    fun readsTheQueryAnswer() {
        val rows = parseWikidataLineage(answer)
        assertEquals(7, rows.size, "the row without a date is dropped")
        assertEquals(WikidataLineageRow(LineageSide.Before, "Prince", "Purple Rain", 1984, 60), rows.first())
        assertTrue(parseWikidataLineage("not json").isEmpty())
    }

    @Test
    fun picksEachInfluencesBestKnownAlbumOnTheRightSide() {
        val rows = parseWikidataLineage(answer)
        val before = pickWikidataLineage(rows, LineageSide.Before, 2001, "Alicia Keys", 5, "Named as an influence")
        // Prince's 2004 album is after 2001; his best known before it is Purple Rain. Her own albums are left out.
        assertEquals(listOf("Purple Rain", "Little Girl Blue"), before.map { it.title })
        assertEquals("Named as an influence", before.first().why)
        val after = pickWikidataLineage(rows, LineageSide.After, 2001, "Alicia Keys", 5, "Names her")
        assertEquals(listOf("H.E.R."), after.map { it.artist }, "Purple Rain is before 2001, so it cannot be on the after side")
    }

    @Test
    fun aRelatedArtistGivesItsNearestAlbumEachSide() {
        val albums = listOf(
            MusicAlbumSummary("a", "Who Is Jill Scott?", emptyList(), "2000", "Album", null),
            MusicAlbumSummary("b", "Beautifully Human", emptyList(), "2004", "Album", null),
            MusicAlbumSummary("c", "The Real Thing", emptyList(), "2007", "Album", null),
            MusicAlbumSummary("d", "Golden", emptyList(), "2002", "Single", null),
        )
        assertEquals("Who Is Jill Scott?", pickRelatedRecord("Jill Scott", albums, LineageSide.Before, 2001, "x")?.title)
        assertEquals("Beautifully Human", pickRelatedRecord("Jill Scott", albums, LineageSide.After, 2001, "x")?.title)
        assertNull(pickRelatedRecord("Jill Scott", albums, LineageSide.Before, 1990, "x"))
    }

    @Test
    fun keptOpenLineageReadsBackThroughTheAiParser() {
        val lineage = Lineage(
            before = listOf(LineageRecord("Prince", "Purple Rain", 1984, "Named as an influence")),
            after = listOf(LineageRecord("H.E.R.", "H.E.R.", 2017, "Names Alicia Keys as an influence")),
            beforeThread = "Artists Alicia Keys names as influences.",
            afterThread = null,
            byAi = false,
            sources = "From Wikidata",
        )
        val json = encodeLineageJson(lineage)
        val back = parseLineage(json, 2001, "Alicia Keys")
        assertEquals(lineage.copy(byAi = true, sources = null), back)
        assertEquals("From Wikidata", lineageSources(json))
    }

    @Test
    fun threadsSayWhatTheRecordsShare() {
        assertNull(openLineageThread(LineageSide.Before, "Alicia Keys", 2001, 0, 0))
        val related = openLineageThread(LineageSide.After, "Alicia Keys", 2001, 0, 3)!!
        assertTrue(related.startsWith("Artists YouTube Music's listeners pair with Alicia Keys"))
        assertTrue("not a claim of influence" in related)
        assertFalse("listeners" in openLineageThread(LineageSide.Before, "Alicia Keys", 2001, 2, 0)!!)
    }

    @Test
    fun theQueryEscapesTheName() {
        assertTrue("\"Guns N' \\\"Roses\\\"\"@en" in wikidataLineageQuery("Guns N' \"Roses\""))
    }

    @Test
    fun anArtistPageListsItsRelatedArtists() {
        val body = """{"header":{"musicImmersiveHeaderRenderer":{"title":{"runs":[{"text":"Alicia Keys"}]}}},
          "contents":{"shelves":[{"musicCarouselShelfRenderer":{
            "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Fans might also like"}]}}},
            "contents":[
              {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Jill Scott","navigationEndpoint":{"browseEndpoint":{"browseId":"UCjill"}}}]},
                "navigationEndpoint":{"browseEndpoint":{"browseId":"UCjill"}}}},
              {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Songs In A Minor"}]},"subtitle":{"runs":[{"text":"Album"},{"text":" • "},{"text":"2001"}]},
                "navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_songs"}}}},
              {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Alicia Keys"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"UCself"}}}}
            ]}}]}}"""
        val page = parseArtist(body, "UCself")!!
        assertEquals(listOf(MusicArtistRef("Jill Scott", "UCjill")), page.related)
        assertEquals(listOf("Songs In A Minor"), page.albums.map { it.title })
    }
}
