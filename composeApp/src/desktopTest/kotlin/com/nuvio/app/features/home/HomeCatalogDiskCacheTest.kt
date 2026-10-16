package com.nuvio.app.features.home

import com.nuvio.app.core.ui.ImageCacheSettings
import com.nuvio.app.core.ui.ImageCacheSettingsRepository
import com.nuvio.app.features.catalog.catalogPageFromPayload
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HomeCatalogDiskCacheTest {
    private val url = "https://addon.example/catalog/movie/top.json"
    private val payload = """{"metas":[{"id":"tt0111161","type":"movie","name":"The Shawshank Redemption","poster":"https://img.example/p.jpg"}]}"""

    @BeforeTest
    fun setUp() = runBlocking {
        ImageCacheSettingsRepository.setHomeCatalogCacheHours(ImageCacheSettings.DefaultHomeCatalogCacheHours)
        HomeCatalogDiskCache.clear()
    }

    @AfterTest
    fun tearDown() = runBlocking {
        ImageCacheSettingsRepository.setHomeCatalogCacheHours(ImageCacheSettings.DefaultHomeCatalogCacheHours)
        HomeCatalogDiskCache.clear()
    }

    @Test
    fun aStoredResponseReplaysToTheSameRowAsALiveOne() = runBlocking {
        HomeCatalogDiskCache.write(profileId = 1, sectionKey = "row", url = url, payload = payload)

        val stored = assertNotNull(HomeCatalogDiskCache.read(profileId = 1, sectionKey = "row", url = url))
        assertEquals(catalogPageFromPayload(payload), catalogPageFromPayload(stored.payload))
    }

    @Test
    fun aChangedAddonUrlOrAnotherProfileIsAMiss() = runBlocking {
        HomeCatalogDiskCache.write(profileId = 1, sectionKey = "row", url = url, payload = payload)

        assertNull(HomeCatalogDiskCache.read(profileId = 1, sectionKey = "row", url = "$url?config=changed"))
        assertNull(HomeCatalogDiskCache.read(profileId = 2, sectionKey = "row", url = url))
    }

    @Test
    fun theRowListSurvivesTheRoundTripPerProfile() = runBlocking {
        val definitions = listOf(
            HomeCatalogDefinition(
                key = "addon:movie:top",
                defaultTitle = "Top Movies",
                addonName = "Cinemeta",
                manifestUrl = "https://addon.example/manifest.json",
                type = "movie",
                catalogId = "top",
                genre = "Action",
                supportsPagination = true,
            ),
        )
        HomeCatalogDiskCache.writeDefinitions(profileId = 1, definitions = definitions)

        assertEquals(definitions, HomeCatalogDiskCache.readDefinitions(profileId = 1))
        assertNull(HomeCatalogDiskCache.readDefinitions(profileId = 2))
    }

    @Test
    fun offKeepsNothing() = runBlocking {
        HomeCatalogDiskCache.write(profileId = 1, sectionKey = "row", url = url, payload = payload)
        ImageCacheSettingsRepository.setHomeCatalogCacheHours(0)

        assertNull(HomeCatalogDiskCache.read(profileId = 1, sectionKey = "row", url = url))
        HomeCatalogDiskCache.write(profileId = 1, sectionKey = "other", url = url, payload = payload)
        ImageCacheSettingsRepository.setHomeCatalogCacheHours(ImageCacheSettings.DefaultHomeCatalogCacheHours)
        assertNull(HomeCatalogDiskCache.read(profileId = 1, sectionKey = "other", url = url))
    }
}
