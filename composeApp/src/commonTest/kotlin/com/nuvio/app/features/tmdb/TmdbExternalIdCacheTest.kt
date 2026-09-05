package com.nuvio.app.features.tmdb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The trimming rule for the persisted TMDB→IMDb map (plan §20.2).
 *
 * Kept as a pure function beside the storage rather than inside it so the eviction order — the part
 * that decides what is lost — is testable without touching disk.
 */
class TmdbExternalIdCacheTest {
    @Test
    fun `a map within the limit is stored whole`() {
        val entries = (1..10).associate { "$it:movie" to "tt$it" }

        val capped = capExternalIdEntries(entries)

        assertEquals(entries, capped)
    }

    @Test
    fun `an oversized map keeps the most recent entries`() {
        // Insertion-ordered, so the oldest resolutions are the ones dropped — anything looked up
        // recently is the most likely to be looked up again.
        val entries = linkedMapOf<String, String>()
        repeat(TMDB_EXTERNAL_ID_CACHE_LIMIT + 500) { entries["$it:movie"] = "tt$it" }

        val capped = capExternalIdEntries(entries)

        assertEquals(TMDB_EXTERNAL_ID_CACHE_LIMIT, capped.size)
        assertTrue("0:movie" !in capped)
        assertTrue("499:movie" !in capped)
        assertTrue("500:movie" in capped)
        assertTrue("${TMDB_EXTERNAL_ID_CACHE_LIMIT + 499}:movie" in capped)
    }

    @Test
    fun `an empty map stays empty`() {
        assertEquals(emptyMap(), capExternalIdEntries(emptyMap()))
    }

    @Test
    fun `a map exactly at the limit is untouched`() {
        val entries = linkedMapOf<String, String>()
        repeat(TMDB_EXTERNAL_ID_CACHE_LIMIT) { entries["$it:movie"] = "tt$it" }

        assertEquals(TMDB_EXTERNAL_ID_CACHE_LIMIT, capExternalIdEntries(entries).size)
        assertTrue("0:movie" in capExternalIdEntries(entries))
    }
}
