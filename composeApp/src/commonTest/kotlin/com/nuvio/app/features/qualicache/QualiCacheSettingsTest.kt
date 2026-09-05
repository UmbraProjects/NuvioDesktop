package com.nuvio.app.features.qualicache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QualiCacheSettingsTest {
    @Test
    fun baseUrlNormalizationDropsLegacyQueryAndEndpointPaths() {
        assertEquals(
            "https://quality.example",
            normalizeBaseUrl("https://quality.example/?min_trust=low"),
        )
        assertEquals(
            "http://qualicache:8000",
            normalizeBaseUrl("qualicache:8000/v1/quality/"),
        )
    }

    @Test
    fun legacyMinimumTrustQueryCanBeMigrated() {
        assertEquals(
            QualiCacheMinimumTrust.LOW,
            minimumTrustFromUrl("https://quality.example/?min_trust=low"),
        )
        assertNull(minimumTrustFromUrl("https://quality.example"))
    }

    @Test
    fun qualityUrlKeepsEndpointAndParametersSeparate() {
        assertEquals(
            "https://quality.example/v1/quality/series/tt10293938" +
                "?season=1&episode=1&min_trust=low&release_date=2026-08-07",
            buildQualiCacheQualityUrl(
                baseUrl = "https://quality.example",
                mediaType = "series",
                imdbId = "tt10293938",
                minimumTrust = QualiCacheMinimumTrust.LOW,
                releaseDate = "2026-08-07",
            ),
        )
    }
}
