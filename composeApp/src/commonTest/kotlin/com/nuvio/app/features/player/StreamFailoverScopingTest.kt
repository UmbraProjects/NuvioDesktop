package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamClientResolve
import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamDebridCacheStatus
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StreamFailoverScopingTest {
    private fun directStream(url: String) =
        StreamItem(url = url, addonName = "AIOStreams", addonId = "addon:aio")

    private fun debridStream(url: String, providerId: String) =
        StreamItem(
            url = url,
            addonName = "AIOStreams",
            addonId = "addon:aio",
            debridCacheStatus = StreamDebridCacheStatus(
                providerId = providerId,
                providerName = providerId,
                state = StreamDebridCacheState.CACHED,
            ),
        )

    @Test
    fun `underlying debrid provider id beats the (aggregator) url domain in the scope key`() {
        assertEquals(
            "provider:torbox",
            debridStream("https://aio.myaio.xyz/a.mkv", "torbox").rateLimitScopeKey(),
        )
    }

    @Test
    fun `client-resolve service supplies the provider scope key`() {
        val stream = StreamItem(
            url = "https://aio.myaio.xyz/a.mkv",
            addonName = "AIOStreams",
            addonId = "addon:aio",
            clientResolve = StreamClientResolve(type = "debrid", service = "RealDebrid"),
        )
        assertEquals("provider:realdebrid", stream.rateLimitScopeKey())
    }

    @Test
    fun `a plain proxied direct link can only fall back to its url domain`() {
        assertEquals("domain:myaio.xyz", directStream("https://aio.myaio.xyz/a.mkv").rateLimitScopeKey())
    }

    @Test
    fun `different subdomains of one provider collapse to the same domain key`() {
        assertEquals(
            directStream("https://store-1.torbox.app/x.mkv").rateLimitScopeKey(),
            directStream("https://store-2.torbox.app/y.mkv").rateLimitScopeKey(),
        )
    }

    @Test
    fun `a throttled provider is skipped but a different provider on the same addon is tried`() {
        val active = debridStream("https://aio.myaio.xyz/torbox1.mkv", "torbox")
        val sameProvider = debridStream("https://aio.myaio.xyz/torbox2.mkv", "torbox")
        val otherProvider = debridStream("https://aio.myaio.xyz/rd1.mkv", "realdebrid")

        val next = nextFailoverStream(
            streams = listOf(active, sameProvider, otherProvider),
            activeIdentityKey = active.playerSourceIdentityKey(),
            triedIdentityKeys = emptySet(),
            excludeScopeKey = active.rateLimitScopeKey(),
        )
        assertEquals(otherProvider.playerSourceIdentityKey(), next?.playerSourceIdentityKey())
    }

    @Test
    fun `a candidate on a different domain is tried rather than assumed to share the throttle`() {
        val active = debridStream("https://aio.myaio.xyz/torbox1.mkv", "torbox")
        val elsewhere = directStream("https://cdn.example.com/movie.mkv")

        val next = nextFailoverStream(
            streams = listOf(active, elsewhere),
            activeIdentityKey = active.playerSourceIdentityKey(),
            triedIdentityKeys = emptySet(),
            excludeScopeKey = active.rateLimitScopeKey(),
        )
        assertEquals(elsewhere.playerSourceIdentityKey(), next?.playerSourceIdentityKey())
    }

    @Test
    fun `two proxied direct links on one aggregator host share a scope key - known limitation`() {
        // Neither exposes its underlying provider (same aio.myaio.xyz host, no structured metadata),
        // so the client can't tell them apart and both collapse to the same domain scope. This is
        // the unavoidable aggregator case: provider-accurate scoping needs (1)/(2) in the scope key.
        val active = directStream("https://aio.myaio.xyz/a.mkv")
        val sibling = directStream("https://aio.myaio.xyz/b.mkv")

        val next = nextFailoverStream(
            streams = listOf(active, sibling),
            activeIdentityKey = active.playerSourceIdentityKey(),
            triedIdentityKeys = emptySet(),
            excludeScopeKey = active.rateLimitScopeKey(),
        )
        assertNull(next)
    }
}
