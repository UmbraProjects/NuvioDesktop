package com.nuvio.app.features.preroll

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PostShowWikipediaTest {
    private val article = """
        Rocky is a 1976 American sports drama film directed by John G. Avildsen.

        == Plot ==
        Rocky Balboa is a small-time boxer who gets a shot at the title.
        === The fight ===
        Rocky survives all fifteen rounds against the champion. He loses by split decision, but he has proved himself.

        == Themes ==
        The film treats boxing as a way of proving self-worth rather than winning a trophy.

        == Production ==
        === Development ===
        Stallone wrote the screenplay in about three and a half days after watching a boxing match. He refused to sell it unless he could star.

        == See also ==
        Boxing films that this article should never use.
    """.trimIndent()

    @Test
    fun `cards are ending, themes then making-of`() {
        val cards = buildPostShowCardsFromArticle(article)
        assertEquals(
            listOf(PostShowCardKind.Ending, PostShowCardKind.Themes, PostShowCardKind.BehindTheScenes),
            cards.map { it.kind },
        )
        assertTrue(cards[0].text.endsWith("he has proved himself."))
        assertTrue(cards.none { "never use" in it.text })
    }

    @Test
    fun `plot subsections fold into the plot`() {
        val sections = topLevelSections(article)
        assertTrue("plot" in sections && "The fight".lowercase() !in sections)
        assertTrue("fifteen rounds" in sections.getValue("plot"))
    }

    @Test
    fun `too little material gives no cards`() {
        assertEquals(emptyList(), buildPostShowCardsFromArticle("A film.\n\n== Plot ==\nOne long enough sentence ends the film here."))
    }

    @Test
    fun `ending keeps the last two sentences only when short enough`() {
        val plot = "First sentence is here for context. The hero walks away from the city. The credits roll over the sea."
        assertEquals("The hero walks away from the city. The credits roll over the sea.", endingOf(plot))
        val long = "x".repeat(200)
        assertEquals("The credits roll over the sea.", endingOf("$long. $long. The credits roll over the sea."))
        assertNull(endingOf(null))
    }

    @Test
    fun `asides in parentheses are dropped from the ending`() {
        assertEquals("He walks home to the quiet house where he lives.", endingOf("He walks home (at dawn) to the quiet house where he lives."))
    }
}
