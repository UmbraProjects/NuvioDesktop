package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImmersiveShelfLayoutTest {

    @Test
    fun `landscape TV shelf leaves more room for the hero`() {
        val portraitHeight = immersiveShelfHeightDp(
            viewportHeightDp = 1080f,
            landscapeMode = false,
        )
        val landscapeHeight = immersiveShelfHeightDp(
            viewportHeightDp = 1080f,
            landscapeMode = true,
        )

        assertEquals(440f, portraitHeight)
        assertEquals(340f, landscapeHeight)
        assertTrue(landscapeHeight < portraitHeight)
    }

    @Test
    fun `landscape TV cards fit the shorter shelf at common viewport sizes`() {
        val fullHdBaseWidth = immersiveCatalogPosterBaseWidthDp(
            maxWidthDp = 1920f,
            shelfHeightDp = immersiveShelfHeightDp(1080f, landscapeMode = true),
            sectionPaddingDp = 32f,
            hideLabels = false,
            landscapeMode = true,
        )
        val hdBaseWidth = immersiveCatalogPosterBaseWidthDp(
            maxWidthDp = 1280f,
            shelfHeightDp = immersiveShelfHeightDp(720f, landscapeMode = true),
            sectionPaddingDp = 28f,
            hideLabels = false,
            landscapeMode = true,
        )

        assertEquals(210, fullHdBaseWidth)
        assertEquals(136, hdBaseWidth)
    }

    @Test
    fun `portrait TV shelf sizing remains unchanged by default`() {
        val defaultSizing = immersiveCatalogPosterBaseWidthDp(
            maxWidthDp = 1920f,
            shelfHeightDp = 440f,
            sectionPaddingDp = 32f,
            hideLabels = true,
        )
        val explicitPortraitSizing = immersiveCatalogPosterBaseWidthDp(
            maxWidthDp = 1920f,
            shelfHeightDp = 440f,
            sectionPaddingDp = 32f,
            hideLabels = true,
            landscapeMode = false,
        )

        assertEquals(defaultSizing, explicitPortraitSizing)
    }
}
