package com.nuvio.app.features.p2p

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class P2pStreamingDefaultsTest {
    @Test
    fun `performance defaults raise connection limit and retain torrents`() {
        val original = buildJsonObject {
            put("ConnectionsLimit", 25)
            put("TorrentDisconnectTimeout", 30)
            put("CacheSize", 64 * 1024 * 1024)
        }

        val configured = original.withNuvioP2pPerformanceDefaults()

        assertEquals(NUVIO_TORRENT_CONNECTION_LIMIT, configured.getValue("ConnectionsLimit").jsonPrimitive.int)
        assertEquals(
            NUVIO_TORRENT_RETENTION_SECONDS,
            configured.getValue("TorrentDisconnectTimeout").jsonPrimitive.int,
        )
        assertEquals(64 * 1024 * 1024, configured.getValue("CacheSize").jsonPrimitive.int)
    }

    @Test
    fun `performance defaults are idempotent`() {
        val original = buildJsonObject {
            put("ConnectionsLimit", NUVIO_TORRENT_CONNECTION_LIMIT)
            put("TorrentDisconnectTimeout", NUVIO_TORRENT_RETENTION_SECONDS)
        }

        assertEquals(original, original.withNuvioP2pPerformanceDefaults())
    }
}
