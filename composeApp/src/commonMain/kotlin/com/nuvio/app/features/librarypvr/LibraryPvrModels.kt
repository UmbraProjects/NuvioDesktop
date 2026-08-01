package com.nuvio.app.features.librarypvr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a monitored item watches for.
 *
 * The `@SerialName`s are the pre-episode-picker wire values and must stay: they migrate existing
 * profiles in place. An old FUTURE_EPISODES item has an empty selection, which makes
 * [SELECTED_PLUS_FUTURE] behave exactly as it did before.
 */
@Serializable
enum class MonitorMode {
    /** Series: the episode selection, plus anything airing after the item was added. */
    @SerialName("FUTURE_EPISODES")
    SELECTED_PLUS_FUTURE,

    /** Series: every already-aired episode not yet on disk (can be a lot for a long run). */
    ALL_MISSING,

    /** Series: only what the episode picker selected — nothing is added as new episodes air. */
    @SerialName("SELECTED_SEASONS")
    SELECTED_ONLY,

    /** Movie: grab once it becomes digitally available. */
    MOVIE_WHEN_AVAILABLE,
}

/**
 * A title the library auto-downloader watches. Ids mirror [com.nuvio.app.features.locallibrary.LocalMediaItem]
 * so grabs flow through the same stream/scrobble pipeline. [targetFolderId] scopes downloads inside a
 * configured local-library folder.
 */
@Serializable
data class MonitoredItem(
    val id: String,
    val contentId: String,          // tt… / tmdb:… / kitsu:… — same shapes as LocalMediaItem.contentId
    val contentType: String,        // "movie" | "series"
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val kitsuId: Int? = null,
    val malId: Int? = null,
    val isAnime: Boolean = false,
    val title: String,
    val year: Int? = null,
    val poster: String? = null,
    val background: String? = null,
    val targetFolderId: String,     // LocalFolder.id — download destination
    val mode: MonitorMode,
    val monitoredSeasons: Set<Int> = emptySet(),
    /**
     * Per-episode opt-in/opt-out keyed `"season:episode"`, overriding whatever [monitoredSeasons]
     * implies. Only entries that disagree with the season default are stored, so a season toggled
     * wholesale leaves no episode entries behind. See [selectsEpisode].
     */
    val episodeOverrides: Map<String, Boolean> = emptyMap(),
    val paused: Boolean = false,
    val addedAtEpochMs: Long,
    val lastCheckedAtEpochMs: Long? = null,
    val lastError: String? = null,
    // Records the most recent TMDB availability probe for diagnostics/UI.
    val lastAvailabilityCheckEpochMs: Long? = null,
) {
    val isSeries: Boolean get() = contentType.equals("series", ignoreCase = true)
    val isMovie: Boolean get() = !isSeries

    /**
     * True when this episode is part of the picker's selection: an explicit per-episode override if
     * one exists, otherwise whether its season is monitored. Says nothing about release dates —
     * [MonitorMode.SELECTED_PLUS_FUTURE] layers that on top in the scheduler.
     */
    fun selectsEpisode(season: Int, episode: Int): Boolean =
        episodeOverrides[episodeSelectionKey(season, episode)] ?: (season in monitoredSeasons)

    /** True when every episode in [episodes] of [season] is selected. */
    fun selectsWholeSeason(season: Int, episodes: List<Int>): Boolean =
        episodes.isNotEmpty() && episodes.all { selectsEpisode(season, it) }

    /** True when some but not all of [episodes] are selected. */
    fun selectsPartOfSeason(season: Int, episodes: List<Int>): Boolean {
        val selected = episodes.count { selectsEpisode(season, it) }
        return selected > 0 && selected < episodes.size
    }
}

internal fun episodeSelectionKey(season: Int, episode: Int): String = "$season:$episode"

@Serializable
enum class GrabStatus {
    QUEUED,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    SKIPPED,
    CANCELLED,
}

/**
 * History + dedup record for one grab attempt: never grab the same episode twice. Keyed logically
 * by (monitoredItemId, season, episode); a movie grab leaves both null.
 */
@Serializable
data class GrabRecord(
    val id: String,
    val monitoredItemId: String,
    val title: String,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val status: GrabStatus,
    val downloadId: String? = null,     // link into DownloadsRepository
    val streamLabel: String? = null,
    /** Stable fingerprints already attempted for this episode/movie, persisted across retries. */
    val attemptedStreamKeys: Set<String> = emptySet(),
    val streamKey: String? = null,
    /** Delete superseded local files after this replacement transfer succeeds. */
    val replaceExistingFile: Boolean = false,
    val failReason: String? = null,
    /** How many times this episode/movie has been re-attempted after a terminal failure. */
    val retryCount: Int = 0,
    /**
     * When a terminal grab is still inside its retry budget, the earliest time the scheduler may
     * try again. null means "no retry pending" — either it never failed, or the budget is spent.
     * A grab with a pending retry blocks later episodes of the same series from starting.
     */
    val nextAttemptAtEpochMs: Long? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
) {
    /** Stable per-episode key used to dedup across scheduler cycles. */
    val logicalKey: String
        get() = "$monitoredItemId|${season ?: -1}|${episode ?: -1}"

    val isInFlight: Boolean
        get() = status == GrabStatus.QUEUED || status == GrabStatus.DOWNLOADING

    /** A terminal grab that the scheduler still intends to re-attempt. */
    val isAwaitingRetry: Boolean
        get() = !isInFlight && status != GrabStatus.COMPLETED && nextAttemptAtEpochMs != null
}

/**
 * User-facing settings for library auto-download. Every field has a working default so the feature
 * needs no setup. Stream selection reuses the user's existing auto-play tuning unless overridden.
 */
@Serializable
data class LibraryPvrSettings(
    val enabled: Boolean = true,
    val checkIntervalHours: Int = DEFAULT_CHECK_INTERVAL_HOURS,
    /** Catch up on launch only when a full configured interval elapsed while Nuvio was closed. */
    val checkOnAppStart: Boolean = true,
    /** Pause automatic transfers during in-app playback and resume only those paused by playback. */
    val pauseDownloadsWhilePlaying: Boolean = false,
    /** Wait this long after the metadata release/availability timestamp before looking for sources. */
    val postReleaseDelayHours: Int = 0,
    /** Internal persisted scheduler clock used to make launch catch-up compare real elapsed time. */
    val lastCompletedCheckEpochMs: Long? = null,
    /** One-time acknowledgement for the destructive per-title force-start action. */
    val forceStartWarningAccepted: Boolean = false,
    val maxConcurrentDownloads: Int = DEFAULT_MAX_CONCURRENCY,
    /** Aggregate automatic-download bandwidth in Mbit/s. Zero means unlimited. */
    val bandwidthLimitMbps: Int = 0,
    /** When true, downloads pick streams the same way auto-play does (the user's tuned behaviour). */
    val useAutoPlayPreferenceForDownloads: Boolean = true,
    /**
     * When an automatic grab lands on a season pack the debrid service can list, take the rest of
     * the wanted episodes from that same pack instead of searching for each one separately.
     *
     * The scheduler otherwise grabs one episode per cycle, so a ten-episode gap costs ten searches
     * spread over ten check intervals. The pack is already paid for; reading it is one call.
     */
    val expandSeasonPacks: Boolean = true,
    val minSizeBytes: Long? = null,
    val maxSizeBytes: Long? = null,
    /** Separate storage ceilings; null means unlimited. */
    val maxEpisodeSizeBytes: Long? = null,
    val maxMovieSizeBytes: Long? = null,
    /**
     * Unused since episodes became strictly in-order: a series now has at most one unfinished
     * episode at a time, so the per-cycle cap has nothing left to bound. Retained so existing
     * persisted settings keep round-tripping; [maxConcurrentDownloads] governs parallelism, and it
     * now applies across monitored titles rather than within one series.
     */
    val maxEpisodesPerSeriesPerCycle: Int = DEFAULT_MAX_EPISODES_PER_SERIES_PER_CYCLE,
) {
    val checkIntervalMs: Long
        get() = checkIntervalHours.coerceIn(1, 168).toLong() * 60L * 60L * 1000L

    companion object {
        const val DEFAULT_CHECK_INTERVAL_HOURS = 6
        const val DEFAULT_MAX_CONCURRENCY = 2
        const val DEFAULT_MAX_EPISODES_PER_SERIES_PER_CYCLE = 5
    }
}

data class LibraryPvrUiState(
    val settings: LibraryPvrSettings = LibraryPvrSettings(),
    val monitoredItems: List<MonitoredItem> = emptyList(),
    val grabHistory: List<GrabRecord> = emptyList(),
    val isLoaded: Boolean = false,
    val isChecking: Boolean = false,
    val lastCheckAtEpochMs: Long? = null,
) {
    fun monitoredItem(id: String): MonitoredItem? = monitoredItems.firstOrNull { it.id == id }

    fun isMonitored(contentId: String): Boolean =
        monitoredItems.any { it.contentId == contentId }

    fun monitoredFor(contentId: String): MonitoredItem? =
        monitoredItems.firstOrNull { it.contentId == contentId }
}
