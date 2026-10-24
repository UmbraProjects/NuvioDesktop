package com.nuvio.app.features.discover

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoverRowLayoutTest {
    @Test
    fun `an empty saved order is the default order`() {
        assertEquals(DefaultDiscoverRowOrder, normalizeDiscoverRowOrder(emptyList(), emptyList()))
    }

    @Test
    fun `a saved order wins over the default`() {
        val saved = listOf("gems", "finish", "trending", "favourites", "because")
        assertEquals(saved, normalizeDiscoverRowOrder(saved, emptyList()))
    }

    @Test
    fun `a deleted custom row is dropped from the order`() {
        val saved = listOf("finish", "custom:gone", "because", "custom:kept", "favourites", "gems", "trending")
        assertEquals(
            listOf("finish", "because", "custom:kept", "favourites", "gems", "trending"),
            normalizeDiscoverRowOrder(saved, listOf("kept")),
        )
    }

    @Test
    fun `a new custom row is appended, not inserted`() {
        val saved = listOf("finish", "because", "favourites", "gems", "trending")
        assertEquals(saved + "custom:new", normalizeDiscoverRowOrder(saved, listOf("new")))
    }

    /**
     * The migration case: a saved order written before a family existed. It has to land where it
     * belongs rather than behind whatever the user has added since.
     */
    @Test
    fun `a family missing from a saved order lands at its default position`() {
        val saved = listOf("finish", "because", "trending", "custom:mine")
        assertEquals(
            listOf("finish", "because", "favourites", "gems", "trending", "custom:mine"),
            normalizeDiscoverRowOrder(saved, listOf("mine")),
        )
    }

    @Test
    fun `a missing first family lands at the front, not the back`() {
        val saved = listOf("because", "favourites", "gems", "trending", "custom:mine")
        assertEquals("finish", normalizeDiscoverRowOrder(saved, listOf("mine")).first())
    }

    /** A reordered list must not drag an inserted family back to its default neighbourhood. */
    @Test
    fun `insertion follows the user's arrangement, not the default one`() {
        val saved = listOf("trending", "gems", "finish")
        val normalized = normalizeDiscoverRowOrder(saved, emptyList())
        assertEquals(listOf("trending", "gems", "finish", "because", "favourites"), normalized)
    }

    @Test
    fun `duplicates in a saved order are collapsed`() {
        val saved = listOf("finish", "finish", "because", "because")
        val normalized = normalizeDiscoverRowOrder(saved, emptyList())
        assertEquals(normalized.distinct(), normalized)
        assertEquals(DefaultDiscoverRowOrder.size, normalized.size)
    }

    @Test
    fun `rows are sorted into the configured order`() {
        val rows = listOf("gems" to "a", "finish" to "b", "custom:x" to "c")
        val ordered = orderDiscoverRows(rows, listOf("custom:x", "finish", "gems")) { it.first }
        assertEquals(listOf("c", "b", "a"), ordered.map { it.second })
    }

    @Test
    fun `rows in one family keep the order they were generated in`() {
        val rows = listOf(
            "because" to "seed1",
            "because" to "seed2",
            "because" to "seed3",
            "finish" to "local",
        )
        val ordered = orderDiscoverRows(rows, listOf("because", "finish")) { it.first }
        assertEquals(listOf("seed1", "seed2", "seed3", "local"), ordered.map { it.second })
    }

    @Test
    fun `a row whose entry is not in the order is kept, at the end`() {
        val rows = listOf("gems" to "a", "unknown" to "b", "finish" to "c")
        val ordered = orderDiscoverRows(rows, listOf("finish", "gems")) { it.first }
        assertEquals(listOf("c", "a", "b"), ordered.map { it.second })
    }

    @Test
    fun `custom entry ids round-trip and never collide with a family`() {
        val entryId = customDiscoverEntryId("abc-123")
        assertEquals("abc-123", customDiscoverRowId(entryId))
        assertTrue(DefaultDiscoverRowOrder.none { it == entryId })
        DefaultDiscoverRowOrder.forEach { familyId ->
            assertEquals(null, customDiscoverRowId(familyId), "family id $familyId read as a custom row")
        }
    }

    /**
     * TMDB rejects a release-date sort aimed at the wrong namespace, so the two must not share a
     * parameter name — see [CustomDiscoverSort].
     */
    @Test
    fun `date sorts use the per-namespace parameter`() {
        assertEquals("primary_release_date.desc", CustomDiscoverSort.Newest.tmdbSortBy("movie"))
        assertEquals("first_air_date.desc", CustomDiscoverSort.Newest.tmdbSortBy("tv"))
        assertEquals("first_air_date.asc", CustomDiscoverSort.Oldest.tmdbSortBy("tv"))
        // Everything else is namespace-neutral and must stay identical across the two.
        assertEquals(
            CustomDiscoverSort.Popularity.tmdbSortBy("movie"),
            CustomDiscoverSort.Popularity.tmdbSortBy("tv"),
        )
        assertEquals(
            CustomDiscoverSort.Rating.tmdbSortBy("movie"),
            CustomDiscoverSort.Rating.tmdbSortBy("tv"),
        )
    }

    /**
     * The rule that keeps a narrowed query narrow: a namespace that cannot answer the filter must
     * return nothing from it, never fall back to an unfiltered list.
     */
    @Test
    fun `a status filter applies only to the namespace that can answer it`() {
        assertTrue(CustomDiscoverStatus.InCinemas.appliesTo("movie"))
        assertTrue(!CustomDiscoverStatus.InCinemas.appliesTo("tv"))
        assertTrue(CustomDiscoverStatus.Returning.appliesTo("tv"))
        assertTrue(!CustomDiscoverStatus.Returning.appliesTo("movie"))
        // "Any" is not a filter, so it applies everywhere.
        assertTrue(CustomDiscoverStatus.Any.appliesTo("movie"))
        assertTrue(CustomDiscoverStatus.Any.appliesTo("tv"))
    }

    @Test
    fun `each status carries parameters for exactly the namespaces it claims`() {
        CustomDiscoverStatus.entries.forEach { status ->
            if (status == CustomDiscoverStatus.Any) {
                assertEquals(null, status.movieReleaseTypes)
                assertEquals(null, status.tvStatuses)
            } else {
                assertTrue(
                    (status.movieReleaseTypes != null) != (status.tvStatuses != null),
                    "$status must answer exactly one namespace",
                )
            }
        }
    }

    /**
     * The picker stores an id and a display name; only the id may ever reach TMDB. A row whose
     * studio was renamed must keep working, which it does precisely because the name is inert.
     */
    @Test
    fun `a picked reference is identified by id, not by name`() {
        val saved = TmdbRef(id = 41077, name = "A24")
        val renamed = saved.copy(name = "A24 Films")

        assertEquals(saved.id, renamed.id)
        assertTrue(listOf(saved).distinctBy { it.id } == listOf(saved))
        // Same id twice is one filter, whatever the two records call themselves.
        assertEquals(1, listOf(saved, renamed).distinctBy { it.id }.size)
    }

    @Test
    fun `media type maps to the namespaces it queries`() {
        assertEquals(listOf("movie"), CustomDiscoverMediaType.Movies.tmdbMediaTypes)
        assertEquals(listOf("tv"), CustomDiscoverMediaType.Shows.tmdbMediaTypes)
        assertEquals(listOf("movie", "tv"), CustomDiscoverMediaType.Both.tmdbMediaTypes)
    }

    @Test
    fun `only the three non-obvious row kinds carry a provenance badge`() {
        assertEquals(DiscoverRowProvenance.Ai, discoverRowProvenance(aiDiscoverEntryId("row-1")))
        assertEquals(
            DiscoverRowProvenance.Imported,
            discoverRowProvenance(importedDiscoverEntryId("row-2")),
        )
        assertEquals(
            DiscoverRowProvenance.Custom,
            discoverRowProvenance("${DiscoverRecommendationsRepository.CUSTOM_ROW_KEY_PREFIX}row-3"),
        )

        // The built-in families are the default, and badging every one of them says nothing.
        assertNull(discoverRowProvenance(DiscoverRecommendationsRepository.FINISH_ROW_KEY))
        assertNull(discoverRowProvenance(DiscoverRecommendationsRepository.FAVOURITES_ROW_KEY))
        assertNull(discoverRowProvenance(DiscoverRecommendationsRepository.HIDDEN_GEMS_ROW_KEY))
        assertNull(discoverRowProvenance("${DiscoverRecommendationsRepository.TRENDING_ROW_KEY_PREFIX}horror"))
        assertNull(discoverRowProvenance("discover:because:tv:1396"))
    }

    @Test
    fun `a custom row is matched on its row key, not its entry id`() {
        // The two differ on purpose — `discover:custom:x` addresses the row, `custom:x` addresses
        // the row-management entry — and the renderer only ever holds the former. Matching the
        // entry id here would badge nothing and look like the feature was never wired up.
        assertEquals(
            DiscoverRowProvenance.Custom,
            discoverRowProvenance("${DiscoverRecommendationsRepository.CUSTOM_ROW_KEY_PREFIX}q1"),
        )
        assertNull(discoverRowProvenance(customDiscoverEntryId("q1")))
    }

    @Test
    fun `a key made unique by a suffix keeps its badge`() {
        // ensureUniqueKeys disambiguates by appending, so prefix matching has to survive it.
        assertEquals(DiscoverRowProvenance.Ai, discoverRowProvenance(aiDiscoverEntryId("row-1") + "#2"))
    }

    @Test
    fun `TMDB vote averages format the way the poster badge reads them`() {
        assertEquals("7.4", tmdbVoteAverageLabel(7.44))
        assertEquals("7.5", tmdbVoteAverageLabel(7.46))
        assertEquals("8.0", tmdbVoteAverageLabel(8.0))
        assertEquals("10.0", tmdbVoteAverageLabel(10.0))
        // Zero is TMDB's "nobody has rated this", and a badge reading 0.0 would be a claim.
        assertNull(tmdbVoteAverageLabel(0.0))
        assertNull(tmdbVoteAverageLabel(-1.0))
    }
}
