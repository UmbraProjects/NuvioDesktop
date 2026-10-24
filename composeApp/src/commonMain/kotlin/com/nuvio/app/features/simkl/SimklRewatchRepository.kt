package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.metadata.MediaIdResolver
import com.nuvio.app.features.metadata.toSimklIds
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val SIMKL_BASE_URL = "https://api.simkl.com"
private const val MAX_REWATCH_SESSIONS_PER_ITEM = 50

@Serializable
internal enum class SimklRewatchKind { MOVIE, SHOW, ANIME }

@Serializable
internal data class SimklRewatchSession(
    val rewatchId: Int,
    val kind: SimklRewatchKind,
    val contentId: String,
    val title: String,
    val ids: SimklScrobbleRepository.SimklIds,
    val status: String,
    val startedAtEpochMs: Long = 0L,
    val lastWatchedAt: String? = null,
    val watchedEpisodeKeys: Set<String> = emptySet(),
    val watchedEpisodesCount: Int = 0,
    val totalEpisodesCount: Int = 0,
)

internal data class SimklRewatchUiState(
    val sessions: List<SimklRewatchSession> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

internal sealed interface SimklRewatchStartResult {
    data class Started(val session: SimklRewatchSession) : SimklRewatchStartResult
    data class Failed(val reason: String) : SimklRewatchStartResult
}

/**
 * Explicit SIMKL rewatch sessions. Ordinary scrobbles never enter this repository: only the user
 * action creates a session, after which playback-completion edges may write to its pinned id.
 */
internal object SimklRewatchRepository {
    private val log = Logger.withTag("SimklRewatch")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
    private val _uiState = MutableStateFlow(SimklRewatchUiState())
    val uiState: StateFlow<SimklRewatchUiState> = _uiState.asStateFlow()

    private var sessions = mutableListOf<SimklRewatchSession>()
    private var lastActivitiesAt: String? = null
    private var lastRemovedStamp: String? = null
    private var loaded = false

    /**
     * `rewatchId:episodeKey` pairs SIMKL answered with `added=0` this run. SIMKL collapses a second
     * write of an episode watched in the last 48 hours (the original watch counts), and repeating
     * it only spends quota: one finished episode used to cost three identical POSTs, from the
     * scrobbler and the completion edge.
     */
    private val ignoredBySimkl = mutableSetOf<String>()

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val stored = SimklRewatchStorage.loadPayload()
            ?.takeIf(String::isNotBlank)
            ?.let { payload ->
                runCatching { json.decodeFromString<StoredRewatchPayload>(payload) }
                    .getOrNull()
            }
        sessions = stored?.sessions?.toMutableList() ?: mutableListOf()
        lastActivitiesAt = stored?.lastActivitiesAt
        lastRemovedStamp = stored?.lastRemovedStamp
        publish()
    }

    fun onProfileChanged() {
        loaded = false
        ignoredBySimkl.clear()
        sessions = mutableListOf()
        lastActivitiesAt = null
        lastRemovedStamp = null
        ensureLoaded()
    }

    fun clearLocalState() {
        loaded = true
        ignoredBySimkl.clear()
        sessions = mutableListOf()
        lastActivitiesAt = null
        lastRemovedStamp = null
        SimklRewatchStorage.clearPayload()
        publish()
    }

    fun refreshAsync(full: Boolean = false) {
        scope.launch { refreshNow(full) }
    }

    /**
     * SIMKL's two-phase sync. Phase 1 — the first read, or [full] — is the one `extended=full` read
     * without `date_from`; SIMKL's all-items reference allows that payload only as a one-time
     * baseline. After it, a delta (`date_from`) when activities moved, nothing when they did not.
     *
     * A delta never reports a session SIMKL deleted, so deletions are reconciled separately, as the
     * sync guide prescribes: an `extended=simkl_ids_only` read diffed against the stored sessions,
     * run only when `removed_from_list` moves ("your cue to run the check") and only while sessions
     * are stored. This used to re-read the whole `extended=full` payload then, and at least daily.
     */
    suspend fun refreshNow(full: Boolean = false): Boolean {
        ensureLoaded()
        if (!rewatchRequestsAllowed()) return false
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        return runCatching {
            if (!SimklAuthRepository.hasUsableToken()) error("SIMKL is not connected.")
            // Read before the all-items request, so a change landing while it is in flight is
            // newer than the stored watermark and the next delta still sees it.
            val activities = SimklAuthRepository.fetchActivities()
                ?: error("SIMKL activity state could not be read.")
            val removedStamp = simklRemovedFromListStamp(activities)
            var needsFull = full || lastActivitiesAt.isNullOrBlank()
            val needsDeletionCheck = !needsFull && sessions.isNotEmpty() &&
                removedStamp != null && removedStamp != lastRemovedStamp
            if (needsDeletionCheck && removedStamp != null && !reconcileDeletions(removedStamp)) {
                // The ids-only answer could not say which sessions survive; fall back to one baseline.
                needsFull = true
            }
            val deltaFrom = lastActivitiesAt?.takeIf(String::isNotBlank)?.takeUnless { needsFull }
            if (deltaFrom != null && activities.all == deltaFrom) {
                publish()
                return@runCatching true
            }
            val dateFromQuery = deltaFrom?.let { "&date_from=${simklUrlEncode(it)}" }.orEmpty()
            val response = simklRequest(
                method = "GET",
                url = SimklAuthRepository.appendParams(
                    "$SIMKL_BASE_URL/sync/all-items/all" +
                        "?allow_rewatch=yes&extended=full&episode_watched_at=yes$dateFromQuery",
                ),
            )
            if (response.status !in 200..299) error("SIMKL rewatch sync failed (${response.status}).")
            val body = response.body.trim()
            val remote: List<SimklRewatchSession> = if (body.isEmpty() || body == "null" || body == "[]") {
                emptyList()
            } else {
                json.decodeFromString<SimklAllItemsResponse>(body).toRewatchSessions()
            }
            mutex.withLock {
                sessions = if (deltaFrom == null) {
                    remote.toMutableList()
                } else {
                    sessions.mergeRewatchDelta(remote)
                }
                lastActivitiesAt = activities.all?.takeIf(String::isNotBlank) ?: lastActivitiesAt
                if (deltaFrom == null) lastRemovedStamp = removedStamp ?: lastRemovedStamp
                persist()
                publish()
            }
            true
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.w(error) { "Failed to refresh SIMKL rewatches" }
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                errorMessage = error.message ?: "SIMKL rewatches could not be refreshed.",
            )
        }.getOrDefault(false)
    }

    /**
     * Drops stored sessions SIMKL no longer has, from the shared ids-only library read (see
     * [SimklDeletionCheck]). Returns false when the answer cannot decide that (see
     * [reconcileRewatchDeletions]), leaving sessions as is.
     */
    private suspend fun reconcileDeletions(removedStamp: String): Boolean {
        val present = SimklDeletionCheck.currentLibrary(removedStamp)
        return mutex.withLock {
            val kept = sessions.reconcileRewatchDeletions(present) ?: run {
                log.w { "SIMKL rewatch deletion check: ids-only rows carried no session ids; doing a full read" }
                return@withLock false
            }
            if (kept.size != sessions.size) {
                log.i { "SIMKL rewatch deletion check: dropped ${sessions.size - kept.size} session(s) SIMKL no longer has" }
            }
            sessions = kept.toMutableList()
            lastRemovedStamp = removedStamp
            persist()
            publish()
            true
        }
    }

    suspend fun startRewatch(item: MetaPreview): SimklRewatchStartResult {
        ensureLoaded()
        if (!SimklSettingsRepository.isRewatchTrackingEnabled()) {
            return SimklRewatchStartResult.Failed("Enable Track SIMKL rewatches in Settings first.")
        }
        if (!SimklAuthRepository.isAuthenticated.value) {
            return SimklRewatchStartResult.Failed("SIMKL is not connected.")
        }
        if (!SimklAuthRepository.uiState.value.canUseRewatches) {
            return SimklRewatchStartResult.Failed("SIMKL Pro or VIP is required for rewatch tracking.")
        }

        val kind = item.rewatchKind()
        val resolved = runCatching {
            MediaIdResolver.resolve(
                contentType = item.metadataType,
                parentMetaId = item.metadataId,
                videoId = item.defaultVideoId,
                title = item.name,
                isAnimeHint = kind == SimklRewatchKind.ANIME,
            )
        }.getOrElse { error ->
            return SimklRewatchStartResult.Failed(error.message ?: "The title could not be resolved for SIMKL.")
        }
        val ids = resolved.toSimklIds()
        if (!ids.hasRewatchIdentity()) {
            return SimklRewatchStartResult.Failed("No SIMKL-compatible id could be found for this title.")
        }
        if (sessions.count { it.ids.matches(ids) } >= MAX_REWATCH_SESSIONS_PER_ITEM) {
            return SimklRewatchStartResult.Failed("SIMKL allows at most 50 rewatch sessions per title.")
        }

        val response = postMutation(
            body = buildRewatchRequest(
                kind = kind,
                media = RewatchHistoryMedia(
                    title = item.name,
                    ids = ids,
                    isRewatch = true,
                    rewatchStatus = if (kind == SimklRewatchKind.MOVIE) "completed" else "active",
                ),
            ),
            retryPinnedWrite = false,
        ).getOrElse { error ->
            return SimklRewatchStartResult.Failed(error.message ?: "SIMKL rejected the rewatch session.")
        }
        val mutation = response.rewatchMutation()
            ?: return SimklRewatchStartResult.Failed(
                if (response.added.total == 0) "SIMKL did not create a rewatch session for this account."
                else "SIMKL did not return the new rewatch session id.",
            )
        val session = SimklRewatchSession(
            rewatchId = mutation.rewatchId,
            kind = kind,
            contentId = item.metadataId,
            title = item.name,
            ids = ids,
            status = mutation.rewatchStatus
                ?: if (kind == SimklRewatchKind.MOVIE) "completed" else "active",
            startedAtEpochMs = System.currentTimeMillis(),
        )
        mutex.withLock {
            sessions.removeAll { it.rewatchId == session.rewatchId && it.kind == session.kind }
            sessions += session
            persist()
            publish()
        }
        invalidateHistoryCaches()
        return SimklRewatchStartResult.Started(session)
    }

    fun onPlaybackCompleted(entry: WatchProgressEntry) {
        ensureLoaded()
        if (!rewatchRequestsAllowed()) return
        if (sessions.none { it.status.equals("active", true) && it.mayAddress(entry.parentMetaId) }) return
        scope.launch {
            runCatching {
                val item = SimklScrobbleRepository.buildItem(
                    contentType = entry.contentType,
                    parentMetaId = entry.parentMetaId,
                    videoId = entry.videoId,
                    title = entry.title,
                    seasonNumber = entry.seasonNumber,
                    episodeNumber = entry.episodeNumber,
                    isAnime = false,
                ) as? SimklScrobbleItem.Episode
                if (item == null) {
                    log.i { "SIMKL rewatch: ${entry.videoId} did not resolve to a SIMKL episode; not recorded" }
                } else {
                    recordEpisode(item)
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                log.w(error) { "Failed to append playback completion to SIMKL rewatch" }
            }
        }
    }

    /**
     * Whether a finished playback of [item] belongs to an active rewatch session rather than the
     * title's original watch.
     *
     * The scrobbler asks this before its completing `stop`: a plain `/scrobble/stop` always lands on
     * the canonical (original) watch, so during a rewatch every finished episode used to be written
     * to the original as well — or instead, when the pinned write below did not land — and SIMKL
     * then reports the original as restarted at the rewatched episodes.
     */
    fun hasActiveRewatchFor(item: SimklScrobbleItem.Episode): Boolean {
        ensureLoaded()
        if (!rewatchRequestsAllowed()) return false
        return sessions.findActiveFor(item) >= 0
    }

    /**
     * Appends one finished episode to its active rewatch session, pinned to the session's id.
     * Idempotent per episode, so the scrobbler and the playback-completion edge may both call it.
     * Returns whether the episode is now recorded in the session.
     */
    suspend fun recordEpisode(item: SimklScrobbleItem.Episode): Boolean = mutex.withLock {
        val index = sessions.findActiveFor(item)
        if (index < 0) {
            log.i { "SIMKL rewatch: no active session matches ${item.itemKey} (ids=${item.ids}); not recorded" }
            return@withLock false
        }
        val session = sessions[index]
        val kind = session.kind
        val episodeKey = if (kind == SimklRewatchKind.ANIME) {
            item.number.toString()
        } else {
            "${item.season}:${item.number}"
        }
        if (episodeKey in session.watchedEpisodeKeys) return@withLock true
        val ignoredKey = "${session.rewatchId}:$episodeKey"
        if (ignoredKey in ignoredBySimkl) return@withLock false

        // SIMKL's rewatch guide pins every appended episode to a watched_at; without it the write came back 201 with added=0.
        val watchedAt = kotlin.time.Instant.fromEpochMilliseconds(System.currentTimeMillis()).toString()
        val media = RewatchHistoryMedia(
            title = item.showTitle,
            ids = item.ids,
            isRewatch = true,
            rewatchId = session.rewatchId,
            rewatchStatus = "active",
            seasons = if (kind == SimklRewatchKind.SHOW) {
                listOf(RewatchHistorySeason(item.season, listOf(RewatchHistoryEpisode(item.number, watchedAt))))
            } else emptyList(),
            episodes = if (kind == SimklRewatchKind.ANIME) {
                listOf(RewatchHistoryEpisode(item.number, watchedAt))
            } else emptyList(),
        )
        val response = postMutation(buildRewatchRequest(kind, media), retryPinnedWrite = true).getOrThrow()
        val mutation = response.rewatchMutation()
        if (response.added.episodes == 0) {
            // SIMKL answered 2xx but stored nothing, so it is not recorded; but asking again will not
            // change the answer, so not again this run.
            ignoredBySimkl += ignoredKey
            log.w {
                "SIMKL rewatch #${session.rewatchId} (${session.title}): $episodeKey was not added (added=0); " +
                    "SIMKL ignores an episode already in the session or watched within the last 48h"
            }
            return@withLock false
        }
        log.i {
            "SIMKL rewatch #${session.rewatchId} (${session.title}): recorded $episodeKey, " +
                "added=${response.added.episodes} status=${mutation?.rewatchStatus ?: session.status}"
        }
        sessions[index] = session.copy(
            status = mutation?.rewatchStatus ?: session.status,
            watchedEpisodeKeys = session.watchedEpisodeKeys + episodeKey,
            watchedEpisodesCount = maxOf(session.watchedEpisodesCount + 1, response.added.episodes),
        )
        persist()
        publish()
        invalidateHistoryCaches()
        true
    }

    private fun List<SimklRewatchSession>.findActiveFor(item: SimklScrobbleItem.Episode): Int {
        val kind = if (item.isAnime) SimklRewatchKind.ANIME else SimklRewatchKind.SHOW
        return indexOfFirst { session ->
            session.kind == kind && session.status.equals("active", true) && session.ids.matches(item.ids)
        }
    }

    private suspend fun postMutation(
        body: RewatchHistoryRequest,
        retryPinnedWrite: Boolean,
    ): Result<RewatchMutationResponse> {
        val attempts = if (retryPinnedWrite) 2 else 1
        var lastFailure: Throwable? = null
        repeat(attempts) { attempt ->
            val result = runCatching {
                if (!SimklAuthRepository.hasUsableToken()) error("SIMKL is not connected.")
                val response = simklRequest(
                    method = "POST",
                    url = SimklAuthRepository.appendParams("$SIMKL_BASE_URL/sync/history?allow_rewatch=yes"),
                    body = json.encodeToString(body),
                )
                log.i { "SIMKL rewatch POST /sync/history ${response.status} req=${json.encodeToString(body)} resp=${response.body.take(600)}" }
                if (response.status !in 200..299) {
                    error("SIMKL rewatch update failed (${response.status}): ${response.body.take(160)}")
                }
                json.decodeFromString<RewatchMutationResponse>(response.body)
            }
            if (result.isSuccess) return result
            lastFailure = result.exceptionOrNull()
            if (attempt + 1 < attempts) delay(3_000L)
        }
        return Result.failure(lastFailure ?: IllegalStateException("SIMKL rewatch update failed."))
    }

    /** Whether `allow_rewatch=yes` may be sent at all: tracking on, connected, PRO or VIP. */
    internal fun requestsAllowed(): Boolean = rewatchRequestsAllowed()

    private fun rewatchRequestsAllowed(): Boolean =
        SimklSettingsRepository.isRewatchTrackingEnabled() &&
            SimklAuthRepository.isAuthenticated.value &&
            SimklAuthRepository.uiState.value.canUseRewatches

    private fun persist() {
        SimklRewatchStorage.savePayload(
            json.encodeToString(
                StoredRewatchPayload(
                    sessions = sessions,
                    lastActivitiesAt = lastActivitiesAt,
                    lastRemovedStamp = lastRemovedStamp,
                ),
            ),
        )
    }

    private fun publish() {
        _uiState.value = SimklRewatchUiState(sessions = sessions.toList(), isLoading = false)
    }

    private fun invalidateHistoryCaches() {
        SimklSettingsRepository.setLastLibraryActivitiesAt("")
        SimklSettingsRepository.setLastCwActivitiesAt("")
        SimklProgressRepository.refreshAsync()
    }
}

internal fun MutableList<SimklRewatchSession>.mergeRewatchDelta(
    delta: List<SimklRewatchSession>,
): MutableList<SimklRewatchSession> = apply {
    delta.forEach { changed ->
        removeAll { existing ->
            existing.kind == changed.kind && existing.rewatchId == changed.rewatchId
        }
        add(changed)
    }
}

/**
 * The stored sessions an `extended=simkl_ids_only` + `allow_rewatch=yes` read still lists, matched
 * by session id. Null when the read cannot decide: it lists no rewatch row at all (every session
 * deleted, or the ids-only shape leaves `is_rewatch` out — indistinguishable), or a rewatch row
 * without its `rewatch_id`. The caller then falls back to a full read rather than guess, because
 * wrongly dropping an active session sends that rewatch's episodes back to the original watch.
 */
internal fun List<SimklRewatchSession>.reconcileRewatchDeletions(
    present: SimklAllItemsResponse,
): List<SimklRewatchSession>? {
    if (isEmpty()) return this
    val rows = (present.shows + present.movies + present.anime).filter { it.isRewatch || it.rewatchId != null }
    if (rows.isEmpty() || rows.any { it.rewatchId == null }) return null
    val presentIds = rows.mapNotNullTo(mutableSetOf(), SimklAllItemsEntry::rewatchId)
    return filter { it.rewatchId in presentIds }
}

private fun MetaPreview.rewatchKind(): SimklRewatchKind {
    val normalizedType = metadataType.trim().lowercase()
    return when {
        normalizedType == "anime" || !animeType.isNullOrBlank() || carriesAnimeCatalogueId -> SimklRewatchKind.ANIME
        normalizedType in setOf("movie", "film") -> SimklRewatchKind.MOVIE
        else -> SimklRewatchKind.SHOW
    }
}

private fun SimklScrobbleRepository.SimklIds.hasRewatchIdentity(): Boolean =
    simkl != null || !imdb.isNullOrBlank() || tmdb != null || tvdb != null ||
        mal != null || kitsu != null || anilist != null || anidb != null

private fun SimklScrobbleRepository.SimklIds.matches(other: SimklScrobbleRepository.SimklIds): Boolean =
    (simkl != null && simkl == other.simkl) ||
        (!imdb.isNullOrBlank() && imdb == other.imdb) ||
        (tmdb != null && tmdb == other.tmdb) ||
        (tvdb != null && tvdb == other.tvdb) ||
        (mal != null && mal == other.mal) ||
        (kitsu != null && kitsu == other.kitsu) ||
        (anilist != null && anilist == other.anilist) ||
        (anidb != null && anidb == other.anidb)

private fun SimklRewatchSession.mayAddress(rawContentId: String): Boolean {
    if (contentId.equals(rawContentId, ignoreCase = true)) return true
    val raw = rawContentId.trim()
    return when {
        raw.startsWith("tt", ignoreCase = true) -> ids.imdb.equals(raw.substringBefore(':'), true)
        raw.startsWith("simkl:", ignoreCase = true) -> ids.simkl == raw.substringAfter(':').substringBefore(':').toIntOrNull()
        raw.startsWith("tmdb:", ignoreCase = true) -> ids.tmdb == raw.substringAfter(':').substringBefore(':').toIntOrNull()
        raw.startsWith("tvdb:", ignoreCase = true) -> ids.tvdb == raw.substringAfter(':').substringBefore(':').toIntOrNull()
        else -> false
    }
}

internal fun SimklAllItemsResponse.toRewatchSessions(): List<SimklRewatchSession> = buildList {
    fun addEntries(entries: List<SimklAllItemsEntry>, kind: SimklRewatchKind) {
        entries.filter(SimklAllItemsEntry::isRewatch).forEach { entry ->
            val mediaTitle: String?
            val mediaIds: SimklMediaIds
            when (kind) {
                SimklRewatchKind.MOVIE -> {
                    val media = entry.movie ?: return@forEach
                    mediaTitle = media.title
                    mediaIds = media.ids
                }
                SimklRewatchKind.SHOW -> {
                    val media = entry.show ?: return@forEach
                    mediaTitle = media.title
                    mediaIds = media.ids
                }
                SimklRewatchKind.ANIME -> {
                    val media = entry.anime ?: return@forEach
                    mediaTitle = media.title
                    mediaIds = media.ids
                }
            }
            val rewatchId = entry.rewatchId ?: return@forEach
            val ids = mediaIds.toRewatchIds()
            val contentId = when (kind) {
                SimklRewatchKind.ANIME -> mediaIds.toBestAnimeContentId()
                else -> mediaIds.toBestContentId()
            } ?: "simkl:${ids.simkl ?: rewatchId}"
            val episodeKeys = entry.seasons.flatMap { season ->
                season.episodes.mapNotNull { episode ->
                    val number = episode.number ?: return@mapNotNull null
                    if (kind == SimklRewatchKind.ANIME) number.toString()
                    else "${season.number ?: 0}:$number"
                }
            }.toSet()
            add(
                SimklRewatchSession(
                    rewatchId = rewatchId,
                    kind = kind,
                    contentId = contentId,
                    title = mediaTitle.orEmpty(),
                    ids = ids,
                    status = entry.rewatchStatus ?: "closed",
                    startedAtEpochMs = entry.addedToWatchlistAt?.let(::parseSimklTimestamp) ?: 0L,
                    lastWatchedAt = entry.lastWatchedAt,
                    watchedEpisodeKeys = episodeKeys,
                    watchedEpisodesCount = entry.watchedEpisodesCount ?: episodeKeys.size,
                    totalEpisodesCount = entry.totalEpisodesCount ?: 0,
                ),
            )
        }
    }
    addEntries(movies, SimklRewatchKind.MOVIE)
    addEntries(shows, SimklRewatchKind.SHOW)
    addEntries(anime, SimklRewatchKind.ANIME)
}

private fun SimklMediaIds.toRewatchIds() = SimklScrobbleRepository.SimklIds(
    simkl = simkl,
    imdb = imdb,
    tmdb = tmdb?.toIntOrNull(),
    tvdb = tvdb,
    mal = mal?.toIntOrNull(),
    kitsu = kitsu?.toIntOrNull(),
    anilist = anilist?.toIntOrNull(),
    anidb = anidb?.toIntOrNull(),
)

@Serializable
private data class StoredRewatchPayload(
    val sessions: List<SimklRewatchSession> = emptyList(),
    val lastActivitiesAt: String? = null,
    val lastRemovedStamp: String? = null,
)

/**
 * The `removed_from_list` stamps across every category, or null when SIMKL reports none. A move here
 * is the one activities signal that something was deleted, which a `date_from` delta cannot show.
 */
internal fun simklRemovedFromListStamp(activities: SimklActivities?): String? {
    if (activities == null) return null
    val parts = listOf(
        "shows" to activities.tvShows?.removedFromList,
        "movies" to activities.movies?.removedFromList,
        "anime" to activities.anime?.removedFromList,
    )
    if (parts.all { (_, value) -> value.isNullOrBlank() }) return null
    return parts.joinToString("|") { (name, value) -> "$name=${value.orEmpty()}" }
}

@Serializable
internal data class RewatchHistoryRequest(
    val movies: List<RewatchHistoryMedia> = emptyList(),
    val shows: List<RewatchHistoryMedia> = emptyList(),
    val anime: List<RewatchHistoryMedia> = emptyList(),
)

@Serializable
internal data class RewatchHistoryMedia(
    val title: String? = null,
    val ids: SimklScrobbleRepository.SimklIds,
    @SerialName("is_rewatch") val isRewatch: Boolean,
    @SerialName("rewatch_id") val rewatchId: Int? = null,
    @SerialName("rewatch_status") val rewatchStatus: String? = null,
    val seasons: List<RewatchHistorySeason> = emptyList(),
    val episodes: List<RewatchHistoryEpisode> = emptyList(),
)

@Serializable
internal data class RewatchHistorySeason(
    val number: Int,
    val episodes: List<RewatchHistoryEpisode>,
)

@Serializable
internal data class RewatchHistoryEpisode(
    val number: Int,
    @SerialName("watched_at") val watchedAt: String? = null,
)

internal fun buildRewatchRequest(
    kind: SimklRewatchKind,
    media: RewatchHistoryMedia,
): RewatchHistoryRequest = when (kind) {
    SimklRewatchKind.MOVIE -> RewatchHistoryRequest(movies = listOf(media))
    SimklRewatchKind.SHOW -> RewatchHistoryRequest(shows = listOf(media))
    SimklRewatchKind.ANIME -> RewatchHistoryRequest(anime = listOf(media))
}

@Serializable
internal data class RewatchMutationResponse(
    val added: RewatchAdded = RewatchAdded(),
)

@Serializable
internal data class RewatchAdded(
    val movies: Int = 0,
    val shows: Int = 0,
    val episodes: Int = 0,
    val statuses: List<RewatchMutationStatus> = emptyList(),
) {
    val total: Int get() = movies + shows + episodes
}

@Serializable
internal data class RewatchMutationStatus(
    val response: RewatchMutationDetails? = null,
)

@Serializable
internal data class RewatchMutationDetails(
    @SerialName("rewatch_id") val rewatchId: Int,
    @SerialName("rewatch_status") val rewatchStatus: String? = null,
)

internal fun RewatchMutationResponse.rewatchMutation(): RewatchMutationDetails? =
    added.statuses.firstNotNullOfOrNull(RewatchMutationStatus::response)
