package com.nuvio.app.features.livescores

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** fork:live-scores - the feeds' answers (trimmed copies of real ones, Nov 11 2026) and the pop-up rules. */
class LiveScoreRulesTest {
    private val united = LiveTeam(LiveSport.Football, "360", "Manchester United", "Man United")
    private val liverpool = LiveTeam(LiveSport.Football, "364", "Liverpool", "Liverpool")

    private fun football(
        home: Int?,
        away: Int?,
        phase: MatchPhase,
        incidents: List<MatchIncident> = emptyList(),
        status: String = "STATUS_SECOND_HALF",
    ) = LiveMatch(LiveFeed.Espn, "m1", LiveSport.Football, "eng.1", "Premier League", united, liverpool, home, away, phase, status, "67'", 0L, incidents)

    private fun goal(clock: String, teamId: String, player: String, kind: IncidentKind = IncidentKind.Goal) =
        MatchIncident(incidentKey(kind, clock, teamId, player), kind, teamId, clock, player, "Goal")

    @Test
    fun theFirstLookPopsNothing() {
        val now = football(1, 0, MatchPhase.Live, listOf(goal("12'", "360", "Bruno Fernandes")))
        val result = detectScoreEvents(null, now, favourite = true, seen = emptySet())
        assertTrue(result.events.isEmpty())
        // ...and a second identical look pops nothing either.
        assertTrue(detectScoreEvents(now, now, true, result.seen).events.isEmpty())
    }

    @Test
    fun kickOffGoalHalfTimeAndFullTime() {
        val before = football(null, null, MatchPhase.Before, status = "STATUS_SCHEDULED")
        var seen = detectScoreEvents(null, before, true, emptySet()).seen
        val live = football(0, 0, MatchPhase.Live)
        val kickOff = detectScoreEvents(before, live, true, seen)
        assertEquals(listOf(ScoreEventKind.KickOff), kickOff.events.map { it.kind })
        seen = kickOff.seen

        val scored = football(1, 0, MatchPhase.Live, listOf(goal("12'", "360", "Bruno Fernandes", IncidentKind.PenaltyGoal)))
        val goal = detectScoreEvents(live, scored, true, seen)
        assertEquals(listOf(ScoreEventKind.Goal), goal.events.map { it.kind })
        assertEquals(true, goal.events.single().forHome)
        assertEquals("Bruno Fernandes · pen", goal.events.single().detail)
        assertTrue(goal.events.single().favourite)
        seen = goal.seen

        val half = scored.copy(phase = MatchPhase.HalfTime, statusName = "STATUS_HALFTIME")
        val ht = detectScoreEvents(scored, half, true, seen)
        assertEquals(listOf(ScoreEventKind.HalfTime), ht.events.map { it.kind })
        val full = scored.copy(phase = MatchPhase.Finished, statusName = "STATUS_FULL_TIME")
        assertEquals(listOf(ScoreEventKind.FullTime), detectScoreEvents(half, full, true, ht.seen).events.map { it.kind })
    }

    @Test
    fun aGoalSeenFromTheScoreIsNotShownAgainWhenItsRowArrives() {
        val live = football(0, 0, MatchPhase.Live)
        var seen = detectScoreEvents(null, live, true, emptySet()).seen
        val scoreFirst = football(0, 1, MatchPhase.Live)
        val first = detectScoreEvents(live, scoreFirst, true, seen)
        assertEquals(listOf(ScoreEventKind.Goal), first.events.map { it.kind })
        assertEquals(false, first.events.single().forHome)
        seen = first.seen
        val rowLater = football(0, 1, MatchPhase.Live, listOf(goal("30'", "364", "Mohamed Salah")))
        assertTrue(detectScoreEvents(scoreFirst, rowLater, true, seen).events.isEmpty())
    }

    @Test
    fun aGoalRuledOutIsVarAndTheNextGoalStillShows() {
        val one = football(1, 0, MatchPhase.Live, listOf(goal("50'", "360", "Rasmus Hojlund")))
        var seen = detectScoreEvents(null, one, true, emptySet()).seen
        val two = football(2, 0, MatchPhase.Live, one.incidents + goal("60'", "360", "Amad"))
        val scored = detectScoreEvents(one, two, true, seen)
        assertEquals(listOf(ScoreEventKind.Goal), scored.events.map { it.kind })
        seen = scored.seen
        val ruledOut = football(1, 0, MatchPhase.Live, one.incidents)
        val varEvent = detectScoreEvents(two, ruledOut, true, seen)
        assertEquals(listOf(ScoreEventKind.Var), varEvent.events.map { it.kind })
        assertEquals("Goal ruled out", varEvent.events.single().detail)
        seen = varEvent.seen
        val again = football(2, 0, MatchPhase.Live, one.incidents + goal("75'", "360", "Mason Mount"))
        val next = detectScoreEvents(ruledOut, again, true, seen)
        assertEquals(listOf(ScoreEventKind.Goal), next.events.map { it.kind })
        assertEquals("Mason Mount", next.events.single().detail)
    }

    @Test
    fun cardsAndPenalties() {
        val live = football(0, 0, MatchPhase.Live)
        val seen = detectScoreEvents(null, live, false, emptySet()).seen
        val red = MatchIncident(incidentKey(IncidentKind.RedCard, "40'", "364", "Virgil van Dijk"), IncidentKind.RedCard, "364", "40'", "Virgil van Dijk", "Red Card")
        val yellow = MatchIncident(incidentKey(IncidentKind.YellowCard, "41'", "360", "Casemiro"), IncidentKind.YellowCard, "360", "41'", "Casemiro", "Yellow Card")
        val missed = MatchIncident(incidentKey(IncidentKind.PenaltyMissed, "44'", "360", "Bruno Fernandes"), IncidentKind.PenaltyMissed, "360", "44'", "Bruno Fernandes", "Penalty - Missed")
        val result = detectScoreEvents(live, live.copy(incidents = listOf(red, yellow, missed)), false, seen)
        // A football yellow card is not an event worth a pop-up.
        assertEquals(listOf(ScoreEventKind.RedCard, ScoreEventKind.PenaltyMissed), result.events.map { it.kind })
        assertEquals(false, result.events.first().forHome)
        assertTrue(result.events.none { it.favourite })
    }

    @Test
    fun rugbyTriesPointsAndSinBins() {
        val bath = LiveTeam(LiveSport.Rugby, "25898", "Bath Rugby", "Bath")
        val sale = LiveTeam(LiveSport.Rugby, "25907", "Sale Sharks", "Sale")
        fun rugby(home: Int, away: Int, incidents: List<MatchIncident>) =
            LiveMatch(LiveFeed.Espn, "r1", LiveSport.Rugby, "rugby.267979", "Premiership Rugby", bath, sale, home, away, MatchPhase.Live, "STATUS_FIRST_HALF", "20'", 0L, incidents)
        fun row(kind: IncidentKind, clock: String, team: String, player: String, text: String) =
            MatchIncident(incidentKey(kind, clock, team, player), kind, team, clock, player, text)
        val start = rugby(0, 0, emptyList())
        val seen = detectScoreEvents(null, start, false, emptySet()).seen
        val tryRow = row(IncidentKind.Try, "12'", "25898", "Finn Russell", "try")
        val conversion = row(IncidentKind.Conversion, "13'", "25898", "Finn Russell", "conversion")
        val sinBin = row(IncidentKind.YellowCard, "15'", "25907", "Tom Curry", "yellow card")
        val kick = row(IncidentKind.KickedPoints, "18'", "25907", "George Ford", "penalty goal")
        val after = rugby(7, 3, listOf(tryRow, conversion, sinBin, kick))
        val events = detectScoreEvents(start, after, false, seen).events
        assertEquals(listOf(ScoreEventKind.Try, ScoreEventKind.SinBin, ScoreEventKind.Points), events.map { it.kind })
        assertEquals("George Ford · Penalty goal", events.last().detail)
    }

    @Test
    fun fightNightsStartAndResult() {
        val card = LiveMatch(
            LiveFeed.Espn, "ufc334", LiveSport.Mma, "ufc", "UFC", LiveTeam(LiveSport.Mma, "a", "Ciryl Gane"),
            LiveTeam(LiveSport.Mma, "b", "Alex Hokit"), null, null, MatchPhase.Before, "pre", null, 0L, headline = "UFC 334: Gane vs. Hokit",
        )
        val seen = detectScoreEvents(null, card, false, emptySet()).seen
        val on = card.copy(phase = MatchPhase.Live)
        val start = detectScoreEvents(card, on, false, seen)
        assertEquals(listOf(ScoreEventKind.FightStart), start.events.map { it.kind })
        val done = on.copy(phase = MatchPhase.Finished, result = "Ciryl Gane beat Alex Hokit · KO/TKO")
        val result = detectScoreEvents(on, done, false, start.seen)
        assertEquals(listOf(ScoreEventKind.FightResult), result.events.map { it.kind })
        assertEquals("Ciryl Gane beat Alex Hokit · KO/TKO", result.events.single().detail)
    }

    @Test
    fun espnScoreboardParses() {
        val text = """
            {"leagues":[{"id":"700","name":"English Premier League"}],"events":[{"id":"401879244","uid":"s:600~l:700~e:401879244",
            "date":"2026-11-08T14:00Z","competitions":[{"date":"2026-11-08T14:00Z",
            "status":{"clock":5400.0,"displayClock":"67'","period":2,"type":{"name":"STATUS_SECOND_HALF","state":"in","shortDetail":"67'"}},
            "competitors":[
              {"homeAway":"home","score":"1","team":{"id":"360","displayName":"Manchester United","shortDisplayName":"Man United"}},
              {"homeAway":"away","score":"0","team":{"id":"364","displayName":"Liverpool","shortDisplayName":"Liverpool"}}],
            "details":[
              {"type":{"id":"70","text":"Goal"},"clock":{"value":870.0,"displayValue":"15'"},"team":{"id":"360"},"scoringPlay":true,
               "redCard":false,"yellowCard":false,"penaltyKick":false,"ownGoal":false,"athletesInvolved":[{"displayName":"Bruno Fernandes"}]},
              {"type":{"id":"94","text":"Yellow Card"},"clock":{"displayValue":"22'"},"team":{"id":"364"},"scoringPlay":false,"yellowCard":true,
               "athletesInvolved":[{"displayName":"Wataru Endo"}]}]}]}]}
        """.trimIndent()
        val match = parseEspnScoreboard(text, LiveSport.Football).single()
        assertEquals("eng.1", match.competitionKey)
        assertEquals("Premier League", match.competitionName)
        assertEquals(MatchPhase.Live, match.phase)
        assertEquals("67'", match.clock)
        assertEquals(1, match.homeScore)
        assertEquals("Man United", match.home.shortName)
        assertEquals(listOf(IncidentKind.Goal, IncidentKind.YellowCard), match.incidents.map { it.kind })
        assertEquals(isoToEpochMs("2026-11-08T14:00:00Z"), match.startEpochMs)
    }

    @Test
    fun theMatchReportGivesTheSameKeysAndMissedPenalties() {
        val text = """
            {"header":{"id":"401879244","league":{"id":"700","name":"English Premier League","slug":"eng.1"},
            "competitions":[{"date":"2026-11-08T14:00Z","status":{"type":{"name":"STATUS_HALFTIME","state":"in"}},
            "competitors":[{"homeAway":"home","score":"1","team":{"id":"360","displayName":"Manchester United"}},
                           {"homeAway":"away","score":"0","team":{"id":"364","displayName":"Liverpool"}}]}]},
            "keyEvents":[
              {"id":"1","type":{"text":"Kickoff"}},
              {"id":"2","type":{"text":"Goal"},"clock":{"displayValue":"15'"},"scoringPlay":true,"team":{"id":"360"},
               "participants":[{"athlete":{"displayName":"Bruno Fernandes"}}]},
              {"id":"3","type":{"text":"Penalty - Missed"},"clock":{"displayValue":"40'"},"team":{"id":"364"},
               "participants":[{"athlete":{"displayName":"Mohamed Salah"}}]}]}
        """.trimIndent()
        val match = parseEspnSummary(text, LiveSport.Football, "401879244")!!
        assertEquals(MatchPhase.HalfTime, match.phase)
        assertEquals("eng.1", match.competitionKey)
        assertEquals(listOf(IncidentKind.Goal, IncidentKind.PenaltyMissed), match.incidents.map { it.kind })
        assertEquals(incidentKey(IncidentKind.Goal, "15'", "360", "Bruno Fernandes"), match.incidents.first().key)
    }

    @Test
    fun boxingFromTheSportsDb() {
        val text = """
            {"events":[{"idEvent":"2607376","strEvent":"MVPW 07 Turhan vs Baumgardner","strTimestamp":"2026-11-08T22:15:00",
            "strStatus":"FT","strResult":"Alycia Baumgardner produced a dominant performance to defeat Elif Nur Turhan by unanimous decision. More text."}]}
        """.trimIndent()
        val fight = parseSportsDbFights(text, nowMs = isoToEpochMs("2026-11-09T10:00:00Z")!!).single()
        assertEquals(MatchPhase.Finished, fight.phase)
        assertEquals("Turhan", fight.home.name)
        assertEquals("Baumgardner", fight.away.name)
        assertTrue(fight.result!!.startsWith("Alycia Baumgardner produced"))
        assertEquals("Jhon Orobio" to "Hendri Cedeno", fighterNames("Jhon Orobio vs Hendri Cedeno"))
    }

    @Test
    fun apiFootballBackupParses() {
        val text = """
            {"response":[{"fixture":{"id":1035,"timestamp":1762610400,"status":{"short":"2H","elapsed":67}},
            "league":{"id":39,"name":"Premier League"},
            "teams":{"home":{"id":33,"name":"Manchester United"},"away":{"id":40,"name":"Liverpool"}},
            "goals":{"home":1,"away":1},
            "events":[{"time":{"elapsed":15,"extra":null},"team":{"id":33},"player":{"name":"B. Fernandes"},"type":"Goal","detail":"Penalty"},
                      {"time":{"elapsed":55,"extra":null},"team":{"id":40},"player":{"name":"M. Salah"},"type":"Goal","detail":"Normal Goal"},
                      {"time":{"elapsed":60,"extra":null},"team":{"id":33},"player":{"name":"Casemiro"},"type":"Card","detail":"Red Card"}]}]}
        """.trimIndent()
        val match = parseApiFootballLive(text).single()
        assertEquals("eng.1", match.competitionKey)
        assertEquals(MatchPhase.Live, match.phase)
        assertEquals("67'", match.clock)
        assertEquals(listOf(IncidentKind.PenaltyGoal, IncidentKind.Goal, IncidentKind.RedCard), match.incidents.map { it.kind })
        // The favourite (ESPN's id 360) is still recognised on this feed by name.
        assertTrue(isFavouriteTeam(match.home, listOf(united)))
    }

    @Test
    fun favouritesAndFollowing() {
        val match = football(0, 0, MatchPhase.Before)
        assertTrue(isFollowedMatch(match, listOf(united), emptySet()))
        assertTrue(isFollowedMatch(match, emptyList(), setOf("eng.1")))
        assertTrue(!isFollowedMatch(match, emptyList(), setOf("eng.2")))
        assertEquals("manchester united", teamNameKey("Manchester Utd FC"))
    }

    @Test
    fun teamSearchFindsManUtd() {
        val teams = listOf(
            LiveTeam(LiveSport.Football, "382", "Manchester City", "Man City"),
            united,
            LiveTeam(LiveSport.Rugby, "25898", "Bath Rugby", "Bath"),
        )
        assertEquals(united, searchTeams(teams, "Man Utd").first())
        assertEquals(united, searchTeams(teams, "united").first())
        assertTrue(searchTeams(teams, "  ").isEmpty())
    }

    @Test
    fun favouritesSurviveStorage() {
        val teams = listOf(united, LiveTeam(LiveSport.Rugby, "1|2", "Odd | Name\nX", "Odd"))
        assertEquals(teams, decodeTeams(encodeTeams(teams)))
        assertTrue(decodeTeams(null).isEmpty())
    }

    @Test
    fun timesAndUids() {
        assertEquals(0L, isoToEpochMs("1970-01-01T00:00Z"))
        assertEquals(1_762_610_400_000L, isoToEpochMs("2025-11-08T14:00:00Z"))
        assertEquals(isoToEpochMs("2026-11-08T14:00:00Z"), isoToEpochMs("2026-11-08T15:00:00+01:00"))
        assertEquals("700", espnLeagueIdFromUid("s:600~l:700~e:401879244"))
        assertNull(espnLeagueIdFromUid(null))
    }

    @Test
    fun notificationText() {
        val event = ScoreEvent(ScoreEventKind.Goal, football(2, 1, MatchPhase.Live), true, "Bruno Fernandes · pen", "67'", true, "k")
        assertEquals("GOAL · Man United 2 – 1 Liverpool" to "Bruno Fernandes · pen · 67' · Premier League", scoreNotificationText(event))
    }
}
