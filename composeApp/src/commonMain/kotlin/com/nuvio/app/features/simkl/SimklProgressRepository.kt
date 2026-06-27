package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

internal const val WatchProgressSourceSimkl = "simkl_playback"

data class SimklProgressUiState(
    val entries: List<WatchProgressEntry> = emptyList(),
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val errorMessage: String? = null,
)

private const val BASE_URL = "https://api.simkl.com"

internal object SimklProgressRepository {
    private val log = Logger.withTag("SimklProgress")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    private val _uiState = MutableStateFlow(SimklProgressUiState())
    val uiState: StateFlow<SimklProgressUiState> = _uiState.asStateFlow()

    // videoId → SIMKL session id, needed for DELETE /sync/playback/{id}.
    private val sessionIdByVideoId = mutableMapOf<String, Int>()

    private var refreshJob: Job? = null
    private var loaded = false
    private var cachedWatchingSeeds: List<WatchProgressEntry> = emptyList()

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (SimklAuthRepository.isAuthenticated.value) refreshAsync()
    }

    fun refreshAsync() {
        refreshJob?.cancel()
        refreshJob = scope.launch { refreshNow() }
    }

    suspend fun refreshNow() {
        if (!SimklAuthRepository.isAuthenticated.value) {
            _uiState.value = SimklProgressUiState(hasLoaded = true)
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        runCatching { fetchAll() }.fold(
            onSuccess = { entries ->
                _uiState.value = SimklProgressUiState(
                    entries = entries,
                    isLoading = false,
                    hasLoaded = true,
                )
                log.d { "SIMKL CW: ${entries.count { !it.isCompleted }} in-progress, ${entries.count { it.isCompleted }} up-next seeds" }
            },
            onFailure = { error ->
                log.w(error) { "SIMKL playback fetch failed" }
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
        _uiState.value = SimklProgressUiState()
    }

    fun enrichEntry(videoId: String, poster: String?, background: String?, episodeTitle: String?, episodeThumbnail: String?) {
        val current = _uiState.value.entries.toMutableList()
        val idx = current.indexOfFirst { it.videoId == videoId }
        if (idx == -1) return
        current[idx] = current[idx].copy(
            poster = poster?.takeIf { it.isNotBlank() } ?: current[idx].poster,
            background = background?.takeIf { it.isNotBlank() } ?: current[idx].background,
            episodeTitle = episodeTitle?.takeIf { it.isNotBlank() } ?: current[idx].episodeTitle,
            episodeThumbnail = episodeThumbnail?.takeIf { it.isNotBlank() } ?: current[idx].episodeThumbnail,
        )
        _uiState.value = _uiState.value.copy(entries = current)
    }

    fun applyOptimisticRemoval(videoId: String) {
        val updated = _uiState.value.entries.filter { it.videoId != videoId }
        _uiState.value = _uiState.value.copy(entries = updated)
        // Do NOT clear sessionIdByVideoId here — deleteSession still needs the ID.
    }

    suspend fun deleteSession(videoId: String) {
        val sessionId = sessionIdByVideoId.remove(videoId) ?: return
        val headers = SimklAuthRepository.authorizedHeaders() ?: return
        val url = SimklAuthRepository.appendParams("$BASE_URL/sync/playback/$sessionId")
        runCatching {
            httpRequestRaw(method = "DELETE", url = url, headers = headers, body = "")
        }.onFailure { log.w(it) { "SIMKL DELETE /sync/playback/$sessionId failed" } }
        sessionIdByVideoId.remove(videoId)
    }

    fun clearLocalState() {
        refreshJob?.cancel()
        loaded = false
        cachedWatchingSeeds = emptyList()
        sessionIdByVideoId.clear()
        _uiState.value = SimklProgressUiState()
    }

    private suspend fun fetchAll(): List<WatchProgressEntry> {
        val headers = SimklAuthRepository.authorizedHeaders() ?: return emptyList()

        // In-progress sessions (< 80% watched) — shown as resumable CW cards.
        val playbackUrl = SimklAuthRepository.appendParams("$BASE_URL/sync/playback?hide_watched=true&limit=100")
        val playbackResponse = httpRequestRaw(method = "GET", url = playbackUrl, headers = headers, body = "")
        if (playbackResponse.status !in 200..299) {
            error("SIMKL /sync/playback returned ${playbackResponse.status}")
        }
        val sessions = json.decodeFromString<List<SimklPlaybackSession>>(playbackResponse.body)
        sessionIdByVideoId.clear()
        val playbackEntries = sessions.mapNotNull { session ->
            session.toWatchProgressEntry()?.also { entry ->
                session.id?.let { sessionIdByVideoId[entry.videoId] = it }
            }
        }

        // Shows in "watching" status with last_watched episode marker — used as completed seeds
        // for the existing up-next pipeline.
        // Rule: check /sync/activities before /sync/all-items to avoid unnecessary full downloads.
        val activities = SimklAuthRepository.fetchActivities()
        val latestCwTs = activities?.tvShows?.watching
        val savedCwTs = SimklSettingsRepository.lastCwActivitiesAt()
        if (latestCwTs != null && latestCwTs == savedCwTs && playbackEntries.isNotEmpty()) {
            log.d { "SIMKL CW: watching-list activities unchanged, skipping re-fetch" }
            return playbackEntries + cachedWatchingSeeds
        }

        val watchingUrl = SimklAuthRepository.appendParams("$BASE_URL/sync/all-items/all/watching")
        val watchingSeeds = runCatching {
            val resp = httpRequestRaw(method = "GET", url = watchingUrl, headers = headers, body = "")
            if (resp.status !in 200..299) return@runCatching emptyList()
            val parsed = json.decodeFromString<SimklAllItemsResponse>(resp.body)
            val playbackVideoIds = playbackEntries.map { it.videoId }.toSet()
            (parsed.shows + parsed.anime).mapNotNull { entry ->
                entry.toLastWatchedSeedEntry(playbackVideoIds)
            }
        }.getOrDefault(emptyList())

        cachedWatchingSeeds = watchingSeeds
        if (latestCwTs != null) SimklSettingsRepository.setLastCwActivitiesAt(latestCwTs)
        return playbackEntries + watchingSeeds
    }

    private fun SimklAllItemsEntry.toLastWatchedSeedEntry(
        playbackVideoIds: Set<String>,
    ): WatchProgressEntry? {
        val marker = lastWatched?.takeIf { it.isNotBlank() } ?: return null
        val (season, episode) = parseSimklEpisodeMarker(marker) ?: return null
        if (season == 0) return null // specials
        val isAnime = anime != null
        val s = show ?: anime ?: return null
        val showId = (if (isAnime) s.ids.toBestAnimeContentId() else s.ids.toBestContentId()) ?: return null
        val videoId = "$showId:$season:$episode"
        // Skip if this exact episode is already an active playback session — the in-progress
        // card is more useful, and it already serves as an implicit up-next seed.
        if (videoId in playbackVideoIds) return null
        val watchedMs = lastWatchedAt?.let { parseSimklTimestamp(it) } ?: System.currentTimeMillis()
        val cachedMeta = MetaDetailsRepository.peek("series", showId)
        val posterUrl = cachedMeta?.poster
            ?: s.poster?.takeIf { it.isNotBlank() }?.simklPosterUrl()

        return WatchProgressEntry(
            contentType = "series",
            parentMetaId = showId,
            parentMetaType = "series",
            videoId = videoId,
            title = s.title.orEmpty(),
            poster = posterUrl,
            background = cachedMeta?.background ?: posterUrl,
            seasonNumber = season,
            episodeNumber = episode,
            lastPositionMs = 0L,
            durationMs = 0L,
            isCompleted = true,
            progressPercent = 100f,
            lastUpdatedEpochMs = watchedMs,
            source = WatchProgressSourceSimkl,
        )
    }

    private fun SimklPlaybackSession.toWatchProgressEntry(): WatchProgressEntry? {
        val progress = progress ?: return null
        if (progress >= 80f) return null

        val updatedMs = watchedAt?.let { com.nuvio.app.features.simkl.parseSimklTimestamp(it) } ?: System.currentTimeMillis()

        return when (type) {
            "movie" -> {
                val m = movie ?: return null
                val id = m.ids.toBestContentId() ?: return null
                val cachedMeta = MetaDetailsRepository.peek("movie", id)
                val posterUrl = cachedMeta?.poster
                    ?: m.poster?.takeIf { it.isNotBlank() }?.simklPosterUrl()
                WatchProgressEntry(
                    contentType = "movie",
                    parentMetaId = id,
                    parentMetaType = "movie",
                    videoId = id,
                    title = m.title.orEmpty(),
                    poster = posterUrl,
                    background = cachedMeta?.background ?: posterUrl,
                    lastPositionMs = 0L,
                    durationMs = 0L,
                    progressPercent = progress,
                    lastUpdatedEpochMs = updatedMs,
                    source = WatchProgressSourceSimkl,
                )
            }
            "episode" -> {
                val isAnime = anime != null
                val s = show ?: anime ?: return null
                val ep = episode ?: return null
                val season = ep.season ?: return null
                val number = ep.number ?: return null
                val showId = (if (isAnime) s.ids.toBestAnimeContentId() else s.ids.toBestContentId()) ?: return null
                val videoId = "$showId:$season:$number"
                val cachedMeta = MetaDetailsRepository.peek("series", showId)
                val posterUrl = cachedMeta?.poster
                    ?: s.poster?.takeIf { it.isNotBlank() }?.simklPosterUrl()
                WatchProgressEntry(
                    contentType = "series",
                    parentMetaId = showId,
                    parentMetaType = "series",
                    videoId = videoId,
                    title = s.title.orEmpty(),
                    poster = posterUrl,
                    background = cachedMeta?.background ?: posterUrl,
                    seasonNumber = season,
                    episodeNumber = number,
                    episodeTitle = ep.title?.takeIf { it.isNotBlank() },
                    lastPositionMs = 0L,
                    durationMs = 0L,
                    progressPercent = progress,
                    lastUpdatedEpochMs = updatedMs,
                    source = WatchProgressSourceSimkl,
                )
            }
            else -> null
        }
    }
}
