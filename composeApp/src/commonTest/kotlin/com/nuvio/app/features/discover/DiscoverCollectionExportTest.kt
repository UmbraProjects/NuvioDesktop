package com.nuvio.app.features.discover

import com.nuvio.app.features.collection.TmdbCollectionMediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A custom row as collection sources (plan §11, §17).
 *
 * These assert the *query*, not the plumbing: the receiving side rebuilds a catalog from exactly
 * these fields, so a wrong join character or a dropped floor is a catalog that quietly returns the
 * wrong titles on somebody else's server.
 */
class DiscoverCollectionExportTest {
    private val genres = mapOf("movie" to listOf(28, 878), "tv" to listOf(10759))

    private fun row(
        mediaType: CustomDiscoverMediaType = CustomDiscoverMediaType.Movies,
        block: CustomDiscoverRow.() -> CustomDiscoverRow = { this },
    ) = CustomDiscoverRow(id = "custom-1", title = "Loud Space Films", mediaType = mediaType).block()

    @Test
    fun `a movie row becomes one tmdb discover source`() {
        val export = row().toCollectionSources(genres)

        assertEquals(1, export.sources.size)
        val source = export.sources.single()
        assertEquals("tmdb", source.provider)
        assertEquals("DISCOVER", source.tmdbSourceType)
        assertEquals("MOVIE", source.mediaType)
        assertEquals("Loud Space Films", source.title)
    }

    @Test
    fun `a Both row becomes two sources, one per namespace`() {
        val export = row(CustomDiscoverMediaType.Both)
            .toCollectionSources(genres, movieSuffix = "(Films)", seriesSuffix = "(Series)")

        assertEquals(listOf("MOVIE", "TV"), export.sources.map { it.mediaType })
        // A source is one namespace or the other, so the two halves need telling apart on Home.
        assertEquals(
            listOf("Loud Space Films (Films)", "Loud Space Films (Series)"),
            export.sources.map { it.title },
        )
    }

    @Test
    fun `genres resolve per namespace, not once`() {
        val export = row(CustomDiscoverMediaType.Both).toCollectionSources(genres)

        // 28/878 are film ids; 10759 is the television namespace's own. Sending either list to the
        // wrong endpoint returns a plausible, wrong row.
        assertEquals("28|878", export.sources[0].filters?.withGenres)
        assertEquals("10759", export.sources[1].filters?.withGenres)
    }

    @Test
    fun `matchAllGenres picks the join character TMDB reads as AND`() {
        val anyOf = row { copy(genres = setOf("Action"), matchAllGenres = false) }
            .toCollectionSources(genres).sources.single()
        val allOf = row { copy(genres = setOf("Action"), matchAllGenres = true) }
            .toCollectionSources(genres).sources.single()

        assertEquals("28|878", anyOf.filters?.withGenres)
        assertEquals("28,878", allOf.filters?.withGenres)
    }

    @Test
    fun `year bounds become the dates TMDB wants`() {
        val source = row { copy(fromYear = 1999, toYear = 2004) }
            .toCollectionSources(genres).sources.single()

        assertEquals("1999-01-01", source.filters?.releaseDateGte)
        assertEquals("2004-12-31", source.filters?.releaseDateLte)
    }

    @Test
    fun `the rating-sort vote floor travels with the query`() {
        val source = row { copy(sort = CustomDiscoverSort.Rating) }
            .toCollectionSources(genres).sources.single()

        // Without this the exported catalog returns the four-votes-and-a-ten tail, which reads as a
        // broken row on the service rather than a missing filter.
        assertEquals(RATING_SORT_MIN_VOTES, source.filters?.voteCountGte)
    }

    @Test
    fun `an explicit vote floor wins over the sort default`() {
        val source = row { copy(sort = CustomDiscoverSort.Rating, minVotes = 5) }
            .toCollectionSources(genres).sources.single()

        assertEquals(5, source.filters?.voteCountGte)
    }

    @Test
    fun `runtime and people reach the fields widened for them`() {
        val source = row {
            copy(
                minRuntime = 90,
                maxRuntime = 150,
                cast = listOf(TmdbRef(id = 1, name = "A")),
                crew = listOf(TmdbRef(id = 2, name = "B")),
                companies = listOf(TmdbRef(id = 3, name = "C")),
            )
        }.toCollectionSources(genres).sources.single()

        assertEquals(90, source.filters?.withRuntimeGte)
        assertEquals(150, source.filters?.withRuntimeLte)
        // Cast and crew merge: the one field downstream reads is with_people.
        assertEquals("1|2", source.filters?.withPeople)
        assertEquals("3", source.filters?.withCompanies)
    }

    @Test
    fun `people are not sent to the television namespace`() {
        val export = row(CustomDiscoverMediaType.Both) {
            copy(cast = listOf(TmdbRef(id = 1, name = "A")))
        }.toCollectionSources(genres)

        val tv = export.sources.single { it.mediaType == TmdbCollectionMediaType.TV.name }
        // TMDB's tv endpoint ignores a people filter rather than rejecting it, which would return an
        // unfiltered list of shows beside a correctly filtered list of films.
        assertNull(tv.filters?.withPeople)
        assertTrue(DroppedDiscoverFilter.PeopleOnSeries in export.droppedFilters)
    }

    @Test
    fun `filters with no equivalent are reported, not swallowed`() {
        val export = row {
            copy(status = CustomDiscoverStatus.HomeRelease, certification = "PG-13")
        }.toCollectionSources(genres)

        // The exported query is broader than the row without these, and nothing downstream will
        // ever say so — hence a report rather than a silent narrowing.
        assertEquals(
            listOf(DroppedDiscoverFilter.Status, DroppedDiscoverFilter.Certification),
            export.droppedFilters,
        )
    }

    @Test
    fun `a row that drops nothing reports nothing`() {
        val export = row { copy(minRating = 7, language = "ja") }.toCollectionSources(genres)

        assertEquals(emptyList(), export.droppedFilters)
        assertEquals(7.0, export.sources.single().filters?.voteAverageGte)
        assertEquals("ja", export.sources.single().filters?.withOriginalLanguage)
    }

    @Test
    fun `sort follows the namespace where TMDB names it differently`() {
        val export = row(CustomDiscoverMediaType.Both) { copy(sort = CustomDiscoverSort.Newest) }
            .toCollectionSources(genres)

        assertEquals("primary_release_date.desc", export.sources[0].sortBy)
        assertEquals("first_air_date.desc", export.sources[1].sortBy)
    }

    @Test
    fun `the exported file is the shape AIOMetadata's importer detects`() {
        val export = row().toCollectionSources(genres)
        val json = encodeCollectionsExport(
            discoverRowCollection("Loud Space Films", export.sources, "col-1", "fold-1"),
        )

        // Their detectFormat reads a bare ARRAY whose first element carries `folders` as Nuvio's
        // own format. An object wrapper here is an unrecognised file on their side.
        assertTrue(json.trimStart().startsWith("["), json.take(40))
        assertTrue(json.contains("\"folders\""), json)

        // fromNativeSource reads exactly these four off the source; miss one and it reports
        // "a source with no media type" rather than rebuilding the catalog.
        assertTrue(json.contains("\"provider\": \"tmdb\""), json)
        assertTrue(json.contains("\"tmdbSourceType\": \"DISCOVER\""), json)
        assertTrue(json.contains("\"mediaType\": \"MOVIE\""), json)
        assertTrue(json.contains("\"filters\""), json)
    }

    @Test
    fun `a collection wraps every source in one folder`() {
        val export = row(CustomDiscoverMediaType.Both).toCollectionSources(genres)
        val collection = discoverRowCollection("Both Halves", export.sources, "col-1", "fold-1")

        // A Both row is two views of one question, so it is one folder with two sources rather than
        // two folders the user then has to keep in step.
        assertEquals(1, collection.folders.size)
        assertEquals(2, collection.folders.single().sources.size)
        assertEquals("Both Halves", collection.title)
        assertEquals("Both Halves", collection.folders.single().title)
    }

    @Test
    fun `unset bounds stay null rather than becoming zero`() {
        val filters = row().toCollectionSources(genres).sources.single().filters

        // 0 means "unbounded" in the row model and "match nothing sensible" as a TMDB parameter.
        assertNull(filters?.withRuntimeGte)
        assertNull(filters?.withRuntimeLte)
        assertNull(filters?.voteAverageGte)
        assertNull(filters?.releaseDateGte)
        assertNull(filters?.withCompanies)
    }
}
