package com.nuvio.app.features.watchprogress

import co.touchlab.kermit.Logger
import com.nuvio.app.features.home.CompletedSeriesCandidate
import com.nuvio.app.features.watching.domain.daysUntilExplicitRelease

/**
 * Traces why a Continue Watching row did — or did not — end up with a release badge.
 *
 * The Up Next pipeline drops a series at any of seven independent stages, and every one of them
 * fails the same way from the outside: no card, or a card with the generic "Up Next" badge. That
 * made real reports ("New Episode shows on mobile, not here", "the tile vanished") impossible to
 * act on without waiting for the next real-world air date to come round again.
 *
 * Emits one line per series per pass, deduplicated on content, under the `NextUpDiag` tag. On
 * desktop that lands in `nuvio.log` via the stdout tee. Set [ENABLED] to false to compile it out
 * of the hot path entirely.
 */
object NextUpDiagnostics {

    const val ENABLED = true

    private val log = Logger.withTag("NextUpDiag")

    /**
     * Last line emitted per key, so a recomposition storm does not repeat a hundred identical
     * lines. Cleared whenever a pass starts from scratch.
     *
     * Copy-on-write behind a volatile reference rather than a mutable map: the resolver logs from
     * several `Dispatchers.Default` coroutines at once, and a shared mutable map would eventually
     * throw a ConcurrentModificationException — turning a diagnostic into a crash. Losing a race
     * here only costs a duplicated log line.
     */
    @Volatile
    private var lastLineByKey: Map<String, String> = emptyMap()

    fun reset() {
        if (!ENABLED) return
        lastLineByKey = emptyMap()
    }

    /** Stage 1: which stores are allowed to seed Up Next at all. */
    fun logSourceGate(
        continueWatchingSource: String,
        remoteSourceActive: Boolean,
        seedFromNuvioSyncEnabled: Boolean,
        progressEntryCount: Int,
        watchedItemCount: Int,
        watchedSeedCount: Int,
    ) {
        if (!ENABLED) return
        val verdict = when {
            !remoteSourceActive -> "local source — full watched history seeds Up Next"
            seedFromNuvioSyncEnabled -> "remote source + seedNextUpFromNuvioSync ON — watched history still seeds Up Next"
            else -> "remote source + seedNextUpFromNuvioSync OFF — watched history DISCARDED as a seed"
        }
        emit(
            key = "source-gate",
            line = "source=$continueWatchingSource remoteActive=$remoteSourceActive " +
                "seedFromNuvioSync=$seedFromNuvioSyncEnabled | progressEntries=$progressEntryCount " +
                "watchedItems=$watchedItemCount -> watchedSeeds=$watchedSeedCount | $verdict",
        )
    }

    /** Stage 2: the provider day-cap, and stage 3, in-progress suppression. */
    internal fun logSeedFunnel(
        allSeeds: List<CompletedSeriesCandidate>,
        afterDayCap: List<CompletedSeriesCandidate>,
        afterSuppression: List<CompletedSeriesCandidate>,
        traktActive: Boolean,
        traktDaysCap: Int,
        simklActive: Boolean,
        simklDaysCap: Int,
        nowEpochMs: Long,
    ) {
        if (!ENABLED) return
        emit(
            key = "seed-funnel",
            line = "seeds: ${allSeeds.size} -> ${afterDayCap.size} (day cap) -> " +
                "${afterSuppression.size} (in-progress suppression) | " +
                "traktActive=$traktActive traktCap=${traktDaysCap}d " +
                "simklActive=$simklActive simklCap=${simklDaysCap}d",
        )

        val keptAfterCap = afterDayCap.mapTo(mutableSetOf()) { it.content.id }
        allSeeds.filterNot { it.content.id in keptAfterCap }.forEach { dropped ->
            emit(
                key = "cap:${dropped.content.id}",
                line = "DROPPED@day-cap ${dropped.content.id} seed=S${dropped.seasonNumber}E${dropped.episodeNumber} " +
                    "markedAt=${dropped.markedAtEpochMs} (${daysAgo(dropped.markedAtEpochMs, nowEpochMs)}d ago) " +
                    "— older than the active provider window, so no Up Next card and no badge",
            )
        }

        val keptAfterSuppression = afterSuppression.mapTo(mutableSetOf()) { it.content.id }
        afterDayCap.filterNot { it.content.id in keptAfterSuppression }.forEach { dropped ->
            emit(
                key = "suppressed:${dropped.content.id}",
                line = "DROPPED@in-progress-suppression ${dropped.content.id} " +
                    "seed=S${dropped.seasonNumber}E${dropped.episodeNumber} " +
                    "— a partially watched episode is newer than this completed seed",
            )
        }
    }

    /** Stage 4: resolving the seed into an actual next episode. */
    fun logResolutionRejected(
        contentId: String,
        seedSeasonNumber: Int,
        seedEpisodeNumber: Int,
        stage: String,
    ) {
        if (!ENABLED) return
        emit(
            key = "resolve:$contentId",
            line = "DROPPED@$stage $contentId seed=S${seedSeasonNumber}E$seedEpisodeNumber",
        )
    }

    /** Stages 5-7: the resolved card, its air-date inputs and the badge verdict. */
    fun logResolvedCard(
        contentId: String,
        title: String,
        seedSeasonNumber: Int,
        seedEpisodeNumber: Int,
        seedMarkedAtEpochMs: Long,
        nextSeasonNumber: Int?,
        nextEpisodeNumber: Int?,
        releasedIso: String?,
        todayIsoDate: String,
        alertState: ReleaseAlertState,
        origin: String,
    ) {
        if (!ENABLED) return
        val nowMs = WatchProgressClock.nowEpochMs()
        val releaseEpoch = parseReleaseDateToEpochMs(releasedIso)
        val daysUntil = daysUntilExplicitRelease(
            todayIsoDate = todayIsoDate,
            releasedDate = releasedIso,
        )
        val airDateBadge = when {
            releasedIso.isNullOrBlank() -> "none (no release date)"
            releaseEpoch != null && nowMs >= releaseEpoch -> "none (already aired)"
            daysUntil == null -> "none (release date not a calendar date: $releasedIso)"
            daysUntil < 0 -> "none (release date in the past)"
            else -> "days-until=$daysUntil (0=today, 1=tomorrow, 2..7=countdown, >7=formatted date)"
        }
        emit(
            key = "card:$contentId",
            line = "CARD [$origin] \"$title\" ($contentId) " +
                "seed=S${seedSeasonNumber}E$seedEpisodeNumber " +
                "markedAt=$seedMarkedAtEpochMs (${daysAgo(seedMarkedAtEpochMs, nowMs)}d ago) | " +
                "next=S${nextSeasonNumber}E$nextEpisodeNumber released=${releasedIso ?: "null"} " +
                "releaseEpoch=${releaseEpoch ?: "null"} | today=$todayIsoDate | " +
                "airDateBadge=$airDateBadge | " +
                "releaseAlert=${alertState.isReleaseAlert} newSeason=${alertState.isNewSeasonRelease} " +
                "reason=${alertState.reason}",
        )
    }

    private fun daysAgo(thenEpochMs: Long, nowEpochMs: Long): Long =
        if (thenEpochMs <= 0L) -1L else (nowEpochMs - thenEpochMs) / 86_400_000L

    private fun emit(key: String, line: String) {
        if (lastLineByKey[key] == line) return
        lastLineByKey = lastLineByKey + (key to line)
        log.i { line }
    }
}
