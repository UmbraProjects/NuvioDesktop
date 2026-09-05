package com.nuvio.app.features.discover

import kotlin.test.Test
import kotlin.test.assertEquals

/** Editing an imported list — the part that is logic rather than layout (plan §14). */
class ImportedDiscoverRowEditTest {
    private fun item(id: String, name: String = id) = ImportedDiscoverItem(
        id = id,
        type = "movie",
        name = name,
    )

    private fun row(vararg ids: String) = ImportedDiscoverRow(
        id = "imported-1",
        title = "Best of 2025",
        items = ids.map { item(it) },
    )

    @Test
    fun `removing a title drops exactly that position`() {
        val edited = row("tmdb:1", "tmdb:2", "tmdb:3").withoutItemAt(1)

        assertEquals(listOf("tmdb:1", "tmdb:3"), edited.items.map { it.id })
    }

    @Test
    fun `a repeated id loses only the copy that was clicked`() {
        // Somebody else's export can repeat an id — merged, hand-edited, or built from two sources.
        // Removing by id would take both copies on a click aimed at one of them.
        val edited = row("tmdb:1", "tmdb:2", "tmdb:1").withoutItemAt(0)

        assertEquals(listOf("tmdb:2", "tmdb:1"), edited.items.map { it.id })
    }

    @Test
    fun `an out of range index leaves the row untouched`() {
        val original = row("tmdb:1", "tmdb:2")

        assertEquals(original, original.withoutItemAt(5))
        assertEquals(original, original.withoutItemAt(-1))
        assertEquals(original, original.withoutItemAt(2))
    }

    @Test
    fun `removing the last title empties the list without discarding the row`() {
        val edited = row("tmdb:1").withoutItemAt(0)

        // The row survives so it stays visible in settings and can be renamed or deleted
        // deliberately, rather than vanishing mid-edit.
        assertEquals(emptyList(), edited.items)
        assertEquals("Best of 2025", edited.title)
        assertEquals("imported-1", edited.id)
    }

    @Test
    fun `everything but the items is preserved`() {
        val original = ImportedDiscoverRow(
            id = "imported-2",
            title = "Imported",
            sourceKind = "trending",
            generatedAt = "2026-08-25T00:00:00Z",
            enabled = false,
            items = listOf(item("tmdb:1"), item("tmdb:2")),
        )

        val edited = original.withoutItemAt(0)

        assertEquals(original.copy(items = listOf(item("tmdb:2"))), edited)
    }
}
