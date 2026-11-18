package com.nuvio.app.features.usenet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UsenetSeekProbeTest {
    private fun diag(downloads: Int, hits: Int, articles: Int, bytes: Long, ttfb: Double): JsonObject =
        Json.parseToJsonElement(
            """{"store":{"requests":0,"cacheHits":$hits,"sharedJoins":0,"downloads":$downloads,"cancellations":1},
               "providers":[{"server":0,"articles":$articles,"bytes":$bytes,"missing":0,"errors":0,"ttfbMs":$ttfb}]}""",
        ).jsonObject

    @Test
    fun describesASeekOutsideTheBuffer() {
        val line = UsenetSeekProbe.describe(
            waitedMs = 2000,
            fromMs = 723_000,
            bufferedMs = 760_000,
            toMs = 3_944_000,
            profile = "balanced",
            first = diag(downloads = 100, hits = 5, articles = 100, bytes = 70L shl 20, ttfb = 150.0),
            last = diag(downloads = 118, hits = 5, articles = 118, bytes = 82L shl 20, ttfb = 190.4),
        )
        assertEquals(
            "seek 0:12:03 → 1:05:44 waited 2000 ms [balanced]: 18 articles fetched " +
                "(12.0 MB, 6.0 MB/s), 0 cache hits, 0 joined, 0 cancelled, 0 wrong article guesses; provider 0: 18 articles, 12.0 MB, first byte ~190 ms recently",
            line,
        )
    }

    @Test
    fun aSeekCountsReopenedConnectionsAndTheNewRangesFirstByte() {
        fun d(connects: Int, connectMs: Double, rangeStart: Double, firstByte: Double, served: Long, fourMb: Double = -1.0) = Json.parseToJsonElement(
            """{"store":{"downloads":0,"cacheHits":0,"sharedJoins":0,"cancellations":0},
               "providers":[{"server":0,"articles":0,"bytes":0,"connects":$connects,"connectMs":$connectMs}],
               "latestRange":{"startMs":$rangeStart,"firstByteMs":$firstByte,"bytes":$served,"fourMiBMs":$fourMb}}""",
        ).jsonObject
        val line = UsenetSeekProbe.describe(
            waitedMs = 1500, fromMs = 0, bufferedMs = 0, toMs = 3_600_000, profile = "throughput",
            first = d(connects = 40, connectMs = 9000.0, rangeStart = 1000.0, firstByte = 1200.0, served = 5L shl 20),
            last = d(connects = 52, connectMs = 10200.0, rangeStart = 50_000.0, firstByte = 50_640.0, served = 24L shl 20, fourMb = 51_100.0),
        )
        assertTrue(line.contains("12 connections reopened (1200 ms connecting)"), line)
        assertTrue(line.endsWith("; new range: engine first byte 640 ms, first 4 MB at 1100 ms, 24.0 MB served"), line)
        // The wait was first seen after the seek's request began: the snapshot from playback
        // before it still tells the new request apart.
        val late = d(connects = 40, connectMs = 9000.0, rangeStart = 50_000.0, firstByte = -1.0, served = 0)
        val seen = UsenetSeekProbe.describe(
            1500, 0, 0, 3_600_000, "throughput", late,
            d(connects = 40, connectMs = 9000.0, rangeStart = 50_000.0, firstByte = 50_300.0, served = 9L shl 20),
            rangeBaseline = d(connects = 40, connectMs = 9000.0, rangeStart = 1000.0, firstByte = 1200.0, served = 5L shl 20),
        )
        assertTrue(seen.endsWith("; new range: engine first byte 300 ms, 9.0 MB served"), seen)
        // The same range before and after: a stall, no new-range note.
        val stall = UsenetSeekProbe.describe(
            1500, 0, 0, 1000, "throughput",
            d(40, 9000.0, 1000.0, 1200.0, 5L shl 20), d(40, 9000.0, 1000.0, 1200.0, 9L shl 20),
        )
        assertTrue(!stall.contains("new range") && !stall.contains("reopened"), stall)
    }

    @Test
    fun thePreferredServersConnectionsAreRead() {
        assertEquals(50, usenetPreferredConnections(listOf("nntps://u:p@news.example:563/50?priority=0", "nntps://b:563/10?priority=1")))
        assertEquals(0, usenetPreferredConnections(emptyList()))
    }

    @Test
    fun aSmallJumpIsAStallAndMissingCountersAreSaid() {
        val line = UsenetSeekProbe.describe(900, 60_000, 90_000, 61_000, null, null, null)
        assertTrue(line.startsWith("stall at 0:01:01 waited 900 ms [?]"), line)
        assertTrue(line.endsWith("engine counters unavailable"), line)
    }
}
