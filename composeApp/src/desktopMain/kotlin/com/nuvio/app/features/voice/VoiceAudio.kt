package com.nuvio.app.features.voice

import java.io.ByteArrayOutputStream
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.TargetDataLine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** fork:voice-control - audio in the form whisper wants: 16 kHz mono floats in -1..1. */
internal object VoiceAudio {
    const val SAMPLE_RATE = 16_000

    /** Rates asked of a microphone, best first; anything but 16 kHz is resampled. */
    private val CAPTURE_RATES = floatArrayOf(16_000f, 48_000f, 44_100f, 32_000f, 22_050f)

    /** Signed 16-bit PCM ([bigEndian] or not) to floats, keeping only the first of [channels]. */
    fun pcm16ToFloats(bytes: ByteArray, length: Int, channels: Int, bigEndian: Boolean): FloatArray {
        val frameSize = 2 * channels
        val frames = length / frameSize
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            val at = i * frameSize
            val lo = if (bigEndian) bytes[at + 1] else bytes[at]
            val hi = if (bigEndian) bytes[at] else bytes[at + 1]
            out[i] = ((hi.toInt() shl 8) or (lo.toInt() and 0xFF)).toShort() / 32768f
        }
        return out
    }

    /**
     * [input] at [fromRate] Hz resampled to [SAMPLE_RATE], through a windowed-sinc low-pass at the
     * new Nyquist (so 48 kHz room noise does not fold into the voice band). Quality is for speech
     * recognition, not listening.
     */
    fun resampleTo16k(input: FloatArray, fromRate: Int): FloatArray {
        if (fromRate == SAMPLE_RATE || input.isEmpty()) return input
        val ratio = fromRate.toDouble() / SAMPLE_RATE
        val outLength = floor(input.size / ratio).toInt()
        val cutoff = minOf(1.0, 1.0 / ratio) // relative to the input's Nyquist
        val halfTaps = 16
        val out = FloatArray(outLength)
        for (i in 0 until outLength) {
            val center = i * ratio
            val first = floor(center).toInt() - halfTaps + 1
            var sum = 0.0
            var weight = 0.0
            for (j in first until first + 2 * halfTaps) {
                if (j < 0 || j >= input.size) continue
                val x = center - j
                val sinc = if (abs(x) < 1e-9) 1.0 else sin(PI * x * cutoff) / (PI * x * cutoff)
                val window = 0.5 + 0.5 * cos(PI * x / halfTaps)
                val w = sinc * window
                sum += input[j] * w
                weight += w
            }
            out[i] = if (weight == 0.0) 0f else (sum / weight).toFloat()
        }
        return out
    }

    /** Root-mean-square of [samples] scaled to roughly 0..1 for a level meter (speech sits around 0.3-0.7). */
    fun level(samples: FloatArray, from: Int = 0, to: Int = samples.size): Float {
        if (to <= from) return 0f
        var sum = 0.0
        for (i in from until to) sum += samples[i] * samples[i]
        return (sqrt(sum / (to - from)) * 6).toFloat().coerceIn(0f, 1f)
    }

    /** Reads an audio file Java can decode (WAV) as 16 kHz mono floats. */
    fun readFile(file: File): FloatArray = AudioSystem.getAudioInputStream(file).use { source ->
        val pcm = toPcm16(source)
        val bytes = pcm.readAllBytes()
        val format = pcm.format
        resampleTo16k(pcm16ToFloats(bytes, bytes.size, format.channels, format.isBigEndian), format.sampleRate.toInt())
    }

    private fun toPcm16(source: AudioInputStream): AudioInputStream {
        val format = source.format
        if (format.encoding == AudioFormat.Encoding.PCM_SIGNED && format.sampleSizeInBits == 16) return source
        val target = AudioFormat(format.sampleRate, 16, format.channels, true, false)
        return AudioSystem.getAudioInputStream(target, source)
    }

    // Java Sound's mixer list is built by jsound.dll through JNI; the native exe needs
    // native-image/java-sound/ for it (without it every Mixer.Info came back null).

    /** Recording devices by mixer name, as Windows names them ("Microphone (USB Audio Device)"). */
    fun microphones(): List<String> = AudioSystem.getMixerInfo().filterNotNull()
        .filter { info ->
            runCatching {
                AudioSystem.getMixer(info).targetLineInfo.any { it.lineClass == TargetDataLine::class.java }
            }.getOrDefault(false)
        }
        // Java Sound lists each device twice on Windows: the DirectSound mixer and a "Port" one
        // (the volume controls) that has no capture line; the filter above drops the Port ones.
        .map { it.name }
        .distinct()

    /** An open, started line on [microphone] (null: the default device) and the format it records in. */
    fun openMicrophone(microphone: String?): Pair<TargetDataLine, AudioFormat> {
        val mixer: Mixer? = microphone?.let { name ->
            AudioSystem.getMixerInfo().filterNotNull().firstOrNull { it.name == name }?.let(AudioSystem::getMixer)
        }
        var lastError: Exception? = null
        for (rate in CAPTURE_RATES) {
            for (channels in intArrayOf(1, 2)) {
                val format = AudioFormat(rate, 16, channels, true, false)
                val info = DataLine.Info(TargetDataLine::class.java, format)
                try {
                    val line = (mixer?.getLine(info) ?: AudioSystem.getLine(info)) as TargetDataLine
                    // ~100 ms of buffer: big enough not to drop audio while the reader is busy.
                    line.open(format, (rate / 10).toInt() * format.frameSize)
                    line.start()
                    return line to format
                } catch (error: Exception) {
                    lastError = error
                }
            }
        }
        throw IllegalStateException(
            if (microphone != null) "Could not open \"$microphone\"" else "No microphone could be opened",
            lastError,
        )
    }

    /**
     * Collects a recording from [line] in 20 ms chunks. With [keep] off nothing is collected (the
     * wake-word listener runs for hours and keeps only the utterances it wants); [samples] is then empty.
     */
    class Recorder(
        private val line: TargetDataLine,
        private val format: AudioFormat,
        private val keep: Boolean = true,
        /** Called once, on the first [close]. */
        private val onClose: () -> Unit = {},
    ) {
        private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        private val raw = ByteArrayOutputStream()
        private val chunk = ByteArray(((format.sampleRate / 50).toInt()) * format.frameSize)

        /** The rate [readChunk]'s samples are at (the microphone's own). */
        val sampleRate: Int get() = format.sampleRate.toInt()

        /** Reads one chunk (blocking ~20 ms) and returns it converted, for the level meter. */
        fun readChunk(): FloatArray {
            val read = line.read(chunk, 0, chunk.size)
            if (read <= 0) return FloatArray(0)
            if (keep) raw.write(chunk, 0, read)
            return pcm16ToFloats(chunk, read, format.channels, format.isBigEndian)
        }

        /** Everything recorded so far, as 16 kHz mono. */
        fun samples(): FloatArray {
            val bytes = raw.toByteArray()
            return resampleTo16k(pcm16ToFloats(bytes, bytes.size, format.channels, format.isBigEndian), format.sampleRate.toInt())
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { line.stop() }
            runCatching { line.close() }
            onClose()
        }
    }
}
