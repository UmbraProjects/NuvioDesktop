package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StreamGridNavigationTest {
    private fun stream(id: String) = StreamItem(name = id, url = "https://example.com/$id", addonName = "A", addonId = "a")

    private fun streamEntry(item: StreamItem) = StreamListEntry.Stream(key = "stream:${item.name}", stream = item)

    @Test
    fun headersBreakPairingAndOddStreamsGetARowOfTheirOwn() {
        val (a, b, c, d) = listOf("a", "b", "c", "d").map(::stream)
        val entries = listOf(
            StreamListEntry.SectionHeader(key = "header:1", addonName = "One", isLoading = false),
            streamEntry(a),
            streamEntry(b),
            streamEntry(c),
            StreamListEntry.SectionHeader(key = "header:2", addonName = "Two", isLoading = false),
            streamEntry(d),
        )

        val rows = pairStreamRows(entries)

        assertEquals(5, rows.size)
        assertTrue(rows[0] is StreamListEntry.SectionHeader)
        assertEquals(listOf(a, b), (rows[1] as StreamListEntry.StreamRow).streams)
        assertEquals(listOf(c), (rows[2] as StreamListEntry.StreamRow).streams)
        assertTrue(rows[3] is StreamListEntry.SectionHeader)
        assertEquals(listOf(d), (rows[4] as StreamListEntry.StreamRow).streams)
        // Row keys stay unique, since lazy-list key collisions crash the app.
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
    }

    @Test
    fun verticalMovesKeepTheColumnAndFallBackToTheFirstPlayableCell() {
        val (a, b, c, d, e) = listOf("a", "b", "c", "d", "e").map(::stream)
        val rows = listOf(listOf(a, b), listOf(c, d), listOf(e))
        val all: (StreamItem) -> Boolean = { true }

        assertSame(d, gridVerticalTarget(rows, b, down = true, isSelectable = all))
        assertSame(e, gridVerticalTarget(rows, d, down = true, isSelectable = all))
        assertSame(b, gridVerticalTarget(rows, d, down = false, isSelectable = all))
        assertNull(gridVerticalTarget(rows, e, down = true, isSelectable = all))
        // An unplayable cell in the same column is skipped for the row's first playable one.
        assertSame(c, gridVerticalTarget(rows, b, down = true, isSelectable = { it !== d }))
    }

    @Test
    fun horizontalMovesStayInTheRowAndStopAtItsEdges() {
        val (a, b, c) = listOf("a", "b", "c").map(::stream)
        val rows = listOf(listOf(a, b), listOf(c))
        val all: (StreamItem) -> Boolean = { true }

        assertSame(b, gridHorizontalTarget(rows, a, right = true, isSelectable = all))
        assertSame(a, gridHorizontalTarget(rows, b, right = false, isSelectable = all))
        // Outer edges return null so the key can switch provider chip instead.
        assertNull(gridHorizontalTarget(rows, b, right = true, isSelectable = all))
        assertNull(gridHorizontalTarget(rows, a, right = false, isSelectable = all))
        assertNull(gridHorizontalTarget(rows, c, right = true, isSelectable = all))
    }
}
