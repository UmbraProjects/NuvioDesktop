package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IptvSportArtTest {
    private fun parse(title: String, kind: String? = null) = assertNotNull(IptvSportTitle.parse(title, kind), title)

    @Test
    fun `competition, round, the two sides and the live mark come apart`() {
        val rugby = parse("European Rugby Champions Cup : Semi-Final: Saracens v Wasps ᴸⁱᵛᵉ")
        assertEquals("European Rugby Champions Cup", rugby.competition)
        assertEquals("Semi-Final", rugby.round)
        assertEquals(listOf("Saracens", "Wasps"), rugby.sides)
        assertEquals("Saracens v Wasps", rugby.headline)
        assertEquals("Rugby", rugby.sport)
        assertTrue(rugby.live)

        val cricket = parse("Cricket Highlights : WBBL 2026: Scorchers vs Strikers - 22nd Match")
        assertEquals(listOf("Scorchers", "Strikers"), cricket.sides)
        assertEquals("WBBL 2026 · 22nd Match", cricket.round)
        assertEquals("Cricket", cricket.sport)

        val bundesliga = parse("Bundesliga Soccer : 1. FSV Mainz 05 vs. Bayern Munich")
        assertEquals(listOf("1. FSV Mainz 05", "Bayern Munich"), bundesliga.sides)
        assertEquals("Soccer", bundesliga.sport)

        val wsl = parse("FA Women's Super League : Manchester United vs. Crystal Palace")
        assertTrue(wsl.women)
        assertEquals("Soccer", wsl.sport)

        assertEquals("American Football", parse("Grey Cup Revisited : 2025: Saskatchewan Roughriders vs. Montreal Alouettes").sport)
    }

    @Test
    fun `not every title is a fixture`() {
        assertNull(IptvSportTitle.parse("Live 2 Hunt With Cody & Kelsy : Never Too Late", IptvProgrammeKind.SPORT))
        assertNull(IptvSportTitle.parse("Kramer vs Kramer", IptvProgrammeKind.FILM))
        assertEquals("Cheers", IptvSportTitle.clean("Cheers ᴸⁱᵛᵉ"))
    }

    @Test
    fun `a fight night's name is not part of the fighter's`() {
        assertEquals("Zuffa Boxing 13" to "Adames", IptvSportTitle.splitFighter("Zuffa Boxing 13 Adames"))
        assertEquals(null to "Warburton", IptvSportTitle.splitFighter("Warburton"))
        assertEquals(IptvSportInfo.SPORT_BOXING, parse("Boxing: Carlos Adames v Warburton").sport)
        assertTrue(parse("UFC 320: Ankalaev v Pereira").people)
    }

    @Test
    fun `the live-marked copy and the plain copy of a match are one tile, with the other channel named`() {
        val minute = 60_000L
        val now = 100_000 * minute
        fun channel(uid: Int, name: String, title: String) = IptvChannelNow(
            IptvMergedChannel(listOf(IptvChannel(uid = uid, key = "k$uid", num = uid, name = name, categoryId = "c"))),
            listOf(IptvEpgEntry(title, now - 10 * minute, now + 60 * minute, IptvProgrammeKind.SPORT)),
            "Sport",
        )
        val channels = listOf(
            channel(1, "Premier Sports 2", "Abu Dhabi T10 League : Arabian Aces v Emirates Eagles"),
            channel(2, "Premier Sports 2 HD", "Abu Dhabi T10 League : Arabian Aces v Emirates Eagles ᴸⁱᵛᵉ"),
            channel(3, "One Soccer", "Bundesliga Soccer : 1. FSV Mainz 05 vs. Bayern Munich"),
        )
        val sport = IptvOnNow.sport(channels, now)
        assertEquals(2, sport.size)
        val t10 = sport.first { it.entry.title.startsWith("Abu Dhabi") }
        assertTrue(IptvSportTitle.markedLive(t10.entry.title), "the copy marked live is the one kept")
        assertEquals(listOf("Premier Sports 2"), IptvOnNow.sportCopies(channels, now)[t10])
        assertEquals(" · also on Premier Sports 2", IptvProgrammeLine.alsoOn(listOf("Premier Sports 2")))
    }
}
