package com.nuvio.app.features.player.audiosync

import co.touchlab.kermit.Logger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Audio subtitle sync on desktop, for streams AutoSync has no embedded subtitles to sync against.
 *
 * Follows NuvioTV-Reshaped's AudioSubtitleSyncController, minus the parts tied to ExoPlayer: a few
 * dialogue spots chosen by [DialogueSpotPlanner] are decoded one at a time (a headless mpv per spot,
 * see `NativePlayerController.decodeSpeechSpot`, 16 kHz mono), turned into speech probability by
 * Silero VAD ([SpeechAnalyzer]) and handed to [AudioSyncTracker], which applies an early estimate
 * once it repeats and locks once confirmed. mpv has no hook for copying audio as the player
 * downloads it, so the spots are the only evidence: up to [MAX_ROUNDS] rounds of [SPOT_COUNT].
 */
internal object DesktopAudioSubtitleSync {
    private val log = Logger.withTag("AudioSync")

    sealed interface Event {
        /** An early, unconfirmed mapping; it may still move or be withdrawn. */
        data class Estimated(val model: SubtitleSyncModel) : Event

        /** The confirmed mapping. Last event of a run. */
        data class Locked(val model: SubtitleSyncModel) : Event

        /** The early estimate lost its support: back to the file's own timing. */
        data object Withdrawn : Event
    }

    sealed interface Outcome {
        data class Done(val model: SubtitleSyncModel, val confirmed: Boolean) : Outcome

        /** Nothing applied: [reason] for the log. */
        data class NoMatch(val reason: String) : Outcome
    }

    @Volatile
    private var cachedWeights: SileroVadWeights? = null

    private fun weights(): SileroVadWeights = cachedWeights ?: synchronized(this) {
        cachedWeights ?: checkNotNull(javaClass.getResourceAsStream(WEIGHTS_RESOURCE)) { "missing $WEIGHTS_RESOURCE" }
            .use(SileroVadWeights::read)
            .also { cachedWeights = it }
    }

    /**
     * Audio analysed for one stream, kept while it plays so another subtitle starts from it instead
     * of sampling the same spots again (Android keeps its timeline per stream the same way). Only
     * the latest stream is kept.
     */
    private class StreamAudio(val sourceKey: String) {
        val timeline = SpeechTimeline()

        /** Start of every spot read for this stream (guarded by itself). */
        val sampled = ArrayList<Long>()
    }

    @Volatile
    private var streamAudio: StreamAudio? = null

    private fun audioFor(sourceKey: String): StreamAudio = synchronized(this) {
        streamAudio?.takeIf { it.sourceKey == sourceKey } ?: StreamAudio(sourceKey).also { streamAudio = it }
    }

    /**
     * Syncs [dialogue] (startMs, endMs, text in subtitle time) to the audio of stream [sourceKey].
     *
     * @param decode 16 kHz mono PCM of [lengthMs] from media time [startMs]; null when that spot
     *   couldn't be read. Blocking calls are expected; it is invoked from this coroutine.
     * @param bytesPerSecond the source's average bitrate, to size spots to the download budget.
     */
    suspend fun run(
        sourceKey: String,
        dialogue: List<Triple<Long, Long, String>>,
        durationMs: Long,
        bytesPerSecond: Double?,
        positionMs: () -> Long,
        decode: suspend (startMs: Long, lengthMs: Long) -> FloatArray?,
        onEvent: suspend (Event) -> Unit,
    ): Outcome {
        val track = SubtitleSpeechTrack.fromCues(dialogue)
        if (track.size < MIN_TRACK_CUES) return Outcome.NoMatch("only ${track.size} dialogue cues")
        val audio = audioFor(sourceKey)
        val timeline = audio.timeline
        val tracker = AudioSyncTracker(track)
        val hold = EstimateHold()
        val analyzer = SpeechAnalyzer(SileroVad(weights()), timeline)
        val readMs = spotReadMs(bytesPerSecond)
        // The budget covers the stream, whichever subtitles the spots were first read for.
        val maxSpots = if (bytesPerSecond != null && bytesPerSecond > 0) {
            (MAX_BYTES / (bytesPerSecond * readMs / 1_000.0)).toInt().coerceIn(1, MAX_SPOTS)
        } else {
            MAX_SPOTS
        }
        fun sampledCount() = synchronized(audio.sampled) { audio.sampled.size }
        log.i {
            "start cues=${track.size} durationMs=$durationMs readMs=$readMs " +
                "bitrate=${bytesPerSecond?.let { "${(it * 8 / 1_000_000).roundToInt()}Mbps" } ?: "?"} " +
                "maxSpots=$maxSpots cachedSpots=${sampledCount()}"
        }

        var applied: SubtitleSyncModel? = null

        /** Applies one tracker run; non-null ends the sync. */
        suspend fun handle(outcome: AudioSyncTracker.Outcome): Outcome? {
            when (outcome) {
                is AudioSyncTracker.Outcome.Locked -> {
                    log.i { "LOCKED ${outcome.model} ${describe(outcome.estimate)} spots=${sampledCount()}" }
                    hold.clear()
                    onEvent(Event.Locked(outcome.model))
                    return Outcome.Done(outcome.model, confirmed = true)
                }
                is AudioSyncTracker.Outcome.Provisional -> {
                    log.i { "provisional ${outcome.model} ${describe(outcome.estimate)} spots=${sampledCount()}" }
                    hold.clear()
                    applied = outcome.model
                    onEvent(Event.Estimated(outcome.model))
                }
                is AudioSyncTracker.Outcome.Retracted -> {
                    val current = applied
                    if (current != null && hold.onWithdrawn(current, outcome.estimate)) {
                        log.i { "estimate kept on screen through stale withdrawal ${describe(outcome.estimate)}" }
                    } else {
                        log.i { "estimate withdrawn ${describe(outcome.estimate)}" }
                        applied = null
                        onEvent(Event.Withdrawn)
                    }
                }
                is AudioSyncTracker.Outcome.Searching -> {
                    log.d { "searching ${outcome.estimate?.let(::describe) ?: "-"} known=${timeline.knownFrameCount()}" }
                    if (hold.onSearching(outcome.estimate)) {
                        log.i { "held estimate withdrawn ${outcome.estimate?.let(::describe) ?: "-"}" }
                        applied = null
                        onEvent(Event.Withdrawn)
                    }
                }
                else -> Unit
            }
            return null
        }

        /** Without a lock: an estimate the tracker already gave up on goes; a live one stays. */
        suspend fun finish(reason: String): Outcome {
            if (hold.held != null) {
                hold.clear()
                applied = null
                onEvent(Event.Withdrawn)
            }
            val kept = applied
            log.i { "stopped: $reason${if (kept != null) ", keeping estimate $kept" else ""}" }
            return if (kept != null) Outcome.Done(kept, confirmed = false) else Outcome.NoMatch(reason)
        }

        // Audio already analysed for this stream (another subtitle): judge it before reading more.
        if (timeline.knownFrameCount() > 0) {
            handle(tracker.update(timeline, positionMs()))?.let { return it }
        }

        for (round in 0 until MAX_ROUNDS) {
            if (sampledCount() >= maxSpots) return finish("spot budget used (${sampledCount()} spots)")
            // The planner always scores whole SPOT_MS spots and puts their openings on dialogue, so
            // a shorter read on a high-bitrate source still starts on speech (as on Android).
            val alreadySampled = synchronized(audio.sampled) { audio.sampled.toList() }
            val spots = DialogueSpotPlanner.plan(
                listOf(track),
                durationMs,
                SPOT_COUNT * (round + 1) + alreadySampled.size,
                SPOT_MS,
            )
                .filter { spot -> alreadySampled.none { abs(it - spot) < SPOT_MS } }
                .take(SPOT_COUNT)
            if (spots.isEmpty()) break
            log.i { "round ${round + 1} spots=${spots.map { it / 1_000 }}s" }
            for (spot in spots) {
                if (sampledCount() >= maxSpots) return finish("spot budget used (${sampledCount()} spots)")
                currentCoroutineContext().ensureActive()
                val startedAt = System.nanoTime()
                val pcm = decode(spot, readMs)
                val decodeMs = (System.nanoTime() - startedAt) / 1_000_000
                currentCoroutineContext().ensureActive()
                synchronized(audio.sampled) { audio.sampled += spot }
                if (pcm == null || pcm.isEmpty()) {
                    log.i { "spot ${spot / 1_000}s unreadable after ${decodeMs}ms" }
                    continue
                }
                log.d { "spot ${spot / 1_000}s ${pcm.size / SAMPLE_RATE}s of audio in ${decodeMs}ms" }
                analyzer.reset()
                var offset = 0
                while (offset < pcm.size) {
                    currentCoroutineContext().ensureActive()
                    val count = min(BLOCK_SAMPLES, pcm.size - offset)
                    val timeUs = spot * 1_000L + offset * 1_000_000L / SAMPLE_RATE
                    analyzer.accept(pcm.copyOfRange(offset, offset + count), count, SAMPLE_RATE, timeUs)
                    offset += count
                    // As on Android, the tracker runs as audio arrives: an early estimate has to
                    // repeat over newly analysed audio before it is applied.
                    handle(tracker.update(timeline, positionMs()))?.let { return it }
                }
            }
        }
        return finish("no confirmed match after ${sampledCount()} spots")
    }

    /**
     * How much of each spot to read: its share of [TARGET_BYTES], at least [MIN_SPOT_MS] (whole
     * sentences) and at most [SPOT_MS]. Unknown bitrate (local files, torrents) reads it all.
     */
    internal fun spotReadMs(bytesPerSecond: Double?): Long {
        if (bytesPerSecond == null || bytesPerSecond <= 0) return SPOT_MS
        val shareMs = (TARGET_BYTES / SPOT_COUNT / bytesPerSecond * 1_000).toLong()
        return shareMs.coerceIn(MIN_SPOT_MS, SPOT_MS)
    }

    private fun describe(estimate: SubtitleAudioAligner.Estimate): String =
        "scale=${"%.5f".format(estimate.scale)} shift=${estimate.shiftMs.roundToInt()}ms " +
            "peak=${"%.3f".format(estimate.peak)} prominence=${"%.3f".format(estimate.prominence)} " +
            "cues=${estimate.cueCount} speech=${estimate.speechSeconds.roundToInt()}s"

    private const val WEIGHTS_RESOURCE = "/audiosync/silero_vad_v5_16k.bin"
    const val SAMPLE_RATE = 16_000
    private const val BLOCK_SAMPLES = 5 * SAMPLE_RATE
    private const val MIN_TRACK_CUES = 20

    // Android's budget: four 30 s spots, about 150 MB in all, never past 400 MB.
    private const val SPOT_COUNT = 4
    private const val SPOT_MS = 30_000L
    private const val MIN_SPOT_MS = 8_000L
    private const val TARGET_BYTES = 150.0 * 1_000_000
    private const val MAX_BYTES = 400.0 * 1_000_000

    // Android keeps listening to the playing audio after its spots; desktop samples more spots.
    private const val MAX_ROUNDS = 3
    private const val MAX_SPOTS = MAX_ROUNDS * SPOT_COUNT
}
