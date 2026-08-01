package com.nuvio.app.features.mdblist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the wire shape against MDBList's documented examples.
 *
 * The API accepts several spellings; these assert the exact one we emit, so a refactor cannot
 * quietly switch to a form the server interprets differently.
 */
class MdbListScrobbleBodyTest {
    @Test
    fun `movie body matches the documented movie scrobble shape`() {
        val body = MdbListScrobbleRepository.encodeBodyForTest(
            MdbListScrobbleItem.Movie(
                ids = MdbListScrobbleRepository.MdbListIds(imdb = "tt0111161", tmdb = 278),
            ),
            progress = 15.5f,
        )

        assertEquals("""{"movie":{"ids":{"imdb":"tt0111161","tmdb":278}},"progress":15.5}""", body)
    }

    @Test
    fun `episode body uses the flat season and episode form`() {
        val body = MdbListScrobbleRepository.encodeBodyForTest(
            MdbListScrobbleItem.Episode(
                ids = MdbListScrobbleRepository.MdbListIds(tmdb = 1396),
                season = 1,
                episode = 2,
            ),
            progress = 10f,
        )

        assertEquals("""{"show":{"ids":{"tmdb":1396},"season":1,"episode":2},"progress":10.0}""", body)
    }

    @Test
    fun `absent ids are omitted rather than sent as null`() {
        val body = MdbListScrobbleRepository.encodeBodyForTest(
            MdbListScrobbleItem.Movie(MdbListScrobbleRepository.MdbListIds(tmdb = 550)),
            progress = 1f,
        )

        assertFalse(body.contains("null"))
        assertFalse(body.contains("imdb"))
        assertTrue(body.contains("\"tmdb\":550"))
    }

    @Test
    fun `progress never exceeds the five-digit decimal MDBList accepts`() {
        // Regression: a raw Float serialised as 10.443218 and every request came back
        // {"error":{"progress":["Ensure that there are no more than 5 digits in total."]}}.
        listOf(0f, 0.024895826f, 10.443218f, 19.949999f, 99.999f, 100f).forEach { raw ->
            val body = MdbListScrobbleRepository.encodeBodyForTest(
                MdbListScrobbleItem.Movie(MdbListScrobbleRepository.MdbListIds(tmdb = 550)),
                progress = raw,
            )
            val rendered = body.substringAfter("\"progress\":").trimEnd('}')
            val digits = rendered.count(Char::isDigit)
            assertTrue(digits <= 5, "progress $rendered rendered $digits digits for input $raw")
        }
    }

    @Test
    fun `progress is rounded rather than truncated and stays in range`() {
        assertEquals(10.44, MdbListScrobbleRepository.wireProgress(10.443218f))
        assertEquals(19.95, MdbListScrobbleRepository.wireProgress(19.949999f))
        assertEquals(100.0, MdbListScrobbleRepository.wireProgress(140f))
        assertEquals(0.0, MdbListScrobbleRepository.wireProgress(-5f))
    }

    @Test
    fun `item keys distinguish episodes of the same show`() {
        val ids = MdbListScrobbleRepository.MdbListIds(tmdb = 1396)
        val first = MdbListScrobbleItem.Episode(ids, season = 1, episode = 1)
        val second = MdbListScrobbleItem.Episode(ids, season = 1, episode = 2)
        val otherSeason = MdbListScrobbleItem.Episode(ids, season = 2, episode = 1)

        assertEquals(3, setOf(first.itemKey, second.itemKey, otherSeason.itemKey).size)
    }

    @Test
    fun `item key prefers imdb then falls back through the accepted namespaces`() {
        fun key(ids: MdbListScrobbleRepository.MdbListIds) = MdbListScrobbleItem.Movie(ids).itemKey

        assertEquals("movie:tt0111161", key(MdbListScrobbleRepository.MdbListIds(imdb = "tt0111161", tmdb = 278)))
        assertEquals("movie:tmdb:278", key(MdbListScrobbleRepository.MdbListIds(tmdb = 278)))
        assertEquals("movie:tvdb:81189", key(MdbListScrobbleRepository.MdbListIds(tvdb = 81189)))
        assertEquals("movie:trakt:389", key(MdbListScrobbleRepository.MdbListIds(trakt = 389)))
        assertEquals("movie:mdblist:a0", key(MdbListScrobbleRepository.MdbListIds(mdblist = "a0")))
    }
}
