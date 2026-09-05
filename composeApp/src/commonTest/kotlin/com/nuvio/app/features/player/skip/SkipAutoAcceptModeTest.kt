package com.nuvio.app.features.player.skip

import com.nuvio.app.features.player.PlayerChapter
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkipAutoAcceptModeTest {
    private fun interval(provider: String, type: String = "intro") = SkipInterval(
        startTime = 30.0,
        endTime = 120.0,
        type = type,
        provider = provider,
    )

    @Test
    fun `manual accepts nothing`() {
        assertFalse(SkipAutoAcceptMode.MANUAL.accepts(interval(CHAPTER_SKIP_PROVIDER)))
        assertFalse(SkipAutoAcceptMode.MANUAL.accepts(interval("skipdb")))
    }

    @Test
    fun `chapter mode accepts only chapter-derived segments`() {
        assertTrue(SkipAutoAcceptMode.CHAPTERS.accepts(interval(CHAPTER_SKIP_PROVIDER)))
        assertFalse(SkipAutoAcceptMode.CHAPTERS.accepts(interval("skipdb")))
        assertFalse(SkipAutoAcceptMode.CHAPTERS.accepts(interval("aniskip")))
        assertFalse(SkipAutoAcceptMode.CHAPTERS.accepts(interval("introdb")))
    }

    @Test
    fun `any source mode accepts community timings as well as chapters`() {
        assertTrue(SkipAutoAcceptMode.ANY_SOURCE.accepts(interval(CHAPTER_SKIP_PROVIDER)))
        assertTrue(SkipAutoAcceptMode.ANY_SOURCE.accepts(interval("skipdb")))
        assertTrue(SkipAutoAcceptMode.ANY_SOURCE.accepts(interval("aniskip")))
    }

    @Test
    fun `recaps are auto-accepted alongside intros`() {
        for (type in listOf("recap", "op", "mixed-op")) {
            assertTrue(
                SkipAutoAcceptMode.ANY_SOURCE.accepts(interval("skipdb", type)),
                "expected $type to auto-accept",
            )
        }
    }

    @Test
    fun `outros are never auto-accepted at any level`() {
        for (type in listOf("outro", "ed", "mixed-ed", "credits")) {
            assertFalse(
                SkipAutoAcceptMode.CHAPTERS.accepts(interval(CHAPTER_SKIP_PROVIDER, type)),
                "expected $type to stay manual under CHAPTERS",
            )
            assertFalse(
                SkipAutoAcceptMode.ANY_SOURCE.accepts(interval(CHAPTER_SKIP_PROVIDER, type)),
                "expected $type to stay manual under ANY_SOURCE",
            )
            assertFalse(
                SkipAutoAcceptMode.ANY_SOURCE.accepts(interval("skipdb", type)),
                "expected $type to stay manual under ANY_SOURCE",
            )
        }
    }

    @Test
    fun `an unrecognised segment kind is left to a deliberate press`() {
        assertFalse(SkipAutoAcceptMode.ANY_SOURCE.accepts(interval("skipdb", "preview")))
    }

    @Test
    fun `chapter detector tags its intervals with the provider chapter mode matches on`() {
        val intervals = ChapterSkipDetector.findIntervals(
            chapters = listOf(
                PlayerChapter(title = "Intro", startTime = 30.0),
                PlayerChapter(title = "Episode", startTime = 120.0),
                PlayerChapter(title = "Ending", startTime = 1300.0),
            ),
            durationSeconds = 1400.0,
        )

        // Both kinds are detected, but only the intro is a candidate for auto-accept.
        assertTrue(intervals.any { it.type == "intro" })
        assertTrue(intervals.any { it.type == "outro" })
        assertTrue(
            intervals.filter { SkipAutoAcceptMode.CHAPTERS.accepts(it) }
                .all { it.type == "intro" },
        )
        assertTrue(intervals.any { SkipAutoAcceptMode.CHAPTERS.accepts(it) })
    }

    @Test
    fun `identity keys separate segments within one episode`() {
        val intro = SkipInterval(0.0, 90.0, "intro", CHAPTER_SKIP_PROVIDER)
        val outro = SkipInterval(1300.0, 1400.0, "outro", CHAPTER_SKIP_PROVIDER)

        assertTrue(intro.identityKey() != outro.identityKey())
        assertTrue(intro.identityKey() == intro.copy(provider = "skipdb").identityKey())
    }
}
