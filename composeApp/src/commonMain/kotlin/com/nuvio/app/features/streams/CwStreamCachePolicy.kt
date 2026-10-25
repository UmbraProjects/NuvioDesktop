package com.nuvio.app.features.streams

import com.nuvio.app.features.debrid.DebridStreamQuality

/**
 * What the Continue Watching stream cache knows about one target from the row it sits in.
 *
 * [releasedEpochMs] is the card's own `released`, which for a Next Up card is the *next* episode's
 * date. Null when the card has none or it could not be parsed.
 */
internal data class CwStreamTarget(
    val releasedEpochMs: Long?,
    val isEpisode: Boolean,
)

/**
 * Whether a target's provider answers are worth keeping for days (Docs/plans/cw-stream-cache-plan.md
 * §2). Pure, so the gates can be tested without a store, a clock or a repository.
 *
 * Every refusal has a reason, and the cache logs it. Silent early returns cost the prefetch work
 * several rounds of diagnosis.
 */
internal object CwStreamCachePolicy {
    const val MIN_AGE_DAYS = 3
    const val MIN_RESULTS = 10
    const val MAX_AGE_DAYS = 7
    const val MAX_TARGETS = 40

    /** An entry younger than this is the answer; older ones are shown first and then refreshed. */
    const val SKIP_REFRESH_UNDER_MS = 6L * 60L * 60L * 1000L

    /**
     * How long a target may be missing from the Continue Watching rows before it is dropped.
     *
     * Not zero: the rows Home publishes fill in source by source after launch, so a target can be
     * briefly absent from a list that is still loading. A day is long enough to ride that out and
     * short enough that a finished title does not linger.
     */
    const val LEFT_CONTINUE_WATCHING_GRACE_MS = 24L * 60L * 60L * 1000L

    const val DAY_MS = 24L * 60L * 60L * 1000L

    sealed interface Decision {
        data object Keep : Decision
        data class Skip(val reason: String) : Decision
    }

    private val lowQualityReleases = setOf(
        DebridStreamQuality.CAM,
        DebridStreamQuality.TS,
        DebridStreamQuality.TC,
        DebridStreamQuality.SCR,
    )

    /**
     * Checks the three gates against every row the target's providers returned, already trimmed by
     * [storableRows]. [target] is null when the title is not in Continue Watching at all.
     */
    fun evaluate(
        target: CwStreamTarget?,
        rows: List<StreamItem>,
        nowMs: Long,
        traitsOf: (StreamItem) -> StreamTraits = StreamTraitDetector::detect,
    ): Decision {
        if (target == null) return Decision.Skip("not in Continue Watching")
        val released = target.releasedEpochMs ?: return Decision.Skip("no release date")
        val ageMs = nowMs - released
        if (ageMs < MIN_AGE_DAYS * DAY_MS) {
            return Decision.Skip("released under $MIN_AGE_DAYS days ago")
        }
        val distinct = distinctResultCount(rows)
        if (distinct < MIN_RESULTS) return Decision.Skip("$distinct results, under $MIN_RESULTS")
        if (!target.isEpisode && rows.none { it.isGoodMovieRelease(traitsOf) }) {
            return Decision.Skip("no 1080p+ non-cam release")
        }
        return Decision.Keep
    }

    /**
     * Counts releases the way the picker's merged list does ([StreamSourceMerge.dedupeKey]), so 40
     * rows of the same three releases from different addons count as three. Rows with no reliable
     * identity fall back to their link or label and count once each.
     */
    fun distinctResultCount(rows: List<StreamItem>): Int =
        rows.mapTo(HashSet()) { row ->
            StreamSourceMerge.dedupeKey(row)
                ?: row.directPlaybackUrl?.let { "url:$it" }
                ?: "label:${row.addonId}:${row.streamLabel}:${row.streamSubtitle.orEmpty()}"
        }.size

    private fun StreamItem.isGoodMovieRelease(traitsOf: (StreamItem) -> StreamTraits): Boolean {
        val traits = traitsOf(this)
        return traits.resolution.value >= 1080 && traits.quality !in lowQualityReleases
    }

    /**
     * Rows as they are kept on disk.
     *
     * Rows whose link carries expiring credentials are dropped: replayed days later they fail. What
     * a live cache check added is cleared, so a replayed row is checked again rather than trusted
     * on a days-old verdict.
     */
    fun storableRows(rows: List<StreamItem>): List<StreamItem> =
        rows.mapNotNull { row ->
            val link = row.playableDirectUrl
            if (link != null && link.hasLikelyExpiringPlaybackCredentials()) return@mapNotNull null
            row.copy(
                badges = emptyList(),
                debridCacheStatus = null,
                resolvedTorrentName = null,
                sourceVideoFileCount = null,
                sourceTotalSizeBytes = null,
                cachedAtMs = null,
            )
        }
}
