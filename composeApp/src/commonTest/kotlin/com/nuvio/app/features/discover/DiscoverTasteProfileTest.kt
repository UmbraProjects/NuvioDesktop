package com.nuvio.app.features.discover

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NOW = 1_800_000_000_000L
private const val DAY = 24L * 60 * 60 * 1000

private fun seed(
    mediaType: String,
    daysAgo: Long,
    vararg genres: Pair<Int, String>,
    episodesWatched: Int = 1,
) = SeedGenres(
    mediaType = mediaType,
    lastWatchedAtEpochMs = NOW - daysAgo * DAY,
    genres = genres.toList(),
    episodesWatched = episodesWatched,
)

class DiscoverTasteProfileTest {

    @Test
    fun `the genre appearing on most seeds ranks first`() {
        val seeds = listOf(
            seed("movie", 5, 28 to "Action", 12 to "Adventure"),
            seed("movie", 10, 28 to "Action", 878 to "Science Fiction"),
            seed("movie", 15, 28 to "Action"),
            seed("movie", 20, 18 to "Drama"),
        )

        val affinities = buildGenreAffinities(seeds, now = NOW)

        assertEquals("Action", affinities.first().name)
        assertEquals(mapOf("movie" to 28), affinities.first().idsByMediaType)
    }

    @Test
    fun `recent watching outweighs older watching`() {
        val seeds = listOf(
            seed("movie", 1, 27 to "Horror"),
            seed("movie", 350, 18 to "Drama"),
            seed("movie", 355, 18 to "Drama"),
        )

        val affinities = buildGenreAffinities(seeds, now = NOW)

        // One title watched yesterday outranks two watched almost a year ago, despite being
        // outnumbered — a profile that ignored recency would have Drama on top here.
        assertEquals("Horror", affinities.first().name)
        // Decayed, not erased: the older genre still ranks, so a taste that only shows up in
        // older history can still produce a row.
        assertTrue(affinities.first { it.name == "Drama" }.weight > 0.0)
    }

    @Test
    fun `enough older watching still outranks a single recent title`() {
        val seeds = listOf(
            seed("movie", 1, 27 to "Horror"),
        ) + (1..5).map { index -> seed("movie", 300L + index, 18 to "Drama") }

        val affinities = buildGenreAffinities(seeds, now = NOW)

        assertEquals("Drama", affinities.first().name)
    }

    @Test
    fun `the same genre name carries a different id per media type`() {
        // TMDB namespaces genre ids: 878 is "Science Fiction" for movies, 10765 covers it for tv.
        // A row that queries both has to ask each namespace by its own number.
        val seeds = listOf(
            seed("movie", 5, 878 to "Science Fiction"),
            seed("tv", 5, 10765 to "Science Fiction"),
        )

        val affinities = buildGenreAffinities(seeds, now = NOW)

        assertEquals(1, affinities.size)
        assertEquals(878, affinities.single().idFor("movie"))
        assertEquals(10765, affinities.single().idFor("tv"))
    }

    @Test
    fun `a genre only one namespace has is not invented for the other`() {
        val seeds = listOf(seed("tv", 5, 10759 to "Action & Adventure"))

        val affinity = buildGenreAffinities(seeds, now = NOW).single()

        assertEquals(10759, affinity.idFor("tv"))
        assertNull(affinity.idFor("movie"))
    }

    @Test
    fun `equal weights order by name so rows do not shuffle between builds`() {
        val seeds = listOf(seed("movie", 5, 35 to "Comedy", 18 to "Drama", 28 to "Action"))

        val affinities = buildGenreAffinities(seeds, now = NOW)

        assertEquals(listOf("Action", "Comedy", "Drama"), affinities.map { it.name })
    }

    @Test
    fun `unnamed or unresolved genres are dropped rather than ranked blank`() {
        val seeds = listOf(seed("movie", 5, 28 to "Action", 0 to "Bad Id", 99 to "  "))

        val affinities = buildGenreAffinities(seeds, now = NOW)

        assertEquals(listOf("Action"), affinities.map { it.name })
    }

    @Test
    fun `how much was watched counts, so a binged show outranks a single film`() {
        // The failure this fixes: 237 episodes of a sitcom counted exactly as much as one film
        // watched once, so the profile described what was most recently *started*, not what the
        // user actually watches.
        val seeds = listOf(
            seed("tv", 5, 35 to "Comedy", episodesWatched = 237),
            seed("movie", 4, 27 to "Horror"),
            seed("movie", 3, 27 to "Horror"),
        )

        val affinities = buildGenreAffinities(seeds, now = NOW)

        assertEquals("Comedy", affinities.first().name)
    }

    @Test
    fun `volume is damped, so one long show cannot own the profile`() {
        // 240 episodes is worth about four films, not two hundred and forty.
        val seeds = listOf(
            seed("tv", 5, 16 to "Animation", episodesWatched = 240),
        ) + (1..6).map { seed("movie", 5, 18 to "Drama") }

        val affinities = buildGenreAffinities(seeds, now = NOW)

        assertEquals("Drama", affinities.first().name)
    }

    @Test
    fun `excluded genres are dropped before ranking, not after`() {
        val seeds = listOf(
            seed("tv", 5, 16 to "Animation", 35 to "Comedy", episodesWatched = 40),
            seed("movie", 5, 18 to "Drama"),
        )

        val affinities = buildGenreAffinities(seeds, now = NOW, excludedGenres = setOf("animation"))

        assertTrue(affinities.none { it.name == "Animation" })
        // Excluding a genre must not disturb the ranking of the ones that remain.
        assertEquals("Comedy", affinities.first().name)
    }

    @Test
    fun `no seeds means no affinities`() {
        assertTrue(buildGenreAffinities(emptyList(), now = NOW).isEmpty())
    }

    /**
     * The point of merging the two vocabularies: a film tagged "Action" and a show tagged
     * "Action & Adventure" are one taste, and used to rank as two half-weight genres that could each
     * fail to make the cut.
     */
    @Test
    fun `the two vocabularies merge into one affinity carrying both ids`() {
        val seeds = listOf(
            seed("movie", 5, 28 to "Action"),
            seed("tv", 5, 10759 to "Action & Adventure"),
        )

        val affinities = buildGenreAffinities(seeds, now = NOW)

        assertEquals(1, affinities.size)
        assertEquals("Action", affinities.single().name)
        assertEquals(28, affinities.single().idFor("movie"))
        assertEquals(10759, affinities.single().idFor("tv"))
    }

    @Test
    fun `excluding a merged genre excludes both spellings`() {
        val seeds = listOf(
            seed("tv", 5, 10759 to "Action & Adventure"),
            seed("movie", 5, 28 to "Action"),
            seed("movie", 5, 18 to "Drama"),
        )

        val affinities = buildGenreAffinities(seeds, now = NOW, excludedGenres = setOf("action"))

        assertEquals(listOf("Drama"), affinities.map { it.name })
    }

    @Test
    fun `a genre TMDB has added since keeps its own name rather than vanishing`() {
        val affinity = buildGenreAffinities(listOf(seed("movie", 5, 9999 to "Holiday")), now = NOW).single()

        assertEquals("Holiday", affinity.name)
        assertEquals(9999, affinity.idFor("movie"))
    }

    @Test
    fun `canonical names resolve from either vocabulary and from stored legacy names`() {
        assertEquals("Action", canonicalDiscoverGenreName("Action & Adventure"))
        assertEquals("Science Fiction", canonicalDiscoverGenreName("Sci-Fi & Fantasy"))
        assertEquals("War", canonicalDiscoverGenreName("War & Politics"))
        assertEquals("Horror", canonicalDiscoverGenreName("horror"))
        assertNull(canonicalDiscoverGenreName("Not A Genre"))
    }

    /**
     * Guards the reason [discoverCombinedGenrePrimary] exists: alphabetically "Fantasy" precedes
     * "Science Fiction", so a reverse map built from list order alone files every science-fiction
     * show under Fantasy.
     */
    @Test
    fun `a combined tv genre resolves to its stated primary, not to whichever sorts first`() {
        assertEquals("Science Fiction", canonicalDiscoverGenreName("Sci-Fi & Fantasy"))
        val affinity = buildGenreAffinities(
            listOf(seed("tv", 5, 10765 to "Sci-Fi & Fantasy")),
            now = NOW,
        ).single()
        assertEquals("Science Fiction", affinity.name)
    }

    @Test
    fun `every TMDB genre name in the table resolves back to exactly one canonical name`() {
        DiscoverGenres.forEach { genre ->
            listOfNotNull(genre.movie, genre.tv).forEach { tmdbName ->
                assertTrue(
                    canonicalDiscoverGenreName(tmdbName) != null,
                    "$tmdbName does not resolve back to a canonical genre",
                )
            }
        }
    }

    @Test
    fun `the user-facing list has no duplicates and every entry queries something`() {
        assertEquals(DiscoverGenreNames.distinct(), DiscoverGenreNames)
        assertTrue(
            DiscoverGenres.all { it.movie != null || it.tv != null },
            "a genre that queries neither namespace can never return anything",
        )
    }

    @Test
    fun `a canonical name maps to the spelling each namespace uses`() {
        assertEquals("Science Fiction", discoverGenreTmdbName("Science Fiction", "movie"))
        assertEquals("Sci-Fi & Fantasy", discoverGenreTmdbName("Science Fiction", "tv"))
        assertEquals("Sci-Fi & Fantasy", discoverGenreTmdbName("Fantasy", "tv"))
        // Television has no horror genre. Null is the answer, not a substitute.
        assertNull(discoverGenreTmdbName("Horror", "tv"))
        assertNull(discoverGenreTmdbName("Kids", "movie"))
    }
}
