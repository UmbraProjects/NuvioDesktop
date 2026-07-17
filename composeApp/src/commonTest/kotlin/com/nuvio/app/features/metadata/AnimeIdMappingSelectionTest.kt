package com.nuvio.app.features.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnimeIdMappingSelectionTest {
    @Test
    fun bundledPokemonMappingResolvesOriginalAndLaterSeasons() {
        val original = AnimeIdMappingRepository.lookup(
            ResolvedMediaIds(
                sourceId = "tt0168366",
                contentType = "series",
                sourceTitle = "Pokemon",
                sourceSeasonNumber = 1,
                sourceEpisodeNumber = 61,
                imdb = "tt0168366",
            ),
        )
        val advanced = AnimeIdMappingRepository.lookup(
            ResolvedMediaIds(
                sourceId = "tmdb:60572",
                contentType = "series",
                sourceTitle = "Pokemon",
                sourceSeasonNumber = 6,
                sourceEpisodeNumber = 1,
                tmdb = 60572,
            ),
        )

        assertEquals(486, original?.kitsuId)
        assertEquals(1404, advanced?.kitsuId)
    }

    @Test
    fun missingSeasonDefaultsToFirstSeason() {
        val originalPokemon = AnimeIdMapping(kitsuId = 486, malId = 527)

        val selected = selectAnimeMappingByCoordinates(
            candidates = listOf(originalPokemon),
            season = 1,
            episode = 61,
            coordinateSystem = AnimeMappingCoordinateSystem.AUTO,
        )

        assertEquals(originalPokemon, selected)
    }

    @Test
    fun episodeOffsetSelectsTheCorrectSplitCour() {
        val firstCour = AnimeIdMapping(kitsuId = 1, tmdbSeason = 2, tmdbEpisodeOffset = 0)
        val secondCour = AnimeIdMapping(kitsuId = 2, tmdbSeason = 2, tmdbEpisodeOffset = 12)

        assertEquals(
            firstCour,
            selectAnimeMappingByCoordinates(
                candidates = listOf(firstCour, secondCour),
                season = 2,
                episode = 12,
                coordinateSystem = AnimeMappingCoordinateSystem.TMDB,
            ),
        )
        assertEquals(
            secondCour,
            selectAnimeMappingByCoordinates(
                candidates = listOf(firstCour, secondCour),
                season = 2,
                episode = 13,
                coordinateSystem = AnimeMappingCoordinateSystem.TMDB,
            ),
        )
    }

    @Test
    fun coordinateNamespacePreventsTmdbTvdbCrossMatch() {
        val tmdbSeasonTwo = AnimeIdMapping(
            kitsuId = 1,
            tmdbSeason = 2,
            tvdbSeason = 3,
        )
        val tvdbSeasonTwo = AnimeIdMapping(
            kitsuId = 2,
            tmdbSeason = 3,
            tvdbSeason = 2,
        )

        assertEquals(
            tmdbSeasonTwo,
            selectAnimeMappingByCoordinates(
                candidates = listOf(tmdbSeasonTwo, tvdbSeasonTwo),
                season = 2,
                episode = 1,
                coordinateSystem = AnimeMappingCoordinateSystem.TMDB,
            ),
        )
        assertEquals(
            tvdbSeasonTwo,
            selectAnimeMappingByCoordinates(
                candidates = listOf(tmdbSeasonTwo, tvdbSeasonTwo),
                season = 2,
                episode = 1,
                coordinateSystem = AnimeMappingCoordinateSystem.TVDB,
            ),
        )
    }

    @Test
    fun ambiguousCoordinateDoesNotGuessFromJsonOrder() {
        val first = AnimeIdMapping(kitsuId = 1, tmdbSeason = 1)
        val second = AnimeIdMapping(kitsuId = 2, tmdbSeason = 1)

        assertNull(
            selectAnimeMappingByCoordinates(
                candidates = listOf(first, second),
                season = 1,
                episode = 1,
                coordinateSystem = AnimeMappingCoordinateSystem.TMDB,
            ),
        )
    }
}
