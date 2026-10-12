package com.nuvio.app.features.player.autosync

import kotlin.test.Test
import kotlin.test.assertEquals

class AutoSyncSubtitleRewriterTest {
    // Pure +1.5 s correction with no per-cue boundaries, so every time goes through the transform.
    private val shiftForward = AutoSyncTimelineRetimeResult(
        cues = emptyList(), groups = emptyList(), targetCoverage = 1.0, referenceCoverage = 1.0,
        skippedTargetCues = 0, skippedReferenceCues = 0, longestTargetSkipRun = 0, averageGroupCost = 0.0,
        oneToOneGroups = 0, oneToTwoGroups = 0, twoToOneGroups = 0, oneToThreeGroups = 0,
        threeToOneGroups = 0, twoToTwoGroups = 0, confident = true, alignmentInterceptMs = 1_500.0,
    )

    @Test
    fun assKeepsStylesAndCommasInText() {
        val ass = "[Script Info]\r\nTitle: x\r\n\r\n[V4+ Styles]\r\nStyle: Default,Arial,20\r\n\r\n" +
            "[Events]\r\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\r\n" +
            "Dialogue: 0,0:00:01.00,0:00:02.50,Default,,0,0,0,,{\\i1}Hello, there{\\i0}\r\n"
        val out = AutoSyncSubtitleRewriter.rewrite(ass, "https://x/sub.ass", shiftForward)
        assertEquals("ass", out.extension)
        assertEquals(
            ass.replace("0:00:01.00,0:00:02.50", "0:00:02.50,0:00:04.00"),
            out.text,
        )
    }

    @Test
    fun vttKeepsCueSettingsAndAddsHoursWhenNeeded() {
        val vtt = "WEBVTT\n\n59:59.000 --> 59:59.900 line:90% align:start\n<b>Bold</b>\n"
        val out = AutoSyncSubtitleRewriter.rewrite(vtt, "https://x/sub.vtt", shiftForward)
        assertEquals("vtt", out.extension)
        assertEquals("WEBVTT\n\n01:00:00.500 --> 01:00:01.400 line:90% align:start\n<b>Bold</b>\n", out.text)
    }

    @Test
    fun srtShiftsTimingLinesOnly() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\n12:00 is noon --> really\n\n"
        val out = AutoSyncSubtitleRewriter.rewrite(srt, "https://x/s.srt", shiftForward)
        assertEquals("1\n00:00:02,500 --> 00:00:03,500\n12:00 is noon --> really\n\n", out.text)
    }
}
