package com.nuvio.app.features.home

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaExternalRating
import com.nuvio.app.features.mdblist.MdbListMetadataService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeroDiscoveryMetadataServiceTest {
    @Test
    fun `keyword festival winners are discovered`() = kotlinx.coroutines.runBlocking {
        val facts = facts(
            MetaDetails(
                id = "tt0166924",
                type = "movie",
                name = "Mulholland Drive",
                mdblistKeywords = listOf("festival-cannes-winner"),
            ),
            priority = listOf("festival"),
        )

        assertEquals("Palme d'Or", facts.single().label)
    }

    @Test
    fun `keyword cult classics are discovered`() = kotlinx.coroutines.runBlocking {
        val facts = facts(
            MetaDetails(
                id = "tt0134847",
                type = "movie",
                name = "Pitch Black",
                mdblistKeywords = listOf("cult-classic"),
            ),
            priority = listOf("cult"),
        )

        assertEquals("Cult Classic", facts.single().label)
    }

    @Test
    fun `anora gets best picture keyword and golden globe id signals`() = kotlinx.coroutines.runBlocking {
        val facts = facts(
            MetaDetails(
                id = "tmdb:1064213",
                type = "movie",
                name = "Anora",
                tmdbId = 1064213,
                mdblistKeywords = listOf("best-picture-winner", "festival-cannes-winner"),
            ),
            priority = listOf("wins", "festival", "gg_noms"),
        ).map { it.label }

        assertTrue("Best Picture" in facts)
        assertTrue("Palme d'Or" in facts)
        assertTrue("Golden Globe" in facts)
    }

    @Test
    fun `fallout gets hardcoded major emmy nomination`() = kotlinx.coroutines.runBlocking {
        val facts = facts(
            MetaDetails(
                id = "tmdb:106379",
                type = "series",
                name = "Fallout",
                tmdbId = 106379,
            ),
            priority = listOf("emmy_noms"),
        )

        assertEquals("Emmy Nominee", facts.single().label)
    }

    @Test
    fun `metacritic must see works from keyword and rating fallback`() = kotlinx.coroutines.runBlocking {
        val keywordFacts = facts(
            MetaDetails(
                id = "tt0000001",
                type = "movie",
                name = "Keyword Must See",
                mdblistKeywords = listOf("metacritic-must-see"),
            ),
            priority = listOf("metacritic"),
        )
        val ratingFacts = facts(
            MetaDetails(
                id = "tt0000002",
                type = "movie",
                name = "Rating Must See",
                externalRatings = listOf(
                    MetaExternalRating(MdbListMetadataService.PROVIDER_METACRITIC, 86.0),
                ),
            ),
            priority = listOf("metacritic"),
        )

        assertEquals("Must-See", keywordFacts.single().label)
        assertEquals("Must-See", ratingFacts.single().label)
    }

    @Test
    fun `foreign language labels use display names`() = kotlinx.coroutines.runBlocking {
        val facts = facts(
            MetaDetails(
                id = "tt0000003",
                type = "movie",
                name = "Spanish Film",
                language = "es",
            ),
            priority = listOf("foreign"),
        )

        assertEquals("Spanish Film", facts.single().label)
        assertEquals("foreign:es", facts.single().category)
    }

    @Test
    fun `credits stinger keywords produce a label per combination`() = kotlinx.coroutines.runBlocking {
        suspend fun labelFor(vararg keywords: String): String? = facts(
            MetaDetails(
                id = "tt0000010",
                type = "movie",
                name = "Stinger Film",
                mdblistKeywords = keywords.toList(),
            ),
            priority = listOf("stinger"),
        ).singleOrNull()?.label

        assertEquals("Post-Credits Scene", labelFor("aftercreditsstinger"))
        assertEquals("Mid-Credits Scene", labelFor("duringcreditsstinger"))
        assertEquals("Mid & Post-Credits", labelFor("duringcreditsstinger", "aftercreditsstinger"))
        assertEquals(null, labelFor("cult-classic"))
    }

    @Test
    fun `credits stinger never fires for series`() = kotlinx.coroutines.runBlocking {
        val facts = facts(
            MetaDetails(
                id = "tt0000011",
                type = "series",
                name = "Stinger Series",
                mdblistKeywords = listOf("aftercreditsstinger", "duringcreditsstinger"),
            ),
            priority = listOf("stinger"),
        )

        assertTrue(facts.isEmpty())
    }

    @Test
    fun `normalizePriority renames slots but never adds one`() {
        // A slot the user has turned off must stay off. Adding it back here is what made
        // "Credits scene" show while its settings checkbox was unticked.
        val unticked = HeroDiscoveryMetadataService.normalizePriority("wins,cult,true_story,short_film")

        assertFalse("stinger" in unticked)
        assertFalse("emmy_noms" in unticked)
        assertEquals(listOf("wins", "cult", "true_story", "short_film"), unticked)

        // The one legitimate rewrite: the retired "structural" slot expands to its three badges.
        val renamed = HeroDiscoveryMetadataService.normalizePriority("wins,structural,cult")

        assertEquals(listOf("wins", "short_film", "mini_series", "binge_ready", "cult"), renamed)
    }

    @Test
    fun `old catalogue titles cannot become new releases through regional availability`() {
        val today = "2026-08-02"

        assertFalse(HeroDiscoveryMetadataService.isRecentOriginalRelease("1997", today))
        assertFalse(HeroDiscoveryMetadataService.isRecentOriginalRelease("2024", today))
        assertTrue(HeroDiscoveryMetadataService.isRecentOriginalRelease("2025", today))
        assertTrue(HeroDiscoveryMetadataService.isRecentOriginalRelease("2026-07-01", today))
        assertFalse(HeroDiscoveryMetadataService.isRecentOriginalRelease(null, today))
    }

    private suspend fun facts(
        meta: MetaDetails,
        priority: List<String>,
    ): List<HeroDiscoveryFact> =
        HeroDiscoveryMetadataService.computeFactsForMeta(
            meta = meta,
            priority = priority,
        )
}
