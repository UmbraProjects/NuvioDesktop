package com.nuvio.app.features.streams

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CwStreamCacheTest {
    private val day = CwStreamCachePolicy.DAY_MS
    private val now = 1_800_000_000_000L

    private fun row(hash: String, name: String = "Show.S01E01.1080p.WEB-DL.x264-GRP", addonId: String = "addon:a:u") =
        StreamItem(name = name, infoHash = hash.padStart(40, '0'), addonName = "A", addonId = addonId)

    private fun rows(count: Int, name: String = "Film.2024.1080p.WEB-DL.x264-GRP") =
        (1..count).map { row(hash = it.toString(16), name = name) }

    private fun episode(releasedDaysAgo: Long) = CwStreamTarget(releasedEpochMs = now - releasedDaysAgo * day, isEpisode = true)
    private fun movie(releasedDaysAgo: Long) = CwStreamTarget(releasedEpochMs = now - releasedDaysAgo * day, isEpisode = false)

    // --- policy -------------------------------------------------------------------------------

    @Test
    fun `exactly three days old passes the age gate`() {
        assertEquals(CwStreamCachePolicy.Decision.Keep, CwStreamCachePolicy.evaluate(episode(3), rows(10), now))
    }

    @Test
    fun `just under three days old is skipped`() {
        val target = CwStreamTarget(releasedEpochMs = now - 3 * day + 1, isEpisode = true)
        assertIs<CwStreamCachePolicy.Decision.Skip>(CwStreamCachePolicy.evaluate(target, rows(10), now))
    }

    @Test
    fun `missing release date or target is skipped`() {
        assertIs<CwStreamCachePolicy.Decision.Skip>(
            CwStreamCachePolicy.evaluate(CwStreamTarget(releasedEpochMs = null, isEpisode = true), rows(20), now),
        )
        assertIs<CwStreamCachePolicy.Decision.Skip>(CwStreamCachePolicy.evaluate(null, rows(20), now))
    }

    @Test
    fun `duplicates across addons count once`() {
        val duplicated = (1..5).flatMap { hash ->
            listOf(row(hash.toString(), addonId = "addon:a:u"), row(hash.toString(), addonId = "addon:b:u"))
        }
        assertEquals(10, duplicated.size)
        assertEquals(5, CwStreamCachePolicy.distinctResultCount(duplicated))
        assertIs<CwStreamCachePolicy.Decision.Skip>(CwStreamCachePolicy.evaluate(episode(10), duplicated, now))
    }

    @Test
    fun `a movie with only cam releases is skipped`() {
        val cams = rows(12, name = "Film.2024.1080p.CAM.x264-GRP")
        assertIs<CwStreamCachePolicy.Decision.Skip>(CwStreamCachePolicy.evaluate(movie(10), cams, now))
        assertEquals(
            CwStreamCachePolicy.Decision.Keep,
            CwStreamCachePolicy.evaluate(movie(10), cams + row("ff", name = "Film.2024.2160p.WEB-DL.x265-GRP"), now),
        )
    }

    @Test
    fun `an episode skips the quality gate`() {
        val lowRes = rows(12, name = "Show.S01E01.480p.HDTV.x264-GRP")
        assertEquals(CwStreamCachePolicy.Decision.Keep, CwStreamCachePolicy.evaluate(episode(10), lowRes, now))
    }

    @Test
    fun `rows with expiring links are dropped and live annotations cleared`() {
        val signed = StreamItem(name = "x", url = "https://cdn.example/v.mkv?token=abc&expires=1", addonName = "A", addonId = "addon:a:u")
        val plain = StreamItem(
            name = "y",
            url = "https://cdn.example/v.mkv",
            addonName = "A",
            addonId = "addon:a:u",
            debridCacheStatus = StreamDebridCacheStatus("rd", "RD", StreamDebridCacheState.CACHED),
            cachedAtMs = 5L,
        )
        val kept = CwStreamCachePolicy.storableRows(listOf(signed, plain))
        assertEquals(1, kept.size)
        assertNull(kept.single().debridCacheStatus)
        assertNull(kept.single().cachedAtMs)
    }

    // --- store --------------------------------------------------------------------------------

    private fun entry(fingerprint: String = "addon:1", at: Long = now, streams: List<StreamItem> = rows(2)) =
        CwStreamStore.ProviderEntry(fingerprint = fingerprint, fetchedAtMs = at, streams = streams)

    @Test
    fun `providers are independent and a fingerprint change misses only its own`() {
        val store = CwStreamStore()
        store.put("k", mapOf("addon:a" to entry("addon:1"), "addon:b" to entry("addon:7")), now)
        assertNull(store.get("k", "addon:a", "addon:2", now))
        assertNotNull(store.get("k", "addon:b", "addon:7", now))
        assertNotNull(store.get("k", "addon:a", "addon:1", now))
    }

    @Test
    fun `an entry past retention or from the future is a miss`() {
        val store = CwStreamStore()
        store.put("k", mapOf("p" to entry(at = now)), now)
        assertNull(store.get("k", "p", "addon:1", now + 7 * day + 1))
        assertNull(store.get("k", "p", "addon:1", now - 1))
        assertNotNull(store.get("k", "p", "addon:1", now + 7 * day))
    }

    @Test
    fun `least recently used targets go past the cap`() {
        val store = CwStreamStore()
        repeat(CwStreamCachePolicy.MAX_TARGETS) { index ->
            store.put("k$index", mapOf("p" to entry(at = now)), now + index)
        }
        // Reading k0 makes it the most recent, so k1 is the one that goes.
        store.get("k0", "p", "addon:1", now + 100)
        store.put("new", mapOf("p" to entry(at = now)), now + 101)
        assertEquals(CwStreamCachePolicy.MAX_TARGETS, store.targetCount())
        assertNotNull(store.get("k0", "p", "addon:1", now + 102))
        assertNull(store.get("k1", "p", "addon:1", now + 102))
    }

    @Test
    fun `a target that left continue watching is dropped after the grace period`() {
        val store = CwStreamStore()
        store.put("gone", mapOf("p" to entry()), now)
        store.put("kept", mapOf("p" to entry()), now)
        assertTrue(!store.onContinueWatching(setOf("kept"), now + day / 2))
        assertEquals(2, store.targetCount())
        assertTrue(store.onContinueWatching(setOf("kept"), now + day + 1))
        assertEquals(1, store.targetCount())
        assertNotNull(store.get("kept", "p", "addon:1", now + day + 1))
    }

    @Test
    fun `snapshot survives a json round trip`() {
        val store = CwStreamStore()
        val streams = listOf(
            StreamItem(
                name = "Film 1080p",
                infoHash = "a".repeat(40),
                addonName = "A",
                addonId = "addon:a:u",
                behaviorHints = StreamBehaviorHints(bingeGroup = "g", filename = "f.mkv", videoSize = 10L),
                clientResolve = StreamClientResolve(type = "debrid", service = "rd", isCached = true),
            ),
        )
        store.put("k", mapOf("p" to entry(streams = streams)), now)
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        val text = json.encodeToString(CwStreamStore.Snapshot.serializer(), store.snapshot())
        val restored = CwStreamStore()
        restored.restore(json.decodeFromString(CwStreamStore.Snapshot.serializer(), text), now)
        assertEquals(streams, restored.get("k", "p", "addon:1", now)?.streams)
    }

    // --- picker -------------------------------------------------------------------------------

    @Test
    fun `autoplay leaves cached rows alone unless a debrid resolve proves them`() {
        val live = row("1")
        // A direct link from a scraper: nothing proves it before playback.
        val cachedPlain = StreamItem(name = "p", url = "https://host.example/v.mkv", addonName = "S", addonId = "plugin:s", cachedAtMs = now)
        val cachedDebrid = StreamItem(
            name = "d",
            addonName = "A",
            addonId = "addon:a:u",
            clientResolve = StreamClientResolve(type = "debrid", service = "rd", isCached = true),
            cachedAtMs = now,
        )
        assertEquals(listOf(live, cachedDebrid), listOf(live, cachedPlain, cachedDebrid).autoPlayEligible())
    }

    @Test
    fun `section header carries the oldest cache time`() {
        val group = AddonStreamGroup(
            addonName = "A",
            addonId = "addon:a:u",
            streams = listOf(row("1"), row("2").copy(cachedAtMs = 50L), row("3").copy(cachedAtMs = 20L)),
        )
        val header = buildStreamListEntries(listOf(group), showGroupHeaders = true)
            .filterIsInstance<StreamListEntry.SectionHeader>().single()
        assertEquals(20L, header.cachedAtMs)
        val liveHeader = buildStreamListEntries(listOf(group.copy(streams = listOf(row("1")))), showGroupHeaders = true)
            .filterIsInstance<StreamListEntry.SectionHeader>().single()
        assertNull(liveHeader.cachedAtMs)
    }
}
