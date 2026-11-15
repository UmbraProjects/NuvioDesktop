package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IptvOnDemandTest {
    private val minute = 60_000L
    private val now = 50_000_000L

    private fun e(title: String, from: Int, to: Int, kind: String? = null, episode: String? = null, flags: Int = 0, year: Int? = null) =
        IptvEpgEntry(title, now + from * minute, now + to * minute, kind, episode = episode, flags = flags, year = year)

    @Test
    fun `worth offering once under way, long enough, and not something only ever live`() {
        assertTrue(IptvOnDemandRules.worth(e("Inception", -20, 120, IptvProgrammeKind.FILM), now))
        assertFalse(IptvOnDemandRules.worth(e("Inception", -1, 120, IptvProgrammeKind.FILM), now), "barely started: just join")
        assertFalse(IptvOnDemandRules.worth(e("Inception", 10, 150, IptvProgrammeKind.FILM), now), "not started yet")
        assertFalse(IptvOnDemandRules.worth(e("BBC News", -20, 40), now))
        assertFalse(IptvOnDemandRules.worth(e("Premier League", -20, 90, IptvProgrammeKind.SPORT), now))
        assertFalse(IptvOnDemandRules.worth(e("Strictly", -20, 90, flags = IptvGuideText.FLAG_LIVE), now))
        assertFalse(IptvOnDemandRules.worth(e("Short", -10, 5), now), "under twenty minutes")
        assertTrue(IptvOnDemandRules.worth(e("Taskmaster", -90, -30), now), "over and not in catch-up")
        assertFalse(IptvOnDemandRules.worth(e("Taskmaster", -90, -30), now, catchup = true))
    }

    @Test
    fun `films are looked up by title and year, episodes by name and numbers`() {
        val film = IptvOnDemandRules.query(e("Heat (1995)", -20, 150, IptvProgrammeKind.FILM))!!
        assertTrue(film.film)
        assertEquals("Heat", film.title)
        assertEquals(1995, film.year)
        assertNull(film.season)
        val show = IptvOnDemandRules.query(e("Taskmaster", -20, 40, episode = "S12 E3"))!!
        assertEquals("Taskmaster", show.title)
        assertEquals(12, show.season)
        assertEquals(3, show.episode)
        assertEquals(null to null, IptvOnDemandRules.episodeNumbers("E4"))
        assertNull(IptvOnDemandRules.query(e("Go", -20, 40)))
    }

    @Test
    fun `targets play a film or a numbered episode, else open the series`() {
        val film = IptvOnDemandTarget("movie", "tt0113277", "Heat", null, null, null, null)
        assertTrue(film.playable)
        assertEquals("tt0113277", film.videoId)
        assertEquals("From the start, on demand", IptvOnDemandRules.playLabel(film))
        val episode = IptvOnDemandTarget("series", "tt2265490", "Taskmaster", 12, 3, null, null)
        assertEquals("tt2265490:12:3", episode.videoId)
        assertEquals("S12 E3 from the start, on demand", IptvOnDemandRules.playLabel(episode))
        val series = IptvOnDemandTarget("series", "tt2265490", "Taskmaster", null, null, null, null)
        assertFalse(series.playable)
        assertEquals("Pick the episode, on demand", IptvOnDemandRules.playLabel(series))
        assertEquals("Started 20 min ago", IptvOnDemandRules.lateLine(e("X", -20, 40), now) { "" })
    }
}

class IptvOnDemandEpisodeTest {
    private val now = 50_000_000L

    @Test
    fun `numbers come from the description when the guide's field is empty`() {
        val entry = IptvEpgEntry("Friends", now - 27 * 60_000L, now + 3 * 60_000L, null, desc = "S3 E22 The One With the Screamer When Rachel finds out...")
        val q = IptvOnDemandRules.query(entry)!!
        assertEquals(3, q.season)
        assertEquals(22, q.episode)
        assertEquals(2 to 5, IptvOnDemandRules.episodeNumbers("Series 2, Episode 5. Something happens."))
        assertEquals(null to null, IptvOnDemandRules.episodeNumbers("Rachel's S3 plan"))
    }

    @Test
    fun `an episode is found by the name its description starts with, the longest name winning`() {
        val seasons = listOf(
            3 to listOf(21 to "The One with the Dollhouse", 22 to "The One with the Screamer"),
            4 to listOf(1 to "The One with the Jellyfish", 2 to "Plan"),
        )
        assertEquals(3 to 22, IptvOnDemandRules.episodeByName("The One With the Screamer. When Rachel finds out...", seasons))
        assertEquals(3 to 22, IptvOnDemandRules.episodeByName("S3 E22 The One With the Screamer When...", seasons))
        assertNull(IptvOnDemandRules.episodeByName("Plan B for something", seasons), "five letters or more")
        assertNull(IptvOnDemandRules.episodeByName("Rachel and Ross argue.", seasons))
    }
}
