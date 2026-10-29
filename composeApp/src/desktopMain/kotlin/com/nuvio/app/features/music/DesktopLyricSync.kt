package com.nuvio.app.features.music

import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.audiosync.DesktopAudioSubtitleSync
import com.nuvio.app.features.player.audiosync.SileroVad
import com.nuvio.app.features.player.audiosync.SileroVadWeights
import com.nuvio.app.features.player.audiosync.SpeechAnalyzer
import com.nuvio.app.features.player.audiosync.SpeechTimeline
import com.nuvio.app.features.player.audiosync.SubtitleAudioAligner
import com.nuvio.app.features.player.audiosync.SubtitleSpeechTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * Finds where a song's lyrics sit on the stream that is playing, for [MusicLyricSync].
 *
 * The video autosync's machinery, pointed at lyrics: a stretch of the stream is decoded by a
 * headless mpv ([decodeSpeechSpot] on the surface's controller), Silero VAD turns it into vocal
 * activity per 32 ms, and [SubtitleAudioAligner] correlates that with the stretches each lyric line
 * is sung (from [lyricSungCues]). Only the offset is searched, never a playback-rate change, and
 * only inside the range the stream's length allows (see [lyricSearchRange]).
 */
internal object DesktopLyricSync {
    private val log = Logger.withTag("LyricSync")

    private class Sampler(val loadId: Long, val decode: (startMs: Long, lengthMs: Long) -> FloatArray?)

    @Volatile
    private var sampler: Sampler? = null

    @Volatile
    private var cachedWeights: SileroVadWeights? = null

    private fun weights(): SileroVadWeights = cachedWeights ?: synchronized(this) {
        cachedWeights ?: checkNotNull(javaClass.getResourceAsStream(WEIGHTS_RESOURCE)) { "missing $WEIGHTS_RESOURCE" }
            .use(SileroVadWeights::read)
            .also { cachedWeights = it }
    }

    /** The surface playing load [loadId] can decode its own audio with [decode] (media ms in, 16 kHz mono PCM out). */
    fun attach(loadId: Long, decode: (startMs: Long, lengthMs: Long) -> FloatArray?) {
        sampler = Sampler(loadId, decode)
        MusicLyricSync.audioEstimator = ::estimate
    }

    fun detach(loadId: Long) {
        if (sampler?.loadId == loadId) sampler = null
    }

    /** Heard but not usable: [acceptLyricAlignment] turns it down, so the lyrics are settled as unsure. */
    private fun inconclusive(lines: Int) = LyricAlignment(0.0, 0.0, 0.0, lines, 0.0, atSearchEdge = false)

    private suspend fun estimate(request: LyricAudioRequest): LyricAlignment? {
        val active = sampler?.takeIf { it.loadId == request.loadId } ?: return null
        val track = SubtitleSpeechTrack.fromCues(lyricSungCues(request.lines))
        if (track.size < MIN_LINES) return inconclusive(track.size)
        val window = lyricDecodeWindow(request.lines, request.range, request.playedDurationMs)
            ?: return inconclusive(track.size)
        return withContext(Dispatchers.IO) {
            val startedAt = System.nanoTime()
            val pcm = active.decode(window.fromMs, window.lengthMs)
            val decodeMs = (System.nanoTime() - startedAt) / 1_000_000
            if (pcm == null || pcm.size < MIN_SAMPLES) {
                log.i { "window ${window.fromMs / 1_000}s+${window.lengthMs / 1_000}s unreadable after ${decodeMs}ms" }
                return@withContext null
            }
            log.i { "window ${window.fromMs / 1_000}s+${pcm.size / SAMPLE_RATE}s decoded in ${decodeMs}ms" }
            val timeline = SpeechTimeline()
            val analyzer = SpeechAnalyzer(SileroVad(weights()), timeline)
            analyzer.reset()
            var offset = 0
            while (offset < pcm.size) {
                currentCoroutineContext().ensureActive()
                val count = min(BLOCK_SAMPLES, pcm.size - offset)
                val timeUs = window.fromMs * 1_000L + offset * 1_000_000L / SAMPLE_RATE
                analyzer.accept(pcm.copyOfRange(offset, offset + count), count, SAMPLE_RATE, timeUs)
                offset += count
            }
            val minShift = request.range.first.toDouble()
            val maxShift = request.range.last.toDouble()
            val speech = SubtitleAudioAligner.prepare(timeline.segments(), minShift, maxShift)
                ?: return@withContext inconclusive(track.size)
            val fit = speech.estimate(track, doubleArrayOf(1.0), minShift, maxShift)
                ?: return@withContext inconclusive(track.size)
            LyricAlignment(
                shiftMs = fit.shiftMs,
                peak = fit.peak,
                prominence = fit.prominence,
                lineCount = fit.cueCount,
                voiceSeconds = fit.speechSeconds,
                atSearchEdge = fit.atSearchEdge,
            )
        }
    }

    private const val WEIGHTS_RESOURCE = "/audiosync/silero_vad_v5_16k.bin"
    private const val SAMPLE_RATE = DesktopAudioSubtitleSync.SAMPLE_RATE
    private const val BLOCK_SAMPLES = 5 * SAMPLE_RATE
    private const val MIN_SAMPLES = 10 * SAMPLE_RATE
    private const val MIN_LINES = 8
}
