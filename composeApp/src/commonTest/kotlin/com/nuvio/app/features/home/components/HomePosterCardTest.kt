package com.nuvio.app.features.home.components

import com.nuvio.app.core.ui.PosterRatingBadgeScale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomePosterCardTest {

    @Test
    fun `landscape artwork ignores custom portrait provider when original is available`() {
        val artwork = posterCardArtwork(
            isLandscapeMode = true,
            posterUrl = "https://poster-provider.example/tt1",
            posterFallbackUrl = "https://image.tmdb.org/original-poster.jpg",
            backdropUrl = null,
        )

        assertEquals("https://image.tmdb.org/original-poster.jpg", artwork.imageUrl)
        assertNull(artwork.fallbackImageUrl)
    }

    @Test
    fun `landscape backdrop falls back to original rather than custom provider`() {
        val artwork = posterCardArtwork(
            isLandscapeMode = true,
            posterUrl = "https://poster-provider.example/tt1",
            posterFallbackUrl = "https://image.tmdb.org/original-poster.jpg",
            backdropUrl = "https://image.tmdb.org/backdrop.jpg",
        )

        assertEquals("https://image.tmdb.org/backdrop.jpg", artwork.imageUrl)
        assertEquals("https://image.tmdb.org/original-poster.jpg", artwork.fallbackImageUrl)
    }

    @Test
    fun `portrait artwork retains custom poster provider and original fallback`() {
        val artwork = posterCardArtwork(
            isLandscapeMode = false,
            posterUrl = "https://poster-provider.example/tt1",
            posterFallbackUrl = "https://image.tmdb.org/original-poster.jpg",
            backdropUrl = "https://image.tmdb.org/backdrop.jpg",
        )

        assertEquals("https://poster-provider.example/tt1", artwork.imageUrl)
        assertEquals("https://image.tmdb.org/original-poster.jpg", artwork.fallbackImageUrl)
    }

    @Test
    fun `an addon landscape poster outranks the backdrop and keeps it as the fallback`() {
        val artwork = posterCardArtwork(
            isLandscapeMode = true,
            posterUrl = "https://poster-provider.example/tt1",
            posterFallbackUrl = "https://image.tmdb.org/original-poster.jpg",
            backdropUrl = "https://image.tmdb.org/backdrop.jpg",
            landscapePosterUrl = "https://aiometadata.example/landscape/tt1.jpg",
        )

        assertEquals("https://aiometadata.example/landscape/tt1.jpg", artwork.imageUrl)
        assertEquals("https://image.tmdb.org/backdrop.jpg", artwork.fallbackImageUrl)
    }

    @Test
    fun `a landscape poster without a backdrop still avoids the custom portrait provider`() {
        val artwork = posterCardArtwork(
            isLandscapeMode = true,
            posterUrl = "https://poster-provider.example/tt1",
            posterFallbackUrl = "https://image.tmdb.org/original-poster.jpg",
            backdropUrl = null,
            landscapePosterUrl = "https://aiometadata.example/landscape/tt1.jpg",
        )

        assertEquals("https://aiometadata.example/landscape/tt1.jpg", artwork.imageUrl)
        assertEquals("https://image.tmdb.org/original-poster.jpg", artwork.fallbackImageUrl)
    }

    @Test
    fun `a title the addon has no landscape art for keeps today's backdrop card`() {
        val artwork = posterCardArtwork(
            isLandscapeMode = true,
            posterUrl = "https://poster-provider.example/tt1",
            posterFallbackUrl = null,
            backdropUrl = "https://image.tmdb.org/backdrop.jpg",
            landscapePosterUrl = "   ",
        )

        assertEquals("https://image.tmdb.org/backdrop.jpg", artwork.imageUrl)
    }

    @Test
    fun `a portrait card ignores landscape art entirely`() {
        val artwork = posterCardArtwork(
            isLandscapeMode = false,
            posterUrl = "https://poster-provider.example/tt1",
            posterFallbackUrl = "https://image.tmdb.org/original-poster.jpg",
            backdropUrl = "https://image.tmdb.org/backdrop.jpg",
            landscapePosterUrl = "https://aiometadata.example/landscape/tt1.jpg",
        )

        assertEquals("https://poster-provider.example/tt1", artwork.imageUrl)
    }

    @Test
    fun `composited landscape art suppresses the logo overlay`() {
        val overlay = landscapeCardTitleOverlay(
            isLandscapeMode = true,
            useTextTitle = false,
            hideLabels = false,
            title = "I Was a Stranger",
            logoUrl = "https://example.com/logo.png",
            artIncludesTitle = true,
        )

        assertNull(overlay.logoUrl)
        assertNull(overlay.text)
    }

    @Test
    fun `composited landscape art outranks the text title preference`() {
        // The title is already in the art; honouring the preference would print it twice.
        val overlay = landscapeCardTitleOverlay(
            isLandscapeMode = true,
            useTextTitle = true,
            hideLabels = false,
            title = "I Was a Stranger",
            logoUrl = null,
            artIncludesTitle = true,
        )

        assertNull(overlay.text)
        assertNull(overlay.logoUrl)
    }

    @Test
    fun `text title preference replaces landscape logo even when labels are hidden`() {
        val overlay = landscapeCardTitleOverlay(
            isLandscapeMode = true,
            useTextTitle = true,
            hideLabels = true,
            title = "I Was a Stranger",
            logoUrl = "https://example.com/logo.png",
        )

        assertEquals("I Was a Stranger", overlay.text)
        assertNull(overlay.logoUrl)
    }

    @Test
    fun `out of ten keeps one decimal so a row stays visually aligned`() {
        assertEquals("8.3", posterRatingBadgeText("8.3", PosterRatingBadgeScale.OutOfTen))
        assertEquals("8.0", posterRatingBadgeText("8", PosterRatingBadgeScale.OutOfTen))
        assertEquals("7.6", posterRatingBadgeText("7.55", PosterRatingBadgeScale.OutOfTen))
        assertEquals("6.9", posterRatingBadgeText(" 6.9 ", PosterRatingBadgeScale.OutOfTen))
    }

    @Test
    fun `out of hundred drops the separator and keeps the trailing zero`() {
        assertEquals("83", posterRatingBadgeText("8.3", PosterRatingBadgeScale.OutOfHundred))
        assertEquals("85", posterRatingBadgeText("8.5", PosterRatingBadgeScale.OutOfHundred))
        assertEquals("50", posterRatingBadgeText("5", PosterRatingBadgeScale.OutOfHundred))
        assertEquals("50", posterRatingBadgeText("5.0", PosterRatingBadgeScale.OutOfHundred))
    }

    @Test
    fun `a perfect score renders on both scales`() {
        assertEquals("10", posterRatingBadgeText("10", PosterRatingBadgeScale.OutOfTen))
        assertEquals("10", posterRatingBadgeText("10.0", PosterRatingBadgeScale.OutOfTen))
        assertEquals("100", posterRatingBadgeText("10", PosterRatingBadgeScale.OutOfHundred))
        assertEquals("100", posterRatingBadgeText("9.99", PosterRatingBadgeScale.OutOfHundred))
    }

    @Test
    fun `both scales round the same tenth`() {
        assertEquals("7.6", posterRatingBadgeText("7.55", PosterRatingBadgeScale.OutOfTen))
        assertEquals("76", posterRatingBadgeText("7.55", PosterRatingBadgeScale.OutOfHundred))
    }

    @Test
    fun `the off scale suppresses even a valid rating`() {
        assertNull(posterRatingBadgeText("8.3", PosterRatingBadgeScale.Off))
        assertNull(posterRatingBadgeText("10", PosterRatingBadgeScale.Off))
    }

    @Test
    fun `rows without a usable rating get no badge`() {
        assertNull(posterRatingBadgeText(null, PosterRatingBadgeScale.OutOfTen))
        assertNull(posterRatingBadgeText("", PosterRatingBadgeScale.OutOfTen))
        assertNull(posterRatingBadgeText("N/A", PosterRatingBadgeScale.OutOfTen))
        assertNull(posterRatingBadgeText("0", PosterRatingBadgeScale.OutOfTen))
        assertNull(posterRatingBadgeText("0.02", PosterRatingBadgeScale.OutOfHundred))
        assertNull(posterRatingBadgeText("-1", PosterRatingBadgeScale.OutOfHundred))
    }

    @Test
    fun `ratings off the ten-point scale are rejected rather than clamped`() {
        // An already-out-of-100 value must not slip through as 830 on the OutOfHundred setting.
        assertNull(posterRatingBadgeText("83", PosterRatingBadgeScale.OutOfTen))
        assertNull(posterRatingBadgeText("83", PosterRatingBadgeScale.OutOfHundred))
        assertNull(posterRatingBadgeText("10.1", PosterRatingBadgeScale.OutOfTen))
    }

    @Test
    fun `an unknown stored scale falls back to out of ten`() {
        assertEquals(PosterRatingBadgeScale.OutOfTen, PosterRatingBadgeScale.fromStoredName(null))
        assertEquals(PosterRatingBadgeScale.OutOfTen, PosterRatingBadgeScale.fromStoredName("Nonsense"))
        assertEquals(
            PosterRatingBadgeScale.OutOfHundred,
            PosterRatingBadgeScale.fromStoredName("OutOfHundred"),
        )
    }

    @Test
    fun `logo remains the default landscape overlay`() {
        val overlay = landscapeCardTitleOverlay(
            isLandscapeMode = true,
            useTextTitle = false,
            hideLabels = false,
            title = "I Was a Stranger",
            logoUrl = "https://example.com/logo.png",
        )

        assertEquals("https://example.com/logo.png", overlay.logoUrl)
        assertNull(overlay.text)
    }
}
