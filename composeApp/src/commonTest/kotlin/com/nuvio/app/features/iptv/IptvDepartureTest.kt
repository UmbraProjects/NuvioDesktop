package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IptvDepartureTest {
    private val minute = 60_000L
    private val now = 1_000_000L

    private fun channel(uid: Int, name: String) = IptvMergedChannel(listOf(IptvChannel(uid = uid, key = "k$uid", num = uid, name = name, categoryId = "c")))

    /** A channel whose guide has [current] on now (when given) and one programme per start offset in minutes. */
    private fun on(uid: Int, vararg startsInMinutes: Int, current: Boolean = false): IptvChannelNow {
        val entries = buildList {
            if (current) add(IptvEpgEntry("Now $uid", now - 30 * minute, now + 5 * minute))
            startsInMinutes.forEach { add(IptvEpgEntry("Show $uid at $it", now + it * minute, now + it * minute + 30 * minute)) }
        }
        return IptvChannelNow(channel(uid, "Channel $uid"), entries)
    }

    @Test
    fun `the board has three buckets by how soon, and only things that have not started`() {
        val board = IptvDeparture.buckets(
            listOf(on(1, 5), on(2, 15), on(3, 16), on(4, 30), on(5, 31), on(6, 60), on(7, 61), on(8, current = true)),
            now,
        )
        assertEquals(listOf("Within 15 minutes", "15 to 30 minutes", "30 to 60 minutes"), board.map { it.label })
        assertEquals(listOf(1, 2), board[0].programmes.map { it.channel.primary.uid }, "5 and exactly 15 minutes are in the first")
        assertEquals(listOf(3, 4), board[1].programmes.map { it.channel.primary.uid })
        assertEquals(listOf(5, 6), board[2].programmes.map { it.channel.primary.uid }, "61 minutes and a programme already on are left out")
    }

    @Test
    fun `a channel is listed once, with its next programme, and an empty bucket is left out`() {
        val board = IptvDeparture.buckets(listOf(on(1, 10, 40, current = true)), now)
        assertEquals(1, board.size)
        assertEquals("Show 1 at 10", board[0].programmes.single().entry.title)
        assertEquals(emptyList(), IptvDeparture.buckets(emptyList(), now))
    }

    @Test
    fun `the board can be limited to some channels and a bucket holds at most six`() {
        val many = (1..9).map { on(it, 5) }
        assertEquals(6, IptvDeparture.buckets(many, now).single().programmes.size)
        val only = IptvDeparture.buckets(many, now, only = { it.primary.uid in setOf(2, 3) })
        assertEquals(listOf(2, 3), only.single().programmes.map { it.channel.primary.uid })
    }

    @Test
    fun `a countdown reads in minutes, then hours, and now`() {
        assertEquals("in 12 min", IptvDeparture.countdown(12 * minute))
        assertEquals("in 1 min", IptvDeparture.countdown(minute - 1))
        assertEquals("now", IptvDeparture.countdown(0))
        assertEquals("now", IptvDeparture.countdown(-5 * minute))
        assertEquals("in 1 h 05 min", IptvDeparture.countdown(65 * minute))
    }

    private fun reminder(start: Long, auto: Boolean, key: String = "k1") =
        IptvReminder("p", key, "BBC One", "News", start, start + 30 * minute, auto)

    @Test
    fun `a plain reminder fires two minutes before and a switching one at the start`() {
        val plain = reminder(now + 10 * minute, auto = false)
        val auto = reminder(now + 10 * minute, auto = true)
        assertEquals(now + 8 * minute, IptvReminderRules.fireAt(plain))
        assertEquals(now + 10 * minute, IptvReminderRules.fireAt(auto))
        assertEquals(emptyList(), IptvReminderRules.due(listOf(plain, auto), now + 7 * minute))
        assertEquals(listOf(plain), IptvReminderRules.due(listOf(plain, auto), now + 9 * minute))
        assertEquals(listOf(plain, auto), IptvReminderRules.due(listOf(plain, auto), now + 10 * minute))
    }

    @Test
    fun `a reminder that missed its time by too long is stale, not due`() {
        val old = reminder(now, auto = true)
        assertEquals(listOf(old), IptvReminderRules.due(listOf(old), now + 9 * minute))
        assertEquals(emptyList(), IptvReminderRules.due(listOf(old), now + 11 * minute))
        assertEquals(listOf(old), IptvReminderRules.stale(listOf(old), now + 11 * minute))
    }

    @Test
    fun `setting a reminder twice keeps one, earliest first, and it is found by any copy`() {
        val later = reminder(now + 20 * minute, auto = false)
        val sooner = reminder(now + 5 * minute, auto = false)
        var list = IptvReminderRules.add(emptyList(), later)
        list = IptvReminderRules.add(list, sooner)
        list = IptvReminderRules.add(list, sooner.copy(autoSwitch = true))
        assertEquals(listOf(sooner.copy(autoSwitch = true), later), list)
        val twin = IptvMergedChannel(listOf(IptvChannel(9, "other", 9, "BBC One HD", categoryId = "c"), IptvChannel(1, "k1", 1, "BBC One", categoryId = "c")))
        assertNotNull(IptvReminderRules.find(list, "p", twin, now + 5 * minute))
        assertNull(IptvReminderRules.find(list, "p", twin, now + 6 * minute))
        assertNull(IptvReminderRules.find(list, "q", twin, now + 5 * minute))
    }

    @Test
    fun `the card says how long is left`() {
        assertEquals("BBC One · starts in 2 min", IptvReminderRules.cardLine(reminder(now + 2 * minute, false), now))
        assertEquals("BBC One · starting now", IptvReminderRules.cardLine(reminder(now, true), now))
    }

    @Test
    fun `the reminder card asks, with At the start highlighted, and Back is Not now`() {
        val options = IptvReminderRules.CARD_OPTIONS
        assertEquals(listOf("Switch now", "At the start", "Not now"), options)
        assertEquals("At the start", options[IptvReminderRules.CARD_FOCUS])
        assertEquals(IptvReminderAnswer.Now, IptvReminderRules.answerOf(options, 0))
        assertEquals(IptvReminderAnswer.AtStart, IptvReminderRules.answerOf(options, 1))
        assertEquals(IptvReminderAnswer.Dismiss, IptvReminderRules.answerOf(options, 2))
        assertEquals(IptvReminderAnswer.Dismiss, IptvReminderRules.answerOf(options, -1))
        val plain = IptvReminder("p", "k", "BBC One", "Doctor Who", 1_000_000L, 4_000_000L, autoSwitch = false)
        val later = IptvReminderRules.atStart(plain)
        assertEquals(plain.id, later.id)
        assertEquals(plain.startMs, IptvReminderRules.fireAt(later), "a switching reminder fires at the start, not two minutes before")
    }
}
