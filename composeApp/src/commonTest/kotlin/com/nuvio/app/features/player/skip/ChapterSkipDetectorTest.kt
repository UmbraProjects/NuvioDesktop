package com.nuvio.app.features.player.skip

import com.nuvio.app.features.player.PlayerChapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChapterSkipDetectorTest {

    @Test
    fun returnsAnIntroIntervalForAnExplicitIntroChapter() {
        val intervals = ChapterSkipDetector.findIntervals(
            listOf(
                PlayerChapter(0.0, "Intro"),
                PlayerChapter(72.5, "Episode"),
            ),
        )

        assertEquals(
            listOf(SkipInterval(0.0, 72.5, "intro", "chapters")),
            intervals,
        )
    }

    @Test
    fun rejectsAmbiguousOrUnsafeChapterSpans() {
        val intervals = ChapterSkipDetector.findIntervals(
            listOf(
                PlayerChapter(0.0, "Opening scene"),
                PlayerChapter(45.0, "Intro"),
                PlayerChapter(400.0, "Main story"),
            ),
        )

        assertTrue(intervals.isEmpty())
    }

    @Test
    fun treatsAnOpeningMarkerAfterZeroAsTheEndOfTheOpening() {
        val intervals = ChapterSkipDetector.findIntervals(
            listOf(
                PlayerChapter(0.0, "Prologue"),
                PlayerChapter(88.0, "Opening"),
                PlayerChapter(640.0, "Part A"),
            ),
        )

        assertEquals(
            listOf(SkipInterval(0.0, 88.0, "intro", "chapters")),
            intervals,
        )
    }

    @Test
    fun returnsAnOutroIntervalForAnExplicitEndingChapter() {
        val intervals = ChapterSkipDetector.findIntervals(
            chapters = listOf(
                PlayerChapter(0.0, "Episode"),
                PlayerChapter(1_260.0, "Ending"),
            ),
            durationSeconds = 1_350.0,
        )

        assertEquals(
            listOf(SkipInterval(1_260.0, 1_350.0, "outro", "chapters")),
            intervals,
        )
    }

    @Test
    fun preservesChapterOutroWhenCommunityOnlyProvidesAnIntro() {
        val merged = mergeCommunityAndChapterSkipIntervals(
            communityIntervals = listOf(SkipInterval(0.0, 75.0, "intro", "community")),
            chapterIntervals = listOf(
                SkipInterval(0.0, 72.0, "intro", "chapters"),
                SkipInterval(1_260.0, 1_350.0, "outro", "chapters"),
            ),
        )

        assertEquals(
            listOf(
                SkipInterval(0.0, 75.0, "intro", "community"),
                SkipInterval(1_260.0, 1_350.0, "outro", "chapters"),
            ),
            merged,
        )
    }
}
