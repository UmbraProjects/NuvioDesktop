package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
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
    private var loaded = false

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
        publish()
    }

    fun onProfileChanged() {
        loaded = false
        sessions = mutableListOf()
        lastActivitiesAt = null
        ensureLoaded()
    }

    fun clearLocalState() {
        loaded = true
        sessions = mutableListOf()
        lastActivitiesAt = null
        SimklRewatchStorage.clearPayload()
        publish()
    }

    fun refreshAsync() {
        scope.launch { refreshNow() }
    }

    suspend fun refreshNow(): Boolean {
        ensureLoaded()
        if (!rewatchRequestsAllowed()) return false
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        return runCatching {
            val headers = SimklAuthRepository.authorizedHeaders() ?: error("SIMKL is not connected.")
            val deltaFrom = lastActivitiesAt?.takeIf(String::isNotBlank)
            val activities = if (deltaFrom != null) {
                SimklAuthRepository.fetchActivities()
                    ?: error("SIMKL activity state could not be read.")
            } else {
                null
            }
            if (deltaFrom != null && activities?.all == deltaFrom) {
                publish()
                return@runCatching true
            }
            val dateFromQuery = deltaFrom?.let { "&date_from=$it" }.orEmpty()
            val response = httpRequestRaw(
                method = "GET",
                url = SimklAuthRepository.appendParams(
                    "$SIMKL_BASE_URL/sync/all-items/all" +
                        "?allow_rewatch=yes&extended=full&episode_watched_at=yes$dateFromQuery",
                ),
                headers = headers,
                body = "",
            )
            if (response.status !in 200..299) error("SIMKL rewatch sync failed (${response.status}).")
            val remote = json.decodeFromString<SimklAllItemsResponse>(response.body).toRewatchSessions()
            val nextActivitiesAt = activities?.all
                ?: SimklAuthRepository.fetchActivities()?.all
            mutex.withLock {
                sessions = if (deltaFrom == null) {
                    remote.toMutableList()
                } else {
                    sessions.mergeRewatchDelta(remote)
                }
                lastActivitiesAt = nextActivitiesAt ?: lastActivitiesAt
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
            runCatching { recordPlaybackCompletion(entry) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    log.w(error) { "Failed to append playback completion to SIMKL rewatch" }
                }
        }
    }

    private suspend fun recordPlaybackCompletion(entry: WatchProgressEntry) = mutex.withLock {
        val item = SimklScrobbleRepository.buildItem(
            contentType = entry.contentType,
            parentMetaId = entry.parentMetaId,
            videoId = entry.videoId,
            title = entry.title,
            seasonNumber = entry.seasonNumber,
            episodeNumber = entry.episodeNumber,
            isAnime = false,
        ) as? SimklScrobbleItem.Episode ?: return@withLock
        val kind = if (item.isAnime) SimklRewatchKind.ANIME else SimklRewatchKind.SHOW
        val index = sessions.indexOfFirst { session ->
            session.kind == kind && session.status.equals("active", true) && session.ids.matches(item.ids)
        }
        if (index < 0) return@withLock
        val session = sessions[index]
        val episodeKey = if (kind == SimklRewatchKind.ANIME) {
            item.number.toString()
        } else {
            "${item.season}:${item.number}"
        }
        if (episodeKey in session.watchedEpisodeKeys) return@withLock

        val media = RewatchHistoryMedia(
            title = item.showTitle,
            ids = item.ids,
            isRewatch = true,
            rewatchId = session.rewatchId,
            rewatchStatus = "active",
            seasons = if (kind == SimklRewatchKind.SHOW) {
                listOf(RewatchHistorySeason(item.season, listOf(RewatchHistoryEpisode(item.number))))
            } else emptyList(),
            episodes = if (kind == SimklRewatchKind.ANIME) {
                listOf(RewatchHistoryEpisode(item.number))
            } else emptyList(),
        )
        val response = postMutation(buildRewatchRequest(kind, media), retryPinnedWrite = true).getOrThrow()
        val mutation = response.rewatchMutation()
        sessions[index] = session.copy(
            status = mutation?.rewatchStatus ?: session.status,
            watchedEpisodeKeys = session.watchedEpisodeKeys + episodeKey,
            watchedEpisodesCount = maxOf(session.watchedEpisodesCount + 1, response.added.episodes),
        )
        persist()
        publish()
        invalidateHistoryCaches()
    }

    private suspend fun postMutation(
        body: RewatchHistoryRequest,
        retryPinnedWrite: Boolean,
    ): Result<RewatchMutationResponse> {
        val attempts = if (retryPinnedWrite) 2 else 1
        var lastFailure: Throwable? = null
        repeat(attempts) { attempt ->
            val result = runCatching {
                val headers = SimklAuthRepository.authorizedHeaders() ?: error("SIMKL is not connected.")
                val response = httpRequestRaw(
                    method = "POST",
                    url = SimklAuthRepository.appendParams("$SIMKL_BASE_URL/sync/history?allow_rewatch=yes"),
                    headers = headers,
                    body = json.encodeToString(body),
                )
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
)

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
internal data class RewatchHistoryEpisode(val number: Int)

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
