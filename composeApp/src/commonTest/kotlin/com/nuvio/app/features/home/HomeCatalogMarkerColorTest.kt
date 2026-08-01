package com.nuvio.app.features.home

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeCatalogMarkerColorTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun markerColorsHaveStableSerializedNames() {
        assertEquals("\"blue\"", json.encodeToString(HomeCatalogMarkerColor.Blue))
        assertEquals(
            HomeCatalogMarkerColor.Pink,
            json.decodeFromString<HomeCatalogMarkerColor>("\"pink\""),
        )
    }

    @Test
    fun syncCatalogItemReadsLegacyPayloadWithoutMarkerColor() {
        val item = json.decodeFromString<SyncCatalogItem>(
            """{"addon_id":"addon","type":"movie","catalog_id":"popular"}""",
        )

        assertNull(item.markerColor)
    }

    @Test
    fun syncCatalogItemWritesSelectedMarkerColor() {
        val encoded = json.encodeToString(
            SyncCatalogItem(
                addonId = "addon",
                type = "movie",
                catalogId = "popular",
                markerColor = HomeCatalogMarkerColor.Blue.storageValue,
            ),
        )

        assertTrue(encoded.contains("\"marker_color\":\"blue\""))
    }
}
