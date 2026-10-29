package com.nuvio.app.features.music.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Fixtures follow the shapes of Last.fm's JSON API (artist.getTopTags, tag.getTopArtists, error answers). */
class LastFmTest {
    @Test
    fun topTagsKeepTheCorrectedNameAndDropWeakTags() {
        val body = """
        {"toptags":{"tag":[
          {"count":100,"name":"Indie","url":"https://www.last.fm/tag/indie"},
          {"count":62,"name":"soul","url":"https://www.last.fm/tag/soul"},
          {"count":3,"name":"seen live","url":"https://www.last.fm/tag/seen+live"}
        ],"@attr":{"artist":"Hozier"}}}
        """
        val tags = parseLastFmTopTags(body)!!
        assertEquals("Hozier", tags.artist)
        assertEquals(mapOf("indie" to 100, "soul" to 62), tags.tags)
    }

    @Test
    fun opinionAndTriviaTagsAreDroppedAndGenresKept() {
        val tags = lastFmGenreTags(
            mapOf(
                "easy listening" to 100, "cute" to 80, "nice" to 70, "disney" to 60, "teen pop" to 55,
                "Hip Hop" to 40, "hip-hop" to 50, "beautiful" to 30, "indie folk" to 20,
            ),
        )
        assertEquals(listOf("easy listening", "teen pop", "hip-hop", "indie folk"), tags.keys.toList())
        assertEquals(50, tags["hip-hop"])
    }

    @Test
    fun aSingleTagArrivesAsAnObjectAndStillParses() {
        val body = """{"toptags":{"tag":{"count":"40","name":"Folk"},"@attr":{"artist":"Someone"}}}"""
        assertEquals(mapOf("folk" to 40), parseLastFmTopTags(body)!!.tags)
    }

    @Test
    fun errorAnswersAreNull() {
        val bad = """{"message":"Invalid API key - You must be granted a valid key by last.fm","error":10}"""
        assertNull(parseLastFmTopTags(bad))
        assertEquals(10, lastFmErrorCode(bad))
        assertNull(lastFmErrorCode("""{"toptags":{}}"""))
        assertEquals(emptyList(), parseLastFmTagArtists(bad))
    }

    @Test
    fun tagChartListsArtistsInOrder() {
        val body = """
        {"topartists":{"artist":[
          {"name":"Slowdive","mbid":"x","@attr":{"rank":"1"}},
          {"name":"My Bloody Valentine","@attr":{"rank":"2"}},
          {"name":"Slowdive","@attr":{"rank":"3"}}
        ],"@attr":{"tag":"shoegaze"}}}
        """
        assertEquals(listOf("Slowdive", "My Bloody Valentine"), parseLastFmTagArtists(body))
    }
}
