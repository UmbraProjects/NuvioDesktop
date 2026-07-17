package com.nuvio.app.features.discord

import kotlin.test.Test
import kotlin.test.assertEquals

class DiscordRichPresenceFormattingTest {
    @Test
    fun moviePausedPresenceShowsYearAndFrozenTime() {
        val text = discordPausedPresenceText(
            title = "Parasite",
            releaseYear = "2019",
            episodeLabel = null,
            episodeTitle = null,
            positionMs = 4_234_000L,
            durationMs = 7_920_000L,
        )

        assertEquals("Paused: Parasite", text.details)
        assertEquals("2019 · 1:10:34 / 2:12:00", text.state)
    }

    @Test
    fun seriesPausedPresenceShowsEpisodeAndFrozenTime() {
        val text = discordPausedPresenceText(
            title = "The Office (US)",
            releaseYear = null,
            episodeLabel = "S07E18",
            episodeTitle = "Garage Sale",
            positionMs = 854_000L,
            durationMs = 1_766_000L,
        )

        assertEquals("Paused: The Office (US)", text.details)
        assertEquals("S07E18 · Garage Sale · 14:14 / 29:26", text.state)
    }
}
