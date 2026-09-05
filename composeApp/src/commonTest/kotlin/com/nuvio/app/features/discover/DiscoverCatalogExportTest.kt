package com.nuvio.app.features.discover

import com.nuvio.app.features.home.MetaPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoverCatalogExportTest {

    private fun row(
        key: String = "discover:because:tmdb:1",
        title: String = "Because you watched Severance",
        entryId: String = DiscoverRowFamily.Because.entryId,
        items: List<MetaPreview> = listOf(
            MetaPreview(
                id = "tmdb:95396",
                type = "series",
                name = "Severance",
                poster = "https://image.tmdb.org/t/p/w500/a.jpg",
                banner = "https://image.tmdb.org/t/p/w780/b.jpg",
                description = "Mark leads a team of office workers.",
                releaseInfo = "2022",
            ),
        ),
    ) = DiscoverRecommendationRow(key = key, title = title, items = items, entryId = entryId)

    private fun document(row: DiscoverRecommendationRow = row(), customRow: CustomDiscoverRow? = null) =
        row.toCatalogDocument(generatedAt = "2026-08-24T00:00:00Z", customRow = customRow)

    @Test
    fun `a document round trips through its own parser`() {
        val original = document()
        val parsed = parseDiscoverCatalog(original.encodeToString()).getOrThrow()
        assertEquals(original, parsed)
    }

    @Test
    fun `the format and version are written so a file identifies itself`() {
        val raw = document().encodeToString()
        assertTrue("\"format\": \"nuvio-discover-catalog\"" in raw, raw.take(200))
        assertTrue("\"version\": 1" in raw, raw.take(200))
    }

    @Test
    fun `item ids are exported as Nuvio addresses them`() {
        // Converting tmdb: to IMDb on the way out would cost a request per item, and whether any
        // target service even wants IMDb ids is what the §6.2 spike has to establish. A local
        // round trip needs the id unchanged.
        val parsed = parseDiscoverCatalog(document().encodeToString()).getOrThrow()
        assertEquals("tmdb:95396", parsed.items.single().id)
    }

    @Test
    fun `a generated row carries provenance but no query`() {
        val source = document().source
        assertEquals("tmdb-recommendations", source.kind)
        assertEquals(DiscoverRowFamily.Because.entryId, source.entryId)
        assertNull(source.query)
    }

    @Test
    fun `a custom row exports the query that produced it`() {
        // This is what makes §6.2's "query export where the row has one" possible without a second
        // export path: the document carries both the items and the question that produced them.
        val custom = CustomDiscoverRow(
            id = "abc",
            title = "Nineties Horror",
            genres = setOf("Horror", "Mystery"),
            fromYear = 1990,
            toYear = 1999,
            minVotes = 200,
        )
        val doc = document(
            row = row(entryId = customDiscoverEntryId("abc"), title = "Nineties Horror"),
            customRow = custom,
        )
        assertEquals("custom-query", doc.source.kind)
        val query = requireNotNull(doc.source.query)
        assertEquals(listOf("Horror", "Mystery"), query.genres)
        assertEquals(1990, query.fromYear)
        assertEquals(1999, query.toYear)
        assertEquals(200, query.minVotes)
    }

    @Test
    fun `every row family maps to a stable service-facing kind`() {
        // These names go into files people keep, so they must not be the internal entry ids by
        // accident — renaming a family must not silently change what an exported file claims.
        val kinds = DiscoverRowFamily.entries.map { family ->
            document(row(entryId = family.entryId)).source.kind
        }
        assertTrue(kinds.none { it == "unknown" }, "unmapped family in $kinds")
        assertEquals(kinds.size, kinds.toSet().size, "two families share a kind: $kinds")
    }

    @Test
    fun `a file of another format is rejected as such, not as broken`() {
        val manifest = """{"id":"com.example.addon","name":"Some Addon","types":["movie"]}"""
        val error = parseDiscoverCatalog(manifest).exceptionOrNull() as DiscoverCatalogImportException
        assertEquals(DiscoverCatalogImportError.NotADiscoverCatalog(null), error.error)
    }

    @Test
    fun `a newer version is reported as newer rather than unreadable`() {
        // The two need different actions from the user — update Nuvio, versus the file is corrupt —
        // so collapsing them into one failure would give the wrong instruction.
        val newer = """
            {"format":"nuvio-discover-catalog","version":99,"name":"x","generatedAt":"z",
             "source":{"kind":"custom-query","entryId":"custom:1"},"items":[]}
        """.trimIndent()
        val error = parseDiscoverCatalog(newer).exceptionOrNull() as DiscoverCatalogImportException
        assertEquals(DiscoverCatalogImportError.UnsupportedVersion(99), error.error)
    }

    @Test
    fun `unknown fields from a newer writer do not make a file unreadable`() {
        val withExtras = """
            {"format":"nuvio-discover-catalog","version":1,"name":"x","generatedAt":"z",
             "somethingNew":{"a":1},
             "source":{"kind":"custom-query","entryId":"custom:1","futureField":true},
             "items":[{"id":"tmdb:1","type":"movie","name":"A","unknown":"y"}]}
        """.trimIndent()
        assertEquals("tmdb:1", parseDiscoverCatalog(withExtras).getOrThrow().items.single().id)
    }

    @Test
    fun `malformed json is unreadable`() {
        val error = parseDiscoverCatalog("not json at all {").exceptionOrNull()
            as DiscoverCatalogImportException
        assertTrue(error.error is DiscoverCatalogImportError.Unreadable)
    }

    @Test
    fun `items with no id or no name are dropped, and a file of only those is empty`() {
        val partly = """
            {"format":"nuvio-discover-catalog","version":1,"name":"x","generatedAt":"z",
             "source":{"kind":"custom-query","entryId":"custom:1"},
             "items":[{"id":"","type":"movie","name":"A"},
                      {"id":"tmdb:2","type":"movie","name":""},
                      {"id":"tmdb:3","type":"movie","name":"C"}]}
        """.trimIndent()
        assertEquals(listOf("tmdb:3"), parseDiscoverCatalog(partly).getOrThrow().items.map { it.id })

        val allBad = partly.replace("""{"id":"tmdb:3","type":"movie","name":"C"}""", """{"id":"","type":"movie","name":""}""")
        val error = parseDiscoverCatalog(allBad).exceptionOrNull() as DiscoverCatalogImportException
        assertEquals(DiscoverCatalogImportError.Empty, error.error)
    }

    @Test
    fun `a file with a query but no items is not empty`() {
        // A query is content in its own right — it imports as a live custom row, and its items were
        // already stale when the file was written. Treating this as empty would refuse the one kind
        // of export that does not go out of date.
        val queryOnly = """
            {"format":"nuvio-discover-catalog","version":1,"name":"Nineties Horror","generatedAt":"z",
             "source":{"kind":"custom-query","entryId":"custom:1",
                       "query":{"mediaType":"Movies","genres":["Horror"]}},
             "items":[]}
        """.trimIndent()
        val parsed = parseDiscoverCatalog(queryOnly).getOrThrow()
        assertTrue(parsed.items.isEmpty())
        assertEquals(listOf("Horror"), parsed.source.query?.genres)
    }
}
