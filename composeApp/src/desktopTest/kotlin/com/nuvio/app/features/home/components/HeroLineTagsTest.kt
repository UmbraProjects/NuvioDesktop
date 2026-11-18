package com.nuvio.app.features.home.components

import kotlin.test.Test
import kotlin.test.assertEquals

/** The hero's genre line drops its extra tags, last first, before the year or runtime is squeezed. */
class HeroLineTagsTest {
    // Horror, Drama, Mystery, [Dark Comedy, Folk Horror], 2019 July 3, 2h 28m
    private val widths = listOf(70, 60, 80, 110, 110, 120, 70)
    private val sep = 20

    @Test
    fun `everything fits`() {
        assertEquals(2, heroLineTagsThatFit(widths, sep, firstTag = 3, tagCount = 2, maxWidth = 2000))
    }

    @Test
    fun `the last tag goes first`() {
        // all: 620 + 6 * 20 = 740; without Folk Horror: 510 + 5 * 20 = 610
        assertEquals(1, heroLineTagsThatFit(widths, sep, firstTag = 3, tagCount = 2, maxWidth = 700))
    }

    @Test
    fun `both tags go when the rest only just fits`() {
        assertEquals(0, heroLineTagsThatFit(widths, sep, firstTag = 3, tagCount = 2, maxWidth = 500))
    }

    @Test
    fun `no tags, nothing to drop`() {
        assertEquals(0, heroLineTagsThatFit(listOf(70, 120), sep, firstTag = -1, tagCount = 0, maxWidth = 10))
    }
}
