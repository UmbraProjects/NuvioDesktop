package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamSourceMergeTest {

    private fun stream(
        addon: String,
        name: String = "release",
        infoHash: String? = null,
        filename: String? = null,
        size: Long? = null,
        url: String? = "https://example.test/file",
    ) = StreamItem(
        name = name,
        url = url,
        infoHash = infoHash,
        addonName = addon,
        addonId = "addon:$addon",
        behaviorHints = StreamBehaviorHints(filename = filename, videoSize = size),
    )

    private fun group(addon: String, vararg streams: StreamItem) =
        AddonStreamGroup(addonName = addon, addonId = "addon:$addon", streams = streams.toList())

    @Test
    fun `collapses the same infohash offered by several addons into one row`() {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        val merged = StreamSourceMerge.merge(
            listOf(
                group("Torrentio", stream("Torrentio", infoHash = hash)),
                group("Comet", stream("Comet", infoHash = hash)),
                group("AIOStreams", stream("AIOStreams", infoHash = hash)),
            ),
        ) { 10 }

        assertEquals(1, merged.size)
        assertEquals(1, merged.single().streams.size)
    }

    @Test
    fun `keeps the highest scoring instance of a duplicate`() {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        val weak = stream("Torrentio", name = "weak", infoHash = hash)
        val strong = stream("Comet", name = "strong", infoHash = hash)

        val merged = StreamSourceMerge.merge(
            listOf(group("Torrentio", weak), group("Comet", strong)),
        ) { if (it.name == "strong") 50 else 5 }

        assertEquals("strong", merged.single().streams.single().name)
    }

    @Test
    fun `never drops a stream that has no reliable identity`() {
        // Two direct links with no hash, no filename and no size cannot be proven identical.
        val a = stream("AddonA", name = "a", filename = null, size = null)
        val b = stream("AddonB", name = "b", filename = null, size = null)

        val merged = StreamSourceMerge.merge(listOf(group("AddonA", a), group("AddonB", b))) { 1 }

        assertEquals(2, merged.single().streams.size)
    }

    @Test
    fun `direct links dedupe on filename and size together`() {
        val a = stream("AddonA", filename = "Movie.2024.2160p.mkv", size = 1_234L, infoHash = null)
        val b = stream("AddonB", filename = "movie.2024.2160p.mkv", size = 1_234L, infoHash = null)
        // Same name, different size — a genuinely different release, so both must survive.
        val c = stream("AddonC", filename = "Movie.2024.2160p.mkv", size = 9_999L, infoHash = null)

        val merged = StreamSourceMerge.merge(
            listOf(group("AddonA", a), group("AddonB", b), group("AddonC", c)),
        ) { 1 }

        assertEquals(2, merged.single().streams.size)
    }

    @Test
    fun `orders the merged list by score descending`() {
        val low = stream("AddonA", name = "low", infoHash = "a".repeat(40))
        val high = stream("AddonB", name = "high", infoHash = "b".repeat(40))
        val mid = stream("AddonC", name = "mid", infoHash = "c".repeat(40))

        val scores = mapOf("low" to 1, "mid" to 20, "high" to 99)
        val merged = StreamSourceMerge.merge(
            listOf(group("AddonA", low), group("AddonB", high), group("AddonC", mid)),
        ) { scores.getValue(it.name.orEmpty()) }

        assertEquals(listOf("high", "mid", "low"), merged.single().streams.map { it.name })
    }

    @Test
    fun `marks the merged group so the renderer can flatten it`() {
        val merged = StreamSourceMerge.merge(listOf(group("AddonA", stream("AddonA"))) ) { 1 }
        assertEquals(StreamSourceMerge.MERGED_ADDON_ID, merged.single().addonId)
    }

    @Test
    fun `empty input is returned untouched`() {
        assertTrue(StreamSourceMerge.merge(emptyList()) { 1 }.isEmpty())
    }

    @Test
    fun `dedupe key is null when nothing identifies the release`() {
        assertNull(StreamSourceMerge.dedupeKey(stream("AddonA", filename = null, size = null)))
    }

    @Test
    fun `htpc tab collapses and ranks exactly like a merge`() {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        val low = stream("AddonA", name = "low", infoHash = "a".repeat(40))
        val high = stream("AddonB", name = "high", infoHash = "b".repeat(40))
        val dupe = stream("AddonC", name = "high", infoHash = hash)
        val dupeAgain = stream("AddonD", name = "high", infoHash = hash)

        val scores = mapOf("low" to 1, "high" to 99)
        val tab = StreamSourceMerge.htpcTab(
            listOf(
                group("AddonA", low),
                group("AddonB", high),
                group("AddonC", dupe),
                group("AddonD", dupeAgain),
            ),
        ) { scores.getValue(it.name.orEmpty()) }

        assertEquals(listOf("high", "high", "low"), tab?.streams?.map { it.name })
    }

    @Test
    fun `htpc tab is labelled and marked as its own synthetic source`() {
        val tab = StreamSourceMerge.htpcTab(listOf(group("AddonA", stream("AddonA")))) { 1 }

        assertEquals(StreamSourceMerge.HTPC_ADDON_ID, tab?.addonId)
        assertEquals(StreamSourceMerge.HTPC_TAB_LABEL, tab?.addonName)
        assertTrue(StreamSourceMerge.isFlatSection(StreamSourceMerge.HTPC_ADDON_ID))
    }

    @Test
    fun `htpc tab is absent when there is nothing to rank`() {
        assertNull(StreamSourceMerge.htpcTab(emptyList()) { 1 })
        assertNull(StreamSourceMerge.htpcTab(listOf(group("AddonA"))) { 1 })
    }

    @Test
    fun `htpc tab survives a group that is still loading`() {
        val loading = AddonStreamGroup(
            addonName = "AddonA",
            addonId = "addon:AddonA",
            streams = emptyList(),
            isLoading = true,
        )

        val tab = StreamSourceMerge.htpcTab(listOf(loading)) { 1 }

        assertTrue(tab != null && tab.isLoading)
    }
}
