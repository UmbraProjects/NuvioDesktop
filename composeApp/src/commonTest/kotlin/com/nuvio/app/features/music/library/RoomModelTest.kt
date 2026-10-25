package com.nuvio.app.features.music.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomModelTest {
    private fun a(
        type: String? = null, area: String? = null, begin: String? = null, end: String? = null, ended: Boolean = false,
        disambiguation: String? = null, bio: String? = null, mood: String? = null, genres: List<String> = emptyList(),
    ) = LibraryArtist(
        id = "x", name = "X", type = type, area = area, beginYear = begin, endYear = end, ended = ended,
        disambiguation = disambiguation, biography = bio, mood = mood, genres = genres,
    )

    @Test
    fun passportLineReadsLikeTheMock() {
        assertEquals("Solo artist · Ireland · born 1990", passportLine(a("Person", "Ireland", "1990")))
        assertEquals("Group · United States · formed 1981", passportLine(a("Group", "United States", "1981")))
        assertEquals("Group · UK · 1990–2004", passportLine(a("Group", "UK", "1990", "2004", ended = true)))
        assertEquals("", passportLine(a()))
        assertEquals("Ireland", passportLine(a(area = "Ireland")))
    }

    @Test
    fun taglinePrefersDisambiguationThenFirstSentence() {
        assertEquals("Irish singer/songwriter", artistTagline(a(disambiguation = "Irish singer/songwriter", bio = "Other.")))
        assertEquals("He writes songs.", artistTagline(a(bio = "He writes songs. And more songs.")))
        assertNull(artistTagline(a(bio = "   ")))
        assertTrue(firstSentence("x".repeat(200), 90)!!.endsWith("…"))
    }

    @Test
    fun chipsDropAGenreThatRepeatsTheMood() {
        val (mood, genres) = passportChips(a(mood = "Soul", genres = listOf("soul", "blues", "Blues", "rock", "a", "b", "c")))
        assertEquals("Soul", mood)
        assertEquals(listOf("blues", "rock", "a", "b", "c"), genres)
    }

    private fun era(i: Int, from: Int, to: Int): TimelineEra {
        val g = ReleaseGroup(
            LibraryRelease(key = "k$i", title = "t$i", kind = ReleaseKind.Album, year = "$from"),
            emptyList(),
        )
        return TimelineEra(i, from, to, "t$i", g, listOf(g))
    }

    @Test
    fun eraBlocksNeverOverlapAndStayInside() {
        val eras = listOf(era(0, 2014, 2014), era(1, 2015, 2015), era(2, 2023, 2024), era(3, 2025, 2026))
        val slots = layoutEraBlocks(eras, startYear = 2014, spanYears = 13, totalWidth = 832f, minWidth = 90f)
        for (i in 1 until slots.size) assertTrue(slots[i].left >= slots[i - 1].left + slots[i - 1].width, "block $i overlaps")
        slots.forEach { assertTrue(it.left >= 0f && it.left + it.width <= 832f + 0.01f) }
        assertTrue(slots.all { it.width >= 90f - 0.01f })
    }

    @Test
    fun crowdedErasShareTheWidth() {
        val eras = (0 until 6).map { era(it, 2000 + it * 3, 2000 + it * 3) }
        val slots = layoutEraBlocks(eras, 2000, 16, totalWidth = 300f, minWidth = 90f)
        assertEquals(6, slots.size)
        assertTrue(slots.last().left + slots.last().width <= 300.01f)
    }

    @Test
    fun rulerHelpers() {
        assertEquals(416f, rulerX(2020.0, 2014, 12, 832f), 0.01f)
        assertEquals(listOf(2014, 2015, 2016, 2017), rulerTicks(2014, 2016))
        assertEquals(listOf(2013, 2015, 2017, 2019, 2021, 2023, 2025, 2027), rulerTicks(2013, 2026))
    }

    @Test
    fun civilDateMatchesKnownTimestamps() {
        assertEquals(Triple(1970, 1, 1), civilDate(0L))
        assertEquals(Triple(2000, 2, 29), civilDate(951_782_400_000L))
        assertEquals(Triple(2026, 10, 25), civilDate(1_792_929_600_000L))
        assertEquals(Triple(1969, 12, 31), civilDate(-1L))
    }

    @Test
    fun releaseDaysFormat() {
        assertEquals("24 Oct 2025", formatReleaseDay("2025-10-24"))
        assertEquals("Oct 2025", formatReleaseDay("2025-10"))
        assertEquals("2025", formatReleaseDay("2025"))
    }

    @Test
    fun manyErasOverflowIntoAScrollingStrip() {
        assertTrue(eraBlocksOverflow(24, 1500f, 90f))
        assertTrue(!eraBlocksOverflow(4, 832f, 90f))
        assertTrue(!eraBlocksOverflow(0, 100f, 90f))
    }
}
