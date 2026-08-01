package com.nuvio.app.features.yamtrack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the wire shape and the URL normalisation.
 *
 * The episode body here is byte-identical to one accepted by a live instance
 * (`{"detail":"accepted"}`), so a refactor that changes the shape will fail here rather than
 * silently at runtime.
 */
class YamtrackScrobbleBodyTest {
    private val episode = YamtrackScrobbleItem.Episode(
        ids = YamtrackScrobbleRepository.YamtrackIds(
            tmdb = "98201",
            imdb = "tt11083696",
            tvdb = "382556",
        ),
        seriesTitle = "Cruel Summer",
        title = "Happy Birthday, Jeanette Turner",
        season = 1,
        episode = 1,
    )

    @Test
    fun `episode start body matches what the instance accepted`() {
        val body = YamtrackScrobbleRepository.buildBodyJson(
            action = "start",
            item = episode,
            progressPercent = 43.5f,
            positionSeconds = 1200,
            durationSeconds = 2760,
        )

        assertEquals(
            """{"action":"start","media_type":"episode",""" +
                """"ids":{"tmdb":"98201","imdb":"tt11083696","tvdb":"382556"},""" +
                """"title":"Happy Birthday, Jeanette Turner","series_title":"Cruel Summer",""" +
                """"season_number":1,"episode_number":1,""" +
                """"position_seconds":1200,"duration_seconds":2760}""",
            body,
        )
    }

    @Test
    fun `completed is stated only on stop, and follows our own threshold`() {
        fun bodyFor(action: String, percent: Float) = YamtrackScrobbleRepository.buildBodyJson(
            action = action,
            item = episode,
            progressPercent = percent,
            positionSeconds = null,
            durationSeconds = null,
        )

        assertFalse(bodyFor("start", 95f).contains("completed"))
        assertFalse(bodyFor("pause", 95f).contains("completed"))
        assertTrue(bodyFor("stop", 95f).contains(""""completed":true"""))
        assertTrue(bodyFor("stop", 12f).contains(""""completed":false"""))
        // Exactly at the threshold counts as watched.
        assertTrue(bodyFor("stop", 80f).contains(""""completed":true"""))
    }

    @Test
    fun `ids are strings and absent ones are omitted`() {
        val body = YamtrackScrobbleRepository.buildBodyJson(
            action = "start",
            item = YamtrackScrobbleItem.Movie(
                ids = YamtrackScrobbleRepository.YamtrackIds(tmdb = "550"),
                title = "Fight Club",
            ),
            progressPercent = 1f,
            positionSeconds = null,
            durationSeconds = null,
        )

        assertTrue(body.contains(""""tmdb":"550""""))
        assertFalse(body.contains("imdb"))
        assertFalse(body.contains("null"))
        assertFalse(body.contains("season_number"))
    }

    @Test
    fun `a pasted address bar url is reduced to an origin`() {
        assertEquals("https://tracker.example.com", normalizeBaseUrl("https://tracker.example.com/"))
        assertEquals("https://tracker.example.com", normalizeBaseUrl(" tracker.example.com "))
        assertEquals("https://tracker.example.com", normalizeBaseUrl("https://tracker.example.com/settings/integrations"))
        assertEquals("http://192.168.1.5:8000", normalizeBaseUrl("http://192.168.1.5:8000/api/v1"))
        assertEquals("", normalizeBaseUrl("   "))
    }

    @Test
    fun `http is never rewritten to https, but remote http is flagged`() {
        // Self-hosted instances on a LAN are legitimately plain HTTP.
        assertEquals("http://nas.local:8000", normalizeBaseUrl("http://nas.local:8000"))

        assertTrue(isInsecureRemoteBaseUrl("http://tracker.example.com"))
        assertFalse(isInsecureRemoteBaseUrl("https://tracker.example.com"))
        assertFalse(isInsecureRemoteBaseUrl("http://localhost:8000"))
        assertFalse(isInsecureRemoteBaseUrl("http://127.0.0.1:8000"))
        assertFalse(isInsecureRemoteBaseUrl("http://nas.local:8000"))
    }
}
