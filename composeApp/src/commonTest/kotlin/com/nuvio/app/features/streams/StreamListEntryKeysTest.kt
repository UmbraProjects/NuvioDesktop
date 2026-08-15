package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The source list is rebuilt and re-sorted every time an addon answers. LazyColumn only holds the
 * viewport still across such a rebuild when a row's key survives it, so these pin the properties the
 * scroll position depends on: keys carry no index, and they are still unique.
 */
class StreamListEntryKeysTest {

    private fun stream(addon: String, url: String) = StreamItem(
        name = url,
        url = url,
        addonName = addon,
        addonId = "addon:$addon",
    )

    private fun group(addon: String, vararg streams: StreamItem) =
        AddonStreamGroup(addonName = addon, addonId = "addon:$addon", streams = streams.toList())

    private fun keysOf(groups: List<AddonStreamGroup>, showGroupHeaders: Boolean = true) =
        buildStreamListEntries(groups, showGroupHeaders).map { it.key }

    @Test
    fun `a row keeps its key when a late source is inserted above it`() {
        val existing = group("Torrentio", stream("Torrentio", "https://a.test/1"))
        val before = keysOf(listOf(existing))
        val after = keysOf(listOf(group("Comet", stream("Comet", "https://b.test/1")), existing))

        assertTrue(after.containsAll(before), "expected $after to still contain $before")
    }

    @Test
    fun `a row keeps its key when sorting moves it`() {
        val a = stream("Torrentio", "https://a.test/1")
        val b = stream("Torrentio", "https://a.test/2")
        val ascending = keysOf(listOf(group("Torrentio", a, b)), showGroupHeaders = false)
        val descending = keysOf(listOf(group("Torrentio", b, a)), showGroupHeaders = false)

        assertEquals(ascending, descending.reversed())
    }

    @Test
    fun `duplicate identities still get distinct keys`() {
        val duplicated = stream("Torrentio", "https://a.test/1")
        val keys = keysOf(listOf(group("Torrentio", duplicated, duplicated)))

        assertEquals(keys.size, keys.toSet().size, "duplicate lazy keys crash the app: $keys")
    }

    @Test
    fun `entry order matches the drawn list`() {
        val entries = buildStreamListEntries(
            groups = listOf(group("Torrentio", stream("Torrentio", "https://a.test/1"))),
            showGroupHeaders = true,
        )

        assertTrue(entries.first() is StreamListEntry.SectionHeader)
        assertTrue(entries.last() is StreamListEntry.Stream)
        assertEquals(2, entries.size)
    }

    @Test
    fun `section headers are dropped when a single addon is filtered to`() {
        val entries = buildStreamListEntries(
            groups = listOf(group("Torrentio", stream("Torrentio", "https://a.test/1"))),
            showGroupHeaders = false,
        )

        assertEquals(1, entries.size)
        assertTrue(entries.single() is StreamListEntry.Stream)
    }
}
