package com.nuvio.app.features.player.skip

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * SkipDB's full export of approved segments. Unknown fields are dropped on parse, so only what
 * indexing needs is even parsed — the export also carries ids, vote tallies, timestamps and (in the
 * GitHub release variant) titles, none of which are read here.
 *
 * This is the parse shape, not the retained one: [SkipDbDumpIndex] compacts what it keeps and these
 * become garbage as soon as it is built.
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
 * Stands in for a submission that carries no runtime, so [Entry.durationMs] can be a primitive.
 * No real duration can collide with it.
 */
private const val NO_DURATION_MS = Long.MIN_VALUE

/**
 * A parsed export, arranged for lookup by title and episode.
 *
 * Built once per sync and then queried entirely in memory: the export is complete, so an episode
 * that is absent here is one SkipDB genuinely has nothing for, and no request needs to be made to
 * find that out.
 *
 * The whole export is held for the lifetime of the process, so it is stored compacted rather than
 * as the parsed [SkipDbDumpSegment]s. Keeping those was mostly waste: every segment held its own
 * copy of an imdb id that is already in the key it is filed under, of a status that is always
 * "approved" by the time it is stored, and of a segment type drawn from four distinct values — a
 * real export has 2,850 distinct ids and 4 distinct types across 101,650 segments — plus three
 * boxed Longs. [Entry] keeps only the five fields [lookup] reads, shares one instance of each type
 * string, and holds the times as primitives, which measured 23 MB smaller on that export.
 */
class SkipDbDumpIndex private constructor(
    private val byKey: Map<String, Array<Entry>>,
    val generatedAt: String?,
    val segmentCount: Int,
) {

    /**
     * One usable submission. The type stays a string rather than becoming an enum so that a kind
     * this build has never heard of still round-trips through the index the way it used to — it
     * simply matches no lookup, exactly as an unrecognised [SkipDbDumpSegment.segmentType] did.
     */
    private class Entry(
        val segmentType: String,
        val startMs: Long,
        val endMs: Long,
        val durationMs: Long,
        val score: Int,
    )

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
    private fun Array<Entry>.bestOfType(
        type: String,
        durationMs: Long?,
    ): SkipDbSegment? {
        // Walked by hand rather than filtered-then-sorted: this runs over every kind on every
        // lookup, and the comparator version allocated a list and a pair of lambdas each time.
        // Ties keep the first entry seen, which is what minWithOrNull did.
        var best: Entry? = null
        var bestOffsetMagnitudeMs = 0L
        for (entry in this) {
            if (!entry.segmentType.equals(type, ignoreCase = true)) continue
            val offsetMagnitudeMs = entry.offsetMagnitudeMs(durationMs)
            val incumbent = best
            val wins = incumbent == null ||
                offsetMagnitudeMs < bestOffsetMagnitudeMs ||
                (offsetMagnitudeMs == bestOffsetMagnitudeMs && entry.score > incumbent.score)
            if (wins) {
                best = entry
                bestOffsetMagnitudeMs = offsetMagnitudeMs
            }
        }
        if (best == null) return null

        val offsetMs = best.signedOffsetMs(durationMs)
        val match = when {
            durationMs == null || !best.hasDuration -> SkipDbMatch.AGNOSTIC
            bestOffsetMagnitudeMs <= EXACT_MATCH_TOLERANCE_MS -> SkipDbMatch.EXACT
            bestOffsetMagnitudeMs <= SHIFTED_MATCH_TOLERANCE_MS -> SkipDbMatch.SHIFTED
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

    /** False for a submission whose export row carried no runtime to match against. */
    private val Entry.hasDuration: Boolean get() = durationMs != NO_DURATION_MS

    private fun Entry.signedOffsetMs(playedDurationMs: Long?): Long {
        if (playedDurationMs == null || !hasDuration) return 0L
        return playedDurationMs - durationMs
    }

    /** Sorts unmatchable submissions last without letting them look like a perfect match. */
    private fun Entry.offsetMagnitudeMs(playedDurationMs: Long?): Long {
        if (playedDurationMs == null) return 0L
        if (!hasDuration) return Long.MAX_VALUE
        val offset = playedDurationMs - durationMs
        return if (offset < 0L) -offset else offset
    }

    companion object {
        fun from(dump: SkipDbDump): SkipDbDumpIndex {
            // Filtered, keyed and compacted in one pass. The parsed segments are still live in
            // `dump` throughout, so the old filter-then-groupBy also held a full-length copy of the
            // list on the way through — on a real export that is another 101,650 references at the
            // exact moment the index is at its largest.
            val canonicalTypes = HashMap<String, String>()
            val grouped = HashMap<String, MutableList<Entry>>()
            var usableCount = 0
            for (segment in dump.segments) {
                val startMs = segment.startMs ?: continue
                val endMs = segment.endMs ?: continue
                if (segment.imdbId.isBlank() || segment.segmentType.isBlank()) continue
                // A 0/0 row records that somebody confirmed this segment does not exist, so it is
                // not a candidate to rank — left in, it could out-rank a real submission on runtime
                // closeness and hide it. Dropping it here matches the API, which never returns
                // sentinels either.
                if (startMs == 0L && endMs == 0L) continue
                // Exports have carried non-approved rows before; only published data is used.
                val status = segment.status
                if (status != null && !status.equals(STATUS_APPROVED, ignoreCase = true)) continue

                usableCount++
                val entry = Entry(
                    // One shared instance per distinct type, so the four values a real export uses
                    // are not re-held 101,650 times.
                    segmentType = canonicalTypes.getOrPut(segment.segmentType) { segment.segmentType },
                    startMs = startMs,
                    endMs = endMs,
                    durationMs = segment.durationMs ?: NO_DURATION_MS,
                    score = segment.score,
                )
                val key = indexKey(segment.imdbId, segment.season, segment.episode)
                grouped.getOrPut(key) { ArrayList(2) }.add(entry)
            }

            // Fixed arrays rather than the growable lists used to build them: an export averages
            // under two submissions per episode, so the list wrapper cost about as much as the
            // entries it held.
            val byKey = HashMap<String, Array<Entry>>(grouped.size * 4 / 3 + 1)
            for ((key, entries) in grouped) {
                byKey[key] = entries.toTypedArray()
            }
            return SkipDbDumpIndex(
                byKey = byKey,
                generatedAt = dump.generatedAt,
                segmentCount = usableCount,
            )
        }

        private fun indexKey(imdbId: String, season: Int?, episode: Int?): String =
            if (season == null || episode == null) imdbId else "$imdbId:$season:$episode"
    }
}
