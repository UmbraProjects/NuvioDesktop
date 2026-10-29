package com.nuvio.app.features.preroll

import com.nuvio.app.features.details.MetaDetails
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TriviaFallbackTest {
    private fun meta(block: MetaDetails.() -> MetaDetails = { this }) =
        MetaDetails(id = "tt1", type = "movie", name = "Film", releaseInfo = "2022").block()

    @Test
    fun buildsSlidesFromMetadataOnly() {
        val pack = buildMetadataTrivia(
            meta { copy(director = listOf("A"), runtime = "130 min", imdbRating = "8.3", genres = listOf("Action")) },
        )
        assertNotNull(pack)
        val texts = pack.slides.map { it.text }
        assertTrue("Directed by A." in texts)
        assertTrue("Running time: 130 min." in texts)
        assertTrue("Rated 8.3 out of 10 by IMDb users." in texts)
        // Year and running time are on the record; a director question needs cast to borrow wrong
        // answers from, and this film has none.
        assertEquals(listOf("In which year did Film first come out?", "What running time is listed for Film?"),
            pack.questions.map { it.question })
        assertEquals(TriviaOrigin.Metadata, pack.origin)
    }

    @Test
    fun tooLittleMetadataGivesNothing() {
        assertNull(buildMetadataTrivia(meta()))
        assertNull(buildMetadataTrivia(meta { copy(director = listOf("A")) }))
    }

    @Test
    fun neverMoreThanSixSlides() {
        val pack = buildMetadataTrivia(
            meta {
                copy(
                    director = listOf("A"), runtime = "1h", imdbRating = "7.0", country = "US", language = "English",
                    awards = "Won 1 Oscar", collectionName = "X Collection",
                )
            },
        )
        assertEquals(true, (pack?.slides?.size ?: 0) <= 6)
    }
}
