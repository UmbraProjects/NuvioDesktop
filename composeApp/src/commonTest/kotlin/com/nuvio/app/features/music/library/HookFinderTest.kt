package com.nuvio.app.features.music.library

import com.nuvio.app.features.music.LyricLine
import com.nuvio.app.features.music.Lyrics
import com.nuvio.app.features.music.parseLrc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HookFinderTest {
    // Sample LRC files. The songs are made up for the tests.

    /** Four-line chorus sung three times, with a "♪" gap and a blank line around it. Chorus first sung at 0:33.5. */
    private val paperBoats = """
        [ar:Test Band]
        [ti:Paper Boats]
        [00:00.00] ♪
        [00:14.20] Streetlights hum along the river
        [00:18.00] I fold the evening into small boats
        [00:22.10] Send them out on the quiet water
        [00:26.40] Hoping that you'll read the notes
        [00:31.00]
        [00:33.50] Hold on, hold on to the morning
        [00:37.80] We were never meant to sink
        [00:42.00] Hold on, hold on to the morning
        [00:46.30] Light will find us at the brink
        [00:51.00] ♪
        [01:02.00] Footsteps echo through the station
        [01:06.00] Every window holds a name
        [01:10.20] I keep counting all the platforms
        [01:14.50] Nothing here is quite the same
        [01:19.00]
        [01:21.00] Hold on, hold on to the morning
        [01:25.30] We were never meant to sink
        [01:29.50] Hold on, hold on to the morning
        [01:33.80] Light will find us at the brink
        [01:38.00] ♪
        [02:10.00] Hold on, hold on to the morning
        [02:14.30] We were never meant to sink
        [02:18.50] Hold on, hold on to the morning
        [02:22.80] Light will find us at the brink
        [02:28.00] ♪
    """.trimIndent()

    /** The chorus is written once with three stamps, the way many LRC files compress a repeat. First at 0:21. */
    private val lanterns = """
        [ti:Lanterns]
        [00:12.00] Nobody lit the road tonight
        [00:16.50] So we carried our own lanterns
        [00:21.00][01:05.00][02:20.00] Raise them high, raise them high
        [00:25.00][01:09.00][02:24.00] We are the light in the dark sky
        [00:30.00]
        [00:40.00] Rivers learned our names by heart
        [00:44.00] Mountains kept the rest in store
        [00:48.00] Everything we lost was ours before
        [00:52.00]
        [01:30.00] The bridge is long and the night is longer
        [01:35.00] But nobody here is going home
        [01:40.00]
    """.trimIndent()

    /** No line comes back: every line is different. */
    private val noRepeats = """
        [00:10.00] The first thing I remember is the rain
        [00:15.00] Then the smell of coal and wet tin roofs
        [00:20.00] My mother counting coins at the window
        [00:25.00] A train that never stopped in our town
        [00:30.00] Years went by like pages in a book
        [00:35.00] And I wrote my name in every one
    """.trimIndent()

    private fun lines(lrc: String) = parseLrc(lrc)

    // region chorus found

    @Test
    fun theChorusOfAFullSongIsFoundAtItsFirstOccurrence() {
        val hook = findHook(lines(paperBoats), durationMs = 200_000L)
        assertEquals(HookSource.Chorus, hook.source)
        // 0:33.5 minus the lead-in; the line before it is a blank gap, so the lead-in is allowed.
        assertEquals(33_500L - HOOK_LEAD_IN_MS, hook.startMs)
        assertEquals(4, hook.lineCount)
        // The block really is the chorus: parsed line 6 is "Hold on, hold on to the morning".
        assertEquals("Hold on, hold on to the morning", lines(paperBoats)[hook.lineIndex].text)
        assertEquals(33_500L, lines(paperBoats)[hook.lineIndex].timeMs)
    }

    @Test
    fun theHookCoversTheChorusAndStaysInTheLengthLimits() {
        val parsed = lines(paperBoats)
        val hook = findHook(parsed, durationMs = 200_000L)
        assertTrue(hook.lengthMs in HOOK_MIN_LENGTH_MS..HOOK_MAX_LENGTH_MS)
        // The whole four-line chorus (last line stamped 0:46.3) is inside the hook.
        assertTrue(hook.endMs >= 46_300L, "hook ends at ${hook.endMs}")
        assertEquals(hook.startMs + hook.lengthMs, hook.endMs)
    }

    @Test
    fun aChorusWrittenWithSeveralStampsIsFound() {
        val parsed = lines(lanterns)
        val hook = findHook(parsed, durationMs = 190_000L)
        assertEquals(HookSource.Chorus, hook.source)
        // 0:21 minus the lead-in; the previous line ends (estimated) at 19.75 s, before that.
        assertEquals(19_800L, hook.startMs)
        assertEquals("Raise them high, raise them high", parsed[hook.lineIndex].text)
        assertEquals(21_000L, parsed[hook.lineIndex].timeMs)
        assertEquals(2, hook.lineCount)
    }

    @Test
    fun theLeadInNeverRunsBackIntoTheLineBefore() {
        // The line before the chorus is sung from 0:20 up to the chorus stamp at 0:21: no room for a lead-in.
        val lrc = """
            [00:10.00] Something quiet to begin with
            [00:20.00] Walking out into the rain
            [00:21.00] Never let go of the light
            [00:25.00] Never let go of the light
            [00:40.00] Another verse goes by here
            [01:00.00] Never let go of the light
        """.trimIndent()
        val hook = findHook(lines(lrc), durationMs = 120_000L)
        assertEquals(HookSource.Chorus, hook.source)
        assertEquals(21_000L, hook.startMs)
    }

    @Test
    fun aChorusAtTheVeryStartDoesNotStartBeforeZero() {
        val lrc = """
            [00:00.50] Shout it out for everyone
            [00:05.00] A verse nobody will remember
            [00:10.00] Shout it out for everyone
            [00:15.00] Shout it out for everyone
        """.trimIndent()
        val hook = findHook(lines(lrc), durationMs = 60_000L)
        assertEquals(HookSource.Chorus, hook.source)
        assertEquals(0L, hook.startMs)
    }

    @Test
    fun caseAndPunctuationDoNotHideARepeat() {
        val lrc = """
            [00:20.00] NEVER, let go of the light!
            [00:24.00] A verse in between the two
            [00:40.00] never let go of the light
            [00:44.00] Another verse in the middle
            [01:00.00] Never let go... of the light.
        """.trimIndent()
        val hook = findHook(lines(lrc), durationMs = 100_000L)
        assertEquals(HookSource.Chorus, hook.source)
        assertEquals(0, hook.lineIndex)
    }

    @Test
    fun aChorusWithAnAdLibInTheLastRepeatIsStillFound() {
        val lrc = """
            [00:10.00] A verse to open the song
            [00:20.00] Carry me over the water
            [00:24.00] Carry me over the hills
            [00:28.00] Carry me over the water
            [00:32.00] Home is where the lantern is
            [00:50.00] A second verse arrives here
            [01:00.00] Carry me over the water
            [01:04.00] Carry me over the hills
            [01:08.00] Carry me over the water
            [01:12.00] Home is where the lantern is
            [01:30.00] A closing verse to finish
            [01:40.00] Carry me over the water
            [01:44.00] Carry me over the hills
            [01:48.00] Carry me over the water
            [01:52.00] Home, home, wherever you are
        """.trimIndent()
        val parsed = lines(lrc)
        val hook = findHook(parsed, durationMs = 220_000L)
        assertEquals(HookSource.Chorus, hook.source)
        assertEquals(20_000L - HOOK_LEAD_IN_MS, hook.startMs)
        assertEquals(1, hook.lineIndex)
        assertTrue(hook.lineCount >= 3)
    }

    @Test
    fun theBlockWithTheMostRepeatedMaterialWinsOverAShortTag() {
        // "Rolling down the highway" comes back in every verse (4 times); the real chorus is two lines sung 3 times.
        val lrc = """
            [00:10.00] I left my town at the break of day
            [00:14.00] Rolling down the highway
            [00:18.00] Counted every mile marker
            [00:30.00] Sing it loud for all of them
            [00:34.00] We are only passing through
            [00:50.00] Another town another door
            [00:54.00] Rolling down the highway
            [00:58.00] Never looked behind me once
            [01:10.00] Sing it loud for all of them
            [01:14.00] We are only passing through
            [01:30.00] The last verse rolls along
            [01:34.00] Rolling down the highway
            [01:38.00] Watching the sun go down
            [01:50.00] Sing it loud for all of them
            [01:54.00] We are only passing through
            [02:10.00] Rolling down the highway
        """.trimIndent()
        val parsed = lines(lrc)
        val hook = findHook(parsed, durationMs = 200_000L)
        assertEquals(HookSource.Chorus, hook.source)
        assertEquals("Sing it loud for all of them", parsed[hook.lineIndex].text)
        assertEquals(30_000L, parsed[hook.lineIndex].timeMs)
        assertEquals(2, hook.lineCount)
    }

    // endregion

    // region length

    @Test
    fun aOneLineChorusStillPlaysTheMinimumLength() {
        val lrc = """
            [00:20.00] Dance dance dance tonight
            [00:23.00] A line to fill the verse
            [00:40.00] Dance dance dance tonight
            [00:43.00] Another line in the verse
            [01:00.00] Dance dance dance tonight
        """.trimIndent()
        val hook = findHook(lines(lrc), durationMs = 200_000L)
        assertEquals(HookSource.Chorus, hook.source)
        assertEquals(HOOK_MIN_LENGTH_MS, hook.lengthMs)
    }

    @Test
    fun aVeryLongChorusIsCappedAtTheMaximumLength() {
        val chorus = (0 until 10).joinToString("\n") { i -> "[%02d:%02d.00] Chorus line number $i goes here".format(0, 30 + i * 6) }
        val chorusAgain = (0 until 10).joinToString("\n") { i -> "[%02d:%02d.00] Chorus line number $i goes here".format(2, i * 6) }
        val parsed = lines("$chorus\n[01:40.00] A verse in between\n$chorusAgain")
        val hook = findHook(parsed, durationMs = 300_000L)
        assertEquals(HookSource.Chorus, hook.source)
        assertEquals(HOOK_MAX_LENGTH_MS, hook.lengthMs)
    }

    @Test
    fun theHookNeverRunsPastTheEndOfTheTrack() {
        val lrc = """
            [00:20.00] A verse before the end
            [00:50.00] Last chorus holds the line
            [00:55.00] A little bridge in here
            [01:20.00] Last chorus holds the line
        """.trimIndent()
        val hook = findHook(lines(lrc), durationMs = 62_000L)
        assertEquals(HookSource.Chorus, hook.source)
        assertTrue(hook.endMs <= 62_000L, "ends at ${hook.endMs}")
    }

    // endregion

    // region fallback

    @Test
    fun aSongWithNoRepeatedLineFallsBackToTheFixedOffset() {
        val hook = findHook(lines(noRepeats), durationMs = 240_000L)
        assertEquals(HookSource.FixedOffset, hook.source)
        assertEquals(72_000L, hook.startMs)
        assertEquals(FALLBACK_LENGTH_MS, hook.lengthMs)
        assertEquals(-1, hook.lineIndex)
        assertEquals(0, hook.lineCount)
    }

    @Test
    fun theFallbackUsesAFixedStartWhenTheLengthIsUnknown() {
        assertEquals(Hook(FALLBACK_UNKNOWN_START_MS, FALLBACK_LENGTH_MS, HookSource.FixedOffset), findHook(emptyList()))
        assertEquals(Hook(FALLBACK_UNKNOWN_START_MS, FALLBACK_LENGTH_MS, HookSource.FixedOffset), fixedOffsetHook(0L))
    }

    @Test
    fun theFallbackAlwaysFitsInsideAShortTrack() {
        val short = fixedOffsetHook(25_000L)
        assertEquals(7_500L, short.startMs)
        assertEquals(17_500L, short.lengthMs)
        assertTrue(short.endMs <= 25_000L)

        val tiny = fixedOffsetHook(8_000L)
        assertEquals(0L, tiny.startMs)
        assertEquals(8_000L, tiny.lengthMs)
    }

    @Test
    fun noLyricsAtAllIsTheFixedOffsetToo() {
        assertEquals(HookSource.FixedOffset, findHook(emptyList(), 180_000L).source)
        assertEquals(HookSource.FixedOffset, findHook(listOf(LyricLine(1_000L, ""), LyricLine(5_000L, "")), 180_000L).source)
    }

    @Test
    fun instrumentalMarkersAndFillerSyllablesAreNotAChorus() {
        val lrc = """
            [00:00.00] ♪
            [00:10.00] Oh oh
            [00:12.00] ♪
            [00:20.00] Oh oh
            [00:22.00] ♪
            [00:30.00] Oh oh
            [00:40.00] Yeah
            [00:45.00] Yeah
            [00:50.00] ♪
        """.trimIndent()
        assertEquals(HookSource.FixedOffset, findHook(lines(lrc), 180_000L).source)
    }

    @Test
    fun lyricsStampedPastTheEndOfTheTrackFallBack() {
        // A different cut of the song: the chorus is stamped at 4:00 but the track is only 3:20 long.
        val lrc = """
            [03:50.00] Never let go of the light
            [04:00.00] A verse that comes late
            [04:10.00] Never let go of the light
        """.trimIndent()
        val hook = findHook(lines(lrc), durationMs = 200_000L)
        assertEquals(HookSource.FixedOffset, hook.source)
        assertEquals(60_000L, hook.startMs)
    }

    @Test
    fun onlySyncedLyricsAreSearched() {
        val synced = Lyrics.Synced(lines(paperBoats))
        assertEquals(HookSource.Chorus, hookFor(synced, 200_000L).source)
        assertEquals(HookSource.FixedOffset, hookFor(Lyrics.Plain(listOf("Hold on", "Hold on", "Hold on")), 200_000L).source)
        assertEquals(HookSource.FixedOffset, hookFor(Lyrics.Instrumental, 200_000L).source)
        assertEquals(HookSource.FixedOffset, hookFor(null, 200_000L).source)
        assertEquals(60_000L, hookFor(null, 200_000L).startMs)
    }

    // endregion

    // region robustness

    @Test
    fun theSameInputAlwaysGivesTheSameHook() {
        val parsed = lines(paperBoats)
        assertEquals(findHook(parsed, 200_000L), findHook(parsed, 200_000L))
        assertEquals(findHook(parsed, 200_000L), findHook(parsed.toList(), 200_000L))
    }

    @Test
    fun aHugeLyricIsHandledAndStillFindsARepeat() {
        val huge = (0 until 3_000).map { LyricLine(it * 1_000L, "la la la la la la") }
        val hook = findHook(huge, durationMs = 4_000_000L)
        assertEquals(HookSource.Chorus, hook.source)
        assertEquals(0L, hook.startMs)
    }

    @Test
    fun theHookCanBeFoundFromARawLrcStringWithMetadataAndWordStamps() {
        val lrc = """
            [ar:Test Band]
            [length:03:20]
            [00:20.00] <00:20.00>Never <00:20.50>let <00:21.00>go of the light
            [00:25.00] A verse about the road
            [00:40.00] Never let go of the light
            [00:45.00] Another verse about the sea
            [01:00.00] Never let go of the light
        """.trimIndent()
        val hook = findHook(parseLrc(lrc), durationMs = 200_000L)
        assertEquals(HookSource.Chorus, hook.source)
        assertEquals(20_000L - HOOK_LEAD_IN_MS, hook.startMs)
    }

    // endregion
}
