package com.nuvio.app.features.preroll

import com.nuvio.app.features.trailer.TrailerPlaybackSource
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * A constant volume for music and trailers: a fixed gain per stream, then a gentle compressor and a limiter.
 *
 * `loudnorm` in one pass cannot know what is coming, so it rides the gain: a quiet intro was lifted, and when the
 * band came in the gain was pulled down again over the next ten seconds (the "starts loud, then dips" on a song like
 * Virtual Insanity). Here the level between sources is set once, before the first sample, from what is known about the
 * stream (YouTube's own loudness figure, a measurement kept from an earlier play, else what the kind of programme
 * usually is), and nothing rides afterwards. What is left inside a stream is handled by a compressor and limiter with
 * no memory of the gain, so there is nothing to pump.
 *
 * The target is [LOUDNESS_TARGET_LUFS] (LoudnessTarget.kt): the film's level, which stays untouched.
 */

/** What kind of programme a stream is: the assumption made when nothing is known about it, and how hard it is held. */
internal enum class LoudnessClass(
    /** Integrated loudness assumed for a stream nothing is known about: typical of the class as mastered. */
    val assumedLufs: Double,
    /** Compressor threshold in dBFS, after the gain (the programme then sits at [LOUDNESS_TARGET_LUFS]). */
    val compressorThresholdDb: Int,
    val compressorRatio: Double,
) {
    /** Album masters sit around -9 to -14 LUFS. Held lightly, so a song keeps its own dynamics. */
    Music(assumedLufs = -11.0, compressorThresholdDb = -20, compressorRatio = 2.0),

    /** Trailers are mastered hot with loud bangs; held harder, so a finale cannot spike. */
    Trailer(assumedLufs = -12.0, compressorThresholdDb = -22, compressorRatio = 4.0),
}

/** The loudness YouTube normalises to; its per-stream `loudnessDb` is the offset from this. */
internal const val YOUTUBE_REFERENCE_LUFS = -14.0

/** The most a stream is turned up or down by a plan. Beyond this the figure is not believed. */
internal const val MAX_GAIN_DB = 9.0
internal const val MIN_GAIN_DB = -30.0

/** Linear sample ceiling of the limiter, about -1.9 dBFS. */
internal const val LIMITER_CEILING = 0.8

/**
 * The integrated loudness YouTube's `loudnessDb` implies, or null when it is missing or not believable. YouTube
 * reports how far the stream is above its reference, and turns it down by that much.
 */
internal fun lufsFromYouTube(loudnessDb: Double?): Double? =
    loudnessDb?.takeIf { it.isFinite() && it in -20.0..20.0 }?.let { YOUTUBE_REFERENCE_LUFS + it }

/** The gain in dB that brings a stream of [lufs] (or the class's assumption) to [target]. */
internal fun planGainDb(
    lufs: Double?,
    loudnessClass: LoudnessClass,
    target: Double = LOUDNESS_TARGET_LUFS.toDouble(),
): Double = (target - (lufs ?: loudnessClass.assumedLufs)).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)

/**
 * How far to move from a gain already applied ([appliedDb]) to the one a better figure gives ([wantedDb]), or null when
 * the difference is too small to be worth a change. Never more than a boost the limiter, which sits before it, could
 * not have protected.
 */
internal fun gainCorrectionDb(appliedDb: Double, wantedDb: Double): Double? {
    val delta = wantedDb - appliedDb
    return if (abs(delta) < MIN_CORRECTION_DB) null else delta.coerceIn(-MAX_CORRECTION_DB, MAX_CORRECTION_DB)
}

private const val MIN_CORRECTION_DB = 1.0
private const val MAX_CORRECTION_DB = 12.0

/** A dB figure to one decimal in plain notation (never `1.0E-4`), as ffmpeg and mpv read it. */
internal fun formatDb(db: Double): String {
    val tenths = (db * 10).roundToInt()
    if (tenths == 0) return "0"
    val sign = if (tenths < 0) "-" else ""
    val magnitude = abs(tenths)
    return "$sign${magnitude / 10}.${magnitude % 10}"
}

/**
 * The libavfilter expressions, in order: the fixed gain, a gentle compressor (30 ms attack, 400 ms release) and a
 * limiter. The limiter's `level=disabled` matters: left on, it normalises the output back up to full scale.
 */
internal fun staticLoudnessChain(loudnessClass: LoudnessClass, gainDb: Double): List<String> = listOf(
    "volume=${formatDb(gainDb)}dB",
    "acompressor=threshold=${loudnessClass.compressorThresholdDb}dB:ratio=${loudnessClass.compressorRatio}" +
        ":attack=30:release=400:makeup=1",
    "alimiter=limit=$LIMITER_CEILING:attack=5:release=60:level=disabled",
)

/** [staticLoudnessChain] as the value of mpv's `af` option, with [extra] expressions (a fade, say) after it. */
internal fun loudnessAfValue(loudnessClass: LoudnessClass, gainDb: Double, extra: List<String> = emptyList()): String =
    mpvAudioFilterValue(*(staticLoudnessChain(loudnessClass, gainDb) + extra).toTypedArray())

/** The chain for a trailer played from [sourceUrl]: YouTube's figure for it when the resolver saw one, else the class's. */
internal fun trailerLoudnessChain(sourceUrl: String?): List<String> =
    staticLoudnessChain(
        LoudnessClass.Trailer,
        planGainDb(StreamLoudnessBook.lufsFor(sourceUrl), LoudnessClass.Trailer),
    )

/** Every URL a source can be opened from, its fallbacks included. */
internal fun TrailerPlaybackSource.streamUrls(): List<String> =
    generateSequence(this) { it.fallback }
        .flatMap { sequenceOf(it.videoUrl, it.audioUrl) }
        .filterNotNull()
        .filter { it.isNotBlank() }
        .toList()

/**
 * What YouTube said about each stream's loudness, by URL, so a surface that is only handed a URL can still plan its gain.
 * Kept in memory only (URLs expire within hours). Copy-on-write; a lost update only means a stream is planned from the
 * class's assumption instead.
 */
internal object StreamLoudnessBook {
    private const val CAPACITY = 300

    @Volatile
    private var lufsByUrl: Map<String, Double> = emptyMap()

    /** Notes [loudnessDb] (YouTube's figure) for each of [urls]. Nothing is kept for a figure that is not believable. */
    fun record(urls: Iterable<String>, loudnessDb: Double?) {
        val lufs = lufsFromYouTube(loudnessDb) ?: return
        val next = LinkedHashMap(lufsByUrl)
        for (url in urls) {
            next.remove(url)
            next[url] = lufs
        }
        while (next.size > CAPACITY) next.remove(next.keys.first())
        lufsByUrl = next
    }

    /** The loudness noted for the first of [urls] that has one. */
    fun lufsFor(vararg urls: String?): Double? {
        val book = lufsByUrl
        return urls.firstNotNullOfOrNull { url -> url?.let(book::get) }
    }

    internal fun clearForTest() {
        lufsByUrl = emptyMap()
    }
}
