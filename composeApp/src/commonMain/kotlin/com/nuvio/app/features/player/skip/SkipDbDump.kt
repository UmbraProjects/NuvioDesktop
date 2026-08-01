package com.nuvio.app.features.player.skip

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * SkipDB's full export of approved segments. Unknown fields are dropped on parse, so only what the
 * lookup actually needs is held in memory — the export also carries ids, vote tallies, timestamps
 * and (in the GitHub release variant) titles, none of which are read here.
 */
@Serializable
data class SkipDbDump(
    @SerialName("segments") val segments: List<SkipDbDumpSegment> = emptyList(),
    @SerialName("count") val count: Int? = null,
    @SerialName("generated_at") val generatedAt: String? = null,
)

@Serializable
data class SkipDbDumpSegment(
    @SerialName("imdb_id") val imdbId: String = "",
    @SerialName("season") val season: Int? = null,
    @SerialName("episode") val episode: Int? = null,
    @SerialName("segment_type") val segmentType: String = "",
    @SerialName("status") val status: String? = null,
    @SerialName("start_ms") val startMs: Long? = null,
    @SerialName("end_ms") val endMs: Long? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("score") val score: Int = 0,
)

/** Only approved submissions are published; anything else in an export is ignored. */
private const val STATUS_APPROVED = "approved"

// Thresholds SkipDB itself applies when it duration-matches server-side, confirmed against the live
// API: within 2s of a stored runtime is an exact match, up to 15s is a shifted one, past that the
// stored cut is too different to say anything about this release.
private const val EXACT_MATCH_TOLERANCE_MS = 2_000L
private const val SHIFTED_MATCH_TOLERANCE_MS = 15_000L

private const val CONFIDENCE_EXACT = 0.9
private const val CONFIDENCE_SHIFTED = 0.82
private const val CONFIDENCE_AGNOSTIC = 0.75
private const val CONFIDENCE_OUT_OF_RANGE = 0.6

/**
 * A parsed export, arranged for lookup by title and episode.
 *
 * Built once per sync and then queried entirely in memory: the export is complete, so an episode
 * that is absent here is one SkipDB genuinely has nothing for, and no request needs to be made to
 * find that out.
 */
class SkipDbDumpIndex private constructor(
    private val byKey: Map<String, List<SkipDbDumpSegment>>,
    val generatedAt: String?,
    val segmentCount: Int,
) {

    val isEmpty: Boolean get() = byKey.isEmpty()

    /**
     * Best segment of each kind for one title, in the same shape the SkipDB API answers with so
     * both paths share one notion of what counts as a usable interval. Returns null when the export
     * has nothing at all for this episode.
     *
     * Pass a null [season]/[episode] for a movie, and [durationSeconds] whenever the runtime is
     * known — matching the played runtime against the submitted one is the only way to tell a
     * re-cut release from the one the timings were taken from.
     */
    fun lookup(
        imdbId: String,
        season: Int?,
        episode: Int?,
        durationSeconds: Long?,
    ): SkipDbSegments? {
        val candidates = byKey[indexKey(imdbId, season, episode)] ?: return null
        val durationMs = durationSeconds?.takeIf { it > 0L }?.let { it * 1000L }
        return SkipDbSegments(
            intro = candidates.bestOfType("intro", durationMs),
            recap = candidates.bestOfType("recap", durationMs),
            outro = candidates.bestOfType("outro", durationMs),
            preview = candidates.bestOfType("preview", durationMs),
        )
    }

    /**
     * Picks the submission for one kind that best describes the cut being played. Closeness of
     * runtime wins over vote score, since a well-voted timing taken from a different release is
     * still the wrong timing.
     */
    private fun List<SkipDbDumpSegment>.bestOfType(
        type: String,
        durationMs: Long?,
    ): SkipDbSegment? {
        val matches = filter { it.segmentType.equals(type, ignoreCase = true) }
        if (matches.isEmpty()) return null
        val best = matches.minWithOrNull(
            compareBy<SkipDbDumpSegment> { segment -> segment.offsetMagnitudeMs(durationMs) }
                .thenByDescending { segment -> segment.score }
        ) ?: return null

        val offsetMs = best.signedOffsetMs(durationMs)
        val match = when {
            durationMs == null || best.durationMs == null -> SkipDbMatch.AGNOSTIC
            best.offsetMagnitudeMs(durationMs) <= EXACT_MATCH_TOLERANCE_MS -> SkipDbMatch.EXACT
            best.offsetMagnitudeMs(durationMs) <= SHIFTED_MATCH_TOLERANCE_MS -> SkipDbMatch.SHIFTED
            else -> SkipDbMatch.OUT_OF_RANGE
        }
        return SkipDbSegment(
            startMs = best.startMs,
            endMs = best.endMs,
            match = match,
            // Reads stay unshifted, mirroring the API's conservative mode: a runtime difference is
            // only safely attributable to the head of the file when something says it is, and a
            // release with longer credits would be shifted the wrong way.
            adjusted = false,
            offsetMs = offsetMs,
            confidence = when (match) {
                SkipDbMatch.EXACT -> CONFIDENCE_EXACT
                SkipDbMatch.SHIFTED -> CONFIDENCE_SHIFTED
                SkipDbMatch.OUT_OF_RANGE -> CONFIDENCE_OUT_OF_RANGE
                else -> CONFIDENCE_AGNOSTIC
            },
        )
    }

    private fun SkipDbDumpSegment.signedOffsetMs(playedDurationMs: Long?): Long {
        if (playedDurationMs == null || durationMs == null) return 0L
        return playedDurationMs - durationMs
    }

    /** Sorts unmatchable submissions last without letting them look like a perfect match. */
    private fun SkipDbDumpSegment.offsetMagnitudeMs(playedDurationMs: Long?): Long {
        if (playedDurationMs == null) return 0L
        if (durationMs == null) return Long.MAX_VALUE
        val offset = playedDurationMs - durationMs
        return if (offset < 0L) -offset else offset
    }

    companion object {
        fun from(dump: SkipDbDump): SkipDbDumpIndex {
            val usable = dump.segments.filter { segment ->
                segment.imdbId.isNotBlank() &&
                    segment.segmentType.isNotBlank() &&
                    segment.startMs != null &&
                    segment.endMs != null &&
                    // A 0/0 row records that somebody confirmed this segment does not exist, so it
                    // is not a candidate to rank — left in, it could out-rank a real submission on
                    // runtime closeness and hide it. Dropping it here matches the API, which never
                    // returns sentinels either.
                    !(segment.startMs == 0L && segment.endMs == 0L) &&
                    // Exports have carried non-approved rows before; only published data is used.
                    (segment.status == null || segment.status.equals(STATUS_APPROVED, ignoreCase = true))
            }
            val byKey = usable.groupBy { segment ->
                indexKey(segment.imdbId, segment.season, segment.episode)
            }
            return SkipDbDumpIndex(
                byKey = byKey,
                generatedAt = dump.generatedAt,
                segmentCount = usable.size,
            )
        }

        private fun indexKey(imdbId: String, season: Int?, episode: Int?): String =
            if (season == null || episode == null) imdbId else "$imdbId:$season:$episode"
    }
}
