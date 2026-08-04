package com.nuvio.app.features.tmdb

import com.nuvio.app.features.details.MetaCompany
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaPerson
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.quarantineMismatchedImdbTmdbIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class TmdbMetadataServiceTest {
    @Test
    fun `confirmed id mismatch quarantines provider derived fields but keeps addon content`() {
        val base = MetaDetails(
            id = "tt0314979",
            type = "series",
            name = "The Shiny Group",
            tmdbId = 71365,
            imdbId = "tt0314979",
            tvdbId = "439981",
            poster = "battlestar-poster",
            background = "battlestar-background",
            logo = "battlestar-logo",
            description = "The Kingdom of Huanxi held a court musician selection competition.",
            releaseInfo = "2023",
            imdbRating = "8.5",
            cast = listOf(MetaPerson(name = "Wang Yijin")),
            videos = listOf(MetaVideo(id = "tt0314979:1:1", title = "Episode 1", season = 1, episode = 1)),
        )

        val result = base.quarantineMismatchedImdbTmdbIdentity()

        assertFalse(result.imdbTmdbIdentityTrusted)
        assertNull(result.tmdbId)
        assertNull(result.imdbId)
        assertNull(result.poster)
        assertNull(result.background)
        assertNull(result.logo)
        assertNull(result.imdbRating)
        assertEquals("439981", result.tvdbId)
        assertEquals(base.description, result.description)
        assertEquals(base.cast, result.cast)
        assertEquals(base.videos, result.videos)
        assertEquals(base.id, result.id)
    }

    @Test
    fun `shiny group and battlestar are detected as different identities`() {
        assertEquals(
            true,
            TmdbMetadataService.looksLikeDifferentTitle(
                titleA = "The Shiny Group",
                releaseInfoA = "2023",
                titleB = "Battlestar Galactica",
                releaseInfoB = "2003-12-08",
            ),
        )
    }

    @Test
    fun `buildStandaloneMeta maps tmdb enrichment without addon meta`() {
        val enrichment = TmdbEnrichment(
            localizedTitle = "TMDB Movie",
            description = "TMDB description",
            genres = listOf("Adventure"),
            backdrop = "backdrop",
            logo = "logo",
            poster = "poster",
            people = listOf(MetaPerson(name = "Cast Member", role = "Hero")),
            director = listOf("Director"),
            producer = emptyList(),
            writer = listOf("Writer"),
            releaseInfo = "2026-01-01",
            rating = 8.4,
            runtimeMinutes = 105,
            ageRating = "PG-13",
            status = "Released",
            countries = listOf("US", "GB"),
            language = "en",
            productionCompanies = listOf(MetaCompany(name = "Studio")),
            networks = emptyList(),
        )

        val result = TmdbMetadataService.buildStandaloneMeta(
            type = "movie",
            id = "tmdb:123",
            tmdbId = 123,
            enrichment = enrichment,
        )

        assertEquals("tmdb:123", result.id)
        assertEquals("movie", result.type)
        assertEquals("TMDB Movie", result.name)
        assertEquals("TMDB description", result.description)
        assertEquals("8.4", result.imdbRating)
        assertEquals("105m", result.runtime)
        assertEquals("US, GB", result.country)
        assertEquals(listOf("Cast Member"), result.cast.map { it.name })
        assertEquals(listOf("Studio"), result.productionCompanies.map { it.name })
    }

    @Test
    fun `applyEnrichment replaces enabled metadata groups`() {
        val base = MetaDetails(
            id = "tt1234567",
            type = "series",
            name = "Original",
            description = "Addon description",
            videos = listOf(
                MetaVideo(
                    id = "ep1",
                    title = "Episode 1",
                    season = 1,
                    episode = 1,
                ),
            ),
        )
        val enrichment = TmdbEnrichment(
            localizedTitle = "Localized",
            description = "TMDB description",
            genres = listOf("Drama", "Mystery"),
            backdrop = "https://example.com/backdrop.jpg",
            logo = "https://example.com/logo.png",
            poster = "https://example.com/poster.jpg",
            people = listOf(MetaPerson(name = "Person", role = "Creator")),
            director = listOf("Director Name"),
            producer = emptyList(),
            writer = emptyList(),
            releaseInfo = "2024-01-01",
            rating = 8.4,
            runtimeMinutes = 52,
            ageRating = "TV-MA",
            status = "Returning Series",
            countries = listOf("US"),
            language = "en",
            productionCompanies = listOf(MetaCompany(name = "A24")),
            networks = listOf(MetaCompany(name = "HBO")),
        )
        val episodes = mapOf(
            (1 to 1) to TmdbEpisodeEnrichment(
                title = "Pilot",
                overview = "Episode overview",
                thumbnail = "https://example.com/thumb.jpg",
                airDate = "2024-01-01",
                runtimeMinutes = 58,
            ),
        )

        val result = TmdbMetadataService.applyEnrichment(
            meta = base,
            enrichment = enrichment,
            episodeMap = episodes,
            settings = TmdbSettings(enabled = true),
        )

        assertEquals("Localized", result.name)
        assertEquals("TMDB description", result.description)
        assertEquals(listOf("Drama", "Mystery"), result.genres)
        assertEquals("8.4", result.imdbRating)
        assertEquals("TV-MA", result.ageRating)
        assertEquals("52m", result.runtime)
        assertEquals(listOf("Director Name"), result.director)
        assertEquals(listOf("A24"), result.productionCompanies.map { it.name })
        assertEquals(listOf("HBO"), result.networks.map { it.name })
        assertEquals("Pilot", result.videos.first().title)
        assertEquals(58, result.videos.first().runtime)
    }

    @Test
    fun `applyEnrichment preserves disabled groups`() {
        val base = MetaDetails(
            id = "tt7654321",
            type = "movie",
            name = "Original",
            description = "Original description",
            videos = listOf(
                MetaVideo(
                    id = "movie",
                    title = "Original title",
                ),
            ),
        )
        val enrichment = TmdbEnrichment(
            localizedTitle = "Localized",
            description = "TMDB description",
            genres = listOf("Sci-Fi"),
            backdrop = "backdrop",
            logo = "logo",
            poster = "poster",
            people = listOf(MetaPerson(name = "Cast Member")),
            director = listOf("Director"),
            producer = emptyList(),
            writer = listOf("Writer"),
            releaseInfo = "2025-05-05",
            rating = 7.2,
            runtimeMinutes = 124,
            ageRating = "PG-13",
            status = "Released",
            countries = listOf("US"),
            language = "en",
            productionCompanies = listOf(MetaCompany(name = "Studio")),
            networks = emptyList(),
        )

        val result = TmdbMetadataService.applyEnrichment(
            meta = base,
            enrichment = enrichment,
            episodeMap = emptyMap(),
            settings = TmdbSettings(
                enabled = true,
                useArtwork = false,
                useBasicInfo = false,
                useDetails = false,
                useCredits = false,
                useProductions = false,
                useNetworks = false,
                useEpisodes = false,
            ),
        )

        assertEquals(base.name, result.name)
        assertEquals(base.description, result.description)
        assertEquals(base.genres, result.genres)
        assertEquals(base.director, result.director)
        assertEquals(base.cast, result.cast)
        assertEquals(base.productionCompanies, result.productionCompanies)
    }

    @Test
    fun `applyEnrichment preserves addon cast order while adding tmdb cast details`() {
        val base = MetaDetails(
            id = "tt0386676",
            type = "series",
            name = "The Office",
            cast = listOf(
                MetaPerson(name = "Steve Carell"),
                MetaPerson(name = "Rainn Wilson"),
                MetaPerson(name = "John Krasinski"),
                MetaPerson(name = "Jenna Fischer"),
            ),
        )
        val enrichment = TmdbEnrichment(
            localizedTitle = "The Office",
            description = null,
            genres = emptyList(),
            backdrop = null,
            logo = null,
            poster = null,
            people = listOf(
                MetaPerson(name = "Rainn Wilson", role = "Dwight Schrute", photo = "rainn.jpg", tmdbId = 1),
                MetaPerson(name = "John Krasinski", role = "Jim Halpert", photo = "john.jpg", tmdbId = 2),
                MetaPerson(name = "Jenna Fischer", role = "Pam Beesly", photo = "jenna.jpg", tmdbId = 3),
                MetaPerson(name = "Ed Helms", role = "Andy Bernard", photo = "ed.jpg", tmdbId = 4),
                MetaPerson(name = "Steve Carell", role = "Michael Scott", photo = "steve.jpg", tmdbId = 5),
            ),
            director = emptyList(),
            producer = emptyList(),
            writer = emptyList(),
            releaseInfo = null,
            rating = null,
            runtimeMinutes = null,
            ageRating = null,
            status = null,
            countries = emptyList(),
            language = null,
            productionCompanies = emptyList(),
            networks = emptyList(),
        )

        val result = TmdbMetadataService.applyEnrichment(
            meta = base,
            enrichment = enrichment,
            episodeMap = emptyMap(),
            settings = TmdbSettings(enabled = true),
        )

        assertEquals(
            listOf("Steve Carell", "Rainn Wilson", "John Krasinski", "Jenna Fischer", "Ed Helms"),
            result.cast.map { it.name },
        )
        assertEquals("Michael Scott", result.cast.first().role)
        assertEquals("steve.jpg", result.cast.first().photo)
        assertEquals(5, result.cast.first().tmdbId)
    }
}
