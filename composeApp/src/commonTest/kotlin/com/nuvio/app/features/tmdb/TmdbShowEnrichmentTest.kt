package com.nuvio.app.features.tmdb

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaPerson
import com.nuvio.app.features.details.MetaVideo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TmdbShowEnrichmentTest {

    private val sourceAware = TmdbSettings(enabled = true, apiKey = "k", showEnrichment = TmdbShowEnrichment.SourceAware)
    private val always = sourceAware.copy(showEnrichment = TmdbShowEnrichment.Always)

    private fun series(id: String = "tt0182576", still: String? = null, malId: String? = null, tvdbId: String? = null) =
        MetaDetails(
            id = id,
            type = "series",
            name = "Addon Title",
            malId = malId,
            tvdbId = tvdbId,
            description = "Addon synopsis",
            status = "Continuing",
            cast = listOf(MetaPerson(name = "Seth MacFarlane", role = "Peter")),
            videos = listOf(
                MetaVideo(id = "e1", title = "Addon Episode", season = 1, episode = 1, thumbnail = still),
                MetaVideo(id = "e2", title = "", season = 1, episode = 2),
            ),
        )

    private val enrichment = TmdbEnrichment(
        tmdbId = 1434,
        localizedTitle = "TMDB Title",
        description = "TMDB synopsis",
        genres = listOf("Animation"),
        backdrop = null, logo = null, poster = null,
        people = listOf(
            MetaPerson(name = "Seth MacFarlane", photo = "https://image.tmdb.org/seth.jpg", tmdbId = 1),
            MetaPerson(name = "Someone Only On TMDB", tmdbId = 2),
        ),
        director = emptyList(), producer = emptyList(), writer = emptyList(),
        releaseInfo = "1999", rating = 7.3, runtimeMinutes = 22, ageRating = "TV-14",
        status = "Returning Series", countries = listOf("US"), language = "en",
        productionCompanies = emptyList(), networks = emptyList(),
    )

    private val episodes = mapOf(
        (1 to 1) to TmdbEpisodeEnrichment("TMDB Episode 1", "TMDB overview 1", "https://image.tmdb.org/e1.jpg", null, "1999-01-31", 22),
        (1 to 2) to TmdbEpisodeEnrichment("TMDB Episode 2", "TMDB overview 2", "https://image.tmdb.org/e2.jpg", null, "1999-04-11", 22),
    )

    @Test
    fun `records built from TheTVDB or Kitsu are recognised, Cinemeta's are not`() {
        assertTrue(series(still = "https://artworks.thetvdb.com/banners/episodes/1.jpg").isTvdbOrKitsuShowRecord())
        assertTrue(series(id = "kitsu:12").isTvdbOrKitsuShowRecord())
        assertTrue(series(id = "tvdb:75978").isTvdbOrKitsuShowRecord())
        assertTrue(series(malId = "21").isTvdbOrKitsuShowRecord())
        // Cinemeta: an IMDb id, metahub stills, and a tvdb_id that must not count on its own.
        assertFalse(series(still = "https://episodes.metahub.space/tt0182576/1/1/w780.jpg", tvdbId = "75978").isTvdbOrKitsuShowRecord())
        // Films are always TMDB's to upgrade.
        assertFalse(series(id = "kitsu:12").copy(type = "movie").isTvdbOrKitsuShowRecord())
    }

    @Test
    fun `source-aware keeps a TVDB record's text and episodes and fills only blanks`() {
        val tvdb = series(still = "https://artworks.thetvdb.com/banners/episodes/1.jpg")
        val result = TmdbMetadataService.applyEnrichment(tvdb, enrichment, episodes, sourceAware)

        assertEquals("Addon Title", result.name)
        assertEquals("Addon synopsis", result.description)
        assertEquals("Continuing", result.status)
        assertEquals(listOf("Animation"), result.genres, "blank genres are filled")
        assertEquals("TV-14", result.ageRating, "blank age rating is filled")
        assertEquals(1434, result.tmdbId)

        val first = result.videos[0]
        assertEquals("Addon Episode", first.title)
        assertEquals("https://artworks.thetvdb.com/banners/episodes/1.jpg", first.thumbnail)
        assertEquals("TMDB overview 1", first.overview, "a blank overview is filled")
        val second = result.videos[1]
        assertEquals("TMDB Episode 2", second.title, "a blank title is filled")
        assertEquals("https://image.tmdb.org/e2.jpg", second.thumbnail)

        // One name is top billing only, so TMDB's other people follow it (see the test below).
        assertEquals(listOf("Seth MacFarlane", "Someone Only On TMDB"), result.cast.map { it.name })
        assertEquals("https://image.tmdb.org/seth.jpg", result.cast.first().photo, "TMDB adds photos and links")
    }

    @Test
    fun `a full TVDB cast is the cast, a top-billing-only one is topped up from TMDB after it`() {
        val fullCast = listOf("Seth MacFarlane", "Alex Borstein", "Seth Green", "Mila Kunis", "Mike Henry", "Patrick Warburton", "Adam West")
        val full = series(still = "https://artworks.thetvdb.com/banners/episodes/1.jpg")
            .copy(cast = fullCast.map { MetaPerson(name = it) })
        val kept = TmdbMetadataService.applyEnrichment(full, enrichment, episodes, sourceAware)
        assertEquals(fullCast, kept.cast.map { it.name }, "TMDB adds no people to a full cast")
        assertEquals("https://image.tmdb.org/seth.jpg", kept.cast.first().photo, "but does add photos and links")

        // AIOMetadata's TVDB records carry five names (Orange Is the New Black, 2026-10-19).
        val five = full.copy(cast = fullCast.take(5).map { MetaPerson(name = it) })
        val topped = TmdbMetadataService.applyEnrichment(five, enrichment, episodes, sourceAware)
        assertEquals(fullCast.take(5) + "Someone Only On TMDB", topped.cast.map { it.name }, "addon order first, TMDB after")
    }

    @Test
    fun `always, and Cinemeta records under source-aware, take TMDB's data`() {
        val tvdb = series(still = "https://artworks.thetvdb.com/banners/episodes/1.jpg")
        val replaced = TmdbMetadataService.applyEnrichment(tvdb, enrichment, episodes, always)
        assertEquals("TMDB Title", replaced.name)
        assertEquals("TMDB Episode 1", replaced.videos[0].title)
        assertEquals(2, replaced.cast.size)

        val cinemeta = series(still = "https://episodes.metahub.space/tt0182576/1/1/w780.jpg")
        val upgraded = TmdbMetadataService.applyEnrichment(cinemeta, enrichment, episodes, sourceAware)
        assertEquals("TMDB Title", upgraded.name)
        assertEquals("TMDB synopsis", upgraded.description)
        assertEquals("TMDB Episode 1", upgraded.videos[0].title)
    }
}
