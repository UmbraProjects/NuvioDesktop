package com.nuvio.app.features.streams

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamPrefetchCacheTest {

    private val tenMinutesMs = 10L * 60L * 1000L

    private fun stream(name: String, addonId: String = ADDON) = StreamItem(
        name = name,
        addonName = "Addon",
        addonId = addonId,
    )

    @BeforeTest
    fun setUp() = StreamPrefetchCache.clear()

    @AfterTest
    fun tearDown() = StreamPrefetchCache.clear()

    @Test
    fun `stored response is served back within the retention window`() {
        val key = StreamPrefetchCache.contentKey("series", "tt999:1:2", 1, 2)
        StreamPrefetchCache.putAt(nowMs = 1_000L, contentKey = key, providerId = ADDON, streams = listOf(stream("a")))

        val entry = StreamPrefetchCache.getAt(
            nowMs = 1_000L + tenMinutesMs - 1L,
            contentKey = key,
            providerId = ADDON,
            maxAgeMs = tenMinutesMs,
        )

        assertEquals(listOf("a"), entry?.streams?.map { it.name })
    }

    @Test
    fun `response older than the retention window is dropped`() {
        val key = StreamPrefetchCache.contentKey("movie", "tt123", null, null)
        StreamPrefetchCache.putAt(nowMs = 1_000L, contentKey = key, providerId = ADDON, streams = listOf(stream("a")))

        assertNull(
            StreamPrefetchCache.getAt(
                nowMs = 1_000L + tenMinutesMs + 1L,
                contentKey = key,
                providerId = ADDON,
                maxAgeMs = tenMinutesMs,
            ),
        )
        // Expiry evicts rather than merely hiding, so a stale target stops occupying a slot.
        assertEquals(0, StreamPrefetchCache.targetCount())
    }

    @Test
    fun `a clock that moved backwards is treated as a miss, not as an infinitely fresh entry`() {
        val key = StreamPrefetchCache.contentKey("movie", "tt123", null, null)
        StreamPrefetchCache.putAt(nowMs = 10_000L, contentKey = key, providerId = ADDON, streams = listOf(stream("a")))

        assertNull(
            StreamPrefetchCache.getAt(
                nowMs = 5_000L,
                contentKey = key,
                providerId = ADDON,
                maxAgeMs = tenMinutesMs,
            ),
        )
    }

    @Test
    fun `zero retention disables the cache entirely`() {
        // This is how a forced refresh opts out: reload() distrusts what it already has, so it must
        // not be handed a background result either.
        val key = StreamPrefetchCache.contentKey("movie", "tt123", null, null)
        StreamPrefetchCache.putAt(nowMs = 1_000L, contentKey = key, providerId = ADDON, streams = listOf(stream("a")))

        assertNull(
            StreamPrefetchCache.getAt(nowMs = 1_000L, contentKey = key, providerId = ADDON, maxAgeMs = 0L),
        )
    }

    @Test
    fun `a provider that answered with nothing is stored and served`() {
        // "I have nothing for this title" is a real answer. With 61 scrapers enabled it is most of
        // them, and discarding it meant re-running fifty providers on the real play — including the
        // ones that take a full minute to time out — for an answer already in hand.
        val key = StreamPrefetchCache.contentKey("movie", "tt123", null, null)
        StreamPrefetchCache.putAt(nowMs = 1_000L, contentKey = key, providerId = ADDON, streams = emptyList())

        val entry = StreamPrefetchCache.getAt(
            nowMs = 1_000L,
            contentKey = key,
            providerId = ADDON,
            maxAgeMs = tenMinutesMs,
        )

        assertEquals(emptyList(), entry?.streams)
        assertEquals(1, StreamPrefetchCache.targetCount())
    }

    @Test
    fun `a failed fetch is not stored and frees its slot`() {
        // The other half of the same distinction: a provider that never answered has nothing to
        // replay, so the interactive path must go and ask it itself.
        val key = StreamPrefetchCache.contentKey("movie", "tt123", null, null)
        StreamPrefetchCache.claimInFlight(key, ADDON)

        StreamPrefetchCache.releaseInFlight(key, ADDON, null)

        assertNull(StreamPrefetchCache.get(key, ADDON, tenMinutesMs))
        assertEquals(0, StreamPrefetchCache.targetCount())
    }

    @Test
    fun `providers for one target are stored independently`() {
        val key = StreamPrefetchCache.contentKey("movie", "tt123", null, null)
        StreamPrefetchCache.putAt(1_000L, key, "addon:one", listOf(stream("from-one", "addon:one")))
        StreamPrefetchCache.putAt(1_000L, key, "addon:two", listOf(stream("from-two", "addon:two")))

        assertEquals(
            "from-one",
            StreamPrefetchCache.getAt(1_000L, key, "addon:one", tenMinutesMs)?.streams?.single()?.name,
        )
        assertEquals(
            "from-two",
            StreamPrefetchCache.getAt(1_000L, key, "addon:two", tenMinutesMs)?.streams?.single()?.name,
        )
        assertNull(StreamPrefetchCache.getAt(1_000L, key, "addon:three", tenMinutesMs))
        assertEquals(1, StreamPrefetchCache.targetCount())
    }

    @Test
    fun `targets past the cap are evicted`() {
        repeat(20) { index ->
            StreamPrefetchCache.putAt(
                nowMs = 1_000L,
                contentKey = StreamPrefetchCache.contentKey("movie", "tt$index", null, null),
                providerId = ADDON,
                streams = listOf(stream("s$index")),
            )
        }

        assertEquals(12, StreamPrefetchCache.targetCount())
        assertNull(
            StreamPrefetchCache.getAt(
                1_000L,
                StreamPrefetchCache.contentKey("movie", "tt0", null, null),
                ADDON,
                tenMinutesMs,
            ),
        )
        assertTrue(
            StreamPrefetchCache.getAt(
                1_000L,
                StreamPrefetchCache.contentKey("movie", "tt19", null, null),
                ADDON,
                tenMinutesMs,
            ) != null,
        )
    }

    @Test
    fun `reading a target keeps it from being evicted as the oldest`() {
        val first = StreamPrefetchCache.contentKey("movie", "first", null, null)
        StreamPrefetchCache.putAt(1_000L, first, ADDON, listOf(stream("first")))
        repeat(11) { index ->
            StreamPrefetchCache.putAt(
                1_000L,
                StreamPrefetchCache.contentKey("movie", "filler$index", null, null),
                ADDON,
                listOf(stream("filler$index")),
            )
        }
        // Cache is exactly full; touching `first` should make the next insert drop filler0 instead.
        assertEquals(12, StreamPrefetchCache.targetCount())
        assertTrue(StreamPrefetchCache.getAt(1_000L, first, ADDON, tenMinutesMs) != null)

        StreamPrefetchCache.putAt(
            1_000L,
            StreamPrefetchCache.contentKey("movie", "newest", null, null),
            ADDON,
            listOf(stream("newest")),
        )

        assertTrue(StreamPrefetchCache.getAt(1_000L, first, ADDON, tenMinutesMs) != null)
        assertNull(
            StreamPrefetchCache.getAt(
                1_000L,
                StreamPrefetchCache.contentKey("movie", "filler0", null, null),
                ADDON,
                tenMinutesMs,
            ),
        )
    }

    @Test
    fun `invalidate drops one target and leaves the rest`() {
        val doomed = StreamPrefetchCache.contentKey("movie", "tt1", null, null)
        val kept = StreamPrefetchCache.contentKey("movie", "tt2", null, null)
        StreamPrefetchCache.putAt(1_000L, doomed, ADDON, listOf(stream("a")))
        StreamPrefetchCache.putAt(1_000L, kept, ADDON, listOf(stream("b")))

        StreamPrefetchCache.invalidate(doomed)

        assertNull(StreamPrefetchCache.getAt(1_000L, doomed, ADDON, tenMinutesMs))
        assertTrue(StreamPrefetchCache.getAt(1_000L, kept, ADDON, tenMinutesMs) != null)
    }

    @Test
    fun `episodes of one show do not share a key`() {
        assertNotEquals(
            StreamPrefetchCache.contentKey("series", "tt999", 1, 1),
            StreamPrefetchCache.contentKey("series", "tt999", 1, 2),
        )
        assertNotEquals(
            StreamPrefetchCache.contentKey("series", "tt999", 1, 1),
            StreamPrefetchCache.contentKey("series", "tt999", 2, 1),
        )
    }

    @Test
    fun `content type casing does not split a key`() {
        assertEquals(
            StreamPrefetchCache.contentKey("Series", "tt999", 1, 1),
            StreamPrefetchCache.contentKey("series", "tt999", 1, 1),
        )
    }

    @Test
    fun `re-stamping leaves rows that already carry the right provider identity untouched`() {
        val rows = listOf(stream("a"), stream("b"))

        assertSame(rows, rows.reStampedFor("Addon", ADDON))
    }

    @Test
    fun `re-stamping relabels rows cached under a different provider identity`() {
        // Plugin rows are stamped with a group id derived from the group-by-repository setting, and
        // an addon can be renamed between the background search and the play. Neither should cost a
        // hit, so the hydrate path relabels instead of keying on them.
        val rows = listOf(stream("a", "plugin:old"), stream("b", "plugin:old"))

        val restamped = rows.reStampedFor("Repo Name", "plugin-repo:example")

        assertEquals(listOf("Repo Name", "Repo Name"), restamped.map { it.addonName })
        assertEquals(listOf("plugin-repo:example", "plugin-repo:example"), restamped.map { it.addonId })
        assertEquals(listOf("a", "b"), restamped.map { it.name })
    }

    // --- in-flight single flight ----------------------------------------------------------------

    @Test
    fun `a provider fetch can only be claimed once`() {
        val key = StreamPrefetchCache.contentKey("movie", "tt123", null, null)

        assertTrue(StreamPrefetchCache.claimInFlight(key, ADDON))
        assertFalse(StreamPrefetchCache.claimInFlight(key, ADDON))
    }

    @Test
    fun `releasing a claim publishes its streams to the cache`() {
        val key = StreamPrefetchCache.contentKey("movie", "tt123", null, null)
        StreamPrefetchCache.claimInFlight(key, ADDON)

        StreamPrefetchCache.releaseInFlight(key, ADDON, listOf(stream("a")))

        assertEquals(
            "a",
            StreamPrefetchCache.get(key, ADDON, tenMinutesMs)?.streams?.single()?.name,
        )
        // Slot is free again, so a later sweep may fetch the same provider.
        assertTrue(StreamPrefetchCache.claimInFlight(key, ADDON))
    }

    @Test
    fun `releasing a claim after a failure frees the slot without caching anything`() {
        val key = StreamPrefetchCache.contentKey("movie", "tt123", null, null)
        StreamPrefetchCache.claimInFlight(key, ADDON)

        StreamPrefetchCache.releaseInFlight(key, ADDON, null)

        assertNull(StreamPrefetchCache.get(key, ADDON, tenMinutesMs))
        assertTrue(StreamPrefetchCache.claimInFlight(key, ADDON))
    }

    @Test
    fun `releasing a claim with an empty answer caches it as an answer`() {
        val key = StreamPrefetchCache.contentKey("movie", "tt123", null, null)
        StreamPrefetchCache.claimInFlight(key, ADDON)

        StreamPrefetchCache.releaseInFlight(key, ADDON, emptyList())

        assertEquals(emptyList(), StreamPrefetchCache.get(key, ADDON, tenMinutesMs)?.streams)
    }

    @Test
    fun `claims are per provider and per target`() {
        val first = StreamPrefetchCache.contentKey("movie", "tt1", null, null)
        val second = StreamPrefetchCache.contentKey("movie", "tt2", null, null)
        StreamPrefetchCache.claimInFlight(first, "addon:one")

        assertTrue(StreamPrefetchCache.claimInFlight(first, "addon:two"))
        assertTrue(StreamPrefetchCache.claimInFlight(second, "addon:one"))
    }

    @Test
    fun `clear frees every outstanding claim`() {
        // Otherwise a settings change mid-sweep strands every waiting reader until its timeout.
        val key = StreamPrefetchCache.contentKey("movie", "tt123", null, null)
        StreamPrefetchCache.claimInFlight(key, ADDON)

        StreamPrefetchCache.clear()

        assertTrue(StreamPrefetchCache.claimInFlight(key, ADDON))
    }

    private companion object {
        const val ADDON = "addon:example:https://example.com/manifest.json"
    }
}
