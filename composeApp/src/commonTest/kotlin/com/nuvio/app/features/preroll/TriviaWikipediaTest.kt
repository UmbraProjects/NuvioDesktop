package com.nuvio.app.features.preroll

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TriviaWikipediaTest {
    private val article = """
        Rocky is a 1976 American sports drama film directed by John G. Avildsen.

        == Plot ==
        Rocky Balboa is a small-time boxer who gets a shot at the title. Adrian waits at the end of the fight.

        == Production ==
        === Development ===
        Stallone wrote the screenplay in about three and a half days after watching a boxing match. He refused to sell it unless he could star.
        The producers wanted a well-known actor in the lead role, but Stallone held out for himself.

        == Casting ==
        Talia Shire was cast as Adrian after several other actresses were considered for the part.

        == Reception ==
        The film was a critical success and won the Academy Award for Best Picture. Critics praised the surprising ending of the story.

        == See also ==
        Boxing films that this article should never use as a source of trivia for anything.
    """.trimIndent()

    @Test
    fun neverUsesPlotOrUnlistedSections() {
        val texts = buildWikipediaSlides(article).map { it.text }
        assertTrue(texts.none { "small-time boxer" in it || "Adrian waits" in it })
        assertTrue(texts.none { "See also" in it || "should never use" in it })
    }

    @Test
    fun keepsMakingOfFactsAndDropsSpoilerAndDanglingSentences() {
        val texts = buildWikipediaSlides(article).map { it.text }
        assertTrue(texts.any { it.startsWith("Stallone wrote the screenplay") })
        assertTrue(texts.any { it.startsWith("Talia Shire was cast") })
        assertTrue(texts.none { "ending" in it })
        assertTrue(texts.none { it.startsWith("He ") })
    }

    @Test
    fun categoriesFollowTheSection() {
        val slides = buildWikipediaSlides(article)
        assertEquals(TriviaCategory.Cast, slides.first { it.text.startsWith("Talia Shire") }.category)
        assertEquals(TriviaCategory.Production, slides.first { it.text.startsWith("Stallone wrote") }.category)
    }

    @Test
    fun splitsSentencesWithoutBreakingOnInitialsOrAbbreviations() {
        val parts = splitSentences("Directed by John G. Avildsen with Mr. Smith in St. Louis. Filming began in 1975.")
        assertEquals(2, parts.size)
    }

    @Test
    fun cleanSentenceStripsParenthesesAndRejectsBadShapes() {
        assertEquals(
            "The film was shot on location in Philadelphia over twenty-eight days.",
            cleanSentence("The film was shot (on a tiny budget) on location in Philadelphia over twenty-eight days."),
        )
        assertNull(cleanSentence("Too short."))
        assertNull(cleanSentence("The film earned $225 million worldwide against a very small production budget."))
        assertNull(cleanSentence("A".repeat(400) + "."))
    }

    @Test
    fun titleMatchingIsStrict() {
        assertTrue(titleMatches("Rocky (1976 film)", "Rocky"))
        assertTrue(titleMatches("Rocky", "rocky"))
        assertFalse(titleMatches("Rocky II", "Rocky"))
        assertFalse(titleMatches("Rocky Balboa", "Rocky"))
    }

    @Test
    fun leadMustNameTheYearAndAFilm() {
        val source = TriviaSource(
            title = "Rocky", year = "1976", synopsis = null, directors = emptyList(), cast = emptyList(),
            genres = emptyList(), studios = emptyList(), collection = null, awards = null,
            keywords = emptyList(), runtime = null, country = null, language = null,
        )
        assertTrue(looksLikeTheTitle(article, source))
        assertFalse(looksLikeTheTitle("Rocky is a 1999 American sports drama film.", source))
        assertFalse(looksLikeTheTitle("Rocky is a 1976 mountain range.", source))
    }

    @Test
    fun titleMatchingIgnoresAmpersandAndLeadingThe() {
        assertTrue(titleMatches("Fast & Furious", "Fast and Furious"))
        assertTrue(titleMatches("The Godfather (film)", "Godfather"))
        assertTrue(titleMatches("Godfather", "The Godfather"))
        assertFalse(titleMatches("The Godfather Part II", "The Godfather"))
    }

    @Test
    fun rankingPutsTheMainArticleFirstAndDropsSeasonsAndAlbums() {
        val ranked = rankTitles(
            listOf("Fargo (season 2)", "Fargo (soundtrack)", "Fargo", "Fargo (TV series)", "Fargo (1996 film)", "Fargo Rock City"),
            wanted = listOf("Fargo"),
            isSeries = true,
        )
        assertEquals(listOf("Fargo (TV series)", "Fargo", "Fargo (1996 film)"), ranked)
        assertEquals(
            "Fargo (1996 film)",
            rankTitles(listOf("Fargo", "Fargo (TV series)", "Fargo (1996 film)"), listOf("Fargo"), isSeries = false).first(),
        )
    }

    @Test
    fun subtitleSearchOnlyWhenTheYearIsKnown() {
        fun source(year: String?) = TriviaSource(
            title = "Mission: Impossible - Fallout", year = year, synopsis = null, directors = emptyList(),
            cast = emptyList(), genres = emptyList(), studios = emptyList(), collection = null, awards = null,
            keywords = emptyList(), runtime = null, country = null, language = null,
        )
        assertEquals(listOf("Mission: Impossible - Fallout", "Mission"), alternateTitles(source("2018")))
        assertEquals(listOf("Mission: Impossible - Fallout"), alternateTitles(source(null)))
    }

    @Test
    fun leadYearMayBeOffByOne() {
        val source = TriviaSource(
            title = "Rocky", year = "1976", synopsis = null, directors = emptyList(), cast = emptyList(),
            genres = emptyList(), studios = emptyList(), collection = null, awards = null,
            keywords = emptyList(), runtime = null, country = null, language = null,
        )
        assertTrue(looksLikeTheTitle("Rocky is a 1977 American sports drama film.", source))
        assertTrue(looksLikeTheTitle("Rocky is a 1975 American sports drama film.", source))
        assertFalse(looksLikeTheTitle("Rocky is a 1978 American sports drama film.", source))
    }

    @Test
    fun parsesApiResponses() {
        assertEquals(
            listOf("Rocky (1976 film)", "Rocky II"),
            TriviaWikipedia.parseSearchTitles("""{"query":{"search":[{"title":"Rocky (1976 film)"},{"title":"Rocky II"}]}}"""),
        )
        assertEquals(
            "hello",
            TriviaWikipedia.parseExtract("""{"query":{"pages":{"1":{"title":"x","extract":"hello"}}}}"""),
        )
        assertNull(TriviaWikipedia.parseExtract("not json"))
    }
}
