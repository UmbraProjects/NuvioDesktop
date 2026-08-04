package com.nuvio.app.features.details

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MetaDetailsParserTest {

    @Test
    fun `parse rejects null meta object without json object cast crash`() {
        assertFailsWith<IllegalStateException> {
            MetaDetailsParser.parse("""{"meta":null}""")
        }
    }

    @Test
    fun `parse accepts bare meta object response`() {
        val result = MetaDetailsParser.parse(
            """
            {
              "id": "mal:62516",
              "type": "series",
              "name": "The Fragrant Flower Blooms with Dignity"
            }
            """.trimIndent(),
        )

        assertEquals("mal:62516", result.id)
        assertEquals("series", result.type)
        assertEquals("The Fragrant Flower Blooms with Dignity", result.name)
    }

    @Test
    fun `parse preserves anime provider ids and certification fallback`() {
        val result = MetaDetailsParser.parse(
            """
            {
              "meta": {
                "id": "kitsu:42898",
                "type": "series",
                "name": "World Trigger 2",
                "_imdbId": "tt3950102",
                "_malId": "40907",
                "_tmdbId": "61628",
                "_tvdbId": "368613",
                "certification": "TV-14"
              }
            }
            """.trimIndent(),
        )

        assertEquals("tt3950102", result.imdbId)
        assertEquals("40907", result.malId)
        assertEquals(61628, result.tmdbId)
        assertEquals("368613", result.tvdbId)
        assertEquals("TV-14", result.ageRating)
    }
}
