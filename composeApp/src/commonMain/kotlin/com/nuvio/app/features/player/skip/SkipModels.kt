package com.nuvio.app.features.player.skip

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import nuvio.composeapp.generated.resources.*
import com.nuvio.app.core.i18n.localizedString

data class SkipInterval(
    val startTime: Double,
    val endTime: Double,
    val type: String,
    val provider: String,
)

/** [SkipInterval.provider] of an interval derived from the file's own chapter markers. */
const val CHAPTER_SKIP_PROVIDER = "chapters"

/** Identity of a segment within one loaded episode, stable across list rebuilds. */
internal fun SkipInterval.identityKey(): String = "$type@$startTime-$endTime"

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

/**
 * How the skip prompt is accepted. The prompt itself is unaffected — this only decides whether the
 * app presses it for you, and which timing sources it trusts enough to press it for.
 *
 * [CHAPTERS] covers segments read from the file's own chapter markers, which are authored against
 * the exact cut being played. [ANY_SOURCE] additionally accepts the community/API timings, which
 * are matched by title and runtime and so vary in accuracy between sources.
 *
 * Outros are never auto-accepted at any level. Skipping one seeks to the end of the file, which
 * ends the episode and rolls into the next — far too consequential to do without a press, and not
 * what "skip the intro for me" asks for. The outro prompt stays manual.
 */
enum class SkipAutoAcceptMode {
    MANUAL,
    CHAPTERS,
    ANY_SOURCE,
    ;

    fun accepts(interval: SkipInterval): Boolean {
        if (!interval.isAutoAcceptable()) return false
        return when (this) {
            MANUAL -> false
            CHAPTERS -> interval.provider == CHAPTER_SKIP_PROVIDER
            ANY_SOURCE -> true
        }
    }
}

/**
 * Intros and recaps only, as an allowlist rather than an "everything but outro" test: skipping
 * either lands inside the episode, while anything unrecognised is left to a deliberate press.
 */
private fun SkipInterval.isAutoAcceptable(): Boolean =
    type.skipIntervalKind() in AUTO_ACCEPTABLE_SKIP_KINDS

private val AUTO_ACCEPTABLE_SKIP_KINDS = setOf("intro", "recap")

enum class NextEpisodeThresholdMode {
    PERCENTAGE,
    MINUTES_BEFORE_END,
}

// --- IntroDb API response models ---

// IntroDb's /segments endpoint answers with every kind it knows for one episode or film, e.g.:
// {"imdb_id":"tt0944947","media_type":"tv","is_movie":false,"season":1,"episode":1,
//  "intro":{"start_sec":437,"end_sec":531,"start_ms":437000,"end_ms":531000,"confidence":1,..},
//  "recap":null,"outro":{..},"post_credits":null}
// For a film (`is_movie=true`) the outro is the end credits and `post_credits` is a scene inside
// or after them. An unknown title answers with every segment null.
@Serializable
data class IntroDbSegmentsResponse(
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("season") val season: Int? = null,
    @SerialName("episode") val episode: Int? = null,
    @SerialName("intro") val intro: IntroDbSegment? = null,
    @SerialName("recap") val recap: IntroDbSegment? = null,
    @SerialName("outro") val outro: IntroDbSegment? = null,
    @SerialName("post_credits") val postCredits: IntroDbSegment? = null,
)

@Serializable
data class IntroDbSegment(
    @SerialName("start_sec") val startSec: Double? = null,
    @SerialName("end_sec") val endSec: Double? = null,
    @SerialName("start_ms") val startMs: Long? = null,
    @SerialName("end_ms") val endMs: Long? = null,
    @SerialName("confidence") val confidence: Double? = null,
    @SerialName("submission_count") val submissionCount: Int? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

internal const val INTRODB_PROVIDER = "introdb"

internal fun IntroDbSegmentsResponse.toEpisodeSkipIntervals(): List<SkipInterval> = listOfNotNull(
    intro.toSkipInterval("intro"),
    recap.toSkipInterval("recap"),
    outro.toSkipInterval("outro"),
)

/**
 * A film's credits and post-credits scene. The credits are cut short where a scene starts inside
 * them, so skipping the credits can never jump over the scene they contain.
 */
internal fun IntroDbSegmentsResponse.toMovieSkipIntervals(): List<SkipInterval> {
    val credits = outro.toSkipInterval("outro")
    val scene = postCredits.toSkipInterval(POST_CREDITS_SKIP_TYPE)
    val safeCredits = if (
        credits != null && scene != null &&
        scene.startTime > credits.startTime && scene.startTime < credits.endTime
    ) {
        credits.copy(endTime = scene.startTime)
    } else {
        credits
    }
    return listOfNotNull(safeCredits, scene)
}

private fun IntroDbSegment?.toSkipInterval(type: String): SkipInterval? {
    val segment = this ?: return null
    val start = segment.startSec ?: segment.startMs?.let { it / 1000.0 } ?: return null
    val end = segment.endSec ?: segment.endMs?.let { it / 1000.0 } ?: return null
    if (!start.isFinite() || !end.isFinite() || start < 0.0 || end <= start) return null
    return SkipInterval(startTime = start, endTime = end, type = type, provider = INTRODB_PROVIDER)
}

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
            ?: if (accepted) localizedString(Res.string.skip_submit_submitted_to_skipdb) else localizedString(Res.string.skip_submit_skipdb_rejected_the_submission),
    )
}

internal const val SKIPDB_PROVIDER = "skipdb"

/** Segment kinds SkipDB accepts, in the order the pickers show them. */
val SKIP_SEGMENT_TYPES: List<String> = listOf("intro", "recap", "outro", "preview")

/**
 * Where a skip-key press ("Tab" by default, Start on a pad) goes. One resolution shared by every
 * input path so the key means the same thing whichever surface saw it, resolved in priority order
 * by [PlayerScreenRuntime.resolveSkipKeyAction]. Empty string = nothing to do, key not consumed.
 */
object SkipKeyActions {
    const val SKIP_INTERVAL = "skipInterval"
    const val PLAY_NEXT_EPISODE = "playNextEpisode"
    /** Sends the offered chapter timings (see [SkipSubmitOffer]) to SkipDB. */
    const val SUBMIT_OFFER = "skipSubmitOffer"
    /** Starts a capture session at the current position. */
    const val CAPTURE_START = "skipCaptureStart"
    /** Marks the end of the running capture session at the current position. */
    const val CAPTURE_MARK_END = "skipCaptureMarkEnd"
    /** Submits the marked capture session. */
    const val CAPTURE_SUBMIT = "skipCaptureSubmit"
}

/** Phase of the single HUD toast that fronts both the post-skip offer and capture mode. */
enum class SkipSubmitToastPhase { OFFER, CAPTURING, CAPTURED, SUBMITTING, RESULT }

/**
 * A chapter-sourced skip that just landed, held for a few seconds so the viewer can send those
 * timings to SkipDB with one more press. Only chapter intervals are ever offered: a community
 * interval either came from SkipDB (nothing to add) or from another database (not ours to
 * forward), and the merge already drops the chapter copy whenever a community one exists, so a
 * chapter prompt on screen means SkipDB genuinely has nothing for this kind of segment.
 */
data class SkipSubmitOffer(
    val interval: SkipInterval,
    val phase: SkipSubmitToastPhase = SkipSubmitToastPhase.OFFER,
    val resultMessage: String = "",
    val resultAccepted: Boolean = false,
    /** Set once playback has been seen at the segment end. The seek that made the offer is
     *  asynchronous, so until then the reported position is still inside the segment and must not
     *  be read as "seeked back into it". */
    val landed: Boolean = false,
)

/**
 * Capture mode: press once to mark where a segment starts, again where it ends, a third time to
 * submit. [endSec] is null while the segment is still being marked. The kind is inferred from
 * where the marks fall (see [inferCapturedSegmentType]); recaps and previews still go through the
 * Submit Timestamps panel, which is the only place they can be named.
 */
data class SkipCaptureSession(
    val startSec: Double,
    val endSec: Double? = null,
    val phase: SkipSubmitToastPhase = SkipSubmitToastPhase.CAPTURING,
    val resultMessage: String = "",
    val resultAccepted: Boolean = false,
    /** Kind inferred when the end mark landed, with the runtime known; null while still marking. */
    val segmentType: String? = null,
)

/** How long the post-skip offer stays actionable. Matches the skip prompt's own auto-hide. */
const val SKIP_SUBMIT_OFFER_TIMEOUT_MS = 10_000L
/** How long a submission result (accepted or rejected) stays readable before the toast clears. */
const val SKIP_SUBMIT_RESULT_TIMEOUT_MS = 5_000L
/** A capture left open this long was forgotten, not paused: outros run minutes, not tens of them. */
const val SKIP_CAPTURE_MAX_OPEN_MS = 15 * 60_000L
/**
 * The offer is tied to the landing point. A seek back into the segment — because the skip landed
 * wrong — or a jump well past it means the viewer has moved on, and the offer goes with them.
 * Backwards is tight (a wrong landing is the very thing being judged); forwards is loose enough
 * that ordinary playback at any speed never trips it before the timeout does.
 */
const val SKIP_SUBMIT_OFFER_BACKWARD_TOLERANCE_SEC = 3.0
const val SKIP_SUBMIT_OFFER_FORWARD_TOLERANCE_SEC = 60.0
/** Shortest span capture mode will close; matches the chapter detector's minimum intro. */
const val SKIP_CAPTURE_MIN_SPAN_SEC = 2.0

/** Kinds a post-skip offer is made for. Outros are excluded: skipping one seeks to the end, where
 *  the next-episode card owns the same key and the landing point cannot be judged anyway. */
internal fun String.isOfferableSkipKind(): Boolean = skipIntervalKind() in setOf("intro", "recap")

/**
 * Segment kind for a captured span, from where it starts — the same outro threshold
 * [ChapterSkipDetector] trusts for chapter labels. A span starting in the last 40% of the runtime
 * is an outro; anything else, including every span of a file whose runtime is unknown, is called
 * an intro. That is the common case, and the Submit Timestamps panel remains for the rest.
 */
internal fun inferCapturedSegmentType(startSec: Double, durationSec: Double?): String {
    val validDuration = durationSec?.takeIf { it.isFinite() && it > 0.0 }
    if (validDuration != null && startSec >= validDuration * 0.60) return "outro"
    return "intro"
}

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
