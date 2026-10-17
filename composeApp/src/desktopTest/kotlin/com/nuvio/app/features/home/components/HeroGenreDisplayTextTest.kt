package com.nuvio.app.features.home.components

import kotlin.test.Test
import kotlin.test.assertEquals

class HeroGenreDisplayTextTest {

    @Test
    fun `lower-case genres are title-cased`() {
        assertEquals("Action", heroGenreDisplayText("action"))
        assertEquals("Science Fiction", heroGenreDisplayText("science fiction"))
        assertEquals("Sci-Fi", heroGenreDisplayText("sci-fi"))
        assertEquals("Action & Adventure", heroGenreDisplayText("action & adventure"))
    }

    @Test
    fun `already styled genres are left as they are`() {
        assertEquals("Sci-Fi & Fantasy", heroGenreDisplayText("Sci-Fi & Fantasy"))
        assertEquals("TV Movie", heroGenreDisplayText("TV Movie"))
    }
}
