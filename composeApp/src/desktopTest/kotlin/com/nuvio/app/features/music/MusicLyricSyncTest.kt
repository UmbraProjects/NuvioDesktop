package com.nuvio.app.features.music

import com.nuvio.app.features.player.audiosync.SpeechSegment
import com.nuvio.app.features.player.audiosync.SpeechTimeline
import com.nuvio.app.features.player.audiosync.SubtitleAudioAligner
import com.nuvio.app.features.player.audiosync.SubtitleSpeechTrack
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MusicLyricSyncTest {
    /** Irregular gaps and a rest between verses, like a real song: evenly spaced lines would line up at every multiple. */
    private val lines: List<LyricLine> = run {
        val random = Random(7)
        var at = 14_300L
        buildList {
            repeat(44) { index ->
                add(LyricLine(at, "line number $index of the song" + " la".repeat(random.nextInt(3))))
                at += 2_200L + random.nextInt(3_800)
                if (index % 11 == 10) {
                    add(LyricLine(at, ""))
                    at += 9_000L
                }
            }
        }
    }

    /** What the disk would hold: the tests never touch the real saved offsets. */
    private var disk: String? = null

    @BeforeTest
    fun installMemoryStorage() {
        disk = null
        MusicLyricSync.loadSavedPayload = { disk }
        MusicLyricSync.saveSavedPayload = { disk = it }
        MusicLyricSync.restartSession()
    }

    @AfterTest
    fun uninstall() {
        MusicLyricSync.audioEstimator = null
        MusicLyricSync.loadSavedPayload = LyricSyncStorage::load
        MusicLyricSync.saveSavedPayload = LyricSyncStorage::save
        MusicLyricSync.restartSession()
    }

    private fun alignment(shiftMs: Double, peak: Double = 0.7, prominence: Double = 0.4) =
        LyricAlignment(shiftMs, peak, prominence, lineCount = 16, voiceSeconds = 30.0, atSearchEdge = false)

    private fun sync(key: String, track: Long, played: Long, loadId: Long = 1L) = runBlocking {
        MusicLyricSync.sync(key, lines, track, played, loadId)
    }

    @Test
    fun theSameCutIsSettledWithoutReadingAudio() {
        var asked = false
        MusicLyricSync.audioEstimator = { asked = true; null }
        sync("same:audio", 200_000L, 200_500L)
        assertEquals(LyricSyncState.Offset(0L, auto = true), MusicLyricSync.stateOf("same:audio"))
        assertTrue(!asked)
    }

    @Test
    fun aLongerStreamIsAnalysedAndTheOffsetKept() {
        var request: LyricAudioRequest? = null
        MusicLyricSync.audioEstimator = { request = it; alignment(12_300.0) }
        sync("long:video", 200_000L, 212_000L, loadId = 7L)
        assertEquals(LyricSyncState.Offset(12_300L, auto = true), MusicLyricSync.stateOf("long:video"))
        assertEquals(7L, assertNotNull(request).loadId)
        assertEquals(212_000L, request!!.playedDurationMs)
    }

    @Test
    fun aStreamThatCouldNotBeReadStaysOpenForAnotherTry() {
        MusicLyricSync.audioEstimator = { null }
        sync("unread:video", 200_000L, 212_000L)
        assertEquals(LyricSyncState.Idle, MusicLyricSync.stateOf("unread:video"))
        MusicLyricSync.audioEstimator = { alignment(12_000.0) }
        sync("unread:video", 200_000L, 212_000L)
        assertEquals(LyricSyncState.Offset(12_000L, auto = true), MusicLyricSync.stateOf("unread:video"))
    }

    @Test
    fun anUnconvincingAnswerLeavesTheLyricsAlone() {
        MusicLyricSync.audioEstimator = { alignment(9_000.0, peak = 0.04, prominence = 0.02) }
        sync("weak:video", 200_000L, 212_000L)
        assertEquals(LyricSyncState.Unsure, MusicLyricSync.stateOf("weak:video"))
        assertEquals(0L, MusicLyricSync.stateOf("weak:video").offsetMs())
    }

    @Test
    fun aFailingAnalysisIsNotFatal() {
        MusicLyricSync.audioEstimator = { error("boom") }
        sync("fail:video", 200_000L, 212_000L)
        assertEquals(LyricSyncState.Idle, MusicLyricSync.stateOf("fail:video"))
    }

    @Test
    fun anAnsweredStreamIsNotAnalysedAgain() {
        var calls = 0
        MusicLyricSync.audioEstimator = { calls++; alignment(12_000.0) }
        sync("once:video", 200_000L, 212_000L)
        sync("once:video", 200_000L, 212_000L)
        assertEquals(1, calls)
    }

    @Test
    fun nudgesStackOnTopOfWhatWasFound() {
        MusicLyricSync.audioEstimator = { alignment(12_000.0) }
        sync("nudge:video", 200_000L, 212_000L)
        MusicLyricSync.nudge("nudge:video", 500L)
        MusicLyricSync.nudge("nudge:video", 500L)
        val state = assertIs<LyricSyncState.Offset>(MusicLyricSync.stateOf("nudge:video"))
        assertEquals(13_000L, state.offsetMs)
        assertTrue(!state.auto)
        MusicLyricSync.reset("nudge:video")
        assertEquals(0L, MusicLyricSync.stateOf("nudge:video").offsetMs())
    }

    @Test
    fun aNudgeWithNothingFoundStartsFromZero() {
        MusicLyricSync.nudge("manual:audio", -500L)
        assertEquals(-500L, MusicLyricSync.stateOf("manual:audio").offsetMs())
    }

    @Test
    fun aNudgeIsStillThereNextTimeTheSongPlays() {
        sync("saved:video", 200_000L, 200_000L) // the same cut, so settled without audio
        MusicLyricSync.nudge("saved:video", 1_500L)
        MusicLyricSync.restartSession()
        assertEquals(LyricSyncState.Idle, MusicLyricSync.stateOf("saved:video"))
        var asked = false
        MusicLyricSync.audioEstimator = { asked = true; null }
        sync("saved:video", 200_000L, 212_000L) // a stream of another length would normally be analysed
        assertTrue(!asked)
        val state = assertIs<LyricSyncState.Offset>(MusicLyricSync.stateOf("saved:video"))
        assertEquals(1_500L, state.offsetMs)
        assertTrue(!state.auto)
    }

    @Test
    fun aTapIsKeptToo() {
        sync("tap:video", 200_000L, 200_000L)
        MusicLyricSync.tapSync("tap:video", shownLineTimeMs = 14_300L, positionMs = 26_650L)
        assertEquals(12_000L, MusicLyricSync.stateOf("tap:video").offsetMs())
        MusicLyricSync.restartSession()
        sync("tap:video", 200_000L, 212_000L)
        assertEquals(12_000L, MusicLyricSync.stateOf("tap:video").offsetMs())
    }

    @Test
    fun aSavedOffsetForOtherLyricsIsNotUsed() {
        sync("other:video", 200_000L, 200_000L)
        MusicLyricSync.nudge("other:video", 2_000L)
        MusicLyricSync.restartSession()
        MusicLyricSync.audioEstimator = { alignment(12_300.0) }
        sync("other:video", 214_000L, 226_300L) // lyrics of another length now: the person's old fix does not apply
        assertEquals(LyricSyncState.Offset(12_300L, auto = true), MusicLyricSync.stateOf("other:video"))
    }

    @Test
    fun resettingForgetsTheSavedOffsetToo() {
        sync("forget:video", 200_000L, 200_000L)
        MusicLyricSync.nudge("forget:video", 1_000L)
        MusicLyricSync.reset("forget:video")
        MusicLyricSync.restartSession()
        MusicLyricSync.audioEstimator = { alignment(12_000.0) }
        sync("forget:video", 200_000L, 212_000L)
        assertEquals(LyricSyncState.Offset(12_000L, auto = true), MusicLyricSync.stateOf("forget:video"))
    }

    @Test
    fun thePlatformEstimatorIsNeverAskedWithoutOne() {
        assertNull(MusicLyricSync.audioEstimator)
        sync("none:video", 200_000L, 212_000L)
        assertEquals(LyricSyncState.Idle, MusicLyricSync.stateOf("none:video"))
    }

    /**
     * The real aligner on vocal activity shaped like a detector's: sung stretches high, noise and
     * false bursts elsewhere. Pins the sign and units of the shift it reports for lyrics.
     */
    private fun voiceFrames(trueOffset: Long, totalMs: Long, seed: Int): FloatArray {
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        val n = (totalMs / frameMs).toInt()
        val rnd = Random(seed)
        val p = FloatArray(n) { (0.08 + rnd.nextDouble() * 0.25).toFloat() }
        for ((start, end, _) in lyricSungCues(lines)) {
            val from = ((start + trueOffset + 100) / frameMs).toInt()
            val to = ((start + trueOffset + (end - start) * 0.8 + 100) / frameMs).toInt()
            for (f in from until to) if (f in 0 until n) p[f] = (0.75 + rnd.nextDouble() * 0.2).toFloat()
        }
        repeat(20) {
            val at = rnd.nextInt(n - 60)
            for (f in at until at + 20 + rnd.nextInt(30)) p[f] = (0.5 + rnd.nextDouble() * 0.3).toFloat()
        }
        return p
    }

    private fun align(trueOffset: Long, trackMs: Long, playedMs: Long): LyricAlignment {
        val range = assertNotNull(lyricSearchRange(trackMs, playedMs))
        val window = assertNotNull(lyricDecodeWindow(lines, range, playedMs))
        val all = voiceFrames(trueOffset, playedMs, seed = trueOffset.toInt())
        val fromFrame = Math.floorDiv(window.fromMs * 1_000L, SpeechTimeline.FRAME_DURATION_US).toInt()
        val count = (window.lengthMs / SpeechTimeline.FRAME_DURATION_MS).toInt().coerceAtMost(all.size - fromFrame)
        val speech = assertNotNull(
            SubtitleAudioAligner.prepare(
                listOf(SpeechSegment(fromFrame, all.copyOfRange(fromFrame, fromFrame + count))),
                range.first.toDouble(),
                range.last.toDouble(),
            ),
        )
        val track = SubtitleSpeechTrack.fromCues(lyricSungCues(lines))
        val fit = assertNotNull(speech.estimate(track, doubleArrayOf(1.0), range.first.toDouble(), range.last.toDouble()))
        return LyricAlignment(fit.shiftMs, fit.peak, fit.prominence, fit.cueCount, fit.speechSeconds, fit.atSearchEdge)
    }

    @Test
    fun theAlignerFindsAnIntroOnTheStream() {
        val found = assertNotNull(acceptLyricAlignment(align(12_400L, 200_000L, 212_400L)))
        assertTrue(kotlin.math.abs(found - 12_400L) <= 500L, "found $found")
    }

    @Test
    fun theAlignerFindsALongIntro() {
        val found = assertNotNull(acceptLyricAlignment(align(45_000L, 200_000L, 245_000L)))
        assertTrue(kotlin.math.abs(found - 45_000L) <= 500L, "found $found")
    }

    @Test
    fun theAlignerSeesAnUnshiftedStreamWithALongerTail() {
        assertEquals(0L, acceptLyricAlignment(align(0L, 200_000L, 230_000L)))
    }

    @Test
    fun theAlignerFindsAnEarlierStartOnAShorterStream() {
        val found = assertNotNull(acceptLyricAlignment(align(-1_500L, 200_000L, 190_000L)))
        assertTrue(kotlin.math.abs(found + 1_500L) <= 500L, "found $found")
    }
}
