package com.nuvio.app.features.mdblist

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.trakt.parseTraktIsoDateTimeToEpochMs
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val WatchProgressSourceMdbList = "mdblist_playback"

data class MdbListProgressUiState(
    val entries: List<WatchProgressEntry> = emptyList(),
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * Continue Watching backed by MDBList's paused playback sessions.
 *
 * Simpler than the SIMKL equivalent in one important way: MDBList is a franchise-family provider,
 * so the season/episode it returns are already TVDB/TMDB coordinates in the same numbering the
 * app's metadata uses. There is no anime remap on the way back in — the entry-local translation
 * that SIMKL needs has no counterpart here.
 */
internal object MdbListProgressRepository {
    private val log = Logger.withTag("MdbListProgress")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _uiState = MutableStateFlow(MdbListProgressUiState())
    val uiState: StateFlow<MdbListProgressUiState> = _uiState.asStateFlow()

    /** videoId → the ids needed to clear that session, since removal is by item, not session id. */
    private val clearTargetByVideoId = mutableMapOf<String, MdbListScrobbleItem>()

    private var refreshJob: Job? = null
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (MdbListSettingsRepository.trackingApiKey() != null) refreshAsync()
    }

    fun refreshAsync() {
        refreshJob?.cancel()
        refreshJob = scope.launch { refreshNow() }
    }

    suspend fun refreshNow() {
        val apiKey = MdbListSettingsRepository.trackingApiKey()
        if (apiKey == null) {
            _uiState.value = MdbListProgressUiState(hasLoaded = true)
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        runCatching { fetchSessions(apiKey) }.fold(
            onSuccess = { entries ->
                _uiState.value = MdbListProgressUiState(
                    entries = entries,
                    isLoading = false,
                    hasLoaded = true,
                )
                log.d { "MDBList CW: ${entries.size} paused sessions" }
            },
            onFailure = { error ->
                log.w(error) { "MDBList /sync/playback fetch failed" }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    hasLoaded = true,
                    errorMessage = error.message,
                )
            },
        )
    }

    fun onProfileChanged() {
        loaded = false
        clearTargetByVideoId.clear()
        _uiState.value = MdbListProgressUiState()
    }

    fun clearLocalState() {
        refreshJob?.cancel()
        loaded = false
        clearTargetByVideoId.clear()
        _uiState.value = MdbListProgressUiState()
    }

    fun enrichEntry(
        videoId: String,
        poster: String?,
        background: String?,
        episodeTitle: String?,
        episodeThumbnail: String?,
    ) {
        val current = _uiState.value.entries.toMutableList()
        val index = current.indexOfFirst { it.videoId == videoId }
        if (index == -1) return
        current[index] = current[index].copy(
            poster = poster?.takeIf { it.isNotBlank() } ?: current[index].poster,
            background = background?.takeIf { it.isNotBlank() } ?: current[index].background,
            episodeTitle = episodeTitle?.takeIf { it.isNotBlank() } ?: current[index].episodeTitle,
            episodeThumbnail = episodeThumbnail?.takeIf { it.isNotBlank() } ?: current[index].episodeThumbnail,
        )
        _uiState.value = _uiState.value.copy(entries = current)
    }

    /** Publishes player progress immediately rather than leaving the row stale until the refetch. */
    fun applyOptimisticProgress(entry: WatchProgressEntry) {
        if (MdbListSettingsRepository.trackingApiKey() == null) return
        val current = _uiState.value.entries.associateBy { it.videoId }.toMutableMap()
        val providerEntry = entry.copy(source = WatchProgressSourceMdbList)
        val existing = current[providerEntry.videoId]
        if (existing == null || providerEntry.lastUpdatedEpochMs >= existing.lastUpdatedEpochMs) {
            current[providerEntry.videoId] = providerEntry
        }
        _uiState.value = _uiState.value.copy(
            entries = current.values.sortedByDescending { it.lastUpdatedEpochMs },
        )
    }

    fun applyOptimisticRemoval(videoId: String) {
        _uiState.value = _uiState.value.copy(
            entries = _uiState.value.entries.filter { it.videoId != videoId },
        )
        // Keep the clear target — deleteSession still needs it.
    }

    /**
     * Removes the paused session upstream.
     *
     * MDBList has no `DELETE /sync/playback/{id}`; the session is dropped by posting the same item
     * to `/scrobble/clear`, which is why the item ids are retained per videoId at parse time.
     */
    suspend fun deleteSession(videoId: String) {
        val target = clearTargetByVideoId[videoId]
            ?: error("Missing MDBList playback clear target for $videoId")
        check(MdbListScrobbleRepository.scrobbleClear(target)) {
            "MDBList playback clear failed for $videoId"
        }
        if (clearTargetByVideoId[videoId] == target) {
            clearTargetByVideoId.remove(videoId)
        }
    }

    private suspend fun fetchSessions(apiKey: String): List<WatchProgressEntry> {
        val response = httpRequestRaw(
            method = "GET",
            url = "$MDBLIST_BASE_URL/sync/playback?apikey=$apiKey",
            headers = mapOf("Accept" to "application/json"),
            body = "",
        )
        if (response.status !in 200..299) {
            error("MDBList /sync/playback returned ${response.status}")
        }
        val sessions = json.decodeFromString<List<MdbListPlaybackSession>>(response.body)
        clearTargetByVideoId.clear()
        return sessions.mapNotNull { session ->
            session.toWatchProgressEntry()?.also { entry ->
                session.toClearTarget()?.let { clearTargetByVideoId[entry.videoId] = it }
            }
        }
    }

    private fun MdbListPlaybackSession.toClearTarget(): MdbListScrobbleItem? {
        val ids = (show ?: movie)?.ids?.toScrobbleIds() ?: return null
        return when (type) {
            "episode" -> {
                val season = episode?.season ?: return null
                val number = episode.number ?: return null
                MdbListScrobbleItem.Episode(ids = ids, season = season, episode = number)
            }
            "movie" -> MdbListScrobbleItem.Movie(ids = ids)
            else -> null
        }
    }

    private fun MdbListPlaybackSession.toWatchProgressEntry(): WatchProgressEntry? {
        // `progress` arrives as a decimal string ("73.80"), matching the field that rejects
        // full-precision floats on the way in.
        val progressPercent = progress?.toFloatOrNull() ?: return null
        val updatedMs = updatedAtTs?.let { it * 1000L }
            ?: updatedAt?.let(::parseTraktIsoDateTimeToEpochMs)
            ?: System.currentTimeMillis()

        return when (type) {
            "movie" -> {
                val media = movie ?: return null
                val id = media.ids.normalizedContentId() ?: return null
                val cachedMeta = MetaDetailsRepository.peek("movie", id)
                WatchProgressEntry(
                    contentType = "movie",
                    parentMetaId = id,
                    parentMetaType = "movie",
                    videoId = id,
                    title = media.title?.trim()?.takeIf(String::isNotBlank)
                        ?: cachedMeta?.name.orEmpty(),
                    poster = cachedMeta?.poster,
                    background = cachedMeta?.background ?: cachedMeta?.poster,
                    lastPositionMs = 0L,
                    durationMs = 0L,
                    progressPercent = progressPercent,
                    lastUpdatedEpochMs = updatedMs,
                    source = WatchProgressSourceMdbList,
                )
            }
            "episode" -> {
                val media = show ?: return null
                val ep = episode ?: return null
                val season = ep.season ?: return null
                val number = ep.number ?: return null
                if (season == 0) return null // specials
                val showId = media.ids.normalizedContentId() ?: return null
                val videoId = "$showId:$season:$number"
                val cachedMeta = MetaDetailsRepository.peek("series", showId)
                WatchProgressEntry(
                    contentType = "series",
                    parentMetaId = showId,
                    parentMetaType = "series",
                    videoId = videoId,
                    title = media.title?.trim()?.takeIf(String::isNotBlank)
                        ?: cachedMeta?.name.orEmpty(),
                    poster = cachedMeta?.poster,
                    background = cachedMeta?.background ?: cachedMeta?.poster,
                    seasonNumber = season,
                    episodeNumber = number,
                    episodeTitle = ep.title?.takeIf { it.isNotBlank() },
                    lastPositionMs = 0L,
                    durationMs = 0L,
                    progressPercent = progressPercent,
                    lastUpdatedEpochMs = updatedMs,
                    source = WatchProgressSourceMdbList,
                )
            }
            else -> null
        }
    }
}

// ── /sync/playback DTOs ────────────────────────────────────────────────────────

@Serializable
private data class MdbListPlaybackIds(
    val imdb: String? = null,
    val tmdb: Int? = null,
    val tvdb: Int? = null,
    val trakt: Int? = null,
    val mdblist: String? = null,
) {
    fun normalizedContentId(): String? = when {
        !imdb.isNullOrBlank() -> imdb
        tmdb != null -> "tmdb:$tmdb"
        tvdb != null -> "tvdb:$tvdb"
        trakt != null -> "trakt:$trakt"
        else -> null
    }

    fun toScrobbleIds(): MdbListScrobbleRepository.MdbListIds =
        MdbListScrobbleRepository.MdbListIds(
            imdb = imdb?.takeIf { it.isNotBlank() },
            tmdb = tmdb,
            tvdb = tvdb,
            trakt = trakt,
            mdblist = mdblist?.takeIf { it.isNotBlank() },
        )
}

@Serializable
private data class MdbListPlaybackMedia(
    val title: String? = null,
    val year: Int? = null,
    val ids: MdbListPlaybackIds = MdbListPlaybackIds(),
)

@Serializable
private data class MdbListPlaybackEpisode(
    val season: Int? = null,
    val number: Int? = null,
    val title: String? = null,
)

@Serializable
private data class MdbListPlaybackSession(
    val id: Long? = null,
    val progress: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("updated_at_ts") val updatedAtTs: Long? = null,
    val runtime: Int? = null,
    val type: String? = null,
    val movie: MdbListPlaybackMedia? = null,
    val show: MdbListPlaybackMedia? = null,
    val episode: MdbListPlaybackEpisode? = null,
)
