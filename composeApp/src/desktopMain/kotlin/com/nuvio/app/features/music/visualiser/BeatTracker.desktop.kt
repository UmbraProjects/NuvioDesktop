package com.nuvio.app.features.music.visualiser
// FORK UI (Spyder): Nick's Now Playing stage, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.PackagedResources
import com.nuvio.app.features.home.components.OnnxRuntimeNatives
import java.nio.FloatBuffer

/*
 * fork:visualiser - the beat grid from the Beat This! network (Foscarin, Schlüter and Widmer, JKU Linz, ISMIR 2024; MIT), the
 * small model, run with ONNX Runtime as the backdrop framing already is. The signal-processing beat finder could not tell beat
 * one from beat three when the kick plays both (Billie Jean), and read kick and snare unreliably in a full mix; the network was
 * trained on thousands of annotated songs and gives every beat and downbeat directly.
 *
 * It reads the same samples the loudness measurement decodes (48 kHz stereo), made mono and resampled to the network's
 * 22.05 kHz, turned into its log-mel spectrogram (a second small ONNX graph, so the features match the model exactly) about
 * every 30 s of audio, and runs the network over 30 s chunks as Beat This! does (1500 frames with 6 at each edge thrown away),
 * keeping the chunks already done, so asking for the grid again after the next window only runs the new audio.
 * The pipeline follows beat-this-rs (MIT), which was checked against the original PyTorch model.
 */

private val log = Logger.withTag("BeatTracker")

private const val NET_RATE = 22_050
private const val HOP = 441
private const val MELS = 128
private const val MEL_ALIGN = 64
private const val MEL_STRIDE = 1_536

/** The Beat This! models, loaded once. Null when they cannot be (no models in this build, ONNX Runtime will not load). */
private object BeatModels {
    @Volatile private var loaded: Pair<OrtSession, OrtSession>? = null
    @Volatile private var failed = false
    val environment: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    fun get(): Pair<OrtSession, OrtSession>? {
        loaded?.let { return it }
        if (failed) return null
        synchronized(this) {
            loaded?.let { return it }
            if (failed) return null
            return runCatching {
                OnnxRuntimeNatives.install()
                fun session(name: String): OrtSession {
                    val bytes = checkNotNull(PackagedResources.open("beat-tracking/$name")) { "Missing model resource $name" }.use { it.readBytes() }
                    val options = OrtSession.SessionOptions().apply {
                        // Two threads, as the framing uses: it runs beside playback on the HTPC.
                        setIntraOpNumThreads(2)
                        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    }
                    return environment.createSession(bytes, options)
                }
                session("mel_spectrogram.onnx") to session("beat_this_small.onnx")
            }.onFailure {
                failed = true
                log.w(it) { "Beat This! models unavailable; the beat grid falls back to the signal-processing estimate" }
            }.getOrNull()?.also { loaded = it }
        }
    }
}

/**
 * The beats of one track as it is decoded. [add] takes interleaved stereo at [sourceRate]; [grid] gives the grid for what has
 * been read so far (or the whole track, with final = true). Not thread safe: one decode feeds it.
 */
internal class BeatTracker private constructor(
    private val mel: OrtSession,
    private val net: OrtSession,
    sourceRate: Int,
) {
    private val resampler = Resampler(sourceRate, NET_RATE)
    private val melStream = MelStream()
    /** Beat and downbeat logits of the chunks done, by their start frame (the chunks that start on the regular stride). */
    private val chunkCache = HashMap<Int, Pair<FloatArray, FloatArray>>()
    private var lastGridFrames = -1
    private var lastGrid: BeatGrid? = null

    /** Seconds of audio the network has been given. */
    val seconds: Double get() = melStream.frames.toDouble() / BEAT_NET_FPS

    fun add(stereo: FloatArray) {
        val mono = FloatArray(stereo.size / 2) { (stereo[2 * it] + stereo[2 * it + 1]) * 0.5f }
        melStream.push(resampler.push(mono))
    }

    /** The grid for the audio read so far; with [final], the whole track (it flushes the tail, so call it once, at the end). */
    fun grid(final: Boolean): BeatGrid? {
        if (final) {
            melStream.push(resampler.finish())
            melStream.finish()
        }
        // While reading, the audio past the last whole mel window is turned into frames too (not kept), so the first grid comes
        // with the first 15 s window rather than after half a minute.
        if (!final) melStream.provisionalTail()
        val frames = melStream.available
        if (!final && frames == lastGridFrames) return lastGrid
        if (frames < BEAT_NET_CHUNK / 3) return null
        val started = System.nanoTime()
        val (beat, down) = predict(frames, final)
        val beats = pickPeaks(beat, frames)
        val downs = pickPeaks(down, frames)
        val grid = beatGridFrom(beats, downs)
        lastGridFrames = frames
        lastGrid = grid
        if (final) {
            log.i {
                "${"%.0f".format(frames / BEAT_NET_FPS)} s: ${beats.size} beats, ${downs.size} downbeats -> " +
                    (grid?.let { "%.1f BPM, %d beats a bar".format(it.bpm, it.beatsPerBar) } ?: "no grid") +
                    " (last pass ${(System.nanoTime() - started) / 1_000_000} ms)"
            }
        }
        return grid
    }

    /** The network's logits over the first [frames] frames, chunk by chunk as Beat This! splits them. */
    private fun predict(frames: Int, final: Boolean): Pair<FloatArray, FloatArray> {
        val beat = FloatArray(frames) { -1_000f }
        val down = FloatArray(frames) { -1_000f }
        for (start in chunkStarts(frames, final).asReversed()) {
            val regular = (start + BEAT_NET_BORDER) % (BEAT_NET_CHUNK - 2 * BEAT_NET_BORDER) == 0
            // Only a chunk wholly in the kept frames gives the same answer next time.
            val whole = start + BEAT_NET_CHUNK <= melStream.frames
            val cached = if (regular && whole) chunkCache[start] else null
            val (b, d) = cached ?: runChunk(start, frames).also { if (regular && whole) chunkCache[start] = it }
            val valid = b.size - 2 * BEAT_NET_BORDER
            for (i in 0 until valid) {
                val dst = start + BEAT_NET_BORDER + i
                if (dst in 0 until frames) { beat[dst] = b[BEAT_NET_BORDER + i]; down[dst] = d[BEAT_NET_BORDER + i] }
            }
        }
        return beat to down
    }

    private fun runChunk(start: Int, frames: Int): Pair<FloatArray, FloatArray> {
        val from = maxOf(start, 0)
        val to = minOf(start + BEAT_NET_CHUNK, frames)
        val padLeft = maxOf(-start, 0)
        val padRight = maxOf(0, minOf(start + BEAT_NET_CHUNK - frames, BEAT_NET_BORDER))
        val time = padLeft + (to - from) + padRight
        val data = FloatArray(time * MELS)
        melStream.copyFrames(from, to, data, padLeft * MELS)
        val env = BeatModels.environment
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, time.toLong(), MELS.toLong())).use { input ->
            net.run(mapOf("spectrogram" to input)).use { out ->
                fun read(name: String): FloatArray {
                    val t = out.get(name).get() as OnnxTensor
                    val buf = t.floatBuffer
                    return FloatArray(buf.remaining()).also { buf.get(it) }
                }
                return read("beat") to read("downbeat")
            }
        }
    }

    /** The streaming mel spectrogram: windows of [MEL_STRIDE] frames with a [MEL_ALIGN]-frame halo, as beat-this-rs's MelStream. */
    private inner class MelStream {
        private var k = 0
        private var buf = FloatArray(HOP * (MEL_STRIDE + 2 * MEL_ALIGN))
        private var bufLen = 0
        private var bufStart = 0L
        private var total = 0L
        private var out = FloatArray(MELS * 4_096)
        /** Frames kept (from whole windows, or every frame once finished). */
        var frames = 0; private set
        private var finished = false
        private var tail = FloatArray(0)
        private var tailFrames = 0

        /** [frames] plus the provisional tail. */
        val available: Int get() = frames + tailFrames

        private fun windowStartFrame(k: Int): Int = maxOf(0, k * MEL_STRIDE - MEL_ALIGN)
        private fun interiorEnd(k: Int): Long = HOP.toLong() * ((k + 1) * MEL_STRIDE + MEL_ALIGN - 1)

        fun push(samples: FloatArray) {
            if (finished || samples.isEmpty()) return
            if (bufLen + samples.size > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, bufLen + samples.size))
            samples.copyInto(buf, bufLen)
            bufLen += samples.size
            total += samples.size
            while (bufStart + bufLen >= interiorEnd(k)) {
                val o = windowStartFrame(k)
                runWindow(HOP.toLong() * o, interiorEnd(k), (k + 1) * MEL_STRIDE + MEL_ALIGN - o, k * MEL_STRIDE - o, (k + 1) * MEL_STRIDE - o)
                k++
                val nextStart = HOP.toLong() * windowStartFrame(k)
                val drop = (nextStart - bufStart).toInt()
                buf.copyInto(buf, 0, drop, bufLen)
                bufLen -= drop
                bufStart = nextStart
            }
        }

        fun finish() {
            if (finished) return
            finished = true
            tail = FloatArray(0); tailFrames = 0
            if (total == 0L) return
            val o = windowStartFrame(k)
            val all = (1 + total / HOP).toInt()
            runWindow(HOP.toLong() * o, total, all - o, k * MEL_STRIDE - o, all - o)
        }

        /** The frames from the end of the kept ones to the end of what has been read, as the last window would make them. */
        fun provisionalTail() {
            tail = FloatArray(0); tailFrames = 0
            if (finished || total == 0L) return
            val o = windowStartFrame(k)
            val all = (1 + total / HOP).toInt()
            val keep = all - o - (k * MEL_STRIDE - o)
            if (keep <= 0) return
            val kept = frames
            runWindow(HOP.toLong() * o, total, all - o, k * MEL_STRIDE - o, all - o)
            tail = out.copyOfRange(kept * MELS, frames * MELS)
            tailFrames = frames - kept
            frames = kept
        }

        private fun runWindow(start: Long, end: Long, expect: Int, keepFrom: Int, keepTo: Int) {
            val from = (start - bufStart).toInt()
            val length = (end - start).toInt()
            val slice = FloatBuffer.wrap(buf, from, length).slice()
            val env = BeatModels.environment
            OnnxTensor.createTensor(env, slice, longArrayOf(1, length.toLong())).use { input ->
                mel.run(mapOf("audio_pcm" to input)).use { result ->
                    val t = result.get("mel_spectrogram").get() as OnnxTensor
                    val shape = t.info.shape
                    check(shape.size == 3 && shape[2] == MELS.toLong() && shape[1] == expect.toLong()) {
                        "mel graph gave ${shape.toList()} for $length samples, expected $expect frames"
                    }
                    val fb = t.floatBuffer
                    val keep = keepTo - keepFrom
                    if ((frames + keep) * MELS > out.size) out = out.copyOf(maxOf(out.size * 2, (frames + keep) * MELS))
                    fb.position(keepFrom * MELS)
                    fb.get(out, frames * MELS, keep * MELS)
                    frames += keep
                }
            }
        }

        fun copyFrames(from: Int, to: Int, into: FloatArray, at: Int) {
            val keptTo = minOf(to, frames)
            if (keptTo > from) out.copyInto(into, at, from * MELS, keptTo * MELS)
            val tailFrom = maxOf(from, frames)
            if (to > tailFrom) tail.copyInto(into, at + (tailFrom - from) * MELS, (tailFrom - frames) * MELS, (to - frames) * MELS)
        }
    }

    companion object {
        /** A tracker for a track decoded at [sourceRate], or null when the network cannot run here. */
        fun create(sourceRate: Int): BeatTracker? {
            val (mel, net) = BeatModels.get() ?: return null
            return BeatTracker(mel, net, sourceRate)
        }
    }
}
