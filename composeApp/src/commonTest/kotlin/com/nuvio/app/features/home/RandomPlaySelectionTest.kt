package com.nuvio.app.features.home

import com.nuvio.app.features.catalog.CatalogTarget
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RandomPlaySelectionTest {
    @Test
    fun `a cold click refills before reporting no match`() = runBlocking {
        var rows = emptyList<HomeCatalogSection>()
        var loads = 0

        val picked = pickRandomPlayItemWithRefill(
            category = RandomPlayCategory.AnimeMovie,
            settings = settings(),
            watchedKeys = emptySet(),
            sourceSections = { rows },
            poolState = { RandomPlayPoolState.Empty },
            fillCandidates = {},
            loadNextCollectionWindow = {
                loads++
                rows = listOf(animeMovieRow("film"))
                false
            },
            random = Random(1),
        )

        assertEquals("film", picked?.id)
        assertEquals(1, loads)
    }

    @Test
    fun `an existing candidate does not fetch another collection window`() = runBlocking {
        var loads = 0

        val picked = pickRandomPlayItemWithRefill(
            category = RandomPlayCategory.AnimeMovie,
            settings = settings(),
            watchedKeys = emptySet(),
            sourceSections = { listOf(animeMovieRow("ready")) },
            poolState = { RandomPlayPoolState.Empty },
            fillCandidates = {},
            loadNextCollectionWindow = {
                loads++
                false
            },
            random = Random(1),
        )

        assertEquals("ready", picked?.id)
        assertEquals(0, loads)
    }

    @Test
    fun `refill stops when the source loader has no work left`() = runBlocking {
        var loads = 0

        val picked = pickRandomPlayItemWithRefill(
            category = RandomPlayCategory.AnimeMovie,
            settings = settings(),
            watchedKeys = emptySet(),
            sourceSections = { emptyList() },
            poolState = { RandomPlayPoolState.Empty },
            fillCandidates = {},
            loadNextCollectionWindow = {
                loads++
                false
            },
        )

        assertNull(picked)
        assertEquals(1, loads)
    }

    private fun settings() = HomeCatalogSettingsUiState(
        randomPlayEnabled = true,
        randomPlayIncludeCollections = true,
        randomPlayCategories = setOf(RandomPlayCategory.AnimeMovie),
        randomPlayGenres = RandomPlayGenres.toSet(),
        randomPlayMinimumImdbRating = 0f,
    )

    private fun animeMovieRow(id: String) = HomeCatalogSection(
        key = "anime-movies",
        title = "Anime Movies",
        subtitle = "",
        addonName = "Test",
        target = CatalogTarget.Addon(
            manifestUrl = "https://example.test/manifest.json",
            contentType = "anime",
            catalogId = "anime-movies",
        ),
        items = listOf(
            MetaPreview(
                id = id,
                type = "anime",
                name = id,
                animeType = "movie",
            ),
        ),
    )
}
