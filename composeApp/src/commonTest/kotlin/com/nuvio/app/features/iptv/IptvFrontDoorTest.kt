package com.nuvio.app.features.iptv

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IptvFrontDoorTest {
    private val minute = 60_000L
    private val now = ZonedDateTime.of(2026, 11, 15, 19, 56, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()

    private fun channel(uid: Int) = IptvMergedChannel(listOf(IptvChannel(uid = uid, key = "k$uid", num = uid, name = "Channel $uid", categoryId = "c")))
    private fun entry(title: String, from: Int, to: Int, kind: String? = null) = IptvEpgEntry(title, now + from * minute, now + to * minute, kind)
    private fun on(uid: Int, vararg entries: IptvEpgEntry) = IptvChannelNow(channel(uid), entries.toList())

    private val guide = listOf(
        on(1, entry("Countryfile", -56, 4), entry("Antiques Roadshow", 4, 64)),
        on(2, entry("Gardeners' World", -37, 23)),
        on(3, entry("Pacific Rim", -10, 120, IptvProgrammeKind.FILM)),
        on(4, entry("Arsenal v Spurs", -60, 60, IptvProgrammeKind.SPORT)),
        on(5, entry("Old Film", -50, 70, IptvProgrammeKind.FILM)),
    )

    @Test
    fun `the first rule that finds something wins and the rest fill in behind`() {
        val list = IptvDoorRules.candidates(guide, follows = listOf("Arsenal"), followedLive = emptyList(), lineupUids = setOf(1, 2), usuals = listOf(channel(2)), now = now)
        assertEquals(
            listOf("Arsenal v Spurs", "Antiques Roadshow", "Gardeners' World", "Pacific Rim"),
            list.map { it.programme.entry.title },
        )
        assertEquals(
            listOf(IptvDoorReason.Followed, IptvDoorReason.StartsSoon, IptvDoorReason.Usual, IptvDoorReason.Film),
            list.map { it.reason },
        )
    }

    @Test
    fun `starting soon keeps to five minutes and to the viewer's channels`() {
        val late = listOf(on(1, entry("Antiques Roadshow", 6, 66)), on(9, entry("Quiz", 2, 30)))
        val list = IptvDoorRules.candidates(late, emptyList(), emptyList(), lineupUids = setOf(1), usuals = emptyList(), now = now)
        assertTrue(list.none { it.reason == IptvDoorReason.StartsSoon })
    }

    @Test
    fun `a short programme on a usual channel and a film well under way are left out`() {
        val list = IptvDoorRules.candidates(
            listOf(on(2, entry("Bulletin", -5, 10)), on(5, entry("Old Film", -50, 70, IptvProgrammeKind.FILM))),
            emptyList(), emptyList(), emptySet(), listOf(channel(2)), now,
        )
        assertTrue(list.isEmpty())
    }

    @Test
    fun `a followed match tied to a channel counts once`() {
        val match = IptvWallProgramme(channel(4), entry("Arsenal v Spurs", -60, 60, IptvProgrammeKind.SPORT))
        val list = IptvDoorRules.candidates(guide, listOf("Arsenal"), listOf(match), emptySet(), emptyList(), now)
        assertEquals(1, list.count { it.programme.entry.title == "Arsenal v Spurs" })
        assertEquals(IptvDoorReason.Followed, list.first().reason)
    }

    @Test
    fun `the pick holds for a quarter of an hour unless skipped or beaten by something followed`() {
        val usual = IptvDoorPick(IptvWallProgramme(channel(2), entry("Gardeners' World", -37, 23)), IptvDoorReason.Usual)
        val film = IptvDoorPick(IptvWallProgramme(channel(3), entry("Pacific Rim", -10, 120, IptvProgrammeKind.FILM)), IptvDoorReason.Film)
        val followed = IptvDoorPick(IptvWallProgramme(channel(4), entry("Arsenal v Spurs", -60, 60)), IptvDoorReason.Followed)
        assertEquals(usual, IptvDoorRules.choose(listOf(film, usual), emptySet(), usual, now - 5 * minute, now))
        assertEquals(film, IptvDoorRules.choose(listOf(film, usual), emptySet(), usual, now - 16 * minute, now))
        assertEquals(film, IptvDoorRules.choose(listOf(usual, film), setOf(usual.key), usual, now - minute, now))
        assertEquals(followed, IptvDoorRules.choose(listOf(followed, usual), emptySet(), usual, now - minute, now))
        assertNull(IptvDoorRules.choose(listOf(usual), setOf(usual.key), null, 0L, now))
    }

    @Test
    fun `the why line says why`() {
        val soon = IptvDoorPick(IptvWallProgramme(channel(1), entry("Antiques Roadshow", 4, 64)), IptvDoorReason.StartsSoon)
        assertTrue(IptvDoorRules.why(soon, now, null, null).startsWith("Starts in 4 min."))
        val usual = IptvDoorPick(IptvWallProgramme(channel(2), entry("Gardeners' World", -37, 23)), IptvDoorReason.Usual)
        assertEquals("Your usual at this hour, 2 on Your 40.", IptvDoorRules.why(usual, now, 2, null))
        val film = IptvDoorPick(IptvWallProgramme(channel(3), entry("Pacific Rim", -10, 120)), IptvDoorReason.Film)
        assertEquals("A film that began 10 min ago.", IptvDoorRules.why(film, now, null, null))
    }

    @Test
    fun `next up is the viewer's channels only, soonest first, never the pick`() {
        val a = IptvWallProgramme(channel(1), entry("Antiques Roadshow", 4, 64))
        val b = IptvWallProgramme(channel(2), entry("Taskmaster", 24, 84))
        val c = IptvWallProgramme(channel(9), entry("Elsewhere", 10, 40))
        val list = IptvDoorRules.nextUp(listOf(b, c, a), setOf(1, 2), now, exclude = IptvDoorRules.keyOf(a))
        assertEquals(listOf("Taskmaster"), list.map { it.entry.title })
    }

    @Test
    fun `the triage is offered once per playlist per session`() {
        assertTrue(IptvFirstRun.shouldAsk("door-test"))
        assertFalse(IptvFirstRun.shouldAsk("door-test"))
    }

    @Test
    fun `the programme menu offers follow or stop following, and a reminder only before it starts`() {
        val on = IptvWallProgramme(channel(3), entry("Friends", -5, 25))
        val later = IptvWallProgramme(channel(3), entry("Friends", 30, 60))
        assertEquals(
            listOf(IptvProgrammeAction.Watch, IptvProgrammeAction.Follow, IptvProgrammeAction.Close),
            IptvProgrammeMenu.actions(on, now, followed = false),
        )
        assertEquals(
            listOf(IptvProgrammeAction.Watch, IptvProgrammeAction.Unfollow, IptvProgrammeAction.Remind, IptvProgrammeAction.Close),
            IptvProgrammeMenu.actions(later, now, followed = true),
        )
        assertEquals("Follow Friends", IptvProgrammeMenu.label(IptvProgrammeAction.Follow, on, now) { "x" })
        assertEquals(
            listOf(IptvProgrammeAction.Watch, IptvProgrammeAction.Follow, IptvProgrammeAction.Unstar, IptvProgrammeAction.Close),
            IptvProgrammeMenu.actions(on, now, followed = false, starred = true),
        )
        assertEquals("Tune in at 20:26", IptvProgrammeMenu.label(IptvProgrammeAction.Watch, later, now) { "20:26" })
    }

    @Test
    fun `what gets followed is the title without live marks or episode numbers`() {
        assertEquals("WSL : Arsenal v London City Lionesses", IptvProgrammeMenu.followName("WSL : Arsenal v London City Lionesses \u1D38\u2071\u1D5B\u1D49"))
        assertEquals("Hart Broken", IptvProgrammeMenu.followName("Hart Broken - S02E01"))
        assertEquals("Friends", IptvProgrammeMenu.followName("Friends"))
    }

    @Test
    fun `the home country lifts its channels and sinks other countries' news`() {
        assertEquals(IptvRegions.HOME_BONUS, IptvRegions.homeBonus("GB", "GB", null))
        assertEquals(-IptvRegions.FOREIGN_NEWS_PENALTY, IptvRegions.homeBonus("US", "GB", IptvProgrammeKind.NEWS))
        assertEquals(0, IptvRegions.homeBonus("US", "GB", IptvProgrammeKind.FILM))
        assertEquals(0, IptvRegions.homeBonus(null, "GB", IptvProgrammeKind.NEWS))
        assertEquals(0, IptvRegions.homeBonus("US", null, IptvProgrammeKind.NEWS))
        assertEquals("GB", IptvRegions.effectiveHome(null, setOf("GB")))
        assertNull(IptvRegions.effectiveHome(null, setOf("GB", "US")))
        assertEquals("IE", IptvRegions.effectiveHome("IE", setOf("GB")))
        assertEquals("United Kingdom", IptvRegions.countryName("GB"))
    }

    @Test
    fun `earlier today keeps two of each title at most`() {
        val past = (1..6).map { IptvWallProgramme(channel(3), entry("Friends", -30 * it - 30, -30 * it)) } +
            IptvWallProgramme(channel(2), entry("Countryfile", -120, -60))
        val list = IptvDecide.earlier(past, emptyList(), now, limit = 10)
        assertEquals(2, list.count { it.entry.title == "Friends" })
        assertTrue(list.any { it.entry.title == "Countryfile" })
    }

    @Test
    fun `one showing on two channels is one row on the minutes board`() {
        val board = IptvDecide.minutes(
            listOf(on(1, entry("5 Minutes", -1, 7)), on(2, entry("5 Minutes", -1, 7)), on(4, entry("Quiz", -10, 15))),
            emptyList(), now, 60,
        )
        assertEquals(listOf("5 Minutes", "Quiz"), board.alreadyOn.map { it.programme.entry.title })
    }

    @Test
    fun `follows typed in lower case get capitals`() {
        assertEquals("Man Utd", IptvDecide.displayFollow("man utd"))
        assertEquals("Line Of Duty", IptvDecide.displayFollow("line of duty"))
        assertEquals("BBC News", IptvDecide.displayFollow("BBC News"))
    }
}
