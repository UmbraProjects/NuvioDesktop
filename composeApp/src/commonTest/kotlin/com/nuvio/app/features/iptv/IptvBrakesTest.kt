package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IptvBrakesTest {
    private val min = 60_000L

    @Test
    fun fiveChangesInTwoMinutesIsOverBudget() {
        var times = emptyList<Long>()
        for (i in 0 until 4) times = IptvBrakeRules.zapped(times, 1_000_000L + i * 20_000L)
        assertFalse(IptvBrakeRules.overBudget(times, null, 5))
        times = IptvBrakeRules.zapped(times, 1_000_000L + 4 * 20_000L)
        assertTrue(IptvBrakeRules.overBudget(times, null, 5))
    }

    @Test
    fun slowChangesNeverAddUp() {
        var times = emptyList<Long>()
        for (i in 0 until 10) times = IptvBrakeRules.zapped(times, i * 3 * min)
        assertEquals(1, times.size)
        assertFalse(IptvBrakeRules.overBudget(times, null, 5))
    }

    @Test
    fun offeredOncePerNight() {
        val times = (0 until 5).map { it * 1_000L }
        assertTrue(IptvBrakeRules.overBudget(times, 4L, 5))
        assertFalse(IptvBrakeRules.overBudget(times, 5L, 5))
    }

    @Test
    fun endCardNeedsStayingAndTheEndNearby() {
        val end = 100 * min
        assertTrue(IptvBrakeRules.endCardDue(end, end - 20_000L, 30 * min, null))
        assertFalse(IptvBrakeRules.endCardDue(end, end - 20_000L, 10 * min, null))   // only flicked past
        assertFalse(IptvBrakeRules.endCardDue(end, end - 5 * min, 30 * min, null))   // too early
        assertFalse(IptvBrakeRules.endCardDue(end, end + 5 * min, 30 * min, null))   // too late
        assertFalse(IptvBrakeRules.endCardDue(end, end - 20_000L, 30 * min, end))    // already shown
    }

    @Test
    fun tallyAddsAndPrunes() {
        var tally = emptyMap<String, IptvNight>()
        tally = IptvBrakeRules.add(tally, 1000, { it.copy(minutes = it.minutes + 30) })
        tally = IptvBrakeRules.add(tally, 1000, { it.copy(changes = it.changes + 3) })
        assertEquals(IptvNight(minutes = 30, changes = 3), tally["1000"])
        tally = IptvBrakeRules.add(tally, 1000 + 200, { it.copy(minutes = 5) })
        assertEquals(setOf("1200"), tally.keys)   // 1000 is more than 120 nights old
    }

    @Test
    fun weekSumsSevenNights() {
        val tally = mapOf(
            "100" to IptvNight(60, 4, 1, 0),
            "97" to IptvNight(90, 10, 2, 1),
            "93" to IptvNight(500, 99, 9, 9),   // eight nights back: not in the week
        )
        assertEquals(IptvNight(150, 14, 3, 1), IptvBrakeRules.week(tally, 100))
    }

    @Test
    fun linesRead() {
        assertEquals("45 min", IptvBrakeRules.hoursText(45))
        assertEquals("2 h", IptvBrakeRules.hoursText(120))
        assertEquals("1 h 5 min", IptvBrakeRules.hoursText(65))
        assertEquals("1 h 5 min watching, 14 channel changes, 3 programmes seen to the end", IptvBrakeRules.weekLine(IptvNight(65, 14, 3, 0)))
    }

    @Test
    fun `the zap card offers a pick with the stage on, and answers by label`() {
        val three = IptvBrakeRules.zapOptions(stageOn = true)
        assertEquals(listOf("Keep flicking", "Pick something for me", "Turn Live TV off"), three)
        assertEquals(2, IptvBrakeRules.zapOptions(stageOn = false).size)
        assertEquals(IptvBrakeAnswer.KeepGoing, IptvBrakeRules.answerOf(three, 0))
        assertEquals(IptvBrakeAnswer.PickForMe, IptvBrakeRules.answerOf(three, 1))
        assertEquals(IptvBrakeAnswer.TurnOff, IptvBrakeRules.answerOf(three, 2))
        assertEquals(IptvBrakeAnswer.Dismissed, IptvBrakeRules.answerOf(three, -1))
        val end = listOf(IptvBrakeRules.STOP_HERE, IptvBrakeRules.ONE_MORE)
        assertEquals(IptvBrakeAnswer.TurnOff, IptvBrakeRules.answerOf(end, 0))
        assertEquals(IptvBrakeAnswer.KeepGoing, IptvBrakeRules.answerOf(end, 1))
    }

    @Test
    fun `only a chosen programme gets the end card`() {
        val min = 60_000L
        val start = 1_000_000L
        assertTrue(IptvBrakeRules.chosen(start - 2 * min, start, picked = false), "tuned in before it began")
        assertTrue(IptvBrakeRules.chosen(start + 9 * min, start, picked = false), "within ten minutes")
        assertFalse(IptvBrakeRules.chosen(start + 25 * min, start, picked = false), "landed on it while flicking")
        assertTrue(IptvBrakeRules.chosen(start + 25 * min, start, picked = true), "picked on a stage")
    }

    @Test
    fun `your week is seven nights, oldest first, with labels`() {
        val tonight = 20_000L
        val tally = mapOf(IptvBrakeRules.nightKey(tonight) to IptvNight(minutes = 90, changes = 30), IptvBrakeRules.nightKey(tonight - 6) to IptvNight(minutes = 30))
        val nights = IptvBrakeRules.nights(tally, tonight)
        assertEquals(7, nights.size)
        assertEquals(tonight - 6, nights.first().first)
        assertEquals(30, nights.first().second.minutes)
        assertEquals(90, nights.last().second.minutes)
        assertEquals(0, nights[3].second.minutes)
        assertEquals("Tonight", IptvBrakeRules.nightLabel(tonight, tonight))
        assertEquals("Last night", IptvBrakeRules.nightLabel(tonight - 1, tonight))
        assertEquals(3, IptvBrakeRules.nightLabel(tonight - 3, tonight).length)
        assertEquals(15, IptvBrakeRules.changesPerHour(IptvBrakeRules.week(tally, tonight)))
        assertEquals(null, IptvBrakeRules.changesPerHour(IptvNight(minutes = 30, changes = 9)))
    }
}
