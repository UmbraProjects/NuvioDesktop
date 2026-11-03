package com.nuvio.app.features.youtube

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.player.skip.SkipInterval
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
 * fork:youtube-mode
 *
 * SponsorBlock (https://sponsor.ajay.app) for videos played from the YouTube tab: the segments its users
 * marked become the player's ordinary skip prompts. Sponsors, self-promotion and "like and subscribe"
 * reminders are skipped without a prompt while Settings > YouTube account > Skip sponsors is on; intros,
 * end cards, previews and filler get the prompt.
 *
 * Looked up by the first four characters of the id's SHA-256, as the music tab does (MusicSponsorBlock):
 * the server answers for every video sharing the prefix and never learns which one is playing.
 */

/** [SkipInterval.provider] of a SponsorBlock segment. */
internal const val SPONSORBLOCK_SKIP_PROVIDER = "sponsorblock"

/** Skipped without a prompt while the setting is on, as SponsorBlock's own extension does by default. */
internal val YOUTUBE_AUTO_SKIP_TYPES = setOf("sponsor", "selfpromo", "interaction")

private val log = Logger.withTag("YouTubeSponsorBlock")
private val json = Json { ignoreUnknownKeys = true }

private const val API = "https://sponsor.ajay.app/api/skipSegments/"
private const val HASH_PREFIX_LENGTH = 4
private const val MIN_SEGMENT_SEC = 1.0
private const val CACHE_SIZE = 200

/**
 * SponsorBlock category to the skip type the player knows: its intro/outro/recap labels and rules
 * already fit those three, and the rest keep their own name (with their own prompt labels).
 */
private val CATEGORY_TYPES = linkedMapOf(
    "sponsor" to "sponsor",
    "selfpromo" to "selfpromo",
    "interaction" to "interaction",
    "intro" to "intro",
    "outro" to "outro",
    "preview" to "recap",
    "filler" to "filler",
    "music_offtopic" to "music_offtopic",
)

internal object YouTubeSponsorBlock {
    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, List<SkipInterval>>()

    /** The skippable segments of [videoId], in order; empty when it has none or SponsorBlock cannot be reached. */
    suspend fun intervals(videoId: String): List<SkipInterval> {
        mutex.withLock { cache[videoId] }?.let { return it }
        val prefix = ProfilePinCrypto.sha256Hex(videoId).take(HASH_PREFIX_LENGTH).lowercase()
        val categories = CATEGORY_TYPES.keys.joinToString(",", "[", "]") { "\"$it\"" }
        val query = "categories=" + categories.encodeURLParameter() + "&actionTypes=" + "[\"skip\"]".encodeURLParameter()
        val found = try {
            parseYouTubeSponsorSegments(httpGetTextWithHeaders("$API$prefix?$query", emptyMap()), videoId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A prefix with nothing marked is a 404, which arrives here as a failure too.
            log.d { "no segments for $videoId: ${e.message}" }
            emptyList()
        }
        mutex.withLock {
            cache[videoId] = found
            if (cache.size > CACHE_SIZE) cache.remove(cache.keys.first())
        }
        if (found.isNotEmpty()) log.i { "$videoId: ${found.size} segment(s): ${found.groupingBy { it.type }.eachCount()}" }
        return found
    }
}

/**
 * The skip segments of [videoId] in a hash-prefix answer (`[{"videoID", "segments": [{"category",
 * "actionType", "segment": [start, end]}]}]`, seconds), as player skip intervals. Other videos sharing
 * the prefix, other action types (mute, chapter, full-video labels) and sub-second marks are left out.
 */
internal fun parseYouTubeSponsorSegments(body: String, videoId: String): List<SkipInterval> {
    val videos = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return emptyList()
    val video = videos.firstOrNull { (it as? JsonObject)?.get("videoID")?.string() == videoId } as? JsonObject
        ?: return emptyList()
    val segments = video["segments"] as? JsonArray ?: return emptyList()
    return segments.mapNotNull { element ->
        val segment = element as? JsonObject ?: return@mapNotNull null
        val type = segment["category"]?.string()?.let(CATEGORY_TYPES::get) ?: return@mapNotNull null
        if ((segment["actionType"]?.string() ?: "skip") != "skip") return@mapNotNull null
        val times = segment["segment"] as? JsonArray ?: return@mapNotNull null
        val start = (times.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        val end = (times.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        SkipInterval(startTime = start, endTime = end, type = type, provider = SPONSORBLOCK_SKIP_PROVIDER)
            .takeIf { end - start >= MIN_SEGMENT_SEC }
    }.sortedBy { it.startTime }
}

/** Painted on the seek bar in their own colour, and named as chapters. */
private val SEEK_SEGMENT_TYPES = setOf("sponsor", "selfpromo")

/** The SponsorBlock intervals the seek bar paints: sponsors (green) and self-promotion (yellow). */
internal fun sponsorSeekSegments(intervals: List<SkipInterval>): List<com.nuvio.app.features.player.PlayerSeekSegment> =
    intervals
        .filter { it.provider == SPONSORBLOCK_SKIP_PROVIDER && it.type in SEEK_SEGMENT_TYPES && it.endTime > it.startTime }
        .map { com.nuvio.app.features.player.PlayerSeekSegment(it.startTime, it.endTime, it.type) }

/**
 * [chapters] with each sponsor and self-promotion stretch cut in as a chapter of its own ("Sponsor",
 * "Self-promotion"), and the chapter it interrupted resumed after it. A video without chapters gets
 * a "Video" chapter around them, so the bar's chapter names read right on either side.
 */
internal fun withSponsorChapters(
    chapters: List<com.nuvio.app.features.player.PlayerChapter>,
    intervals: List<SkipInterval>,
): List<com.nuvio.app.features.player.PlayerChapter> {
    val segments = sponsorSeekSegments(intervals).sortedBy { it.start }
    if (segments.isEmpty()) return chapters
    val base = chapters.sortedBy { it.startTime }.ifEmpty { listOf(com.nuvio.app.features.player.PlayerChapter(0.0, "Video")) }
    fun titleAt(seconds: Double): String = base.lastOrNull { it.startTime <= seconds }?.title ?: base.first().title
    val out = mutableListOf<com.nuvio.app.features.player.PlayerChapter>()
    // Chapters starting inside a marked stretch are covered by it.
    base.filterTo(out) { chapter -> segments.none { chapter.startTime > it.start && chapter.startTime < it.end } }
    var lastEnd = Double.NEGATIVE_INFINITY
    for (segment in segments) {
        if (segment.start < lastEnd) continue // overlapping marks: the first one wins
        out.removeAll { it.startTime == segment.start }
        out += com.nuvio.app.features.player.PlayerChapter(
            segment.start,
            if (segment.kind == "sponsor") "Sponsor" else "Self-promotion",
        )
        // What plays after it: the chapter it interrupted, unless one starts right there.
        if (out.none { it.startTime == segment.end } && base.none { it.startTime == segment.end }) {
            out += com.nuvio.app.features.player.PlayerChapter(segment.end, titleAt(segment.end))
        }
        lastEnd = segment.end
    }
    return out.sortedBy { it.startTime }
}

private fun kotlinx.serialization.json.JsonElement.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
