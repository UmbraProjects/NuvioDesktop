package com.nuvio.app.features.home.components

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
