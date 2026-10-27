package com.nuvio.app.features.locallibrary

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalMatchSearchQueryTest {

    @Test
    fun `trailing year is split off the dialog's prefilled query`() {
        assertEquals("10 Cloverfield Lane" to 2016, splitTrailingYear("10 Cloverfield Lane 2016"))
        assertEquals("Heat" to 1995, splitTrailingYear("Heat (1995)"))
    }

    @Test
    fun `query without a trailing year is left alone`() {
        assertEquals("10 Cloverfield Lane" to null, splitTrailingYear("  10 Cloverfield Lane "))
        assertEquals("Ocean's 11" to null, splitTrailingYear("Ocean's 11"))
    }

    @Test
    fun `a year on its own is the title`() {
        assertEquals("2012" to null, splitTrailingYear("2012"))
    }
}
