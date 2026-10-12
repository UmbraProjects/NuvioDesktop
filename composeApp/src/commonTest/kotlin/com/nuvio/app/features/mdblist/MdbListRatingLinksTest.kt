package com.nuvio.app.features.mdblist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MdbListRatingLinksTest {

    // Trimmed from MDBList's real response for Ted Lasso (imdb/show/tt10986410), 2026-10-12.
    // IMDb's `url` is a bare number, which a strict String field rejected — every fetch failed.
    private val tedLasso = """
        {
          "ids": {"imdb": "tt10986410", "trakt": 162639, "tmdb": 97546, "tvdb": 383203, "mal": null, "mdblist": "8ic8"},
          "ratings": [
            {"source": "imdb", "value": 8.7, "url": 5},
            {"source": "metacritic", "value": 74, "url": "/ted-lasso"},
            {"source": "trakt", "value": 87, "url": null},
            {"source": "tomatoes", "value": 89, "url": "/tv/ted_lasso"},
            {"source": "popcorn", "value": 81, "url": "/tv/ted_lasso"},
            {"source": "tmdb", "value": 83, "url": null},
            {"source": "letterboxd", "value": null, "url": null}
          ],
          "keywords": []
        }
    """.trimIndent()

    @Test
    fun `each rating links straight to the title on its site`() {
        val links = MdbListMetadataService.parseEnrichment(tedLasso, mediaType = "show")
            .ratings.associate { it.source to it.url }

        assertEquals("https://www.imdb.com/title/tt10986410/", links["imdb"])
        assertEquals("https://www.metacritic.com/tv/ted-lasso/", links["metacritic"])
        assertEquals("https://trakt.tv/shows/162639", links["trakt"])
        assertEquals("https://www.rottentomatoes.com/tv/ted_lasso", links["tomatoes"])
        assertEquals("https://www.rottentomatoes.com/tv/ted_lasso", links["audience"])
        assertEquals("https://www.themoviedb.org/tv/97546", links["tmdb"])
    }

    @Test
    fun `a parsed response is marked as carrying links`() {
        assertTrue(MdbListMetadataService.parseEnrichment(tedLasso, mediaType = "show").hasLinks)
    }

    @Test
    fun `a film links to the film pages`() {
        val film = tedLasso.replace("/tv/ted_lasso", "/m/ted_lasso")
        val links = MdbListMetadataService.parseEnrichment(film, mediaType = "movie")
            .ratings.associate { it.source to it.url }

        assertEquals("https://www.metacritic.com/movie/ted-lasso/", links["metacritic"])
        assertEquals("https://trakt.tv/movies/162639", links["trakt"])
        assertEquals("https://www.themoviedb.org/movie/97546", links["tmdb"])
    }
}
