package com.nuvio.app.features.details

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PersonNameKeyTest {
    @Test
    fun `accents, punctuation and spacing do not split one person`() {
        assertEquals(personNameKey("Lauren Velez"), personNameKey("Lauren Vélez"))
        assertEquals(personNameKey("J.K. Simmons"), personNameKey("JK Simmons"))
        assertEquals(personNameKey("Jean-Luc  Picard"), personNameKey("jean luc picard"))
        assertEquals(personNameKey("Zoë Kravitz"), personNameKey("Zoe Kravitz"))
    }

    @Test
    fun `different people stay different`() {
        assertNotEquals(personNameKey("Michael C. Hall"), personNameKey("Michael Hall"))
        assertNotEquals(personNameKey("Jennifer Carpenter"), personNameKey("Julie Benz"))
    }

    @Test
    fun `non-latin names keep their letters`() {
        assertEquals("渡辺謙", personNameKey("渡辺 謙"))
    }
}
