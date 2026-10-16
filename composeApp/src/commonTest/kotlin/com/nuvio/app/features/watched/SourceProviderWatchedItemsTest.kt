package com.nuvio.app.features.watched

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceProviderWatchedItemsTest {
    private fun episode(id: String, number: Int, importedFrom: String? = null) = WatchedItem(
        id = id,
        type = "series",
        name = "FROM",
        season = 1,
        episode = number,
        markedAtEpochMs = number.toLong(),
        importedFrom = importedFrom,
    )

    private fun WatchedItem.key() = watchedItemKey(type, id, season, episode)

    @Test
    fun `untagged rows the provider reported still seed`() {
        // Already local before the import, so the merge kept them untagged.
        val ownAndReported = episode("tt9813792", 1)
        val tagged = episode("tt9813792", 2, importedFrom = "mdblist")
        val localOnly = episode("tt0000001", 1)

        val seeds = sourceProviderWatchedItems(
            items = listOf(ownAndReported, tagged, localOnly),
            providerStorageId = "mdblist",
            sourceHistoryProvider = "mdblist",
            sourceHistoryKeys = setOf(ownAndReported.key(), tagged.key()),
        )

        assertEquals(listOf(ownAndReported, tagged), seeds)
    }

    @Test
    fun `keys reported by another provider are ignored`() {
        val row = episode("tt9813792", 1)
        val seeds = sourceProviderWatchedItems(
            items = listOf(row, episode("tt9813792", 2, importedFrom = "mdblist")),
            providerStorageId = "mdblist",
            sourceHistoryProvider = "simkl",
            sourceHistoryKeys = setOf(row.key()),
        )
        assertEquals(listOf(2), seeds.map { it.episode })
    }

    @Test
    fun `tagged rows seed before the first import of the session`() {
        val tagged = episode("tt9813792", 2, importedFrom = "mdblist")
        val seeds = sourceProviderWatchedItems(
            items = listOf(episode("tt9813792", 1), tagged),
            providerStorageId = "mdblist",
            sourceHistoryProvider = null,
            sourceHistoryKeys = emptySet(),
        )
        assertEquals(listOf(tagged), seeds)
    }

    @Test
    fun `a local source has no provider history`() {
        val seeds = sourceProviderWatchedItems(
            items = listOf(episode("tt9813792", 1, importedFrom = "mdblist")),
            providerStorageId = null,
            sourceHistoryProvider = "mdblist",
            sourceHistoryKeys = emptySet(),
        )
        assertTrue(seeds.isEmpty())
    }
}
