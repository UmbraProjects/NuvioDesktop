package com.nuvio.app.features.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsTitleCaseTest {
    @Test
    fun `uppercase settings headings become title case`() {
        assertEquals("Skip Segments", settingsTitleCase("SKIP SEGMENTS"))
        assertEquals("TMDB Api Keys", settingsTitleCase("TMDB API KEYS"))
        assertEquals("P2p & Hdr10+", settingsTitleCase("P2P & HDR10+"))
    }

    @Test
    fun `approved uppercase brands retain their styling`() {
        assertEquals(
            "TMDB TVDB SIMKL RTX NVIDIA Api",
            settingsTitleCase("TMDB TVDB SIMKL RTX NVIDIA API"),
        )
    }

    @Test
    fun `mixed case product names remain unchanged`() {
        assertEquals("MDBList Ratings", settingsTitleCase("MDBList RATINGS"))
        assertEquals("QualiCache Server", settingsTitleCase("QualiCache SERVER"))
    }

    @Test
    fun `already styled and localized headings remain readable`() {
        assertEquals("Next Episode", settingsTitleCase("Next Episode"))
        assertEquals("Épisodes À Venir", settingsTitleCase("ÉPISODES À VENIR"))
    }
}
