package com.nuvio.app.features.library

import com.nuvio.app.features.home.PosterShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Nov 16: a title saved from a row of landscape cards (Lupin) showed wide among the Library's portrait posters. */
class LibraryPortraitCardsTest {
    private fun saved(shape: PosterShape, id: String = "tt2531336", poster: String = "https://art.example/lupin-wide.jpg") = LibraryItem(
        id = id,
        type = "series",
        name = "Lupin",
        poster = poster,
        posterShape = shape,
        savedAtEpochMs = 1L,
    )

    @Test
    fun aTitleSavedWideShowsAsAPortraitPoster() {
        val card = saved(PosterShape.Landscape).toMetaPreview()
        assertEquals(PosterShape.Poster, card.posterShape)
        // Its saved art is wide; the portrait poster for its IMDb id stands in.
        assertTrue(card.poster.orEmpty().startsWith("https://images.metahub.space/poster/medium/tt2531336/"), card.poster)
        assertEquals(PosterShape.Poster, saved(PosterShape.Square).toMetaPreview().posterShape)
    }

    @Test
    fun aPortraitTitleKeepsItsOwnArt() {
        val card = saved(PosterShape.Poster, poster = "https://art.example/portrait.jpg").toMetaPreview()
        assertEquals(PosterShape.Poster, card.posterShape)
        assertTrue(card.poster.orEmpty().startsWith("https://art.example/portrait.jpg"), card.poster)
    }

    @Test
    fun aWideTitleWithoutAnImdbIdKeepsItsArtAsAPortraitCard() {
        val card = saved(PosterShape.Landscape, id = "kitsu:123").toMetaPreview()
        assertEquals(PosterShape.Poster, card.posterShape)
        assertTrue(card.poster.orEmpty().startsWith("https://art.example/lupin-wide.jpg"), card.poster)
    }
}
