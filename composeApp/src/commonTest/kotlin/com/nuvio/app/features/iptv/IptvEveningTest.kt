package com.nuvio.app.features.iptv

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IptvEveningTest {
    private val minute = 60_000L
    private val now = 10_000_000L

    private fun merged(uid: Int, name: String = "Channel $uid") = IptvMergedChannel(listOf(IptvChannel(uid = uid, key = "k$uid", num = uid, name = name, categoryId = "c")))
    private fun p(uid: Int, title: String, from: Int, to: Int, kind: String? = null, name: String = "Channel $uid") =
        IptvWallProgramme(merged(uid, name), IptvEpgEntry(title, now + from * minute, now + to * minute, kind))

    @Test
    fun `bedtime is today's, or tomorrow's when it is long past`() {
        val zone = ZoneId.of("UTC")
        val nine = ZonedDateTime.of(2026, 11, 13, 21, 10, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(nine + 140 * minute, IptvRunningOrder.bedtimeMs(nine, 23 * 60 + 30, zone))
        assertEquals(nine + 200 * minute, IptvRunningOrder.bedtimeMs(nine, 30, zone))
    }

    @Test
    fun `the running order chains slots, follows first, and stops before bedtime`() {
        val guide = listOf(
            p(1, "News", -10, 30),
            p(2, "Film A", 0, 100, IptvProgrammeKind.FILM),
            p(3, "Quiz", 30, 60),
            p(4, "Followed Show", 100, 160),
            p(5, "Late Film", 170, 290, IptvProgrammeKind.FILM),
            p(6, "Too Short", 100, 110),
        )
        val order = IptvRunningOrder.build(guide, listOf("Followed Show"), emptySet(), now, now + 200 * minute)
        assertEquals(listOf("Film A", "Followed Show"), order.map { it.entry.title }, "Late Film would pass bedtime, so it is left out")
        // each one starts after the last ends
        assertTrue(order[1].entry.startMs >= order[0].entry.endMs)
    }

    @Test
    fun `a programme nearly over is not the first slot`() {
        val guide = listOf(p(1, "Nearly Done", -50, 10), p(2, "Fresh", 5, 60))
        val order = IptvRunningOrder.build(guide, emptyList(), emptySet(), now, now + 120 * minute)
        assertEquals(listOf("Fresh"), order.map { it.entry.title })
    }

    @Test
    fun `swapping never empties the order - wider channels, then a gap jump, then the oldest swap comes back`() {
        val own = listOf(p(1, "Mine", 0, 60))
        val wide = listOf(p(9, "Kept Elsewhere", 0, 60))
        val bed = now + 300 * minute
        // Own channel swapped out: the triage's other channels fill the slot.
        val one = setOf(IptvDecide.normalise("Mine"))
        assertEquals("Kept Elsewhere", IptvRunningOrder.build(own, emptyList(), emptySet(), now, bed, one, wider = { wide }).first().entry.title)
        // Both swapped out: the one swapped longest ago comes back rather than an empty list.
        val both = linkedSetOf(IptvDecide.normalise("Mine"), IptvDecide.normalise("Kept Elsewhere"))
        assertEquals("Mine", IptvRunningOrder.build(own, emptyList(), emptySet(), now, bed, both, wider = { wide }).first().entry.title)
        // A gap in the guide does not end the evening: it jumps to the next thing that starts.
        val gappy = listOf(p(1, "Early", 0, 60), p(2, "After A Gap", 120, 180))
        assertEquals(listOf("Early", "After A Gap"), IptvRunningOrder.build(gappy, emptyList(), emptySet(), now, bed).map { it.entry.title })
    }

    @Test
    fun `no title twice and nothing after bedtime when there is no room`() {
        val guide = listOf(p(1, "Soap", 0, 30), p(2, "Soap", 30, 60))
        val order = IptvRunningOrder.build(guide, emptyList(), emptySet(), now, now + 45 * minute)
        assertEquals(listOf("Soap"), order.map { it.entry.title })
        assertTrue(IptvRunningOrder.build(guide, emptyList(), emptySet(), now, now - minute).isEmpty())
    }

    @Test
    fun `approved only when every slot has an auto-switch reminder`() {
        val a = p(1, "A", 0, 40)
        val b = p(2, "B", 40, 80)
        fun reminder(x: IptvWallProgramme, auto: Boolean) =
            IptvReminder("pl", x.channel.primary.key, "c", x.entry.title, x.entry.startMs, x.entry.endMs, auto)
        assertFalse(IptvRunningOrder.approved("pl", listOf(a, b), listOf(reminder(a, true))))
        assertFalse(IptvRunningOrder.approved("pl", listOf(a, b), listOf(reminder(a, true), reminder(b, false))))
        assertTrue(IptvRunningOrder.approved("pl", listOf(a, b), listOf(reminder(a, true), reminder(b, true))))
        assertFalse(IptvRunningOrder.approved("pl", emptyList(), emptyList()))
    }

    @Test
    fun `a typed line is read into a time, a kind and words`() {
        assertEquals(30, IptvAsk.parse("something funny, half an hour").minutes)
        assertTrue(IptvAsk.parse("something funny, half an hour").comedy)
        assertEquals(60, IptvAsk.parse("a film for an hour").minutes)
        assertEquals(setOf(IptvProgrammeKind.FILM), IptvAsk.parse("a film for an hour").kinds)
        assertEquals(45, IptvAsk.parse("45 minutes of sport").minutes)
        assertEquals(120, IptvAsk.parse("2 hours").minutes)
        assertEquals(listOf("bond"), IptvAsk.parse("any bond film").words)
        assertNull(IptvAsk.parse("football").minutes)
    }

    @Test
    fun `just ask gives one answer that fits and not that steps on`() {
        val guide = listOf(
            p(1, "Big Film", -5, 120, IptvProgrammeKind.FILM),
            p(2, "Short Comedy Show", -5, 25),
            p(3, "Sitcom Hour", 10, 40),
            p(4, "Weather", -5, 5),
        )
        val first = IptvAsk.answer("something funny, half an hour", guide, emptyList(), emptySet(), now, 0)
        assertNotNull(first.programme)
        assertEquals("Sitcom Hour", first.programme!!.entry.title, "30 minutes long is the closest to half an hour")
        assertEquals(1, first.others)
        val second = IptvAsk.answer("something funny, half an hour", guide, emptyList(), emptySet(), now, 1)
        assertEquals("Short Comedy Show", second.programme!!.entry.title)
        assertEquals(first.programme!!.entry.title, IptvAsk.answer("something funny, half an hour", guide, emptyList(), emptySet(), now, 2).programme!!.entry.title, "it wraps round")
    }

    @Test
    fun `nothing that fits says so`() {
        val answer = IptvAsk.answer("a film, 15 minutes", listOf(p(1, "Quiz", -5, 180)), emptyList(), emptySet(), now, 0)
        assertNull(answer.programme)
        assertTrue(answer.why.startsWith("Nothing"))
    }

    @Test
    fun `too long for the time gives the closest, and says so`() {
        val answer = IptvAsk.answer("a film, 15 minutes", listOf(p(1, "Epic", -5, 180, IptvProgrammeKind.FILM)), emptyList(), emptySet(), now, 0)
        assertEquals("Epic", answer.programme?.entry?.title)
        assertTrue(answer.why.startsWith("Nothing fits 15 minutes"), answer.why)
    }

    @Test
    fun `news on now is live, so ten minutes of it fits, and channels named news count without categories`() {
        val guide = listOf(p(1, "Quiz", -5, 25), p(2, "BBC News at Nine", -10, 50), p(3, "Weather", -2, 8))
        val answer = IptvAsk.answer("news, ten minutes", guide, emptyList(), emptySet(), now, 0)
        assertEquals("BBC News at Nine", answer.programme?.entry?.title)
        assertTrue(answer.reasons.any { it.first == "Live" })
    }

    @Test
    fun `the football asks for sport and prefers football`() {
        val q = IptvAsk.parse("the football")
        assertEquals(setOf(IptvProgrammeKind.SPORT), q.kinds)
        assertTrue(q.words.isEmpty())
        val guide = listOf(p(1, "Live Darts", -5, 90, IptvProgrammeKind.SPORT), p(2, "Premier League Football", -5, 90, IptvProgrammeKind.SPORT), p(3, "Quiz", -5, 30))
        assertEquals("Premier League Football", IptvAsk.answer("the football", guide, emptyList(), emptySet(), now, 0).programme?.entry?.title)
    }
}

class IptvProgrammeLineTest {
    private val minute = 60_000L
    private val now = 10_000_000L
    private fun clock(ms: Long) = "t${(ms - now) / minute}"
    private fun e(from: Int, to: Int) = IptvEpgEntry("X", now + from * minute, now + to * minute)

    @Test
    fun `every state reads in the same shape`() {
        assertEquals("C4 · on now, 12 min in · ends t30", IptvProgrammeLine.text("C4", e(-12, 30), now, 0, ::clock))
        assertEquals("C4 · starts in 2 min · ends t40", IptvProgrammeLine.text("C4", e(2, 40), now, 0, ::clock))
        assertEquals("C4 · starts t120 · ends t150", IptvProgrammeLine.text("C4", e(120, 150), now, 0, ::clock))
        assertEquals("C4 · from catch-up · t-30 to t-20", IptvProgrammeLine.text("C4", e(-30, -20), now, 0, ::clock))
        assertEquals("C4 · on now, 0 min in · ends t30 · 5 min over", IptvProgrammeLine.text("C4", e(0, 30), now, 5, ::clock))
    }
}
