package com.nuvio.app.features.locallibrary

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalTitleNormalizationTest {

    @Test
    fun `accented and unaccented Latin titles normalize identically`() {
        assertEquals(
            normalizeLocalMatchTitle("Pokemon"),
            normalizeLocalMatchTitle("Pokémon"),
        )
        assertEquals(
            normalizeLocalMatchTitle("Amelie"),
            normalizeLocalMatchTitle("Amélie"),
        )
    }

    @Test
    fun `normalization preserves non Latin title characters`() {
        assertEquals("進撃の巨人", normalizeLocalMatchTitle("進撃の巨人"))
    }
}
