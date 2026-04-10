package com.nuvio.app.features.home.components

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeHeroSectionTest {

    @Test
    fun `mobile hero height follows viewport height when provided`() {
        val layout = homeHeroLayout(
            maxWidthDp = 390f,
            viewportHeightDp = 844f,
        )

        assertEquals(false, layout.isTablet)
        assertEquals(717.4f, layout.heroHeight.value, 0.001f)
    }

    @Test
    fun `tablet hero height remains width driven even with viewport height`() {
        val layout = homeHeroLayout(
            maxWidthDp = 840f,
            viewportHeightDp = 1200f,
        )

        assertEquals(true, layout.isTablet)
        assertEquals(386.4f, layout.heroHeight.value, 0.001f)
    }
}