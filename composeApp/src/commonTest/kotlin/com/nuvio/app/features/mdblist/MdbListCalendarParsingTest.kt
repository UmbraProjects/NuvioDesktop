package com.nuvio.app.features.mdblist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MdbListCalendarParsingTest {
    @Test
    fun `episode events map to the parent series and retain coordinates`() {
        val entries = parseMdbListCalendarForTest(
            """
            {
              "events": [
                {
                  "id": "episode-1",
                  "type": "episode",
                  "release_type": "watched",
                  "start": "2026-08-14",
                  "title": "Example Show",
                  "episode_title": "The Return",
                  "show_tmdb": 12345,
                  "episode_tmdb": 98765,
                  "season_number": 3,
                  "episode_number": 2,
                  "poster": "/poster.jpg",
                  "image": "/still.jpg",
                  "is_watchlist": false,
                  "is_watched": false
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals("2026-08-14", entry.dateKey)
        assertEquals("series", entry.type)
        assertEquals("tmdb:12345", entry.contentId)
        assertEquals("Example Show", entry.title)
        assertEquals(3, entry.seasonNumber)
        assertEquals(2, entry.episodeNumber)
        assertEquals("The Return", entry.episodeTitle)
        assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg", entry.posterUrl)
    }

    @Test
    fun `movie events use movie identity and keep absolute artwork URLs`() {
        val entries = parseMdbListCalendarForTest(
            """
            {
              "events": [
                {
                  "id": "movie-1",
                  "type": "movie",
                  "start": "2026-09-01T00:00:00Z",
                  "title": "Example Movie",
                  "movie_tmdb": 67890,
                  "poster": "https://images.example/poster.jpg"
                }
              ]
            }
            """.trimIndent(),
        )

        val entry = entries.single()
        assertEquals("2026-09-01", entry.dateKey)
        assertEquals("movie", entry.type)
        assertEquals("tmdb:67890", entry.contentId)
        assertEquals("https://images.example/poster.jpg", entry.posterUrl)
    }

    @Test
    fun `malformed and unidentifiable events are ignored`() {
        val entries = parseMdbListCalendarForTest(
            """
            {
              "events": [
                { "type": "episode", "start": "not-a-date", "show_tmdb": 1 },
                { "type": "episode", "start": "2026-08-14", "title": "No identity" }
              ]
            }
            """.trimIndent(),
        )

        assertTrue(entries.isEmpty())
    }
}
