package com.nuvio.app.features.music.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LadderTagsTest {
    @Test
    fun keyIgnoresCaseSpacingAndPunctuation() {
        assertEquals("postpunk", genreKey("Post-Punk"))
        assertEquals("postpunk", genreKey("post punk"))
        assertEquals("postpunk", genreKey("POSTPUNK"))
        assertEquals("drumandbass", genreKey("Drum & Bass"))
        assertEquals("drumandbass", genreKey("drum-and-bass"))
        assertEquals("rnb", genreKey("R'n'B"))
        assertEquals("rnb", genreKey("R’n’B"))
    }

    @Test
    fun spellingVariantsShareOneCanonicalName() {
        for (v in listOf("post-punk", "Post Punk", "postpunk", "POST_PUNK")) {
            assertEquals("post-punk", canonicalGenre(v), v)
        }
        for (v in listOf("hip hop", "Hip-Hop", "hiphop", "rap")) {
            assertEquals("hip-hop", canonicalGenre(v), v)
        }
        for (v in listOf("r&b", "R'n'B", "rnb", "rhythm and blues", "RnB")) {
            assertEquals("r&b", canonicalGenre(v), v)
        }
        for (v in listOf("drum and bass", "Drum & Bass", "dnb", "drum 'n' bass", "drum n bass")) {
            assertEquals("drum and bass", canonicalGenre(v), v)
        }
        for (v in listOf("lo-fi", "lofi", "Lo Fi")) assertEquals("lo-fi", canonicalGenre(v), v)
        for (v in listOf("prog", "prog rock", "progressive rock")) assertEquals("progressive rock", canonicalGenre(v), v)
    }

    @Test
    fun unknownGenreIsKeptReadable() {
        assertEquals("witch house", canonicalGenre("Witch-House"))
        assertEquals("witch house", canonicalGenre("  witch   house "))
        assertEquals("hypnagogic pop", canonicalGenre("hypnagogic_pop"))
    }

    @Test
    fun listeningHabitsAndOpinionsAreNotGenres() {
        for (junk in listOf("seen live", "Favorites", "favourite artists", "albums I own", "awesome", "male vocalists", "FEMALE VOCALISTS")) {
            assertTrue(isNonGenreTag(junk), junk)
            assertNull(canonicalGenre(junk), junk)
        }
    }

    @Test
    fun erasPlacesAndNumbersAreNotGenres() {
        for (junk in listOf("90s", "1990s", "00s", "'80s", "1999", "eighties", "british", "UK", "American", "Swedish")) {
            assertTrue(isNonGenreTag(junk), junk)
        }
    }

    @Test
    fun blankTooShortAndTooLongAreNotGenres() {
        assertTrue(isNonGenreTag(""))
        assertTrue(isNonGenreTag("   "))
        assertTrue(isNonGenreTag("x"))
        assertTrue(isNonGenreTag("---"))
        assertTrue(isNonGenreTag("a".repeat(41)))
    }

    @Test
    fun junkOnlyMatchesTheWholeTag() {
        // "uk" alone is a place; "uk garage" is a genre. "live" alone is junk; "live wire" is not.
        assertTrue(isNonGenreTag("uk"))
        assertFalse(isNonGenreTag("uk garage"))
        assertEquals("uk garage", canonicalGenre("UK Garage"))
        assertFalse(isNonGenreTag("live wire"))
    }

    @Test
    fun realGenresPass() {
        for (g in listOf("shoegaze", "krautrock", "highlife", "dream pop", "ambient", "metal")) {
            assertFalse(isNonGenreTag(g), g)
        }
    }

    @Test
    fun sameGenreComparesByKey() {
        assertTrue(sameGenre("Post Punk", "post-punk"))
        assertTrue(sameGenre("dnb", "Drum & Bass"))
        assertFalse(sameGenre("post-punk", "punk"))
        assertFalse(sameGenre("seen live", "seen live"))
        assertFalse(sameGenre("", ""))
    }

    @Test
    fun normalizeGenresMergesVariantsDropsJunkKeepsOrder() {
        val raw = listOf("Shoegaze", "seen live", "dream pop", "Dream-Pop", "90s", "post punk", "postpunk", "british")
        assertEquals(listOf("shoegaze", "dream pop", "post-punk"), normalizeGenres(raw))
    }

    @Test
    fun normalizeGenresOnEmptyInput() {
        assertEquals(emptyList(), normalizeGenres(emptyList()))
        assertEquals(emptyList(), normalizeGenres(listOf("seen live", "90s")))
    }

    @Test
    fun weightedVotesAddUpAcrossVariantsAndSortByWeight() {
        val votes = mapOf(
            "post-punk" to 4,
            "postpunk" to 3,
            "seen live" to 50,
            "new wave" to 5,
            "90s" to 9,
            "goth" to 2,
            "gothic rock" to 1,
        )
        val out = normalizeWeightedGenres(votes)
        assertEquals(listOf("post-punk", "new wave", "goth rock"), out.keys.toList())
        assertEquals(7, out["post-punk"])
        assertEquals(5, out["new wave"])
        assertEquals(3, out["goth rock"])
    }

    @Test
    fun weightedTiesAreOrderedByNameAndNegativeVotesCountAsZero() {
        val out = normalizeWeightedGenres(mapOf("shoegaze" to 3, "ambient" to 3, "drone" to -5))
        assertEquals(listOf("ambient", "shoegaze", "drone"), out.keys.toList())
        assertEquals(0, out["drone"])
    }
}
