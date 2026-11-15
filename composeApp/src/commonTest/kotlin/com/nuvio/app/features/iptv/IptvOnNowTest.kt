package com.nuvio.app.features.iptv

import com.nuvio.app.features.livescores.LiveFeed
import com.nuvio.app.features.livescores.LiveMatch
import com.nuvio.app.features.livescores.LiveSport
import com.nuvio.app.features.livescores.LiveTeam
import com.nuvio.app.features.livescores.MatchPhase
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IptvOnNowTest {
    private val minute = 60_000L
    private val now = 1_800_000_000_000L

    private fun merged(uid: Int, name: String, archiveDays: Int = 0) =
        IptvMergedChannel(listOf(IptvChannel(uid = uid, key = "k$uid", num = uid, name = name, categoryId = "c", streamId = "$uid", archiveDays = archiveDays)))

    private fun entry(title: String, startMin: Long, lengthMin: Long, kind: String? = null) =
        IptvEpgEntry(title, now + startMin * minute, now + (startMin + lengthMin) * minute, kind)

    private fun on(uid: Int, name: String, category: String, vararg entries: IptvEpgEntry) =
        IptvChannelNow(merged(uid, name), entries.toList(), category)

    private fun match(home: String, away: String, phase: MatchPhase = MatchPhase.Live, startMin: Long = -60, homeShort: String = home) = LiveMatch(
        feed = LiveFeed.Espn, id = "$home-$away", sport = LiveSport.Football, competitionKey = null, competitionName = "Europa League",
        home = LiveTeam(LiveSport.Football, "h", home, homeShort), away = LiveTeam(LiveSport.Football, "a", away),
        homeScore = 2, awayScore = 1, phase = phase, statusName = "", clock = "67'", startEpochMs = now + startMin * minute,
    )

    @Test
    fun `guide categories become a kind`() {
        assertEquals(IptvProgrammeKind.FILM, IptvProgrammeKind.of("Movie / Drama"))
        assertEquals(IptvProgrammeKind.SPORT, IptvProgrammeKind.of("Football"))
        assertEquals(IptvProgrammeKind.NEWS, IptvProgrammeKind.of("News / Current affairs"))
        assertNull(IptvProgrammeKind.of("Entertainment"))
    }

    @Test
    fun `a film is what the guide calls one, or feature length on a film channel`() {
        val film4 = on(1, "Film4 HD", "UK | Movies")
        val bbc = on(2, "BBC One", "UK | Entertainment")
        assertTrue(IptvOnNow.isFilm(entry("Heat", -20, 170), film4))
        assertFalse(IptvOnNow.isFilm(entry("Film4 Preview", -5, 10), film4))
        assertFalse(IptvOnNow.isFilm(entry("EastEnders", -10, 30), bbc))
        assertFalse(IptvOnNow.isFilm(entry("Long Documentary", -10, 120), bbc))
        assertTrue(IptvOnNow.isFilm(entry("Paddington 2", -70, 105, IptvProgrammeKind.FILM), bbc))
        assertTrue(IptvOnNow.isFilm(entry("Film: Paddington 2", -70, 105), bbc))
        // The guide knows better than the channel's name.
        assertFalse(IptvOnNow.isFilm(entry("Film Review", -10, 90, IptvProgrammeKind.NEWS), film4))
    }

    @Test
    fun `films list what is on and about to start, once each`() {
        val channels = listOf(
            on(1, "Film4 SD", "UK | Movies", entry("Heat", -20, 170)),
            on(2, "Film4 FHD", "UK | FHD Movies", entry("Heat", -20, 170)),
            on(3, "Sky Cinema Greats", "UK | Movies", entry("Old One", -200, 110), entry("Jaws", -5, 124), entry("Later", 119, 100)),
            on(4, "ITV4", "UK | Entertainment", entry("Mad Max: Fury Road", 19, 120, IptvProgrammeKind.FILM)),
        )
        val films = IptvOnNow.films(channels, now)
        assertEquals(listOf("Jaws", "Heat", "Mad Max: Fury Road"), films.map { it.entry.title })
        assertEquals("Film4 FHD", films[1].channel.primary.name)
    }

    @Test
    fun `film titles are cleaned for a lookup`() {
        assertEquals("Heat" to 1995, IptvOnNow.filmQuery("New: Heat (1995)"))
        assertEquals("Paddington 2" to null, IptvOnNow.filmQuery("Film: Paddington 2"))
        assertEquals("Mad Max: Fury Road" to null, IptvOnNow.filmQuery("Mad Max: Fury Road"))
    }

    @Test
    fun `a title names a team by its long or short form`() {
        assertTrue(IptvSportMatch.mentions("Live: Man Utd v Real Sociedad", "Manchester United", "Man United"))
        assertTrue(IptvSportMatch.mentions("Live: Man Utd v Real Sociedad", "Real Sociedad"))
        assertFalse(IptvSportMatch.mentions("Manchester City v Arsenal", "Manchester United", "Man United"))
        assertTrue(IptvSportMatch.mentions("Spurs v Roma", "Tottenham Hotspur", "Tottenham"))
        assertTrue(IptvSportMatch.mentions("Wolves v Brighton", "Wolverhampton Wanderers", "Wolves"))
        assertTrue(IptvSportMatch.mentions("UEL: Olympique Lyonnais - Rangers FC", "Rangers"))
        assertFalse(IptvSportMatch.mentions("Emmerdale", "Bath"))
    }

    @Test
    fun `a match finds the channel showing it`() {
        val tnt1 = IptvWallProgramme(merged(1, "TNT Sports 1"), entry("Live: Man Utd v Real Sociedad", -75, 150))
        val tnt2 = IptvWallProgramme(merged(2, "TNT Sports 2"), entry("Live: Rangers v Lyon", -75, 150))
        val later = IptvWallProgramme(merged(3, "BBC One"), entry("Match of the Day: Man Utd v Real Sociedad", 140, 60))
        val found = IptvSportMatch.programmeFor(match("Manchester United", "Real Sociedad", homeShort = "Man United"), listOf(later, tnt2, tnt1), now)
        assertEquals(tnt1, found)
        assertNull(IptvSportMatch.programmeFor(match("Chelsea", "Legia Warsaw"), listOf(tnt1, tnt2), now))
    }

    @Test
    fun `the wall puts matches first and does not repeat their programme`() {
        val channels = listOf(
            on(1, "TNT Sports 1", "UK | Sports", entry("Live: Man Utd v Real Sociedad", -75, 150)),
            on(2, "Sky Sports News", "UK | Sports", entry("Sky Sports News", -40, 60)),
            on(3, "Film4", "UK | Movies", entry("Heat", -20, 170)),
            on(4, "BBC One", "UK | Entertainment", entry("EastEnders", -10, 30)),
        )
        val bbc = channels[3].channel
        val wall = IptvWall.build(channels, listOf(match("Manchester United", "Real Sociedad", homeShort = "Man United")), listOf(bbc), now)
        assertEquals(1, wall.matchCount)
        assertEquals(2, wall.sport.size)
        assertEquals("TNT Sports 1", (wall.sport[0] as IptvWallItem.Match).programme?.channel?.primary?.name)
        assertEquals("Sky Sports News", (wall.sport[1] as IptvWallItem.Programme).programme.entry.title)
        assertEquals(listOf("Heat"), wall.films.map { it.programme.entry.title })
        assertEquals("EastEnders", wall.usuals.single().entry?.title)

        val rows = wall.rows(IptvWall.ON_NOW)
        assertEquals(listOf("Live sport", "Films", "Your channels"), rows.map { it.title })
        assertEquals(listOf(4, 3, 4), rows.map { it.columns })
        assertEquals(listOf("Films"), wall.rows(IptvWall.ON_NOW, "heat").map { it.title })
        assertEquals(1, wall.rows(IptvWall.SPORT).size)
    }

    @Test
    fun `usual channels follow the time of day and fade with age`() {
        val zone = ZoneId.of("Europe/London")
        val thursdayEvening = ZonedDateTime.of(2026, 11, 12, 19, 41, 0, 0, zone).toInstant().toEpochMilli()
        val day = 24 * 60 * minute
        val plays = listOf(
            IptvPlay("p:news", thursdayEvening - 7 * day),            // same weekday, same hour
            IptvPlay("p:news", thursdayEvening - 14 * day),
            IptvPlay("p:breakfast", thursdayEvening - day - 11 * 60 * minute),  // yesterday morning
            IptvPlay("p:breakfast", thursdayEvening - 2 * day - 11 * 60 * minute),
            IptvPlay("p:old", thursdayEvening - 300 * day),           // same hour, long ago
        )
        assertEquals(listOf("p:news", "p:breakfast", "p:old"), IptvUsuals.rank(plays, thursdayEvening, zone))
        assertTrue(IptvUsuals.rank(emptyList(), thursdayEvening, zone).isEmpty())
    }

    @Test
    fun `start over needs a provider that records the channel`() {
        val xtream = IptvPlaylist(id = "p", type = IptvPlaylistType.Xtream, name = "x", server = "http://tv.example:8080", username = "u s", password = "p")
        val m3u = xtream.copy(type = IptvPlaylistType.M3u)
        val recorded = merged(7, "BBC One", archiveDays = 2)
        val programme = entry("Paddington 2", -71, 105)
        assertTrue(IptvCatchup.available(xtream, recorded, programme, now))
        assertFalse(IptvCatchup.available(m3u, recorded, programme, now))
        assertFalse(IptvCatchup.available(xtream, merged(8, "Dave"), programme, now))
        assertFalse(IptvCatchup.available(xtream, recorded, entry("Tomorrow", 60, 30), now))
        assertFalse(IptvCatchup.available(xtream, recorded, entry("Last week", -5 * 24 * 60, 30), now))

        val start = ZonedDateTime.of(2026, 11, 12, 18, 30, 0, 0, ZoneId.of("Europe/Lisbon")).toInstant().toEpochMilli()
        val url = IptvCatchup.url(xtream, recorded.primary, IptvEpgEntry("Paddington 2", start, start + 105 * minute), "Europe/Lisbon")
        assertEquals("http://tv.example:8080/timeshift/u%20s/p/105/2026-11-12:18-30/7.ts", url)
    }
}
