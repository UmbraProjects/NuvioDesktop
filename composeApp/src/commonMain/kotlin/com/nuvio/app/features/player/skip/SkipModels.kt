package com.nuvio.app.features.player.skip

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class SkipInterval(
    val startTime: Double,
    val endTime: Double,
    val type: String,
    val provider: String,
)

/**
 * Community timings take precedence for their own kind of segment, while chapter timings fill
 * gaps (for example a community intro with a chapter-provided outro).
 */
internal fun mergeCommunityAndChapterSkipIntervals(
    communityIntervals: List<SkipInterval>,
    chapterIntervals: List<SkipInterval>,
): List<SkipInterval> {
    val coveredKinds = communityIntervals.mapTo(mutableSetOf()) { interval ->
        interval.type.skipIntervalKind()
    }
    return (communityIntervals + chapterIntervals.filter { interval ->
        interval.type.skipIntervalKind() !in coveredKinds
    })
        .distinctBy { interval -> Triple(interval.startTime, interval.endTime, interval.type) }
        .sortedBy(SkipInterval::startTime)
}

private fun String.skipIntervalKind(): String = when (lowercase()) {
    "intro", "op", "mixed-op" -> "intro"
    "outro", "ed", "mixed-ed", "credits" -> "outro"
    else -> this
}

data class NextEpisodeInfo(
    val videoId: String,
    val season: Int,
    val episode: Int,
    val title: String,
    val thumbnail: String?,
    val overview: String?,
    val released: String?,
    val hasAired: Boolean,
    val unairedMessage: String?,
)

enum class NextEpisodeThresholdMode {
    PERCENTAGE,
    MINUTES_BEFORE_END,
}

// --- IntroDb API response models ---

// IntroDb's /intro endpoint returns a single flat intro segment, e.g.:
// {"imdb_id":"tt..","season":2,"episode":1,"start_sec":0,"end_sec":31,
//  "start_ms":0,"end_ms":31000,"confidence":1,"submission_count":1,"updated_at":".."}
// (a missing entry returns {"error":"Not found."}, which parses to all-null timings.)
@Serializable
data class IntroDbSegmentsResponse(
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("season") val season: Int? = null,
    @SerialName("episode") val episode: Int? = null,
    @SerialName("start_sec") val startSec: Double? = null,
    @SerialName("end_sec") val endSec: Double? = null,
    @SerialName("start_ms") val startMs: Long? = null,
    @SerialName("end_ms") val endMs: Long? = null,
    @SerialName("confidence") val confidence: Double? = null,
    @SerialName("submission_count") val submissionCount: Int? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

// --- SkipDB API response models ---

// SkipDB's /api/segments endpoint answers with the best segment of each kind for one movie or
// episode, e.g.:
// {"imdb_id":"tt0413573","season":2,"episode":3,
//  "segments":{"intro":{"start_ms":405500,"end_ms":428500,"adjusted":false,"offset_ms":0,
//                       "match":"exact","confidence":0.9},
//              "recap":null,"outro":{..},"preview":null},
//  "intro_length_estimate_ms":24450}
// An unknown title is not an error: it answers 200 with every segment null.
@Serializable
data class SkipDbSegmentsResponse(
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("season") val season: Int? = null,
    @SerialName("episode") val episode: Int? = null,
    @SerialName("segments") val segments: SkipDbSegments? = null,
    @SerialName("intro_length_estimate_ms") val introLengthEstimateMs: Long? = null,
)

@Serializable
data class SkipDbSegments(
    @SerialName("intro") val intro: SkipDbSegment? = null,
    @SerialName("recap") val recap: SkipDbSegment? = null,
    @SerialName("outro") val outro: SkipDbSegment? = null,
    @SerialName("preview") val preview: SkipDbSegment? = null,
)

@Serializable
data class SkipDbSegment(
    @SerialName("start_ms") val startMs: Long? = null,
    @SerialName("end_ms") val endMs: Long? = null,
    @SerialName("match") val match: String? = null,
    @SerialName("adjusted") val adjusted: Boolean = false,
    @SerialName("offset_ms") val offsetMs: Long? = null,
    @SerialName("confidence") val confidence: Double? = null,
)

/**
 * How closely the timings SkipDB returned line up with the runtime of the cut actually being
 * played. Reported per segment; only [OUT_OF_RANGE] means the answer describes a different
 * release and should not be used.
 */
object SkipDbMatch {
    /** Runtime matched a stored submission within ~2s. */
    const val EXACT = "exact"

    /** Runtime was within ~15s; SkipDB reports the offset it would take to line them up. */
    const val SHIFTED = "shifted"

    /** No runtime was supplied, so the timings are unverified against this cut. */
    const val AGNOSTIC = "agnostic"

    /** The closest stored cut differs too much for the timings to mean anything here. */
    const val OUT_OF_RANGE = "out-of-range"
}

// --- SkipDB submission models ---

/**
 * A contributed segment. Times are milliseconds; [durationMs] is the runtime of the cut they were
 * taken from, which is what lets SkipDB serve them back to the right release later.
 */
@Serializable
data class SkipDbSubmitRequest(
    @SerialName("imdb_id") val imdbId: String,
    @SerialName("season") val season: Int? = null,
    @SerialName("episode") val episode: Int? = null,
    @SerialName("segment_type") val segmentType: String,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    @SerialName("duration_ms") val durationMs: Long? = null,
)

@Serializable
data class SkipDbSubmitResponse(
    @SerialName("id") val id: Long? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("auto_approved") val autoApproved: Boolean = false,
    @SerialName("reasons") val reasons: List<String> = emptyList(),
    @SerialName("message") val message: String? = null,
    @SerialName("error") val error: String? = null,
)

@Serializable
data class SkipDbAnonymousKeyResponse(
    @SerialName("key") val key: String? = null,
    @SerialName("prefix") val prefix: String? = null,
)

/** What a submission attempt should tell the user, in a form both player UIs can render. */
data class SkipSubmitOutcome(
    val accepted: Boolean,
    val message: String,
)

/**
 * Turns SkipDB's reply into something worth showing.
 *
 * A submission does not have to be published to have succeeded — one held for review comes back
 * `pending`, which is still a contribution — so only an outright rejection or an error counts as a
 * failure. SkipDB explains itself when it turns something down (an overlap with an existing
 * segment, a failed validation, a rate limit), and that reason is far more useful than a generic
 * failure line, so it is preferred over anything written here.
 */
internal fun SkipDbSubmitResponse.toOutcome(): SkipSubmitOutcome {
    error?.takeIf { it.isNotBlank() }?.let { reason ->
        return SkipSubmitOutcome(accepted = false, message = reason)
    }
    val accepted = !status.isNullOrBlank() && !status.equals("rejected", ignoreCase = true)
    return SkipSubmitOutcome(
        accepted = accepted,
        message = message?.takeIf { it.isNotBlank() }
            ?: reasons.firstOrNull { it.isNotBlank() }
            ?: if (accepted) "Submitted to SkipDB." else "SkipDB rejected the submission.",
    )
}

internal const val SKIPDB_PROVIDER = "skipdb"

/** Segment kinds SkipDB accepts, in the order the pickers show them. */
val SKIP_SEGMENT_TYPES: List<String> = listOf("intro", "recap", "outro", "preview")

/**
 * Flattens one SkipDB answer into the intervals worth showing. Kinds SkipDB has nothing for come
 * back null and are simply absent from the result.
 */
internal fun SkipDbSegments.toSkipIntervals(): List<SkipInterval> = listOfNotNull(
    intro.toSkipInterval("intro"),
    recap.toSkipInterval("recap"),
    outro.toSkipInterval("outro"),
    preview.toSkipInterval("preview"),
)

/**
 * Drops the two answers SkipDB gives that are not usable intervals: `out-of-range`, where the
 * closest cut it knows about is too far off this release for its timings to mean anything, and the
 * 0/0 sentinel, which records that somebody confirmed this segment does *not* exist rather than
 * that it runs for no time.
 */
private fun SkipDbSegment?.toSkipInterval(type: String): SkipInterval? {
    val segment = this ?: return null
    if (segment.match.equals(SkipDbMatch.OUT_OF_RANGE, ignoreCase = true)) return null
    val startMs = segment.startMs ?: return null
    val endMs = segment.endMs ?: return null
    if (startMs == 0L && endMs == 0L) return null
    if (endMs <= startMs) return null
    return SkipInterval(
        startTime = startMs / 1000.0,
        endTime = endMs / 1000.0,
        type = type,
        provider = SKIPDB_PROVIDER,
    )
}

@Serializable
data class SubmitIntroRequest(
    @SerialName("imdb_id") val imdbId: String,
    @SerialName("season") val season: Int,
    @SerialName("episode") val episode: Int,
    @SerialName("start_sec") val startSec: Double,
    @SerialName("end_sec") val endSec: Double,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    @SerialName("segment_type") val segmentType: String,
)

// --- AniSkip API response models ---

@Serializable
data class AniSkipResponse(
    @SerialName("found") val found: Boolean = false,
    @SerialName("results") val results: List<AniSkipResult>? = null,
)

@Serializable
data class AniSkipResult(
    @SerialName("interval") val interval: AniSkipInterval,
    @SerialName("skipType") val skipType: String,
    @SerialName("skipId") val skipId: String? = null,
)

@Serializable
data class AniSkipInterval(
    @SerialName("startTime") val startTime: Double,
    @SerialName("endTime") val endTime: Double,
)

// --- ARM API response models ---

@Serializable
data class ArmEntry(
    @SerialName("myanimelist") val myanimelist: Int? = null,
    @SerialName("anilist") val anilist: Int? = null,
    @SerialName("kitsu") val kitsu: Int? = null,
    @SerialName("imdb") val imdb: String? = null,
)

// --- Anime-Skip GraphQL API response models ---

@Serializable
data class AnimeSkipGraphqlResponse(
    @SerialName("data") val data: AnimeSkipData? = null,
)

@Serializable
data class AnimeSkipData(
    @SerialName("findShowsByExternalId") val findShowsByExternalId: List<AnimeSkipShow>? = null,
    @SerialName("findEpisodesByShowId") val findEpisodesByShowId: List<AnimeSkipEpisode>? = null,
)

@Serializable
data class AnimeSkipShow(
    @SerialName("id") val id: String,
)

@Serializable
data class AnimeSkipEpisode(
    @SerialName("season") val season: String? = null,
    @SerialName("number") val number: String? = null,
    @SerialName("timestamps") val timestamps: List<AnimeSkipTimestamp>? = null,
)

@Serializable
data class AnimeSkipTimestamp(
    @SerialName("at") val at: Double,
    @SerialName("type") val type: AnimeSkipTimestampType,
)

@Serializable
data class AnimeSkipTimestampType(
    @SerialName("name") val name: String,
)
