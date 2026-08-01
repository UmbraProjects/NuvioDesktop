package com.nuvio.app.features.mdblist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Parsed against a real `/sync/watched` response, not the schema.
 *
 * The schema leaves `episodes` untyped, and an earlier parser inferred from `/sync/playback` got
 * two things wrong that each silently produced zero episodes: the timestamp is `last_watched_at`
 * rather than `watched_at`, and the show is nested *inside* the episode rather than beside it.
 */
class MdbListWatchedParsingTest {
    private val realResponse = """
        {
          "movies": [],
          "shows": [
            { "last_watched_at": "2026-07-31T01:15:37.000Z",
              "show": { "title": "FROM", "year": 2022,
                        "ids": { "trakt": 188205, "imdb": "tt9813792", "tmdb": 124364,
                                 "tvdb": 401003, "mdblist": "2thgy" } } }
          ],
          "seasons": [],
          "episodes": [
            { "last_watched_at": "2026-07-31T01:15:37.000Z",
              "episode": { "season": 1, "number": 3, "name": "Choosing Day",
                           "ids": { "tmdb": 3460121, "tvdb": 8808283 },
                           "show": { "title": "FROM", "year": 2022,
                                     "ids": { "tmdb": 124364, "trakt": 188205,
                                              "imdb": "tt9813792", "mdblist": "2thgy" } } } },
            { "last_watched_at": "2026-07-31T00:37:43.000Z",
              "episode": { "season": 1, "number": 2,
                           "name": "The One with the Sonogram at the End",
                           "ids": { "tmdb": 86012, "tvdb": 303822 },
                           "show": { "title": "Friends", "year": 1994,
                                     "ids": { "tmdb": 1668, "trakt": 1657,
                                              "imdb": "tt0108778", "mdblist": "8x1d" } } } }
          ],
          "pagination": { "offset": 0, "limit": 3, "has_more": true, "next_cursor": "abc" }
        }
    """.trimIndent()

    @Test
    fun `episodes resolve to the show id with their own coordinates`() {
        val items = parseMdbListWatchedForTest(realResponse)

        assertEquals(2, items.size)
        val from = items.first()
        assertEquals("tt9813792", from.id)
        assertEquals("series", from.type)
        assertEquals("FROM", from.name)
        assertEquals(1, from.season)
        assertEquals(3, from.episode)
        assertTrue(from.markedAtEpochMs > 0L, "timestamp should parse from last_watched_at")

        val friends = items[1]
        assertEquals("tt0108778", friends.id)
        assertEquals(2, friends.episode)
    }

    @Test
    fun `the show-level row does not become a second entry`() {
        // `shows` carries an aggregate marker with no coordinates; the per-episode rows are the
        // authoritative source and counting both would double up.
        val items = parseMdbListWatchedForTest(realResponse)

        assertEquals(2, items.count { it.type == "series" })
    }

    @Test
    fun `an empty payload parses to nothing rather than failing`() {
        val items = parseMdbListWatchedForTest(
            """{"movies":[],"shows":[],"seasons":[],"episodes":[],"pagination":{"has_more":false}}""",
        )

        assertTrue(items.isEmpty())
    }
}
