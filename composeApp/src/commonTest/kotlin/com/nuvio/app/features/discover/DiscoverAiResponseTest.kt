package com.nuvio.app.features.discover

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parsing what actually comes back (plan §5).
 *
 * Every case here is a real thing a model does when told to reply with bare JSON. None of them may
 * cost the user the whole row.
 */
class DiscoverAiResponseTest {
    @Test
    fun `a clean array parses`() {
        val items = parseDiscoverAiSuggestions(
            """[{"title":"Arrival","year":2016,"type":"movie","reason":"Quiet science fiction"}]"""
        )

        assertEquals(1, items.size)
        assertEquals("Arrival", items[0].title)
        assertEquals(2016, items[0].year)
        assertEquals("movie", items[0].type)
        assertEquals("Quiet science fiction", items[0].reason)
    }

    @Test
    fun `a markdown fence is not a parse failure`() {
        val items = parseDiscoverAiSuggestions(
            """
            ```json
            [{"title":"Arrival","year":2016,"type":"movie"}]
            ```
            """.trimIndent()
        )

        assertEquals(listOf("Arrival"), items.map { it.title })
    }

    @Test
    fun `prose either side of the array is ignored`() {
        val items = parseDiscoverAiSuggestions(
            "Here are my suggestions:\n" +
                """[{"title":"Heat","year":1995,"type":"movie"}]""" +
                "\nHope this helps!"
        )

        assertEquals(listOf("Heat"), items.map { it.title })
    }

    @Test
    fun `one bad item does not take its neighbours with it`() {
        val items = parseDiscoverAiSuggestions(
            """[
              {"title":"Good One","year":2001,"type":"movie"},
              {"year":2002,"type":"movie"},
              "not an object",
              {"title":"","year":2003},
              {"title":"Other Good One","year":2004,"type":"series"}
            ]"""
        )

        // A missing title, a bare string and a blank title are each unusable on their own terms.
        assertEquals(listOf("Good One", "Other Good One"), items.map { it.title })
    }

    @Test
    fun `type is normalised, and anything unrecognised is treated as a film`() {
        val items = parseDiscoverAiSuggestions(
            """[
              {"title":"A","type":"TV"},{"title":"B","type":"show"},{"title":"C","type":"Series"},
              {"title":"D","type":"film"},{"title":"E","type":"movie"},{"title":"F"}
            ]"""
        )

        assertEquals(
            listOf("series", "series", "series", "movie", "movie", "movie"),
            items.map { it.type },
        )
    }

    @Test
    fun `year survives being sent as a string or a date`() {
        val items = parseDiscoverAiSuggestions(
            """[
              {"title":"A","year":"1999"},
              {"title":"B","year":"1999-10-15"},
              {"title":"C","year":1999.0}
            ]"""
        )

        assertEquals(listOf(1999, 1999, 1999), items.map { it.year })
    }

    @Test
    fun `an implausible year is dropped rather than used`() {
        val items = parseDiscoverAiSuggestions(
            """[{"title":"A","year":20256},{"title":"B","year":"soon"},{"title":"C","year":0}]"""
        )

        // A wrong year sends the TMDB lookup to the wrong title, which is worse than no year.
        assertEquals(listOf(null, null, null), items.map { it.year })
        assertEquals(3, items.size)
    }

    @Test
    fun `duplicates collapse`() {
        val items = parseDiscoverAiSuggestions(
            """[
              {"title":"Arrival","year":2016,"type":"movie"},
              {"title":"arrival","year":2016,"type":"movie"},
              {"title":"Arrival","year":2016,"type":"series"}
            ]"""
        )

        // Same title twice is a wasted row slot; a genuinely different type is not a duplicate.
        assertEquals(2, items.size)
    }

    @Test
    fun `an overlong reason is trimmed rather than dropped`() {
        val long = "x".repeat(1000)
        val items = parseDiscoverAiSuggestions("""[{"title":"A","reason":"$long"}]""")

        assertEquals(240, items.single().reason?.length)
    }

    @Test
    fun `a blank reason becomes null rather than an empty subtitle`() {
        val items = parseDiscoverAiSuggestions("""[{"title":"A","reason":"   "}]""")

        assertNull(items.single().reason)
    }

    @Test
    fun `the suggestion count is capped`() {
        val many = (1..500).joinToString(",") { """{"title":"T$it"}""" }
        val items = parseDiscoverAiSuggestions("[$many]")

        assertEquals(DISCOVER_AI_MAX_SUGGESTIONS, items.size)
    }

    @Test
    fun `garbage yields an empty list, never an exception`() {
        listOf(
            "",
            "   ",
            "I cannot help with that.",
            "{}",
            "[",
            "]",
            "[{,,,}]",
            "null",
        ).forEach { input ->
            assertTrue(parseDiscoverAiSuggestions(input).isEmpty(), "expected nothing from: $input")
        }
    }

    @Test
    fun `unknown keys are ignored rather than rejected`() {
        val items = parseDiscoverAiSuggestions(
            """[{"title":"A","year":2000,"type":"movie","confidence":0.9,"tmdb_id":123}]"""
        )

        assertEquals("A", items.single().title)
    }
}
