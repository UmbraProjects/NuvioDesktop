package com.nuvio.app.features.discover

import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.shouldTreatAsInProgressForContinueWatching

/**
 * Picks the titles the user started, stopped partway through, and never came back to.
 *
 * **This is the one Discover row that needs no network at all** — it is a query over local watch
 * progress, so it renders with no TMDB key, no addon, and no tracking provider connected.
 *
 * Distinct from Continue Watching in both directions:
 *  - CW surfaces *active* viewing, ordered by recency and capped. This row is what falls off the
 *    far end of that list, which is why [continueWatchingParentKeys] is subtracted rather than
 *    assumed — with a short history nothing falls off, and the two rows would be the same row.
 *  - A title only qualifies while it has a genuinely part-watched video sitting in it
 *    ([shouldTreatAsInProgressForContinueWatching]). Having *finished* an episode and not started
 *    the next one is a next-up case, which CW already owns; this row is specifically "you are
 *    thirty minutes into episode four".
 *
 * Pure and synchronous, so the selection rules are testable without a repository.
 */
fun selectFinishWhatYouStarted(
    entries: List<WatchProgressEntry>,
    continueWatchingParentKeys: Set<String>,
    now: Long,
    minIdleMs: Long,
    maxIdleMs: Long = FINISH_MAX_IDLE_MS,
    limit: Int = FINISH_ROW_LIMIT,
): List<WatchProgressEntry> {
    if (limit <= 0) return emptyList()

    return finishWhatYouStartedCandidates(entries, continueWatchingParentKeys)
        .filter { entry ->
            val idle = now - entry.lastUpdatedEpochMs
            // The ceiling is what keeps this from becoming a graveyard: something untouched for
            // years was not paused, it was abandoned, and offering it back is noise.
            idle in minIdleMs..maxIdleMs
        }
        .sortedByDescending { it.lastUpdatedEpochMs }
        .take(limit)
}

/**
 * Every part-watched title that is eligible *before* the idle threshold is applied — one entry per
 * title, Continue Watching's own rows already removed.
 *
 * Split out of [selectFinishWhatYouStarted] so the settings page can answer "how many titles does
 * the threshold I am dragging actually match?" without duplicating the eligibility rules. The
 * threshold is the one Discover setting whose effect is invisible until the row silently fails to
 * appear, and on a normal install most of its range cannot match anything: watch progress is
 * written per playback and cleared as titles finish, so the oldest entry is typically two to three
 * weeks old while the slider offers up to 180.
 */
fun finishWhatYouStartedCandidates(
    entries: List<WatchProgressEntry>,
    continueWatchingParentKeys: Set<String>,
): List<WatchProgressEntry> {
    if (entries.isEmpty()) return emptyList()
    return entries
        .asSequence()
        .filter { it.shouldTreatAsInProgressForContinueWatching() }
        .filter { it.parentMetaId.isNotBlank() && it.title.isNotBlank() }
        // One card per title, not per episode: a show abandoned mid-season typically has several
        // part-watched videos, and they are all the same "finish this".
        .groupBy { finishGroupKey(it.parentMetaType, it.parentMetaId) }
        .filterKeys { it !in continueWatchingParentKeys }
        .mapNotNull { (_, rows) -> rows.maxByOrNull { it.lastUpdatedEpochMs } }
        .toList()
}

/**
 * How the current threshold lands against the data actually on disk.
 *
 * [oldestIdleDays] is what makes an empty row diagnosable: "no titles match, and the oldest thing
 * you left unfinished was 20 days ago" tells the user their threshold is past the end of their
 * history, which no wording on a static description can.
 */
data class FinishWhatYouStartedReach(
    val matching: Int,
    val candidates: Int,
    val oldestIdleDays: Int?,
)

fun finishWhatYouStartedReach(
    entries: List<WatchProgressEntry>,
    continueWatchingParentKeys: Set<String>,
    now: Long,
    minIdleDays: Int,
    maxIdleMs: Long = FINISH_MAX_IDLE_MS,
): FinishWhatYouStartedReach {
    val candidates = finishWhatYouStartedCandidates(entries, continueWatchingParentKeys)
        .map { now - it.lastUpdatedEpochMs }
        .filter { it in 0..maxIdleMs }
    val minIdleMs = minIdleDays * MILLIS_PER_DAY
    return FinishWhatYouStartedReach(
        matching = candidates.count { it >= minIdleMs }.coerceAtMost(FINISH_ROW_LIMIT),
        candidates = candidates.size,
        oldestIdleDays = candidates.maxOrNull()?.let { (it / MILLIS_PER_DAY).toInt() },
    )
}

/** Key for one title across its episodes; matches the shape used for the CW exclusion set. */
fun finishGroupKey(parentMetaType: String, parentMetaId: String): String =
    "${parentMetaType.trim().lowercase()}:${parentMetaId.trim()}"

const val MILLIS_PER_DAY: Long = 24L * 60 * 60 * 1000

/** Past this, a title is forgotten rather than unfinished. Deliberately not user-facing. */
const val FINISH_MAX_IDLE_MS: Long = 730L * 24 * 60 * 60 * 1000

/**
 * Matches the generated rows' length. Unlike them this costs nothing to raise — the candidates are
 * already in memory — so the only question is how many part-watched titles are worth offering back,
 * and a row that ends sooner than its neighbours looks broken rather than short.
 */
const val FINISH_ROW_LIMIT = 50
