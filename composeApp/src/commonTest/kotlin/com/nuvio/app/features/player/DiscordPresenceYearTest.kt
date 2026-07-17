package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DiscordPresenceYearTest {

    @Test
    fun `extracts movie year from release text`() {
        assertEquals("2019", extractDiscordReleaseYear("2019"))
        assertEquals("2019", extractDiscordReleaseYear("2019–2023"))
    }

    @Test
    fun `missing release text has no fallback year`() {
        assertNull(extractDiscordReleaseYear(null))
        assertNull(extractDiscordReleaseYear("Unknown"))
    }
}
