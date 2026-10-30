package com.nuvio.app.features.music.library

import com.nuvio.app.features.music.identity.ArtworkPalette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleasePalettesTest {
    private val palette = ArtworkPalette(accent = 1, onAccent = 2, tint = 3, ground = 4, forUrl = "https://c/1.jpg")

    private fun rel(key: String, cover: String?, p: ArtworkPalette? = null) = LibraryRelease(
        key = key, title = key, kind = ReleaseKind.Album,
        art = ReleaseArtwork(covers = listOfNotNull(cover)), palette = p,
    )

    @Test
    fun needsPaletteOnlyWhenThereIsACoverWithoutAMatchingPalette() {
        assertTrue(rel("a", "https://c/1.jpg").needsPalette)
        assertFalse(rel("a", "https://c/1.jpg", palette).needsPalette)
        assertTrue(rel("a", "https://c/2.jpg", palette).needsPalette)
        assertFalse(rel("a", null).needsPalette)
    }

    @Test
    fun aRefreshKeepsCachedReleasePalettes() {
        val old = LibraryArtist(id = "x", name = "X", releases = listOf(rel("a", "https://c/1.jpg", palette)))
        val fresh = LibraryArtist(id = "x", name = "X", releases = listOf(rel("a", "https://c/1.jpg"), rel("b", "https://c/b.jpg")))
        val merged = fresh.keepingPalettesFrom(old)
        assertEquals(palette, merged.releases.first { it.key == "a" }.palette)
        assertNull(merged.releases.first { it.key == "b" }.palette)
        assertEquals(fresh, fresh.keepingPalettesFrom(null))
    }
}
