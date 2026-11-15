package com.nuvio.app.features.iptv

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IptvStageTest {
    private val minute = 60_000L
    private val zone = ZoneId.of("UTC")
    private val now = ZonedDateTime.of(2026, 11, 14, 19, 58, 0, 0, zone).toInstant().toEpochMilli()

    private fun p(uid: Int, title: String, from: Int, to: Int, kind: String? = null) = IptvWallProgramme(
        IptvMergedChannel(listOf(IptvChannel(uid = uid, key = "k$uid", num = uid, name = "Channel $uid", categoryId = "c"))),
        IptvEpgEntry(title, now + from * minute, now + to * minute, kind),
    )

    @Test
    fun `departures keep to the window, the kinds and the lineup, with a few dimmed after`() {
        val list = listOf(
            p(1, "News", -5, 20),
            p(2, "Film", 2, 120, IptvProgrammeKind.FILM),
            p(3, "Match", 10, 100, IptvProgrammeKind.SPORT),
            p(4, "Quiz", 32, 60),
            p(5, "Late Film", 47, 180, IptvProgrammeKind.FILM),
        )
        val (inside, after) = IptvStageRules.departures(list, now, 15, IptvSoonKind.entries.toSet(), lineupOnly = false, lineupUids = emptySet())
        assertEquals(listOf("Film", "Match"), inside.map { it.entry.title })
        assertEquals(listOf("Quiz", "Late Film"), after.map { it.entry.title })
        val films = IptvStageRules.departures(list, now, 60, setOf(IptvSoonKind.Films), false, emptySet())
        assertEquals(listOf("Film", "Late Film"), films.first.map { it.entry.title })
        val lineup = IptvStageRules.departures(list, now, 60, IptvSoonKind.entries.toSet(), true, setOf(3))
        assertEquals(listOf("Match"), lineup.first.map { it.entry.title })
    }

    @Test
    fun `labels read as on a television`() {
        assertEquals("20:00", IptvStageRules.clock(now + 2 * minute, zone))
        assertEquals("55 min", IptvStageRules.length(55 * minute))
        assertEquals("1h 55m", IptvStageRules.length(115 * minute))
        assertEquals("Today", IptvStageRules.dayLabel(now - 60 * minute, now, zone))
        assertEquals("Yesterday", IptvStageRules.dayLabel(now - 24 * 60 * minute, now, zone))
        assertEquals("in 2 min", IptvStageRules.untilText(IptvEpgEntry("x", now + 2 * minute, now + 30 * minute), now))
        assertEquals("On now", IptvStageRules.untilText(IptvEpgEntry("x", now - minute, now + 30 * minute), now))
        assertEquals("in 2 days", IptvStageRules.untilText(IptvEpgEntry("x", now + 50 * 60 * minute, now + 51 * 60 * minute), now))
        assertEquals("tonight 20:00 on Channel 2", IptvStageRules.foundText(p(2, "x", 2, 30), now, zone))
        assertEquals("on now, Channel 2", IptvStageRules.foundText(p(2, "x", -2, 30), now, zone))
    }

    @Test
    fun `earlier groups by day, today first`() {
        val days = IptvStageRules.byDay(listOf(p(1, "A", -60, -30), p(2, "B", -26 * 60, -25 * 60), p(3, "C", -120, -90)), now, zone)
        assertEquals(listOf("Today", "Yesterday"), days.map { it.first })
        assertEquals(listOf("A", "C"), days.first().second.map { it.entry.title })
    }

    private val layout = listOf(
        listOf(IptvStageGroup("left", 4)),
        listOf(IptvStageGroup("chips", 5, columns = 5), IptvStageGroup("grid", 12, columns = 5)),
        listOf(IptvStageGroup("buttons", 2), IptvStageGroup("empty", 0)),
    )

    @Test
    fun `the highlight walks lists, rows and grids, and leaves upward to the strip`() {
        var f = IptvStageFocus(1, 1, 0)
        f = IptvStageFocusRules.move(layout, f, IptvStageMove.Down)!!
        assertEquals(IptvStageFocus(1, 1, 5), f)
        f = IptvStageFocusRules.move(layout, IptvStageFocus(1, 1, 7), IptvStageMove.Down)!!
        assertEquals(IptvStageFocus(1, 1, 11), f, "a short last row takes the last tile")
        f = IptvStageFocusRules.move(layout, IptvStageFocus(1, 1, 2), IptvStageMove.Up)!!
        assertEquals(IptvStageFocus(1, 0, 2), f, "up from the grid's first row lands on the chip above")
        assertNull(IptvStageFocusRules.move(layout, IptvStageFocus(1, 0, 2), IptvStageMove.Up), "up from the top goes to the strip")
        f = IptvStageFocusRules.move(layout, IptvStageFocus(1, 1, 5), IptvStageMove.Left)!!
        assertEquals(IptvStageFocus(0, 0, 1), f, "left off a grid row goes to the panel at the same height")
        f = IptvStageFocusRules.move(layout, IptvStageFocus(0, 0, 1), IptvStageMove.Right)!!
        assertEquals(IptvStageFocus(1, 0, 0), f, "right off a list goes to the first of the row at that height")
        f = IptvStageFocusRules.move(layout, IptvStageFocus(1, 1, 9), IptvStageMove.Right)!!
        assertEquals(IptvStageFocus(2, 0, 1), f)
        assertEquals(IptvStageFocus(2, 0, 1), IptvStageFocusRules.move(layout, IptvStageFocus(2, 0, 1), IptvStageMove.Down))
        assertEquals(IptvStageFocus(1, 0, 0), IptvStageFocusRules.first(layout, 1))
        assertEquals(IptvStageFocus(0, 0, 3), IptvStageFocusRules.clamp(layout, IptvStageFocus(0, 0, 9)))
    }

    @Test
    fun `swapping one out of the running order and the slot reasons`() {
        val guide = listOf(p(1, "Film A", 0, 100, IptvProgrammeKind.FILM), p(2, "Quiz", 0, 60))
        val first = IptvRunningOrder.build(guide, emptyList(), emptySet(), now, now + 300 * minute)
        assertEquals("Film A", first.first().entry.title)
        val swapped = IptvRunningOrder.build(guide, emptyList(), emptySet(), now, now + 300 * minute, exclude = setOf(IptvDecide.normalise("Film A")))
        assertEquals("Quiz", swapped.first().entry.title)
        assertEquals("a film", IptvRunningOrder.reason(guide[0], emptyList(), emptySet()))
        assertEquals("your usual at this hour", IptvRunningOrder.reason(guide[1], emptyList(), setOf("k2")))
    }
}
