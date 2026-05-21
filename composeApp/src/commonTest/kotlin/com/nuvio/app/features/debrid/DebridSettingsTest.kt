package com.nuvio.app.features.debrid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DebridSettingsTest {
    @Test
    fun `normalizes provider ids when reading api keys`() {
        val settings = DebridSettings(
            providerApiKeys = mapOf(DebridProviders.TORBOX_ID to "tb_key"),
        )

        assertEquals("tb_key", settings.apiKeyFor("TORBOX"))
        assertEquals("tb_key", settings.torboxApiKey)
        assertEquals("", settings.realDebridApiKey)
    }

    @Test
    fun `configured services are driven by visible registered providers`() {
        val settings = DebridSettings(
            providerApiKeys = mapOf(
                DebridProviders.TORBOX_ID to "tb_key",
                DebridProviders.REAL_DEBRID_ID to "rd_key",
            ),
        )

        val services = DebridProviders.configuredServices(settings)

        assertEquals(listOf(DebridProviders.TORBOX_ID), services.map { it.provider.id })
        assertEquals("tb_key", services.single().apiKey)
        assertTrue(settings.hasAnyApiKey)
        assertFalse(DebridProviders.isVisible(DebridProviders.REAL_DEBRID_ID))
    }
}
