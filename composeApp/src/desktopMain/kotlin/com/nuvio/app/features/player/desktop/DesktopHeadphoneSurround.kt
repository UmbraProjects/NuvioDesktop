package com.nuvio.app.features.player.desktop

import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.player.DESKTOP_AUDIO_SWRESAMPLE_OVERRIDE_KEY
import com.nuvio.app.features.player.DESKTOP_HEADPHONE_SURROUND_ON
import com.nuvio.app.features.player.DESKTOP_HEADPHONE_SURROUND_OVERRIDE_KEY
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/*
 * Headphone surround: renders a surround track to two ears instead of folding it into plain left
 * and right, so the rear and side channels sound like they come from around you.
 *
 * FFmpeg's `headphone` filter does the convolution; it needs one impulse response per speaker per
 * ear. Measured sets (KEMAR, CIPIC, HeSuVi) are large and come with licences, so the responses are
 * generated here from Brown & Duda's spherical-head model (1998): arrival-time difference between
 * the ears (Woodworth), head shadow as a one-pole/one-zero filter, and a short decaying tail, as
 * from a small room, which is what keeps the sound from sitting inside the head. No pinna model, so
 * front/back is weaker than with a measured set.
 *
 * Version 1 sounded worse than the plain downmix: the shadow filter alone cut the centre channel's
 * treble by ~5 dB (dialogue went dull), and three discrete wall reflections at 5-10 ms comb-filtered
 * everything (4-5 dB of ripple through the midrange). Version 2 equalises the shadow filter so a
 * speaker at the front comes out nearly flat ([SHADOW_REFERENCE_ALPHA]), drops the discrete
 * reflections and keeps only a quieter diffuse tail.
 */

private val log = Logger.withTag("HeadphoneSurround")

/** The order the `headphone` filter's map lists, and the order of channel pairs in the file. */
internal val HEADPHONE_SPEAKERS: List<Pair<String, Double?>> = listOf(
    "FL" to 30.0,
    "FR" to -30.0,
    "FC" to 0.0,
    "LFE" to null,
    "BL" to 150.0,
    "BR" to -150.0,
    "SL" to 90.0,
    "SR" to -90.0,
)

internal object HeadphoneHrirModel {
    const val SAMPLE_RATE = 48_000
    /** 100 ms: direct sound and the tail. */
    const val LENGTH = 4_800
    /** Bump when the model changes so installed copies are regenerated. */
    const val VERSION = 2

    private const val HEAD_RADIUS_M = 0.0875
    private const val SPEED_OF_SOUND = 343.0
    /** Ears sit a little behind the head's left-right axis. */
    private val EAR_AZIMUTH = Math.toRadians(100.0)
    /** Samples before the earliest arrival, so the windowed-sinc delay has room. */
    private const val LEAD = 48
    private const val TAIL_START_S = 0.012
    private const val TAIL_END_S = 0.095
    private const val TAIL_T60_S = 0.25
    /** About 12 dB below the direct sound: enough room to externalise, little colour. */
    private const val TAIL_GAIN = 0.014
    private const val TAIL_LOWPASS_HZ = 5_000.0

    /**
     * The shadow filter's treble gain is alpha relative to this. 0.7 sits between the centre
     * speaker (alpha 0.575 at both ears) and a front pair's near/far average (~0.82), so dialogue
     * and a phantom centre both stay within about 1.5 dB of flat; the near ear for side and rear
     * speakers comes out brighter, as it does in a real room.
     */
    private const val SHADOW_REFERENCE_ALPHA = 0.7

    /** Two channels (left ear, right ear) per entry of [HEADPHONE_SPEAKERS]. */
    fun generate(): List<DoubleArray> {
        val random = Random(0x6e75L)
        return HEADPHONE_SPEAKERS.flatMap { (_, azimuthDegrees) ->
            if (azimuthDegrees == null) {
                // LFE: no direction, the same for both ears, 6 dB down like a usual fold-down.
                val lfe = DoubleArray(LENGTH)
                addFractionalImpulse(lfe, LEAD + HEAD_RADIUS_M / SPEED_OF_SOUND * SAMPLE_RATE, 0.5)
                listOf(lfe, lfe.copyOf())
            } else {
                val azimuth = Math.toRadians(azimuthDegrees)
                listOf(EAR_AZIMUTH, -EAR_AZIMUTH).map { ear -> speakerAtEar(azimuth, ear, random) }
            }
        }
    }

    private fun speakerAtEar(azimuth: Double, ear: Double, random: Random): DoubleArray {
        val direct = earResponse(azimuth, ear)
        val room = DoubleArray(LENGTH)
        val tailStart = (TAIL_START_S * SAMPLE_RATE).toInt()
        val tailEnd = (TAIL_END_S * SAMPLE_RATE).toInt()
        val fadeSamples = 0.01 * SAMPLE_RATE
        for (i in tailStart until tailEnd) {
            val seconds = i.toDouble() / SAMPLE_RATE
            val fade = minOf(1.0, (tailEnd - i) / fadeSamples)
            room[i] += random.nextGaussian() * TAIL_GAIN * 10.0.pow(-3.0 * seconds / TAIL_T60_S) * fade
        }
        val softened = lowpass(room, TAIL_LOWPASS_HZ)
        return DoubleArray(LENGTH) { direct[it] + softened[it] }
    }

    private fun earResponse(azimuth: Double, ear: Double): DoubleArray {
        val theta = angleBetween(azimuth, ear)
        val buffer = DoubleArray(LENGTH)
        addFractionalImpulse(buffer, LEAD + arrivalDelaySeconds(theta) * SAMPLE_RATE, 1.0)
        return headShadow(buffer, theta)
    }

    /** Angle in [0, π] between a source and an ear. */
    internal fun angleBetween(a: Double, b: Double): Double {
        val wrapped = ((a - b + PI) % (2 * PI) + 2 * PI) % (2 * PI) - PI
        return abs(wrapped)
    }

    /** Woodworth's formula, relative to a source straight at the ear. */
    internal fun arrivalDelaySeconds(theta: Double): Double {
        val radiusTime = HEAD_RADIUS_M / SPEED_OF_SOUND
        return if (theta < PI / 2) radiusTime * (1 - cos(theta)) else radiusTime * (1 + theta - PI / 2)
    }

    /**
     * Brown & Duda's head-shadow filter, bilinear-transformed: treble drops as the ear turns away.
     * Divided by the same filter at [SHADOW_REFERENCE_ALPHA] (same pole), so it is a shelf around
     * a neutral front rather than a treble cut on everything.
     */
    private fun headShadow(input: DoubleArray, theta: Double): DoubleArray {
        val alphaMin = 0.1
        val thetaMin = Math.toRadians(150.0)
        val alpha = (1 + alphaMin / 2) + (1 - alphaMin / 2) * cos(theta / thetaMin * PI)
        val tau = HEAD_RADIUS_M / (2 * SPEED_OF_SOUND)
        val k = 2.0 * SAMPLE_RATE
        val reference = SHADOW_REFERENCE_ALPHA
        val b0 = (1 + alpha * tau * k) / (1 + reference * tau * k)
        val b1 = (1 - alpha * tau * k) / (1 + reference * tau * k)
        val a1 = (1 - reference * tau * k) / (1 + reference * tau * k)
        val output = DoubleArray(input.size)
        var x1 = 0.0
        var y1 = 0.0
        for (i in input.indices) {
            val y = b0 * input[i] + b1 * x1 - a1 * y1
            x1 = input[i]
            y1 = y
            output[i] = y
        }
        return output
    }

    private fun lowpass(input: DoubleArray, cutoffHz: Double): DoubleArray {
        val pole = exp(-2 * PI * cutoffHz / SAMPLE_RATE)
        var state = 0.0
        return DoubleArray(input.size) { i ->
            state = (1 - pole) * input[i] + pole * state
            state
        }
    }

    /** A Hann-windowed sinc, so delays between whole samples keep their timing. */
    private fun addFractionalImpulse(buffer: DoubleArray, position: Double, gain: Double) {
        val whole = floor(position).toInt()
        val fraction = position - whole
        for (k in -16 until 16) {
            val index = whole + k
            if (index !in buffer.indices) continue
            val t = k - fraction
            val sinc = if (t == 0.0) 1.0 else sin(PI * t) / (PI * t)
            val window = if (abs(t) < 16) 0.5 + 0.5 * cos(PI * t / 16) else 0.0
            buffer[index] += gain * sinc * window
        }
    }

    /** 32-bit float WAV, channels interleaved. */
    fun wavBytes(channels: List<DoubleArray>): ByteArray {
        val channelCount = channels.size
        val frames = channels.first().size
        val dataBytes = frames * channelCount * 4
        val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16)
            .putShort(3) // IEEE float
            .putShort(channelCount.toShort())
            .putInt(SAMPLE_RATE)
            .putInt(SAMPLE_RATE * channelCount * 4)
            .putShort((channelCount * 4).toShort())
            .putShort(32)
        buffer.put("data".toByteArray()).putInt(dataBytes)
        for (frame in 0 until frames) {
            for (channel in channels) buffer.putFloat(channel[frame].toFloat())
        }
        return buffer.array()
    }
}

/**
 * The generated responses on disk, written on first use. Null when the file can't be written; the
 * caller then plays without headphone surround rather than with a broken filter.
 */
internal object HeadphoneHrirFile {
    @Volatile
    private var cached: File? = null

    fun path(): String? = runCatching { ensure().absolutePath }
        .onFailure { log.w(it) { "Headphone surround responses unavailable" } }
        .getOrNull()

    @Synchronized
    private fun ensure(): File {
        cached?.takeIf { it.isFile }?.let { return it }
        val directory = DesktopStorage.rootDir.resolve("player").toFile().apply { mkdirs() }
        val file = File(directory, "headphone-hrir-v${HeadphoneHrirModel.VERSION}.wav")
        val bytes = HeadphoneHrirModel.wavBytes(HeadphoneHrirModel.generate())
        if (!file.isFile || file.length() != bytes.size.toLong()) {
            val staging = File(directory, "${file.name}.tmp")
            staging.writeBytes(bytes)
            Files.move(staging.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        cached = file
        return file
    }
}

/**
 * Calibrated so a 7.1 track plays about as loud as mpv's own stereo downmix of it (~2 dB above).
 * The filter lowers its output 3 dB per input channel and every source is converted to 7.1 first,
 * so a stereo source comes out ~5 dB quieter than it plays without headphone surround; the
 * alternative, feeding each source's own layout, makes stereo 11 dB louder than 7.1.
 */
private const val HEADPHONE_GAIN_DB = 14

/**
 * Phantom-centre correction. Sound shared by the front pair reaches each ear twice, the far copy
 * ~0.27 ms late, and the two cancel into a ~7 dB dip around 1 kHz: most of a stereo mix sounded
 * hollow. The pair is split into mid and side, the mid gets this lift, and it is put back together,
 * so only what the two channels share is lifted (dip down to ~3 dB) and hard-panned sound moves by
 * about 2 dB. The round trip is exact without the EQ.
 */
private const val PHANTOM_CENTRE_EQ = "equalizer=f=1300:t=q:w=1:g=6:c=FL"

/** Menu rows whose change rebuilds `af` mid-playback. */
internal val DESKTOP_AUDIO_FILTER_CHAIN_KEYS = setOf(
    "af",
    DESKTOP_HEADPHONE_SURROUND_OVERRIDE_KEY,
    DESKTOP_AUDIO_SWRESAMPLE_OVERRIDE_KEY,
)

/** libswresample's default centre level; the dialogue boost choice is relative to this. */
private const val DEFAULT_CENTER_MIX_LEVEL = 0.7071

/**
 * The `af` value for the main player: the Audio profile choice, then headphone surround when it is
 * on. Empty when neither is set.
 */
internal fun desktopAudioFilterChain(
    overrides: Map<String, String>,
    hrirPath: () -> String? = HeadphoneHrirFile::path,
): String {
    val profile = overrides["af"].orEmpty().trim()
    val headphone = if (overrides[DESKTOP_HEADPHONE_SURROUND_OVERRIDE_KEY] == DESKTOP_HEADPHONE_SURROUND_ON) {
        hrirPath()?.let { path ->
            headphoneSurroundFilter(path, centerGain = dialogueCenterGain(overrides[DESKTOP_AUDIO_SWRESAMPLE_OVERRIDE_KEY]))
        }
    } else {
        null
    }
    return listOfNotNull(profile.takeIf { it.isNotEmpty() }, headphone).joinToString(",")
}

/**
 * Dialogue boost works on mpv's downmix, which headphone surround replaces, so the same choice
 * becomes a centre-channel gain ahead of the filter.
 */
internal fun dialogueCenterGain(swresampleOptions: String?): Double {
    val level = swresampleOptions
        ?.split(',')
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("center_mix_level=") }
        ?.substringAfter('=')
        ?.toDoubleOrNull()
        ?: return 1.0
    return (level / DEFAULT_CENTER_MIX_LEVEL).coerceIn(0.25, 4.0)
}

internal fun headphoneSurroundFilter(hrirPath: String, centerGain: Double): String {
    val map = HEADPHONE_SPEAKERS.joinToString("|") { it.first }
    val center = if (abs(centerGain - 1.0) < 0.01) "FC" else "%.3f*FC".format(java.util.Locale.ROOT, centerGain)
    // Front pair to mid (in FL) and side (in FR), with the dialogue gain on the centre; then back.
    val toMidSide = sevenOnePan(
        "FL" to "0.5*FL+0.5*FR",
        "FR" to "0.5*FL-0.5*FR",
        "FC" to center,
    )
    val fromMidSide = sevenOnePan("FL" to "FL+FR", "FR" to "FL-FR")
    val prepare = listOf("aformat=channel_layouts=7.1", toMidSide, PHANTOM_CENTRE_EQ, fromMidSide).joinToString(",")
    val graph = "$prepare[main];" +
        "amovie=${quoteLavfiValue(hrirPath)}[hrir];" +
        "[main][hrir]headphone=map=$map:hrir=multich:type=freq:gain=$HEADPHONE_GAIN_DB"
    // mpv's %bytes% quoting: the graph's commas, brackets and colons pass through untouched.
    return "lavfi=graph=%${graph.encodeToByteArray().size}%$graph"
}

private fun sevenOnePan(vararg routes: Pair<String, String>): String {
    val byName = routes.toMap()
    return "pan=7.1|" + HEADPHONE_SPEAKERS.joinToString("|") { (name, _) -> "$name=${byName[name] ?: name}" }
}

/** A filter option value in single quotes: `:` escaped for the option parser, `'` closed and reopened. */
internal fun quoteLavfiValue(path: String): String {
    val forward = path.replace('\\', '/')
    val escaped = forward.replace(":", "\\:").replace("'", "'\\''")
    return "'$escaped'"
}
