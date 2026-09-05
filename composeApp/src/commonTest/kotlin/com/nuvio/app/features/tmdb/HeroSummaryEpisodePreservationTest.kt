package com.nuvio.app.features.tmdb

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `MetaDetailsRepository.fetchHeroSummary` drops TMDB's per-episode decoration, which is what
 * removes 51% of the app's TMDB traffic. It is only safe to do that because the decoration is
 * additive: the video list itself comes from the addon, and the discovery badges that count seasons
 * and episodes read that list.
 *
 * These pin exactly that. If enrichment ever starts *building* the video list rather than decorating
 * one, the hero summary would silently start reporting a series as having no seasons, and the
 * "Limited series" and "Binge ready" badges would quietly disappear from every hero.
 */
class HeroSummaryEpisodePreservationTest {

    private val settings = TmdbSettings(
        enabled = true,
        apiKey = "test-key",
        useArtwork = true,
        useEpisodes = true,
    )

    private fun seriesWithEpisodes(): MetaDetails = MetaDetails(
        id = "tt1234567",
        type = "series",
        name = "A Series",
        videos = listOf(
            MetaVideo(id = "s1e1", title = "Pilot", season = 1, episode = 1),
            MetaVideo(id = "s1e2", title = "Second", season = 1, episode = 2),
            MetaVideo(id = "s2e1", title = "Return", season = 2, episode = 1),
        ),
    )

    @Test
    fun `an empty episode map leaves the addon video list untouched`() {
        val base = seriesWithEpisodes()

        val result = TmdbMetadataService.applyEnrichment(
            meta = base,
            enrichment = null,
            episodeMap = emptyMap(),
            settings = settings,
        )

        assertEquals(base.videos, result.videos)
    }

    @Test
    fun `the counts the discovery badges compute survive dropping episode enrichment`() {
        val base = seriesWithEpisodes()

        val result = TmdbMetadataService.applyEnrichment(
            meta = base,
            enrichment = null,
            episodeMap = emptyMap(),
            settings = settings,
        )

        // Mirrors HeroDiscoveryMetadataService's own arithmetic.
        val seasons = result.videos.mapNotNull { it.season }.filter { it > 0 }.distinct()
        val episodeCount = result.videos.count { (it.season ?: 0) > 0 && (it.episode ?: 0) > 0 }

        assertEquals(listOf(1, 2), seasons)
        assertEquals(3, episodeCount)
    }

    @Test
    fun `a series whose addon returned no videos still reports none`() {
        // The empty case must stay empty rather than becoming null or throwing - the hero summary
        // reaches this path for any series whose addon has no video list.
        val base = MetaDetails(id = "tt7654321", type = "series", name = "Empty")

        val result = TmdbMetadataService.applyEnrichment(
            meta = base,
            enrichment = null,
            episodeMap = emptyMap(),
            settings = settings,
        )

        assertEquals(emptyList(), result.videos)
    }

    /**
     * The hero can only ever play a season-1 trailer, so the summary asks for exactly that one
     * season instead of one request per season.
     */
    @Test
    fun `the hero trailer scope asks for season one only`() {
        assertEquals(
            listOf(1),
            trailerSeasonsFor(TmdbMetadataService.TrailerScope.SingleSeason(1), seasonCount = 8),
        )
        assertEquals(
            (1..8).toList(),
            trailerSeasonsFor(TmdbMetadataService.TrailerScope.AllSeasons, seasonCount = 8),
        )
    }
}
