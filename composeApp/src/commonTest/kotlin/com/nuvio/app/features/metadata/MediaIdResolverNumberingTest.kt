package com.nuvio.app.features.metadata

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaIdResolverNumberingTest {
    @Test
    fun entryLocalNativeEpisodeAppliesItsOffset() {
        val ids = ResolvedMediaIds(
            sourceId = "kitsu:100",
            contentType = "series",
            sourceSeasonNumber = 1,
            sourceEpisodeNumber = 1,
            kitsu = 100,
            tmdbSeason = 2,
            tmdbEpisodeOffset = 12,
            isAnime = true,
            franchiseNumbering = false,
        )

        assertEquals(2, ids.canonicalSeasonNumber(1))
        assertEquals(13, ids.canonicalEpisodeNumber(1))
    }

    @Test
    fun franchiseEpisodeDoesNotApplySplitOffsetTwice() {
        val ids = ResolvedMediaIds(
            sourceId = "tmdb:123",
            contentType = "series",
            sourceSeasonNumber = 2,
            sourceEpisodeNumber = 13,
            tmdb = 123,
            tmdbSeason = 2,
            tmdbEpisodeOffset = 12,
            isAnime = true,
            franchiseNumbering = true,
        )

        assertEquals(2, ids.canonicalSeasonNumber(2))
        assertEquals(13, ids.canonicalEpisodeNumber(13))
    }

    @Test
    fun tvdbSourceUsesTvdbSeasonAndOffset() {
        val ids = ResolvedMediaIds(
            sourceId = "tvdb:456",
            contentType = "series",
            sourceSeasonNumber = 1,
            sourceEpisodeNumber = 2,
            tmdbSeason = 4,
            tvdbSeason = 3,
            tmdbEpisodeOffset = 20,
            tvdbEpisodeOffset = 10,
            isAnime = true,
        )

        assertEquals(3, ids.canonicalSeasonNumber(1))
        assertEquals(12, ids.canonicalEpisodeNumber(2))
    }

    @Test
    fun pokemonSeasonMissingFromAnimeMappingPreservesFranchiseEpisodeOne() {
        val resolved = MediaIdResolver.resolveLocalEpisodeIdentity(
            contentType = "series",
            parentMetaId = "kitsu:486",
            videoId = "kitsu:486:7:1",
            title = "Pokemon",
            season = 7,
            episode = 1,
            isAnimeHint = true,
        )

        assertEquals("tt0168366:7:1", resolved.videoId)
        assertEquals(7, resolved.streamSeason)
        assertEquals(1, resolved.streamEpisode)
    }

    @Test
    fun pokemonExplicitMappedSeasonStillUsesEntryLocalKitsuId() {
        val resolved = MediaIdResolver.resolveLocalEpisodeIdentity(
            contentType = "series",
            parentMetaId = "kitsu:486",
            videoId = "kitsu:486:6:41",
            title = "Pokemon",
            season = 6,
            episode = 1,
            isAnimeHint = true,
        )

        assertEquals("kitsu:1404:41", resolved.videoId)
        assertEquals(null, resolved.streamSeason)
        assertEquals(41, resolved.streamEpisode)
    }

    @Test
    fun pokemonFranchiseStreamIdRemainsScopedWhenLaunchIsResolvedAgain() {
        val resolved = MediaIdResolver.resolveLocalEpisodeIdentity(
            contentType = "series",
            parentMetaId = "kitsu:486",
            videoId = "tt0168366:7:1",
            title = "Pokemon",
            season = 7,
            episode = 41,
            isAnimeHint = true,
        )

        assertEquals("tt0168366:7:1", resolved.videoId)
        assertEquals(7, resolved.streamSeason)
        assertEquals(1, resolved.streamEpisode)
    }
}
