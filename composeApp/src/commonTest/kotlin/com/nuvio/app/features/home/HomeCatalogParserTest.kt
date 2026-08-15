package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeCatalogParserTest {

    @Test
    fun `parse catalog response de-duplicates repeated metas but preserves raw count`() {
        val result = HomeCatalogParser.parseCatalogResponse(
            """
            {
              "metas": [
                { "id": "mal:62516", "type": "series", "name": "A" },
                { "id": "mal:62516", "type": "series", "name": "A duplicate" },
                { "id": "mal:1", "type": "movie", "name": "B" }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(3, result.rawItemCount)
        assertEquals(
            listOf("series:mal:62516", "movie:mal:1"),
            result.items.map { it.stableKey() },
        )
        assertEquals("A", result.items.first().name)
    }

    @Test
    fun `parse catalog response reads an addon-supplied landscape poster beside the backdrop`() {
        // AIOMetadata's Landscape URL Pattern writes `landscapePoster` and leaves `poster` and
        // `background` alone, so all three have to survive the same meta independently.
        val result = HomeCatalogParser.parseCatalogResponse(
            """
            {
              "metas": [
                {
                  "id": "tt0903747", "type": "series", "name": "Has landscape art",
                  "poster": "https://example.com/poster.jpg",
                  "background": "https://example.com/backdrop.jpg",
                  "landscapePoster": "https://example.com/landscape.jpg"
                },
                {
                  "id": "tt2250192", "type": "series", "name": "No landscape art",
                  "poster": "https://example.com/poster2.jpg",
                  "background": "https://example.com/backdrop2.jpg"
                }
              ]
            }
            """.trimIndent(),
        )

        val (withArt, withoutArt) = result.items
        assertEquals("https://example.com/landscape.jpg", withArt.landscapePoster)
        assertEquals("https://example.com/backdrop.jpg", withArt.banner)
        assertEquals("https://example.com/poster.jpg", withArt.poster)
        assertEquals(null, withoutArt.landscapePoster)
    }

    @Test
    fun `parse catalog response reads anime form and side-channel anime ids`() {
        // Shaped after a real anime-kitsu meta: the anime catalogue's own id sits beside an imdb
        // one, and animeType names the form the `type` field is too coarse to give.
        val result = HomeCatalogParser.parseCatalogResponse(
            """
            {
              "metas": [
                {
                  "id": "kitsu:11614", "type": "movie", "name": "A film",
                  "animeType": "movie", "kitsu_id": 11614, "imdb_id": "tt5311514"
                },
                {
                  "id": "tt2250192", "type": "series", "name": "Franchise-addressed",
                  "mal_id": "20021"
                },
                { "id": "tt0903747", "type": "series", "name": "Not anime" }
              ]
            }
            """.trimIndent(),
        )

        val (film, franchise, plain) = result.items
        assertEquals("movie", film.animeType)
        assertEquals(true, film.carriesAnimeCatalogueId)
        // A numeric id field reads the same as a string one, and survives the primary id being
        // translated into a franchise namespace.
        assertEquals(true, franchise.carriesAnimeCatalogueId)
        assertEquals(null, franchise.animeType)
        assertEquals(false, plain.carriesAnimeCatalogueId)
    }

    @Test
    fun `parse catalog response respects max item cap without losing raw count`() {
        val result = HomeCatalogParser.parseCatalogResponse(
            payload = """
                {
                  "metas": [
                    { "id": "tt1", "type": "movie", "name": "One" },
                    { "id": "tt1", "type": "movie", "name": "One duplicate" },
                    { "id": "tt2", "type": "movie", "name": "Two" },
                    { "id": "tt3", "type": "movie", "name": "Three" }
                  ]
                }
            """.trimIndent(),
            maxItems = 2,
        )

        assertEquals(4, result.rawItemCount)
        assertEquals(
            listOf("movie:tt1", "movie:tt2"),
            result.items.map { it.stableKey() },
        )
    }

    @Test
    fun `parse catalog response keeps raw released date for unreleased filtering`() {
        val result = HomeCatalogParser.parseCatalogResponse(
            payload = """
                {
                  "metas": [
                    {
                      "id": "tt1",
                      "type": "movie",
                      "name": "Future Movie",
                      "releaseInfo": "2027",
                      "released": "2027-05-12T00:00:00.000Z"
                    }
                  ]
                }
            """.trimIndent(),
        )

        assertEquals("2027", result.items.single().releaseInfo)
        assertEquals("2027-05-12T00:00:00.000Z", result.items.single().rawReleaseDate)
    }

    @Test
    fun `parse catalog response keeps the default video id that marks a single-video meta`() {
        val result = HomeCatalogParser.parseCatalogResponse(
            payload = """
                {
                  "metas": [
                    {
                      "id": "kitsu:11614",
                      "type": "anime",
                      "name": "A Silent Voice",
                      "behaviorHints": { "defaultVideoId": "kitsu:11614" }
                    },
                    { "id": "kitsu:7442", "type": "anime", "name": "Attack on Titan" },
                    {
                      "id": "kitsu:1",
                      "type": "anime",
                      "name": "Blank hint",
                      "behaviorHints": { "defaultVideoId": "" }
                    }
                  ]
                }
            """.trimIndent(),
        )

        assertEquals(
            listOf("kitsu:11614", null, null),
            result.items.map { it.defaultVideoId },
        )
    }
}
