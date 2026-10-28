package com.nuvio.app.features.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Which LRCLIB version of a song's lyrics goes with the stream that plays. */
class LyricVersionTest {
    private fun synced(duration: Double, text: String = "line") =
        Lyrics.Synced(listOf(LyricLine(1_000L, text)), duration)

    private fun record(
        artist: String = "Ren",
        duration: Double = 200.0,
        synced: String? = "[00:01.00] hi",
        plain: String? = "hi",
    ) = LrcLibRecord("Song", artist, duration, instrumental = false, plainLyrics = plain, syncedLyrics = synced)

    @Test
    fun theLyricsKeepTheLengthOfTheRecordingTheyWereTimedOn() {
        val lyrics = assertNotNull(lyricsOf(record(duration = 215.4))) as Lyrics.Synced
        assertEquals(215.4, lyrics.durationSeconds)
        assertEquals(215_400L, lyrics.durationMs)
    }

    @Test
    fun shiftingKeepsTheLength() {
        val shifted = synced(200.0).shiftedBy(3_000L) as Lyrics.Synced
        assertEquals(200.0, shifted.durationSeconds)
        assertEquals(4_000L, shifted.lines.single().timeMs)
    }

    @Test
    fun theAlternativesAreTheTimedVersionsOfThisArtistOnePerLength() {
        val found = syncedAlternatives(
            listOf(
                record(duration = 200.0),
                record(duration = 200.4), // same length to the second: one version
                record(duration = 214.0),
                record(duration = 190.0, synced = null), // plain words only: nothing to time
                record(duration = 0.0), // no length: cannot be told apart
                record(artist = "Someone Else", duration = 230.0),
            ),
            artist = "Ren",
        )
        assertEquals(listOf(200_000L, 214_000L), found.map { it.durationMs })
    }

    @Test
    fun aStreamTheLyricsAlreadyFitKeepsThem() {
        val primary = synced(200.0)
        assertNull(pickLyricsForStream(primary, listOf(synced(214.0)), playedDurationMs = 201_500L))
    }

    @Test
    fun aVideoCutLikeAnotherVersionTakesThatVersion() {
        val primary = synced(200.0)
        val video = synced(214.0, "video")
        val live = synced(231.0, "live")
        val picked = pickLyricsForStream(primary, listOf(primary, live, video), playedDurationMs = 213_000L)
        assertSame(video, picked)
    }

    @Test
    fun theClosestMatchingVersionWins() {
        val primary = synced(200.0)
        val near = synced(212.5, "near")
        val nearer = synced(213.8, "nearer")
        assertSame(nearer, pickLyricsForStream(primary, listOf(near, nearer), playedDurationMs = 214_000L))
    }

    @Test
    fun aVersionOnlyNearlyTheStreamsLengthIsNotSwappedIn() {
        // A remix or live take a few seconds off is another performance, not the video's cut.
        val primary = synced(200.0)
        assertNull(pickLyricsForStream(primary, listOf(synced(222.0)), playedDurationMs = 214_000L))
    }

    @Test
    fun withNoLengthsThereIsNothingToChoose() {
        assertNull(pickLyricsForStream(synced(0.0), listOf(synced(214.0)), playedDurationMs = 214_000L))
        assertNull(pickLyricsForStream(synced(200.0), listOf(synced(214.0)), playedDurationMs = 0L))
        assertNull(pickLyricsForStream(synced(200.0), emptyList(), playedDurationMs = 214_000L))
    }

    @Test
    fun theChosenVersionIsTheStreamsOwnCutSoNothingIsAnalysed() {
        val primary = synced(200.0)
        val picked = assertNotNull(pickLyricsForStream(primary, listOf(videoVersion(214.0)), playedDurationMs = 213_000L))
        assertNull(lyricSearchRange(picked.durationMs, 213_000L))
        assertTrue(lyricSearchRange(primary.durationMs, 213_000L) != null)
    }

    private fun videoVersion(duration: Double) = synced(duration, "video")
}
