package com.nuvio.app.features.discover

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoverCatalogImportTest {

    private fun documentWithQuery(): DiscoverCatalogDocument {
        val row = DiscoverRecommendationRow(
            key = "discover:custom:abc",
            title = "Nineties Horror",
            entryId = customDiscoverEntryId("abc"),
            items = emptyList(),
        )
        val custom = CustomDiscoverRow(
            id = "abc",
            title = "Nineties Horror",
            mediaType = CustomDiscoverMediaType.Movies,
            genres = setOf("Horror"),
            matchAllGenres = true,
            sort = CustomDiscoverSort.Rating,
            minRating = 7,
            minVotes = 500,
            fromYear = 1990,
            toYear = 1999,
            language = "ja",
            certification = "R",
            minRuntime = 80,
            maxRuntime = 130,
            companies = listOf(TmdbRef(41, "Studio Ghibli")),
            cast = listOf(TmdbRef(1, "An Actor")),
            crew = listOf(TmdbRef(2, "A Director")),
        )
        return row.toCatalogDocument(generatedAt = "2026-08-24T00:00:00Z", customRow = custom)
    }

    private fun documentWithoutQuery() = DiscoverRecommendationRow(
        key = "discover:because:1",
        title = "Because you watched Severance",
        entryId = DiscoverRowFamily.Because.entryId,
        items = List(3) {
            com.nuvio.app.features.home.MetaPreview(
                id = "tmdb:$it",
                type = "series",
                name = "Show $it",
                poster = "https://p/$it.jpg",
                description = "A synopsis.",
                releaseInfo = "2024",
            )
        },
    ).toCatalogDocument(generatedAt = "2026-08-24T00:00:00Z")

    @Test
    fun `a file carrying a query becomes a live custom row`() {
        // The whole point of the query half: the items in the file were stale when it was written,
        // the question that produced them was not.
        val restored = assertNotNull(documentWithQuery().toCustomRow("new-id"))
        assertEquals("new-id", restored.id)
        assertEquals("Nineties Horror", restored.title)
        assertEquals(CustomDiscoverMediaType.Movies, restored.mediaType)
        assertEquals(setOf("Horror"), restored.genres)
        assertTrue(restored.matchAllGenres)
        assertEquals(CustomDiscoverSort.Rating, restored.sort)
        assertEquals(7, restored.minRating)
        assertEquals(500, restored.minVotes)
        assertEquals(1990, restored.fromYear)
        assertEquals(1999, restored.toYear)
        assertEquals("ja", restored.language)
        assertEquals("R", restored.certification)
        assertEquals(80, restored.minRuntime)
        assertEquals(130, restored.maxRuntime)
        assertEquals(listOf(TmdbRef(41, "Studio Ghibli")), restored.companies)
        assertEquals(listOf(TmdbRef(1, "An Actor")), restored.cast)
        assertEquals(listOf(TmdbRef(2, "A Director")), restored.crew)
    }

    @Test
    fun `a query survives a full write-read-restore trip`() {
        val onDisk = documentWithQuery().encodeToString()
        val restored = parseDiscoverCatalog(onDisk).getOrThrow().toCustomRow("x")
        assertEquals(documentWithQuery().toCustomRow("x"), restored)
    }

    @Test
    fun `a generated row has no query to recreate`() {
        // Which is the signal to store it as a frozen list instead — the two halves of "both".
        assertNull(documentWithoutQuery().toCustomRow("x"))
    }

    @Test
    fun `a file with no query becomes a stored list that renders as a row`() {
        val stored = documentWithoutQuery().toImportedRow("imp-1")
        assertEquals("Because you watched Severance", stored.title)
        assertEquals("tmdb-recommendations", stored.sourceKind)
        assertEquals(3, stored.items.size)

        val row = stored.toRecommendationRow()
        assertEquals(importedDiscoverEntryId("imp-1"), row.entryId)
        // Key and entry id agree because an imported list is always exactly one row, unlike the
        // families that produce several and share one entry.
        assertEquals(row.entryId, row.key)
        assertEquals(listOf("tmdb:0", "tmdb:1", "tmdb:2"), row.items.map { it.id })
        assertEquals("A synopsis.", row.items.first().description)
        assertEquals("2024", row.items.first().releaseInfo)
    }

    @Test
    fun `a stored list is capped so the settings payload cannot grow without bound`() {
        // These are persisted in full inside the settings file, unlike a custom row which is a
        // handful of filters, so the cap has to hold at the point of import.
        val many = documentWithoutQuery().copy(
            items = List(500) { DiscoverCatalogItem(id = "tmdb:$it", type = "movie", name = "T$it") },
        )
        assertEquals(IMPORTED_DISCOVER_ITEM_LIMIT, many.toImportedRow("x").items.size)
    }

    @Test
    fun `an unrecognised enum falls back rather than failing the import`() {
        // A hand-edited file with a bad sort should give a row the user can fix in the editor, not
        // a rejection that names no field.
        val doc = documentWithQuery().let { original ->
            original.copy(
                source = original.source.copy(
                    query = requireNotNull(original.source.query).copy(
                        sort = "not-a-sort",
                        mediaType = "not-a-type",
                        status = "not-a-status",
                    ),
                ),
            )
        }
        val restored = assertNotNull(doc.toCustomRow("x"))
        assertEquals(CustomDiscoverSort.Popularity, restored.sort)
        assertEquals(CustomDiscoverMediaType.Both, restored.mediaType)
        assertEquals(CustomDiscoverStatus.Any, restored.status)
    }

    @Test
    fun `imported entry ids never collide with custom ones`() {
        // Both are user rows in the same order list; one namespace for both would let an imported
        // id resolve to a custom row and vice versa.
        val id = "same-id"
        assertTrue(importedDiscoverEntryId(id) != customDiscoverEntryId(id))
        assertNull(customDiscoverRowId(importedDiscoverEntryId(id)))
        assertNull(importedDiscoverRowId(customDiscoverEntryId(id)))
        assertEquals(id, importedDiscoverRowId(importedDiscoverEntryId(id)))
    }

    @Test
    fun `the row order keeps imported entries and drops ones that no longer exist`() {
        val order = listOf(
            DiscoverRowFamily.Finish.entryId,
            importedDiscoverEntryId("kept"),
            importedDiscoverEntryId("deleted"),
        )
        val normalised = normalizeDiscoverRowOrder(
            savedOrder = order,
            customRowIds = emptyList(),
            importedRowIds = listOf("kept"),
        )
        assertTrue(importedDiscoverEntryId("kept") in normalised)
        assertTrue(importedDiscoverEntryId("deleted") !in normalised)
    }

    @Test
    fun `a suggested filename is safe and keeps the row recognisable`() {
        assertEquals("because-you-watched-severance.json", discoverCatalogFileName("Because you watched Severance"))
        assertEquals("trending-in-sci-fi-fantasy.json", discoverCatalogFileName("Trending in Sci-Fi & Fantasy"))
        assertEquals("discover-row.json", discoverCatalogFileName("   "))
        assertTrue(discoverCatalogFileName("x".repeat(200)).length <= 65)
    }
}
