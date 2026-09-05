package com.nuvio.app.features.discover

import com.nuvio.app.features.home.MetaPreview
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DiscoverRowCacheTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * A row item carrying every field the Discover generators actually set — the union of
     * `TmdbSearchResult.toMetaPreview`, `WatchProgressEntry.toMetaPreview`,
     * `withCustomLibraryPoster` and the `toRecommendationRow` mappings for imported and AI rows.
     */
    private fun generatedItem() = MetaPreview(
        id = "tmdb:1234",
        type = "movie",
        name = "A Film",
        poster = "https://posters.example/1234.jpg",
        posterFallback = "https://image.tmdb.org/t/p/w500/abc.jpg",
        banner = "https://image.tmdb.org/t/p/w780/def.jpg",
        logo = "https://logos.example/1234.png",
        description = "A synopsis long enough to matter when this row seeds the hero.",
        releaseInfo = "1999",
        popularity = 12.5,
        // TMDB's vote average, which every list response carries and the poster badge reads.
        imdbRating = "7.4",
        // AI rows only, and the reason they exist. Lost here, the hover tooltip goes silent on
        // every relaunch inside the cache TTL — visible to nobody, reported by nobody.
        recommendationReason = "Same slow-burn dread as the one you finished last week.",
    )

    @Test
    fun `a generated row item survives the round trip unchanged`() {
        // The contract this cache depends on: a restored row must be indistinguishable from a
        // freshly built one. If a generator starts setting a field the DTO does not carry, this
        // fails — which is the point.
        val original = generatedItem()
        assertEquals(original, original.toCachedItem().toMetaPreview())
    }

    @Test
    fun `a row keeps its identity, title and entry id`() {
        val row = DiscoverRecommendationRow(
            key = "discover:trending:horror",
            title = "Trending in Horror",
            items = listOf(generatedItem()),
            entryId = "trending",
        )
        assertEquals(row, row.toCachedRow().toRow())
    }

    @Test
    fun `an entry survives serialisation to disk and back`() {
        val entry = DiscoverRowCacheEntry(
            builtAtEpochMs = 1_700_000_000_000,
            settingsSignature = "hideWatched=true|because=4",
            seedSignature = "movie:1|tv:2",
            profileId = 2,
            rows = listOf(
                DiscoverRecommendationRow(
                    key = "discover:custom:abc",
                    title = "Nineties Horror",
                    items = List(3) { generatedItem().copy(id = "tmdb:$it") },
                    entryId = "custom:abc",
                ).toCachedRow(),
            ),
        )
        val restored = json.decodeFromString<DiscoverRowCacheEntry>(json.encodeToString(entry))
        assertEquals(entry, restored)
        assertEquals(DiscoverRowCacheEntry.VERSION, restored.version)
    }

    @Test
    fun `the version is written even when it is the default`() {
        // encodeDefaults is what makes the stored file self-describing; without it a v1 entry and
        // an entry from before versioning existed are the same bytes.
        val entry = DiscoverRowCacheEntry(
            builtAtEpochMs = 0,
            settingsSignature = "",
            seedSignature = null,
            profileId = 1,
            rows = emptyList(),
        )
        assertTrue("\"version\"" in json.encodeToString(entry))
    }

    @Test
    fun `a null seed signature round trips as null rather than as empty`() {
        // The repository distinguishes the three: null means "no seeds were needed", "" means
        // "seeds were needed and none resolved". Collapsing them would change what refresh does.
        val entry = DiscoverRowCacheEntry(
            builtAtEpochMs = 1,
            settingsSignature = "s",
            seedSignature = null,
            profileId = 1,
            rows = emptyList(),
        )
        assertEquals(null, json.decodeFromString<DiscoverRowCacheEntry>(json.encodeToString(entry)).seedSignature)

        val empty = entry.copy(seedSignature = "")
        assertEquals("", json.decodeFromString<DiscoverRowCacheEntry>(json.encodeToString(empty)).seedSignature)
    }

    @Test
    fun `the profile the rows were built for round trips`() {
        // Discover derives from per-profile watch history, so restoring another profile's rows is
        // not a stale cache, it is the wrong user's recommendations. The id has to survive the
        // write for the check on load to mean anything.
        val entry = DiscoverRowCacheEntry(
            builtAtEpochMs = 1,
            settingsSignature = "s",
            seedSignature = "movie:1",
            profileId = 3,
            rows = emptyList(),
        )
        assertEquals(3, json.decodeFromString<DiscoverRowCacheEntry>(json.encodeToString(entry)).profileId)
    }

    @Test
    fun `an older entry decodes so the version check can reject it as superseded`() {
        // Regression: profileId shipped without a default, so a v1 file failed deserialisation
        // before the version check ran and was discarded as *unreadable* — which reads as
        // corruption in the log when the truth is a routine upgrade. Every field here needs a
        // default for the version to remain the thing that does the rejecting.
        val v1 = """{"version":1,"builtAtEpochMs":1,"settingsSignature":"s","seedSignature":null}"""
        val decoded = json.decodeFromString<DiscoverRowCacheEntry>(v1)
        assertEquals(1, decoded.version)
        assertNotEquals(DiscoverRowCacheEntry.VERSION, decoded.version)
        assertEquals(DiscoverRowCacheEntry.UNKNOWN_PROFILE, decoded.profileId)
    }

    @Test
    fun `the unknown profile placeholder never matches a real profile`() {
        // Profile ids are 1-based, so the placeholder must fall outside that range or an entry
        // written before profileId existed would be served to profile N.
        assertTrue(DiscoverRowCacheEntry.UNKNOWN_PROFILE < 1)
    }

    @Test
    fun `the cache is deliberately lossy for fields no generator sets`() {
        // Documenting the boundary rather than guarding it: the DTO is a narrow subset on purpose,
        // so a MetaPreview from somewhere else does NOT survive intact. Anyone widening what a
        // Discover row carries has to widen DiscoverCachedItem and bump VERSION, and this is the
        // test that says so.
        val fromElsewhere = generatedItem().copy(
            genres = listOf("Horror", "Thriller"),
            runtime = "1h 42min",
            ageRating = "15",
        )
        val restored = fromElsewhere.toCachedItem().toMetaPreview()
        assertNotEquals(fromElsewhere, restored)
        assertEquals(emptyList(), restored.genres)
        assertEquals(null, restored.runtime)
        assertEquals(null, restored.ageRating)
        // …while everything the generators do set is still intact.
        assertEquals(generatedItem(), restored)
    }
}
