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

    /**
     * Pokémon season 15 is three anime-list entries — Best Wishes! S2, Episode N and Decolora
     * Adventure — sharing one TVDB/TMDB season at offsets 0/24/38, and the rows carry those offsets
     * under `tvdb` only. Treating the absent tmdb offset as 0 made all three claim the season's
     * first cour, which ties and drops back to whichever entry was already open.
     */
    @Test
    fun bundledPokemonSeasonFifteenSplitsResolveFromASiblingEntry() {
        val decolora = AnimeIdMappingRepository.lookup(
            ResolvedMediaIds(sourceId = "kitsu:7895", contentType = "series", kitsu = 7895),
        )
        assertEquals(7895, decolora?.kitsuId, "bundled mapping no longer contains the Decolora entry")

        fun entryFor(episode: Int) = AnimeIdMappingRepository.franchiseEntryFor(
            base = decolora!!,
            season = 15,
            episode = episode,
        )?.kitsuId

        // Asked from the wrong sibling, so a tie would answer 7895 for every episode.
        assertEquals(7080, entryFor(1))
        assertEquals(7586, entryFor(30))
        assertEquals(7895, entryFor(45))
    }

    @Test
    fun aProviderOnlyOffsetAppliesToTheSeasonItShares() {
        val firstCour = AnimeIdMapping(kitsuId = 1, tmdbSeason = 15, tvdbSeason = 15)
        val laterCour = AnimeIdMapping(
            kitsuId = 2,
            tmdbSeason = 15,
            tvdbSeason = 15,
            tvdbEpisodeOffset = 38,
        )

        assertEquals(
            firstCour,
            selectAnimeMappingByCoordinates(
                candidates = listOf(firstCour, laterCour),
                season = 15,
                episode = 1,
                coordinateSystem = AnimeMappingCoordinateSystem.AUTO,
            ),
        )
        assertEquals(
            laterCour,
            selectAnimeMappingByCoordinates(
                candidates = listOf(firstCour, laterCour),
                season = 15,
                episode = 39,
                coordinateSystem = AnimeMappingCoordinateSystem.AUTO,
            ),
        )
    }

    /** Different seasons mean the offset is not expressed in the same numbering, so it must not
     * carry across: a tvdb offset says nothing about where a differently-numbered tmdb season
     * starts. */
    @Test
    fun aProviderOnlyOffsetDoesNotCarryAcrossDifferentSeasons() {
        val mapping = AnimeIdMapping(
            kitsuId = 1,
            tmdbSeason = 2,
            tvdbSeason = 15,
            tvdbEpisodeOffset = 38,
        )

        assertEquals(
            mapping,
            selectAnimeMappingByCoordinates(
                candidates = listOf(mapping),
                season = 2,
                episode = 1,
                coordinateSystem = AnimeMappingCoordinateSystem.TMDB,
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
