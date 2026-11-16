package com.nuvio.app.features.watched

import kotlin.test.Test
import kotlin.test.assertEquals

class UnreportedImportPruneTest {
    private fun ep(e: Int, from: String? = "simkl") = WatchedItem(
        id = "tt1",
        type = "series",
        name = "Tracker",
        season = 1,
        episode = e,
        markedAtEpochMs = 1_700_000_000_000L,
        importedFrom = from,
    )

    private fun map(vararg items: WatchedItem) =
        items.associateBy { watchedItemKey(it.type, it.id, it.season, it.episode) }

    private fun keys(vararg items: WatchedItem) =
        items.mapTo(mutableSetOf()) { watchedItemKey(it.type, it.id, it.season, it.episode) }

    @Test
    fun unmarkedOnTheServiceLeavesTheStore() {
        val store = map(ep(1), ep(2), ep(3), ep(1).copy(id = "tt2"))
        val result = pruneUnreportedImports(store, "simkl", keys(ep(1).copy(id = "tt2")), reportedCount = 1)
        assertEquals(keys(ep(1).copy(id = "tt2")), result.keys)
    }

    @Test
    fun ownTicksAndOtherProvidersStay() {
        val own = ep(1, from = null)
        val trakt = ep(2, from = "trakt")
        val store = map(own, trakt, ep(3))
        val result = pruneUnreportedImports(store, "simkl", keys(ep(9)), reportedCount = 1)
        assertEquals(keys(own, trakt), result.keys)
    }

    @Test
    fun anEmptyReadRemovesNothing() {
        val store = map(ep(1), ep(2))
        assertEquals(store, pruneUnreportedImports(store, "simkl", emptySet(), reportedCount = 0))
    }
}
