package com.nuvio.app.features.cloud

import com.nuvio.app.features.debrid.DebridProvider
import com.nuvio.app.features.debrid.DebridProviderCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Resolved posters must survive a refresh (plan §24).
 *
 * A refresh replaces the provider lists with what the debrid API just returned, and the API knows
 * nothing about titles. Without carrying the resolution forward, every poster blanks on every
 * refresh and re-resolves — which on screen is a row of cards unloading and reloading while the
 * user scrolls.
 */
class CloudLibraryCarryResolvedTest {
    // Local fixture: the one in CloudLibraryStoreTest is file-private.
    private fun cloudProvider(id: String, name: String) = DebridProvider(
        id = id,
        displayName = name,
        shortName = name.take(1),
        capabilities = setOf(DebridProviderCapability.CloudLibrary),
    )

    private fun provider() = cloudProvider(id = "torbox", name = "TorBox")

    private fun item(
        id: String,
        name: String,
        poster: String? = null,
        resolvedName: String? = null,
    ) = CloudLibraryItem(
        providerId = "torbox",
        providerName = "TorBox",
        id = id,
        type = CloudLibraryItemType.Torrent,
        name = name,
        resolvedName = resolvedName,
        resolvedPoster = poster,
    )

    private fun state(vararg items: CloudLibraryItem) = CloudLibraryUiState(
        isLoaded = true,
        providers = listOf(CloudLibraryProviderState(provider = provider(), items = items.toList())),
    )

    @Test
    fun `a refreshed item keeps the poster resolved before it`() {
        val previous = state(item("a", "Hanna S01E07", poster = "tmdb-poster", resolvedName = "Hanna"))
        val refreshed = state(item("a", "Hanna S01E07"))

        val carried = refreshed.carryResolvedNamesFrom(previous).items.single()

        assertEquals("tmdb-poster", carried.resolvedPoster)
        assertEquals("Hanna", carried.resolvedName)
    }

    @Test
    fun `a newly resolved value wins over the carried one`() {
        val previous = state(item("a", "Show", poster = "old-poster", resolvedName = "Old"))
        val refreshed = state(item("a", "Show", poster = "new-poster", resolvedName = "New"))

        val carried = refreshed.carryResolvedNamesFrom(previous).items.single()

        // Carrying forward must never hold back fresher information.
        assertEquals("new-poster", carried.resolvedPoster)
        assertEquals("New", carried.resolvedName)
    }

    @Test
    fun `an item that is new in the refresh stays unresolved`() {
        val previous = state(item("a", "Show", poster = "poster"))
        val refreshed = state(item("a", "Show"), item("b", "Other Show"))

        val carried = refreshed.carryResolvedNamesFrom(previous).items
        assertEquals("poster", carried.first { it.id == "a" }.resolvedPoster)
        // Nothing to carry — the resolution pass that follows fills it in.
        assertNull(carried.first { it.id == "b" }.resolvedPoster)
    }

    @Test
    fun `an item that disappeared from the refresh is not resurrected`() {
        val previous = state(item("a", "Gone", poster = "poster"), item("b", "Still Here"))
        val refreshed = state(item("b", "Still Here"))

        assertEquals(listOf("b"), refreshed.carryResolvedNamesFrom(previous).items.map { it.id })
    }

    @Test
    fun `identity is the stable key, so a different provider does not donate its poster`() {
        val previous = CloudLibraryUiState(
            isLoaded = true,
            providers = listOf(
                CloudLibraryProviderState(
                    provider = cloudProvider(id = "premiumize", name = "Premiumize"),
                    items = listOf(
                        CloudLibraryItem(
                            providerId = "premiumize",
                            providerName = "Premiumize",
                            id = "a",
                            type = CloudLibraryItemType.Torrent,
                            name = "Show",
                            resolvedPoster = "other-provider-poster",
                        ),
                    ),
                ),
            ),
        )
        val refreshed = state(item("a", "Show"))

        assertNull(refreshed.carryResolvedNamesFrom(previous).items.single().resolvedPoster)
    }

    @Test
    fun `nothing resolved previously is a no-op`() {
        val previous = state(item("a", "Show"))
        val refreshed = state(item("a", "Show"))

        assertEquals(refreshed, refreshed.carryResolvedNamesFrom(previous))
    }
}
