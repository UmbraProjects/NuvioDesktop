package com.nuvio.app.features.debrid

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DebridProviderTest {
    @Test
    fun `torbox exposes local addon capabilities`() {
        assertTrue(DebridProviders.Torbox.authMethod == DebridProviderAuthMethod.DeviceCode)
        assertTrue(DebridProviders.Torbox.supports(DebridProviderCapability.ClientResolve))
        assertTrue(DebridProviders.Torbox.supports(DebridProviderCapability.LocalTorrentCacheCheck))
        assertTrue(DebridProviders.Torbox.supports(DebridProviderCapability.LocalTorrentResolve))
    }

    @Test
    fun `real debrid stays hidden from local addon capability paths`() {
        assertTrue(DebridProviders.RealDebrid.authMethod == DebridProviderAuthMethod.ApiKey)
        assertFalse(DebridProviders.RealDebrid.visibleInUi)
        assertTrue(DebridProviders.RealDebrid.supports(DebridProviderCapability.ClientResolve))
        assertFalse(DebridProviders.RealDebrid.supports(DebridProviderCapability.LocalTorrentCacheCheck))
        assertFalse(DebridProviders.RealDebrid.supports(DebridProviderCapability.LocalTorrentResolve))
    }
}
