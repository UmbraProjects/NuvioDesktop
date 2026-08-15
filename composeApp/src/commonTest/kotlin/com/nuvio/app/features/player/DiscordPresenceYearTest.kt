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

    @Test
    fun `the launch title wins when it has one`() {
        assertEquals(
            "Mushoku Tensei: Jobless Reincarnation",
            resolveDiscordPresenceTitle(
                argsTitle = "Mushoku Tensei: Jobless Reincarnation",
                metaName = "Something else",
                streamReleaseName = "Mushoku.Tensei.S03E01.1080p.WEB-DL.x265-GROUP",
                isEpisode = true,
            ),
        )
    }

    @Test
    fun `a blank launch title falls back to the loaded meta`() {
        // The anime case: nothing named the title at launch because no meta addon answered for a
        // per-entry id, and the meta arrived (or was recovered) afterwards.
        assertEquals(
            "Mushoku Tensei III",
            resolveDiscordPresenceTitle(
                argsTitle = "   ",
                metaName = "Mushoku Tensei III",
                streamReleaseName = null,
                isEpisode = true,
            ),
        )
    }

    @Test
    fun `with no metadata at all the release name is parsed`() {
        assertEquals(
            "Mushoku Tensei",
            resolveDiscordPresenceTitle(
                argsTitle = "",
                metaName = null,
                streamReleaseName = "Mushoku.Tensei.S03E01.1080p.WEB-DL.x265-GROUP",
                isEpisode = true,
            ),
        )
    }

    @Test
    fun `a movie release name is parsed without its release tags`() {
        assertEquals(
            "Fight Club",
            resolveDiscordPresenceTitle(
                argsTitle = null,
                metaName = null,
                streamReleaseName = "Fight.Club.1999.1080p.BluRay.x264-GROUP",
                isEpisode = false,
            ),
        )
    }

    @Test
    fun `nothing to go on stays null so the presence is cleared`() {
        assertNull(
            resolveDiscordPresenceTitle(
                argsTitle = null,
                metaName = null,
                streamReleaseName = "   ",
                isEpisode = false,
            ),
        )
    }
}
