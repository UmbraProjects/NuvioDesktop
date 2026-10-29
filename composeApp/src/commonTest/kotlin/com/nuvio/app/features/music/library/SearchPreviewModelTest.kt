package com.nuvio.app.features.music.library

import com.nuvio.app.features.music.MusicAlbumSummary
import com.nuvio.app.features.music.MusicTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchPreviewModelTest {
    private fun album(id: String, title: String, year: String?, kind: String?) =
        MusicAlbumSummary(browseId = id, title = title, artists = emptyList(), year = year, kind = kind, thumbnailUrl = null)

    private fun track(seconds: Int, available: Boolean = true) =
        MusicTrack(videoId = "v$seconds", title = "t", artists = emptyList(), durationSeconds = seconds, trackNumber = 1, isAvailable = available)

    @Test
    fun splitNeedsEightHundredFortyDp() {
        assertFalse(isSplitView(839f))
        assertTrue(isSplitView(840f))
    }

    @Test
    fun albumsComeBeforeEpsAndSinglesAndEachGroupIsNewestFirst() {
        val list = listOf(
            album("s", "Single One", "2024", "Single"),
            album("a1", "Old", "1999", "Album"),
            album("e", "An EP", "2023", "EP"),
            album("a2", "New", "2021", "Album"),
        )
        assertEquals(listOf("a2", "a1", "e", "s"), previewReleases(list).map { it.browseId })
    }

    @Test
    fun duplicateTitlesAndEditionTailsFoldToTheFirstSeen() {
        val list = listOf(
            album("a", "Loveless", "1991", "Album"),
            album("b", "Loveless (Deluxe Edition)", "1991", "Album"),
            album("c", "LOVELESS [Remastered]", "1991", "Album"),
            album("d", "Isn't Anything", "1988", "Album"),
        )
        assertEquals(listOf("a", "d"), previewReleases(list).map { it.browseId })
    }

    @Test
    fun theLimitHoldsAndNoYearSortsLast() {
        val list = (1..8).map { album("a$it", "T$it", "20${10 + it}", "Album") } + album("none", "No year", null, "Album")
        val shown = previewReleases(list)
        assertEquals(5, shown.size)
        assertEquals("a8", shown.first().browseId)
        assertEquals("none", previewReleases(list, limit = 20).last().browseId)
        assertTrue(previewReleases(list, limit = 0).isEmpty())
    }

    @Test
    fun sameInputGivesSameAnswerWhenYearsTie() {
        val list = listOf(album("x", "X", "2000", "Album"), album("y", "Y", "2000", "Album"))
        assertEquals(listOf("x", "y"), previewReleases(list).map { it.browseId })
    }

    @Test
    fun durationTextReadsLikeAPlayer() {
        assertEquals("-", durationText(0))
        assertEquals("0:07", durationText(7))
        assertEquals("3:07", durationText(187))
        assertEquals("1:02:03", durationText(3723))
    }

    @Test
    fun trackSummaryCountsOnlyPlayableTracks() {
        assertEquals("No playable tracks", trackSummary(emptyList()))
        assertEquals("No playable tracks", trackSummary(listOf(track(200, available = false))))
        assertEquals("1 track · 3 min", trackSummary(listOf(track(180))))
        assertEquals("2 tracks · 7 min", trackSummary(listOf(track(200), track(200), track(900, available = false))))
        assertEquals("1 track", trackSummary(listOf(track(10))))
    }

    @Test
    fun memoDropsTheLeastRecentlyUsedEntry() {
        val memo = PreviewMemo<String, Int>(capacity = 2)
        memo["a"] = 1
        memo["b"] = 2
        assertEquals(1, memo["a"]) // a is now the most recent
        memo["c"] = 3
        assertNull(memo["b"])
        assertEquals(1, memo["a"])
        assertEquals(3, memo["c"])
        assertEquals(2, memo.size)
    }
}
