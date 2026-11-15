package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IptvTasteTest {
    private val minute = 60_000L
    private val day = 24 * 60 * minute
    private val now = 1_000_000_000L

    private fun entry(title: String, from: Int, to: Int, episode: String? = null, flags: Int = 0, desc: String? = null, image: String? = null, kind: String? = null) =
        IptvEpgEntry(title, now + from * minute, now + to * minute, kind, desc = desc, episode = episode, image = image, flags = flags)

    private fun merged(uid: Int) = IptvMergedChannel(listOf(IptvChannel(uid = uid, key = "k$uid", num = uid, name = "Channel $uid", categoryId = "c")))

    /** Plays [minutes] minutes of [e] and returns the events noted. */
    private fun play(e: IptvEpgEntry, minutes: Int, start: IptvWatching? = null): Pair<IptvWatching?, List<IptvWatchEvent>> {
        var w = start
        val events = ArrayList<IptvWatchEvent>()
        repeat(minutes) {
            val (next, event) = IptvTasteRules.step(w, "p:k1", e)
            w = next
            if (event != IptvWatchEvent.None) events.add(event)
        }
        return w to events
    }

    private fun watched(stats: Map<String, IptvTitleStat>, e: IptvEpgEntry, finished: Boolean, at: Long = now): Map<String, IptvTitleStat> {
        var out = IptvTasteRules.note(stats, e, IptvWatchEvent.Seen, at)
        if (finished) out = IptvTasteRules.note(out, e, IptvWatchEvent.Finished, at)
        return out
    }

    @Test
    fun `a programme is seen at five minutes and finished at seven tenths, each once`() {
        val show = entry("Quiz Night", 0, 60)
        assertEquals(emptyList(), play(show, 4).second)
        assertEquals(listOf(IptvWatchEvent.Seen), play(show, 5).second)
        assertEquals(listOf(IptvWatchEvent.Seen, IptvWatchEvent.Finished), play(show, 60).second)
    }

    @Test
    fun `changing programme starts the count again`() {
        val (w, _) = play(entry("First", 0, 60), 4)
        val (_, events) = play(entry("Second", 0, 60), 4, start = w)
        assertEquals(emptyList(), events, "four minutes of the second is not five")
    }

    @Test
    fun `viewings are kept with how many were finished and the last five`() {
        val show = entry("The Chase", 0, 60, episode = "S1 E1")
        var stats = emptyMap<String, IptvTitleStat>()
        repeat(4) { stats = watched(stats, show, finished = true) }
        stats = watched(stats, show, finished = false)
        stats = watched(stats, show, finished = true)
        val stat = IptvTasteRules.statOf(stats, "the chase")!!
        assertEquals(6, stat.seen)
        assertEquals(5, stat.finished)
        assertEquals(listOf(true, true, true, false, true), stat.recent)
        assertEquals("You finished 4 of the last 5", IptvTasteRules.finishText(stat))
        assertTrue(IptvTasteRules.usuallyFinishes(stat))
        assertEquals(listOf("S1 E1"), stat.episodes)
    }

    @Test
    fun `one viewing says nothing yet`() {
        val stats = watched(emptyMap(), entry("Once", 0, 30), finished = true)
        assertNull(IptvTasteRules.finishText(IptvTasteRules.statOf(stats, "Once")))
        assertFalse(IptvTasteRules.usuallyFinishes(IptvTasteRules.statOf(stats, "Once")))
    }

    @Test
    fun `new episodes come above repeats, and a picture and words help`() {
        val stats = watched(emptyMap(), entry("Doctor", 0, 60, episode = "S1 E1"), finished = true)
        val stat = IptvTasteRules.statOf(stats, "Doctor")
        val repeat = entry("Doctor", 0, 60, episode = "S1 E1")
        val fresh = entry("Doctor", 0, 60, episode = "S1 E2", flags = IptvGuideText.FLAG_NEW)
        assertTrue(IptvTasteRules.isRepeat(repeat, stat))
        assertFalse(IptvTasteRules.isRepeat(fresh, stat))
        assertTrue(IptvTasteRules.score(fresh, stat, false) > IptvTasteRules.score(repeat, stat, false))
        val bare = entry("Bare", 0, 60)
        val full = entry("Full", 0, 60, desc = "Words.", image = "http://x/p.jpg")
        assertTrue(IptvTasteRules.score(full, null, false) > IptvTasteRules.score(bare, null, false))
    }

    @Test
    fun `you keep watching what you came back to lately and do not follow`() {
        var stats = emptyMap<String, IptvTitleStat>()
        repeat(3) { stats = watched(stats, entry("Bake Off", 0, 60), finished = true) }
        repeat(3) { stats = watched(stats, entry("Old Show", 0, 60), finished = true, at = now - 40 * day) }
        repeat(3) { stats = watched(stats, entry("Followed", 0, 60), finished = true) }
        stats = watched(stats, entry("Once", 0, 60), finished = true)
        assertEquals(listOf("Bake Off"), IptvTasteRules.keepWatching(stats, listOf("Followed"), now).map { it.title })
    }

    @Test
    fun `the list of titles is capped, the oldest going first`() {
        var stats = emptyMap<String, IptvTitleStat>()
        repeat(IptvTasteRules.MAX_TITLES + 3) { i -> stats = watched(stats, entry("Show $i", 0, 60), finished = false, at = now + i) }
        assertEquals(IptvTasteRules.MAX_TITLES, stats.size)
        assertNull(IptvTasteRules.statOf(stats, "Show 0"))
        assertTrue(IptvTasteRules.statOf(stats, "Show ${IptvTasteRules.MAX_TITLES + 2}") != null)
    }

    @Test
    fun `the safe card leans on what you finish`() {
        val stats = (1..3).fold(emptyMap<String, IptvTitleStat>()) { s, _ -> watched(s, entry("Finisher", 0, 60), finished = true) }
        val channels = listOf(
            IptvChannelNow(merged(1), listOf(entry("Never Finished", -10, 50))),
            IptvChannelNow(merged(2), listOf(entry("Finisher", -10, 50))),
        )
        val rank = { p: IptvWallProgramme -> IptvTasteRules.score(p.entry, IptvTasteRules.statOf(stats, p.entry.title), false) }
        val plain = IptvDecide.three(channels, listOf(merged(1), merged(2)), null, now, 0)
        val ranked = IptvDecide.three(channels, listOf(merged(1), merged(2)), null, now, 0, rank = rank)
        assertEquals(1, plain.safe?.channel?.primary?.uid)
        assertEquals(2, ranked.safe?.channel?.primary?.uid)
    }

    @Test
    fun `just ask says you usually finish it`() {
        val stats = (1..3).fold(emptyMap<String, IptvTitleStat>()) { s, _ -> watched(s, entry("Taskmaster", 0, 60), finished = true) }
        val guide = listOf(
            IptvWallProgramme(merged(1), entry("Something Else", -5, 40)),
            IptvWallProgramme(merged(2), entry("Taskmaster", -5, 40)),
        )
        val answer = IptvAsk.answer("anything", guide, emptyList(), emptySet(), now, 0, stats)
        assertEquals("Taskmaster", answer.programme?.entry?.title)
        assertTrue("you usually finish it" in answer.why, answer.why)
        assertTrue(answer.reasons.any { it.first == "You usually finish it" && it.second == "You finished the last 3" })
    }
}
