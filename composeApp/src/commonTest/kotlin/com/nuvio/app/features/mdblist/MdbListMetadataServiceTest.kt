package com.nuvio.app.features.mdblist

import com.nuvio.app.features.details.MetaDetails
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MdbListMetadataServiceTest {

    @Test
    fun `mismatched imdb tmdb identity is not used for ratings lookup`() {
        val meta = MetaDetails(
            id = "tt0314979",
            type = "series",
            name = "The Shiny Group",
            imdbId = "tt0314979",
            imdbTmdbIdentityTrusted = false,
        )

        assertNull(MdbListMetadataService.resolveLookup(meta, meta.id))
    }

    @Test
    fun `normalizes mdblist myanimelist source to mal provider`() {
        assertEquals(
            MdbListMetadataService.PROVIDER_MAL,
            MdbListMetadataService.providerIdForSource("myanimelist"),
        )
    }

    @Test
    fun `anime-native meta prefers embedded imdb id for lookup`() {
        val meta = MetaDetails(
            id = "kitsu:42898",
            type = "series",
            name = "World Trigger 2",
            imdbId = "tt3950102",
            malId = "40907",
        )

        val lookup = assertNotNull(MdbListMetadataService.resolveLookup(meta, meta.id))

        assertEquals(MdbListMetadataService.PROVIDER_IMDB, lookup.provider)
        assertEquals("tt3950102", lookup.id)
        assertTrue(
            MdbListMetadataService.shouldFetchForMeta(
                meta = meta,
                fallbackItemId = meta.id,
                settings = MdbListSettings(enabled = true, apiKey = "test-key"),
            ),
        )
    }

    @Test
    fun `anime-native meta falls back to direct mal lookup`() {
        val meta = MetaDetails(
            id = "mal:62516",
            type = "series",
            name = "The Fragrant Flower Blooms with Dignity",
            malId = "62516",
        )

        val lookup = assertNotNull(MdbListMetadataService.resolveLookup(meta, meta.id))

        assertEquals(MdbListMetadataService.PROVIDER_MAL, lookup.provider)
        assertEquals("62516", lookup.id)
    }
}
