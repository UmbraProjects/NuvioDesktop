package com.nuvio.app.features.tmdb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The shape PostersPlus hands out: every id it can use is named, so both must be supplied.
private const val QUERY_TEMPLATE =
    "http://postersplus:8000/poster?tmdb_id={tmdb_id}&imdb_id={imdb_id}&type={type}&bar_style=frosted"

// A poster service addressed by one id only (RPDB, and PostersPlus's preset path form).
private const val PATH_TEMPLATE = "https://example.test/p/preset/{type}/{imdb_id}.jpg"

// The key placeholders a self-hosted instance with no keys of its own asks the client to forward.
private const val KEYED_TEMPLATE =
    "http://postersplus:8000/poster?tmdb_id={tmdb_id}&imdb_id={imdb_id}&type={type}" +
        "&tmdb_key={tmdb_key}&mdblist_key={mdblist_key}"

private const val OPTIONAL_ANIME_TEMPLATE =
    "http://postersplus:8000/poster?tmdb_id={tmdb_id?}&imdb_id={imdb_id?}" +
        "&anilist_id={anilist_id?}&kitsu_id={kitsu_id?}&mal_id={mal_id?}&type={type}"

private const val STREMIO_ANIME_TEMPLATE =
    "https://postersplus.stremio.ru/poster?tmdb_id={tmdb_id}&imdb_id={imdb_id}" +
        "&stremio_id={id}&type={type}"

private fun settings(template: String, enabled: Boolean = true, apiKey: String = "") = TmdbSettings(
    apiKey = apiKey,
    libraryPosterEnabled = enabled,
    libraryPosterUrlTemplate = template,
)

class CustomPosterTemplateTest {
    @Test
    fun `a template naming both ids is filled when both are known`() {
        val url = customPosterUrl(
            settings = settings(QUERY_TEMPLATE),
            imdbId = "tt0120689",
            tmdbId = "497",
            type = "movie",
        )

        assertEquals(
            "http://postersplus:8000/poster?tmdb_id=497&imdb_id=tt0120689&type=movie&bar_style=frosted",
            url,
        )
    }

    @Test
    fun `a missing tmdb id yields no url rather than an empty parameter`() {
        // The service answers 400 to `tmdb_id=`, so the request is worse than not making it: the
        // card falls back but every surface that shows the primary url renders a broken image.
        val url = customPosterUrl(
            settings = settings(QUERY_TEMPLATE),
            imdbId = "tt0120689",
            tmdbId = null,
            type = "movie",
        )

        assertNull(url)
    }

    @Test
    fun `a missing imdb id yields no url either`() {
        val url = customPosterUrl(
            settings = settings(QUERY_TEMPLATE),
            imdbId = null,
            tmdbId = "497",
            type = "series",
        )

        assertNull(url)
    }

    @Test
    fun `a blank id is treated as missing`() {
        val url = customPosterUrl(
            settings = settings(QUERY_TEMPLATE),
            imdbId = "  ",
            tmdbId = "497",
            type = "movie",
        )

        assertNull(url)
    }

    @Test
    fun `a template naming one id ignores the id it does not use`() {
        val url = customPosterUrl(
            settings = settings(PATH_TEMPLATE),
            imdbId = "tt0120689",
            tmdbId = null,
            type = "movie",
        )

        assertEquals("https://example.test/p/preset/movie/tt0120689.jpg", url)
    }

    @Test
    fun `a path template with no usable id yields no url instead of a hole in the path`() {
        val url = customPosterUrl(
            settings = settings(PATH_TEMPLATE),
            imdbId = null,
            tmdbId = "497",
            type = "movie",
        )

        assertNull(url)
    }

    @Test
    fun `key placeholders are filled from the user's own keys`() {
        val url = customPosterUrl(
            settings = settings(KEYED_TEMPLATE, apiKey = "tmdb-key-value"),
            imdbId = "tt0120689",
            tmdbId = "497",
            type = "movie",
            mdbListApiKey = "mdblist-key-value",
        )

        assertEquals(
            "http://postersplus:8000/poster?tmdb_id=497&imdb_id=tt0120689&type=movie" +
                "&tmdb_key=tmdb-key-value&mdblist_key=mdblist-key-value",
            url,
        )
    }

    @Test
    fun `an unknown key is sent empty rather than suppressing the request`() {
        // Opposite of the id rule: an instance holding its own TMDB key serves `tmdb_key=` exactly
        // as it serves the parameter being absent, so refusing to build the URL would break it.
        val url = customPosterUrl(
            settings = settings(KEYED_TEMPLATE, apiKey = ""),
            imdbId = "tt0120689",
            tmdbId = "497",
            type = "movie",
            mdbListApiKey = null,
        )

        assertEquals(
            "http://postersplus:8000/poster?tmdb_id=497&imdb_id=tt0120689&type=movie" +
                "&tmdb_key=&mdblist_key=",
            url,
        )
    }

    @Test
    fun `a missing id still suppresses a keyed template`() {
        val url = customPosterUrl(
            settings = settings(KEYED_TEMPLATE, apiKey = "tmdb-key-value"),
            imdbId = "tt0120689",
            tmdbId = null,
            type = "movie",
            mdbListApiKey = "mdblist-key-value",
        )

        assertNull(url)
    }

    @Test
    fun `the feature being off wins over everything`() {
        val url = customPosterUrl(
            settings = settings(QUERY_TEMPLATE, enabled = false),
            imdbId = "tt0120689",
            tmdbId = "497",
            type = "movie",
        )

        assertNull(url)
    }

    @Test
    fun `optional alternative ids allow a kitsu-only library item`() {
        val url = customPosterUrl(
            settings = settings(OPTIONAL_ANIME_TEMPLATE),
            imdbId = null,
            tmdbId = null,
            type = "series",
            kitsuId = "395",
        )

        assertEquals(
            "http://postersplus:8000/poster?tmdb_id=&imdb_id=&anilist_id=&kitsu_id=395&mal_id=&type=series",
            url,
        )
    }

    @Test
    fun `raw stremio id supports the PostersPlus anime contract`() {
        val url = customPosterUrl(
            settings = settings(STREMIO_ANIME_TEMPLATE),
            imdbId = null,
            tmdbId = null,
            type = "series",
            stremioId = "kitsu:395",
        )

        assertEquals(
            "https://postersplus.stremio.ru/poster?tmdb_id=&imdb_id=&stremio_id=kitsu:395&type=series",
            url,
        )
    }

    @Test
    fun `raw stremio id remains required when the template names it`() {
        val url = customPosterUrl(
            settings = settings(STREMIO_ANIME_TEMPLATE),
            imdbId = "tt0347149",
            tmdbId = "4935",
            type = "movie",
            stremioId = null,
        )

        assertNull(url)
    }

    @Test
    fun `raw stremio id marks an anime template as supported`() {
        assertTrue(settings(STREMIO_ANIME_TEMPLATE).customPosterTemplateUsesNativeAnimeId())
    }

    @Test
    fun `all native anime ids are substituted`() {
        val url = customPosterUrl(
            settings = settings(OPTIONAL_ANIME_TEMPLATE),
            imdbId = "tt0347149",
            tmdbId = "4935",
            type = "movie",
            anilistId = "431",
            kitsuId = "395",
            malId = "431",
        )

        assertEquals(
            "http://postersplus:8000/poster?tmdb_id=4935&imdb_id=tt0347149" +
                "&anilist_id=431&kitsu_id=395&mal_id=431&type=movie",
            url,
        )
    }

    @Test
    fun `strict kitsu placeholder still rejects a missing id`() {
        val url = customPosterUrl(
            settings = settings("https://example.test/{KITSU_ID}/{type}.jpg"),
            imdbId = "tt0347149",
            tmdbId = null,
            type = "movie",
            kitsuId = null,
        )

        assertNull(url)
    }

}
