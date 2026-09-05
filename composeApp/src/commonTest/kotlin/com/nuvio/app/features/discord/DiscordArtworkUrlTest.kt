package com.nuvio.app.features.discord

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscordArtworkUrlTest {

    @Test
    fun `accepts the public CDN artwork the stock catalogues hand out`() {
        listOf(
            "https://images.metahub.space/poster/medium/tt0434706/img",
            "https://image.tmdb.org/t/p/w500/abc.jpg",
            "https://api.ratingposterdb.com/key/imdb/poster-default/tt0434706.jpg?fallback=true",
            "https://postersplus.stremio.ru/poster?tmdb_id=&imdb_id=&stremio_id=kitsu:395&type=series",
            "http://cdn.example.com/poster.jpg",
        ).forEach { assertTrue(isExternallyFetchableArtworkUrl(it), it) }
    }

    /**
     * The regression: a self-hosted poster service passed the old scheme-only test, so Discord was
     * handed an address only the user's own machine can resolve.
     */
    @Test
    fun `rejects a self-hosted poster service Discord cannot reach`() {
        listOf(
            "http://postersplus:8000/poster?tmdb_id=497",
            "http://localhost:8000/poster?tmdb_id=497",
            "http://127.0.0.1:8000/poster.jpg",
            "http://192.168.1.50:8000/poster.jpg",
            "http://10.0.0.5/poster.jpg",
            "http://172.16.4.4/poster.jpg",
            "http://172.31.4.4/poster.jpg",
            "http://169.254.1.1/poster.jpg",
            "http://nas.local/poster.jpg",
            "http://media.lan/poster.jpg",
            "http://posters.internal/poster.jpg",
            "http://[::1]:8000/poster.jpg",
        ).forEach { assertFalse(isExternallyFetchableArtworkUrl(it), it) }
    }

    @Test
    fun `keeps public addresses that merely look private`() {
        listOf(
            "https://172.32.4.4/poster.jpg",
            "https://172.15.4.4/poster.jpg",
            "https://192.169.1.1/poster.jpg",
            "https://11.0.0.1/poster.jpg",
        ).forEach { assertTrue(isExternallyFetchableArtworkUrl(it), it) }
    }

    @Test
    fun `rejects anything that is not an absolute http url`() {
        listOf(null, "", "   ", "file:///C:/posters/a.jpg", "data:image/png;base64,AAAA", "/posters/a.jpg", "https://")
            .forEach { assertFalse(isExternallyFetchableArtworkUrl(it), it.orEmpty()) }
    }

    @Test
    fun `ignores userinfo when reading the host`() {
        assertFalse(isExternallyFetchableArtworkUrl("http://user@postersplus:8000/a.jpg"))
        assertTrue(isExternallyFetchableArtworkUrl("https://user@cdn.example.com/a.jpg"))
    }
}
