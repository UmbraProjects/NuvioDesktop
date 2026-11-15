package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IptvAskAiTest {
    private val minute = 60_000L
    private val now = 1_000_000_000L

    private fun merged(uid: Int) = IptvMergedChannel(listOf(IptvChannel(uid = uid, key = "k$uid", num = uid, name = "Channel $uid", categoryId = "c")))
    private fun p(uid: Int, title: String, from: Int, to: Int, desc: String? = null, episode: String? = null) =
        IptvWallProgramme(merged(uid), IptvEpgEntry(title, now + from * minute, now + to * minute, null, desc = desc, episode = episode))

    @Test
    fun `the prompt numbers what is on with times, episodes and short descriptions`() {
        val text = IptvAskAiRules.prompt(
            "something light",
            listOf(p(1, "Taskmaster", -10, 50, desc = "Comedians do silly tasks.", episode = "S2 E3"), p(2, "News", 15, 45)),
            now,
        )
        assertTrue("Request: something light" in text)
        assertTrue("1. Taskmaster (S2 E3) | Channel 1 | on now, 50 min left | Comedians do silly tasks." in text, text)
        assertTrue("2. News | Channel 2 | starts " in text && "30 min long" in text, text)
    }

    @Test
    fun `replies are read in order, off-list and repeated picks dropped, three at most`() {
        val reply = """Sure! {"picks":[{"i":2,"why":"A quick \"laugh\""},{"why":"Light and short","i":1},{"i":9,"why":"no"},{"i":2,"why":"again"},{"i":3,"why":"c"},{"i":4,"why":"d"}]}"""
        val parsed = IptvAskAiRules.parse(reply, 4)
        assertEquals(listOf(1 to "A quick \"laugh\"", 0 to "Light and short", 2 to "c"), parsed)
        assertEquals(emptyList(), IptvAskAiRules.parse("I can't help with that.", 4))
    }

    @Test
    fun `candidates are on now with ten minutes left or start within the hour, one per title`() {
        val guide = listOf(
            p(1, "Ending", -50, 5),
            p(2, "On", -10, 30),
            p(3, "Soon", 20, 60),
            p(4, "Later", 90, 120),
            p(5, "On", -5, 40),
        )
        val list = IptvAskAiRules.candidates(guide, now) { 0 }
        assertEquals(listOf("On", "Soon"), list.map { it.entry.title })
    }

    @Test
    fun `the ai's picks come first, then the rules carry on`() {
        val guide = listOf(p(1, "A", -10, 50), p(2, "B", -5, 40), p(3, "C", 10, 60))
        val pick = IptvAiPick("q", IptvAskAiRules.choices(listOf(1 to "fits", 0 to "also"), guide))
        val first = IptvAskAiRules.answer(pick, guide, 0, now)!!
        assertEquals("B", first.programme?.entry?.title)
        assertTrue("fits" in first.why)
        assertEquals("Why the AI picked it" to "fits", first.reasons.first())
        assertEquals("A", IptvAskAiRules.answer(pick, guide, 1, now)?.programme?.entry?.title)
        assertNull(IptvAskAiRules.answer(pick, guide, 2, now))
        assertEquals(0, IptvAskAiRules.rulesSkips(pick, guide, 2, now))
        assertEquals(1, IptvAskAiRules.rulesSkips(pick, guide, 3, now))
        assertEquals(3, IptvAskAiRules.rulesSkips(null, guide, 3, now))
    }

    @Test
    fun `a pick that has ended or left the guide is passed over`() {
        val guide = listOf(p(1, "A", -10, 50))
        val pick = IptvAiPick("q", listOf(IptvAiChoice(7, now, "gone"), IptvAiChoice(1, now - 10 * minute, "still on")))
        assertEquals("A", IptvAskAiRules.answer(pick, guide, 0, now)?.programme?.entry?.title)
    }
}
