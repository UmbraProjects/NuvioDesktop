package com.nuvio.app.features.music

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.profiles.ProfilePinCrypto
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/*
 * fork:music
 *
 * SponsorBlock (https://sponsor.ajay.app): segments of YouTube videos marked by its users. Music asks only for
 * "music_offtopic", the parts of a music video that are not the song (a spoken intro, a skit, a closing scene), so the
 * player can skip them when Settings > Music > Skip non-music parts is on.
 *
 * Looked up by the first four characters of the id's SHA-256 (SponsorBlock's privacy lookup): the server answers for
 * every video sharing that prefix, and never learns which one is playing.
 */

private val log = Logger.withTag("MusicSponsorBlock")

private val SPONSOR_BLOCK_JSON = Json { ignoreUnknownKeys = true }

private const val SPONSOR_BLOCK_API = "https://sponsor.ajay.app/api/skipSegments/"
private const val SPONSOR_BLOCK_CATEGORY = "music_offtopic"
private const val HASH_PREFIX_LENGTH = 4

/** Segments shorter than this are not worth a seek (and are usually a mis-mark). */
private const val MIN_SEGMENT_MS = 1_000L

/** Videos remembered for the session; a long listening session cycles through far fewer. */
private const val CACHE_SIZE = 300

/** A part of a video to skip, in milliseconds from its start. */
internal data class MusicSkipSegment(val startMs: Long, val endMs: Long)

internal object MusicSponsorBlock {
    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, List<MusicSkipSegment>>()

    /** What is already known for [videoId] without asking (null when it has not been looked up yet). */
    fun cached(videoId: String): List<MusicSkipSegment>? = cache[videoId]

    /** The non-music segments of [videoId], in order; empty when it has none or SponsorBlock cannot be reached. */
    suspend fun segments(videoId: String): List<MusicSkipSegment> {
        mutex.withLock { cache[videoId] }?.let { return it }
        val prefix = ProfilePinCrypto.sha256Hex(videoId).take(HASH_PREFIX_LENGTH).lowercase()
        val query = "categories=" + "[\"$SPONSOR_BLOCK_CATEGORY\"]".encodeURLParameter() +
            "&actionTypes=" + "[\"skip\"]".encodeURLParameter()
        val found = try {
            parseSponsorBlockSegments(httpGetTextWithHeaders("$SPONSOR_BLOCK_API$prefix?$query", emptyMap()), videoId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // SponsorBlock answers 404 for a prefix with nothing marked, which arrives here as a failure too.
            log.d { "no segments for $videoId: ${e.message}" }
            emptyList()
        }
        mutex.withLock {
            cache[videoId] = found
            if (cache.size > CACHE_SIZE) cache.remove(cache.keys.first())
        }
        if (found.isNotEmpty()) log.i { "$videoId: ${found.size} non-music segment(s)" }
        return found
    }
}

/**
 * The music_offtopic skip segments of [videoId] in a hash-prefix answer: `[{"videoID", "segments": [{"category",
 * "actionType", "segment": [start, end]}]}]`, times in seconds. Other videos sharing the prefix are ignored.
 */
internal fun parseSponsorBlockSegments(body: String, videoId: String): List<MusicSkipSegment> {
    val videos = runCatching { SPONSOR_BLOCK_JSON.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return emptyList()
    val video = videos.firstOrNull { (it as? JsonObject)?.get("videoID")?.string() == videoId } as? JsonObject
        ?: return emptyList()
    val segments = video["segments"] as? JsonArray ?: return emptyList()
    return segments.mapNotNull { element ->
        val segment = element as? JsonObject ?: return@mapNotNull null
        if (segment["category"]?.string() != SPONSOR_BLOCK_CATEGORY) return@mapNotNull null
        if ((segment["actionType"]?.string() ?: "skip") != "skip") return@mapNotNull null
        val times = segment["segment"] as? JsonArray ?: return@mapNotNull null
        val start = (times.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        val end = (times.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        MusicSkipSegment((start * 1000).toLong(), (end * 1000).toLong())
            .takeIf { it.endMs - it.startMs >= MIN_SEGMENT_MS }
    }.sortedBy { it.startMs }
}

/**
 * Where to jump from [positionMs]: the end of the segment it is inside, or null. A position in the last half second of a
 * segment is left alone, so a seek that lands just short of the end (a keyframe seek can) does not loop.
 */
internal fun musicSkipTarget(positionMs: Long, segments: List<MusicSkipSegment>): MusicSkipSegment? =
    segments.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs - SKIP_END_SLACK_MS }

private const val SKIP_END_SLACK_MS = 500L

/** A segment starting this close to 0:00 is an intro: the song is opened past it rather than played into it. */
private const val INTRO_START_MS = 1_500L

/** The intro to open [segments]' video past, if it has one that ends well before the song does. */
internal fun musicIntroSegment(segments: List<MusicSkipSegment>, durationMs: Long): MusicSkipSegment? =
    segments.firstOrNull { it.startMs <= INTRO_START_MS }
        ?.takeIf { durationMs <= 0L || it.endMs < durationMs - INTRO_START_MS }

private fun kotlinx.serialization.json.JsonElement.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
