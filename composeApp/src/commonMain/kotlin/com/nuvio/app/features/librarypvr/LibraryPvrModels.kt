package com.nuvio.app.features.librarypvr

import com.nuvio.app.features.locallibrary.LocalMediaItem
import com.nuvio.app.features.locallibrary.isLocalLibraryId
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

    fun isMonitored(contentId: String): Boolean = monitoredFor(contentId) != null

    fun monitoredFor(contentId: String): MonitoredItem? =
        monitoredItems.firstOrNull { it.matchesContentId(contentId) }
}

/**
 * Every content id this title is known by, not just the one it was stored under.
 *
 * The id a title is addressed by is a *choice* — [com.nuvio.app.features.metadata.AnimeIdPreference]
 * decides whether anime uses its franchise id or its own entry's — and that choice can change after
 * this item was written. Comparing stored ids by equality made a monitor stop recognising its own
 * local files and its own finished downloads the moment it did, which no migration can fully repair:
 * franchise → native is ambiguous, since one IMDb id covers every season's entry. Matching on any
 * shared id sidesteps the question entirely and needs no migration in either direction.
 */
internal fun MonitoredItem.knownContentIds(): Set<String> = buildSet {
    add(contentId)
    addAll(nativeContentIds())
    addAll(franchiseContentIds())
}

/**
 * The ids that address this exact entry and nothing else.
 *
 * A Kitsu/MAL entry is one season of one show, so a shared native id is proof of identity in a way
 * a shared franchise id is not.
 */
internal fun MonitoredItem.nativeContentIds(): Set<String> = buildSet {
    kitsuId?.let { add("kitsu:$it") }
    malId?.let { add("mal:$it") }
}

/**
 * The ids that address the whole franchise, which several entries can legitimately share.
 *
 * Every season of an anime maps onto one IMDb/TMDB record, so these identify a title only when no
 * stronger id agrees and exactly one candidate carries them. See [MonitorIdMatch].
 */
internal fun MonitoredItem.franchiseContentIds(): Set<String> = buildSet {
    imdbId?.takeIf { it.isNotBlank() }?.let(::add)
    tmdbId?.let { add("tmdb:$it") }
}

/**
 * How strongly some candidate identifies a monitored title, strongest first.
 *
 * Declaration order is the ranking — [resolveMonitoredExclusively] compares by it.
 */
internal enum class MonitorIdMatch { EXACT, NATIVE, FRANCHISE }

/** True when [candidate] is any of the ids this title is known by. See [knownContentIds]. */
internal fun MonitoredItem.matchesContentId(candidate: String): Boolean =
    matchStrengthForContentId(candidate) != null

/** [matchesContentId] with the strength of the match, or null when nothing matched. */
internal fun MonitoredItem.matchStrengthForContentId(candidate: String): MonitorIdMatch? {
    val wanted = candidate.trim().takeIf { it.isNotBlank() } ?: return null
    fun Set<String>.holds(): Boolean = any { it.equals(wanted, ignoreCase = true) }
    return when {
        contentId.equals(wanted, ignoreCase = true) -> MonitorIdMatch.EXACT
        nativeContentIds().holds() -> MonitorIdMatch.NATIVE
        franchiseContentIds().holds() -> MonitorIdMatch.FRANCHISE
        else -> null
    }
}

/**
 * The monitored titles [candidate] could address, strongest match first.
 *
 * Several monitors can share one franchise id, so a caller that needs exactly one must disambiguate
 * with something outside the id — the grab record's `monitoredItemId`, which is the explicit link.
 */
internal fun List<MonitoredItem>.rankedForContentId(candidate: String): List<MonitoredItem> =
    mapNotNull { item -> item.matchStrengthForContentId(candidate)?.let { it to item } }
        .sortedBy { (strength, _) -> strength }
        .map { (_, item) -> item }

/**
 * Whether this local title is the one [monitored] is monitoring.
 *
 * Matched on any shared id rather than on the two `contentId` values agreeing: which id each side
 * derives depends on the anime identity preference, and the monitor's was fixed when it was created.
 *
 * Answers "could be" — several sibling seasons share one franchise id and all match. Anything that
 * must act on a single title, and everything destructive, needs [resolveMonitoredExclusively].
 */
internal fun LocalMediaItem.matchesMonitored(monitored: MonitoredItem): Boolean =
    matchStrengthFor(monitored) != null

/**
 * [matchesMonitored] with the strength of the match, or null when nothing matched.
 *
 * Compares stored ids only. `LocalMediaItem.contentId` is derived from the live identity preference,
 * and matching must not change meaning because a setting did — that is the bug the whole known-ids
 * scheme exists to avoid.
 */
internal fun LocalMediaItem.matchStrengthFor(monitored: MonitoredItem): MonitorIdMatch? {
    val mine = knownContentIds
    fun Set<String>.sharedWithMine(): Boolean =
        any { theirs -> mine.any { it.equals(theirs, ignoreCase = true) } }
    return when {
        // The scanner's own key names one scanned item and nothing else.
        monitored.contentId.isLocalLibraryId() &&
            mine.any { it.equals(monitored.contentId, ignoreCase = true) } -> MonitorIdMatch.EXACT
        monitored.nativeContentIds().sharedWithMine() -> MonitorIdMatch.NATIVE
        monitored.franchiseContentIds().sharedWithMine() -> MonitorIdMatch.FRANCHISE
        else -> null
    }
}

/**
 * The one local title [monitored] is monitoring, or null when that cannot be decided.
 *
 * Callers that delete files must use this rather than `firstOrNull { matchesMonitored(it) }`: two
 * seasons of an anime share one IMDb/TMDB id, so the loose match returns both and `firstOrNull`
 * picks by list order. Selecting the wrong sibling there deletes a file the user still has.
 *
 * Only the strongest tier present is considered, so an exact id beats a franchise id rather than
 * competing with it; a tie inside that tier is genuinely ambiguous and resolves to null.
 */
internal fun List<LocalMediaItem>.resolveMonitoredExclusively(monitored: MonitoredItem): LocalMediaItem? {
    val ranked = mapNotNull { item -> item.matchStrengthFor(monitored)?.let { it to item } }
    val best = ranked.minOfOrNull { (strength, _) -> strength } ?: return null
    return ranked.filter { (strength, _) -> strength == best }
        .singleOrNull()
        ?.second
}

/**
 * The local titles whose files count as [monitored]'s, strongest tier only.
 *
 * Non-destructive counterpart to [resolveMonitoredExclusively]: an ambiguous franchise tier is kept
 * (missing-episode maths would rather over-count than re-download something already on disk), but a
 * sibling never contributes once an exact or native match exists.
 */
internal fun List<LocalMediaItem>.matchingMonitored(monitored: MonitoredItem): List<LocalMediaItem> {
    val ranked = mapNotNull { item -> item.matchStrengthFor(monitored)?.let { it to item } }
    val best = ranked.minOfOrNull { (strength, _) -> strength } ?: return emptyList()
    return ranked.filter { (strength, _) -> strength == best }.map { (_, item) -> item }
}
