package com.nuvio.app.features.iptv

import com.nuvio.app.features.livescores.LiveSport
import com.nuvio.app.features.livescores.LiveTeam
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IptvTeamsTest {
    private val day = 24 * 3_600_000L
    private val now = 1_000 * day
    private val united = LiveTeam(LiveSport.Football, "360", "Manchester United", "Man United")
    private val city = LiveTeam(LiveSport.Football, "382", "Manchester City", "Man City")
    private val chelsea = LiveTeam(LiveSport.Football, "363", "Chelsea", "Chelsea")
    private val teams = listOf(united, city, chelsea)

    private fun game(title: String) = IptvEpgEntry(title, 0L, 2 * 3_600_000L, IptvProgrammeKind.SPORT)

    @Test
    fun `the two sides are read out of the ways guides write a fixture`() {
        assertEquals(listOf("Man Utd", "Chelsea"), IptvTeamRules.teamsIn("Premier League: Man Utd v Chelsea (Live)", IptvProgrammeKind.SPORT))
        assertEquals(listOf("Brighton", "Manchester United"), IptvTeamRules.teamsIn("Live Brighton vs Manchester United", null))
        assertEquals(listOf("Arsenal", "Man United"), IptvTeamRules.teamsIn("Football - Arsenal v Man United - Live", null))
        assertTrue(IptvTeamRules.teamsIn("Kramer vs Kramer", IptvProgrammeKind.FILM).isEmpty(), "a film is not a fixture")
        assertTrue(IptvTeamRules.teamsIn("Match of the Day", IptvProgrammeKind.SPORT).isEmpty())
    }

    @Test
    fun `every spelling of a team counts as one, and it is offered once watched enough`() {
        var stats = emptyMap<String, IptvTeamStat>()
        listOf("Man Utd v Chelsea", "Brighton v Manchester United", "Live: Man United v Spurs").forEachIndexed { i, title ->
            val at = now - (10 - i) * day
            stats = IptvTeamRules.note(stats, game(title), IptvWatchEvent.Seen, at)
            if (i > 0) stats = IptvTeamRules.note(stats, game(title), IptvWatchEvent.Finished, at)
        }
        val learned = IptvTeamRules.learned(stats, teams, emptyList(), emptySet(), now)
        assertEquals("Manchester United", learned.first().name)
        assertEquals(3, learned.first().games)
        assertEquals(united, learned.first().team)
        assertFalse(learned.any { it.name == "Chelsea" }, "one game is not enough")
        // Followed already, turned down, or watched too long ago: not offered.
        assertTrue(IptvTeamRules.learned(stats, teams, listOf("Man Utd"), emptySet(), now).none { it.team == united })
        assertTrue(IptvTeamRules.learned(stats, teams, emptyList(), setOf(learned.first().key), now).none { it.team == united })
        assertTrue(IptvTeamRules.learned(stats, teams, emptyList(), emptySet(), now + 60 * day).isEmpty())
    }

    @Test
    fun `Man City is not Man United`() {
        assertEquals(city, IptvTeamRules.resolve("Man City", teams))
        assertEquals(united, IptvTeamRules.resolve("Man Utd", teams))
        assertNull(IptvTeamRules.resolve("Wrexham", teams))
    }

    @Test
    fun `a followed team finds every spelling of itself in the guide`() {
        assertTrue(IptvDecide.matchesFollow("Manchester United", "Premier League: Man Utd v Chelsea"))
        assertTrue(IptvDecide.matchesFollow("Man Utd", "Manchester United v Spurs"))
        assertTrue(IptvDecide.matchesFollow("Manchester United", "Brighton v Man United"))
        assertFalse(IptvDecide.matchesFollow("Manchester United", "Man City v Chelsea"))
        assertFalse(IptvDecide.matchesFollow("Manchester United", "Manchester City v Chelsea"))
    }
}
