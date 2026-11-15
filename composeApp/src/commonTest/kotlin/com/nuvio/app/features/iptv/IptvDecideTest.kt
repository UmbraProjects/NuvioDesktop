package com.nuvio.app.features.iptv

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IptvDecideTest {
    private val minute = 60_000L
    private val now = 10_000_000L

    private fun merged(uid: Int, name: String = "Channel $uid") = IptvMergedChannel(listOf(IptvChannel(uid = uid, key = "k$uid", num = uid, name = name, categoryId = "c")))
    private fun entry(title: String, from: Int, to: Int, kind: String? = null) = IptvEpgEntry(title, now + from * minute, now + to * minute, kind)
    private fun on(uid: Int, vararg entries: IptvEpgEntry) = IptvChannelNow(merged(uid), entries.toList())
    private fun uids(list: List<IptvWallProgramme>) = list.map { it.channel.primary.uid }

    @Test
    fun `three things are a usual, a start soon and a film, never on one channel`() {
        val channels = listOf(
            on(1, entry("Usual News", -10, 20)),
            on(2, entry("Earlier", -30, 5), entry("Quiz", 10, 40)),
            on(3, entry("A Film", -5, 90, IptvProgrammeKind.FILM)),
            on(4, entry("Another Film", 5, 100, IptvProgrammeKind.FILM)),
        )
        val three = IptvDecide.three(channels, listOf(merged(1)), lineup = null, now = now, rotation = 0)
        assertEquals(1, three.safe?.channel?.primary?.uid)
        assertEquals(4, three.soon?.channel?.primary?.uid, "the soonest start, even a film")
        assertEquals(3, three.wildcard?.channel?.primary?.uid, "the film left over")
    }

    @Test
    fun `the wildcard keeps off the lineup, and a reshuffle steps every card on`() {
        val channels = listOf(
            on(1, entry("Usual", -10, 20)),
            on(2, entry("Film In Lineup", -5, 60, IptvProgrammeKind.FILM)),
            on(3, entry("Film Outside", -5, 60, IptvProgrammeKind.FILM)),
            on(5, entry("Soon A", 5, 40)),
            on(6, entry("Soon B", 8, 40)),
        )
        val inLineup = { channel: IptvMergedChannel -> channel.primary.uid in setOf(1, 2, 5, 6) }
        val first = IptvDecide.three(channels, listOf(merged(1)), inLineup, now, rotation = 0)
        assertEquals(3, first.wildcard?.channel?.primary?.uid)
        assertEquals(5, first.soon?.channel?.primary?.uid)
        val again = IptvDecide.three(channels, listOf(merged(1)), inLineup, now, rotation = 1)
        assertEquals(6, again.soon?.channel?.primary?.uid)
    }

    @Test
    fun `a card with nothing to offer is empty, and one that runs past bedtime is left out`() {
        val none = IptvDecide.three(emptyList(), emptyList(), null, now, 0)
        assertNull(none.safe); assertNull(none.soon); assertNull(none.wildcard)
        val late = IptvDecide.three(listOf(on(1, entry("Long", -5, 200))), listOf(merged(1)), null, now, 0, endsBy = now + 60 * minute)
        assertNull(late.safe)
    }

    @Test
    fun `minutes offers what ends in time, and marks a near miss`() {
        val channels = listOf(
            on(1, entry("Ends soon", -20, 10)),
            on(2, entry("Too long", -5, 90)),
            on(3, entry("Just over", -5, 30)),
            on(4, entry("Almost done", -20, 3)),
            on(5, entry("Later", 10, 24)),
        )
        val past = listOf(IptvWallProgramme(merged(6), IptvEpgEntry("Old Short", now - 60 * minute, now - 40 * minute)), IptvWallProgramme(merged(7), IptvEpgEntry("Old Long", now - 200 * minute, now - 20 * minute)))
        val board = IptvDecide.minutes(channels, past, now, 25)
        assertEquals(listOf(1, 3), uids(board.alreadyOn.map { it.programme }), "fits first, the near miss after; too long and nearly over are out")
        assertEquals(listOf(0, 5), board.alreadyOn.map { it.overMin })
        assertEquals(listOf(5), uids(board.starting.map { it.programme }))
        assertEquals(listOf(6), uids(board.catchUp.map { it.programme }))
    }

    @Test
    fun `a follow matches by title and a night is counted from five in the morning`() {
        assertTrue(IptvDecide.matchesFollow("Match of the Day", "Match Of The Day: Live"))
        assertTrue(IptvDecide.matchesFollow("news", "Sky News Live"), "four letters or more match inside a title")
        assertTrue(IptvDecide.matchesFollow("new", "New") && !IptvDecide.matchesFollow("new", "Sky News Live"), "shorter words match whole titles only")
        assertFalse(IptvDecide.matchesFollow("", "Anything"))
        val zone = ZoneId.of("UTC")
        val evening = java.time.ZonedDateTime.of(2026, 11, 13, 22, 0, 0, 0, zone).toInstant().toEpochMilli()
        val small = java.time.ZonedDateTime.of(2026, 11, 14, 1, 0, 0, 0, zone).toInstant().toEpochMilli()
        val morning = java.time.ZonedDateTime.of(2026, 11, 14, 6, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(IptvDecide.nightOf(evening, zone), IptvDecide.nightOf(small, zone))
        assertTrue(IptvDecide.nightOf(morning, zone) > IptvDecide.nightOf(small, zone))
    }

    @Test
    fun `one reshuffle a night`() {
        val night = 100L
        assertTrue(IptvDecide.canReshuffle(IptvShuffle(), night))
        assertEquals(0, IptvDecide.rotationFor(IptvShuffle(), night))
        assertFalse(IptvDecide.canReshuffle(IptvShuffle(night, 1), night))
        assertEquals(1, IptvDecide.rotationFor(IptvShuffle(night, 1), night))
        assertTrue(IptvDecide.canReshuffle(IptvShuffle(night, 1), night + 1), "a new night allows one again")
        assertEquals(0, IptvDecide.rotationFor(IptvShuffle(night, 1), night + 1))
    }

    @Test
    fun `follows find a lineup channel first and say when there is nothing`() {
        val ahead = listOf(
            IptvWallProgramme(merged(1), entry("Bond: Skyfall", 30, 150)),
            IptvWallProgramme(merged(2), entry("Bond: Skyfall", 10, 130)),
            IptvWallProgramme(merged(3), entry("Quiz", 5, 35)),
        )
        val results = IptvDecide.follows(listOf("Bond: Skyfall", "Gardening"), ahead, lineup = { it.primary.uid == 1 })
        assertEquals(listOf(1, 2), uids(results[0].matches).sortedBy { it }, "both channels, the lineup one counted first")
        assertEquals(2, results[0].matches.size)
        assertEquals(emptyList(), results[1].matches)
        val one = IptvDecide.follows(listOf("Bond: Skyfall"), ahead, lineup = { it.primary.uid == 1 }, limit = 1)
        assertEquals(1, one[0].matches.single().channel.primary.uid)
    }

    @Test
    fun `earlier today puts followed first, then films, drops the dull short ones`() {
        val ch = merged(1)
        val past = listOf(
            IptvWallProgramme(ch, IptvEpgEntry("Quiz", now - 100 * minute, now - 70 * minute)),
            IptvWallProgramme(ch, IptvEpgEntry("A Film", now - 200 * minute, now - 110 * minute, IptvProgrammeKind.FILM)),
            IptvWallProgramme(ch, IptvEpgEntry("Trailer", now - 70 * minute, now - 65 * minute)),
            IptvWallProgramme(ch, IptvEpgEntry("Gardening Live", now - 60 * minute, now - 30 * minute)),
            IptvWallProgramme(ch, IptvEpgEntry("Still On", now - 10 * minute, now + 10 * minute)),
        )
        val out = IptvDecide.earlier(past, listOf("gardening live"), now)
        assertEquals(listOf("Gardening Live", "A Film", "Quiz"), out.map { it.entry.title })
    }

    @Test
    fun `the sections are the same size in every row`() {
        val three = IptvThree(null, null, null)
        val sections = IptvDecide.sections(three, IptvMinutesBoard(emptyList(), emptyList(), emptyList()), 25, true, emptyList(), listOf(IptvFollowResult("Bond", emptyList())))
        assertEquals(3, sections.getValue(IptvDecide.THREE).first().items.size, "three cards, never fewer or more")
        assertEquals(IptvDecide.MINUTE_CHOICES.size, sections.getValue(IptvDecide.MINUTES).first().items.size)
        assertEquals(listOf(true), sections.getValue(IptvDecide.MINUTES).first().items.map { (it as IptvWallItem.Action).selected }.filter { it })
        assertNotNull(sections.getValue(IptvDecide.FOLLOWING).single().items.single() as? IptvWallItem.Note)
        assertEquals(emptyList(), sections.getValue(IptvDecide.EARLIER))
    }
}
