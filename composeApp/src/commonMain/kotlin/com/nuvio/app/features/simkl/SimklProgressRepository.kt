package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlinx.coroutines.CancellationException
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

    /**
     * videoId → the watch timestamp of a seed the user has cleared.
     *
     * Watched-history seeds are derived from the show's `last_watched` marker, so clearing one from
     * the UI is not enough: the next refresh rebuilds it from the same marker (or straight out of
     * [cachedWatchingSeeds], which the activities gate can serve for a long time). The removal is
     * carried upstream as a history removal by `WatchedRepository`; this keeps the row out of the
     * projection until SIMKL reports a *newer* watch for it, which is what a genuine re-watch looks
     * like. In memory only, like every other cache here — by the next launch the history removal
     * has landed and the marker has moved.
     */
    private val suppressedSeedsByVideoId = mutableMapOf<String, Long>()

    private var refreshJob: Job? = null
    private var loaded = false
    private var cachedWatchingSeeds: List<WatchProgressEntry> = emptyList()
    private var hasLoadedWatchingSeeds = false

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
        cachedWatchingSeeds = emptyList()
        hasLoadedWatchingSeeds = false
        suppressedSeedsByVideoId.clear()
        _uiState.value = SimklProgressUiState()
    }

    fun enrichEntry(
        videoId: String,
        poster: String?,
        background: String?,
        episodeTitle: String?,
        episodeThumbnail: String?,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
    ) {
        val current = _uiState.value.entries.toMutableList()
        val idx = current.indexOfFirst { it.videoId == videoId }
        if (idx == -1) return
        val oldEntry = current[idx]
        val resolvedSeason = seasonNumber ?: oldEntry.seasonNumber
        val resolvedEpisode = episodeNumber ?: oldEntry.episodeNumber
        val resolvedVideoId = if (resolvedSeason != null && resolvedEpisode != null) {
            "${oldEntry.parentMetaId}:$resolvedSeason:$resolvedEpisode"
        } else {
            oldEntry.videoId
        }
        current[idx] = oldEntry.copy(
            videoId = resolvedVideoId,
            seasonNumber = resolvedSeason,
            episodeNumber = resolvedEpisode,
            poster = poster?.takeIf { it.isNotBlank() } ?: current[idx].poster,
            background = background?.takeIf { it.isNotBlank() } ?: current[idx].background,
            episodeTitle = episodeTitle?.takeIf { it.isNotBlank() } ?: current[idx].episodeTitle,
            episodeThumbnail = episodeThumbnail?.takeIf { it.isNotBlank() } ?: current[idx].episodeThumbnail,
        )
        if (resolvedVideoId != videoId) {
            sessionIdByVideoId.remove(videoId)?.let { sessionIdByVideoId[resolvedVideoId] = it }
        }
        _uiState.value = _uiState.value.copy(entries = current)
    }

    /**
     * Publish player progress immediately instead of leaving Continue Watching stale until SIMKL's
     * delayed post-scrobble refresh completes. The canonical refresh still replaces this snapshot.
     */
    fun applyOptimisticProgress(entry: WatchProgressEntry) {
        if (!SimklAuthRepository.isAuthenticated.value) return
        val current = _uiState.value.entries.associateBy { it.videoId }.toMutableMap()
        val providerEntry = entry.copy(source = WatchProgressSourceSimkl)
        val existing = current[providerEntry.videoId]
        if (existing == null || providerEntry.lastUpdatedEpochMs >= existing.lastUpdatedEpochMs) {
            current[providerEntry.videoId] = providerEntry
        }
        _uiState.value = _uiState.value.copy(
            entries = current.values.sortedByDescending { it.lastUpdatedEpochMs },
        )
    }

    fun applyOptimisticRemoval(videoId: String) {
        val updated = _uiState.value.entries.filter { it.videoId != videoId }
        _uiState.value = _uiState.value.copy(entries = updated)
        // Do NOT clear sessionIdByVideoId here — deleteSession still needs the ID.
    }

    /**
     * True when this row is a real playback session, so [deleteSession] has something to delete.
     *
     * False for a watched-history seed. Calling [deleteSession] for one used to throw, and the
     * caller's rollback then put the row straight back — the episode could not be unmarked.
     */
    fun hasPlaybackSession(videoId: String): Boolean = sessionIdByVideoId.containsKey(videoId)

    /** See [suppressedSeedsByVideoId]. */
    fun suppressWatchedSeed(videoId: String, watchedAtEpochMs: Long) {
        suppressedSeedsByVideoId[videoId] = watchedAtEpochMs
        cachedWatchingSeeds = cachedWatchingSeeds.withoutSuppressedSeeds()
    }

    suspend fun deleteSession(videoId: String) {
        val sessionId = sessionIdByVideoId[videoId]
            ?: error("Missing SIMKL playback session id for $videoId")
        val headers = SimklAuthRepository.authorizedHeaders()
            ?: error("SIMKL authentication is unavailable")
        val url = SimklAuthRepository.appendParams("$BASE_URL/sync/playback/$sessionId")
        val response = httpRequestRaw(method = "DELETE", url = url, headers = headers, body = "")
        requireSuccessfulSimklPlaybackDelete(response, sessionId)
        if (sessionIdByVideoId[videoId] == sessionId) {
            sessionIdByVideoId.remove(videoId)
        }
        log.d { "SIMKL playback session $sessionId deleted for $videoId" }
    }

    fun clearLocalState() {
        refreshJob?.cancel()
        loaded = false
        cachedWatchingSeeds = emptyList()
        hasLoadedWatchingSeeds = false
        sessionIdByVideoId.clear()
        suppressedSeedsByVideoId.clear()
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
        sessions.filter { it.type == "movie" }.forEach { session ->
            log.d {
                "SIMKL playback movie session: animeNode=${session.anime != null} " +
                    "movieIds=${session.movie?.ids} animeIds=${session.anime?.ids} title=${session.anime?.title ?: session.movie?.title}"
            }
        }
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
        if (shouldReuseSimklWatchingSeedCache(latestCwTs, savedCwTs, hasLoadedWatchingSeeds)) {
            log.d { "SIMKL CW: watching-list activities unchanged, skipping re-fetch" }
            return playbackEntries + cachedWatchingSeeds.withoutSuppressedSeeds()
        }
        if (latestCwTs != null && latestCwTs == savedCwTs) {
            log.d { "SIMKL CW: watching-list activities unchanged but seed cache is unavailable; re-fetching" }
        }

        val watchingUrl = SimklAuthRepository.appendParams("$BASE_URL/sync/all-items/all/watching")
        val watchingSeeds = try {
            val resp = httpRequestRaw(method = "GET", url = watchingUrl, headers = headers, body = "")
            if (resp.status !in 200..299) {
                error("SIMKL /sync/all-items/all/watching returned ${resp.status}")
            }
            val parsed = json.decodeFromString<SimklAllItemsResponse>(resp.body)
            val playbackVideoIds = playbackEntries.map { it.videoId }.toSet()
            (parsed.shows + parsed.anime).mapNotNull { entry ->
                entry.toLastWatchedSeedEntry(playbackVideoIds)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w(error) { "SIMKL watching-list seed fetch failed" }
            null
        }

        if (watchingSeeds != null) {
            cachedWatchingSeeds = watchingSeeds.withoutSuppressedSeeds()
            hasLoadedWatchingSeeds = true
            if (latestCwTs != null) SimklSettingsRepository.setLastCwActivitiesAt(latestCwTs)
        }
        return playbackEntries + if (hasLoadedWatchingSeeds) cachedWatchingSeeds else emptyList()
    }

    private fun List<WatchProgressEntry>.withoutSuppressedSeeds(): List<WatchProgressEntry> =
        withoutSuppressedSimklSeeds(this, suppressedSeedsByVideoId)

    private fun SimklAllItemsEntry.toLastWatchedSeedEntry(
        playbackVideoIds: Set<String>,
    ): WatchProgressEntry? {
        val marker = lastWatched?.takeIf { it.isNotBlank() } ?: return null
        val (rawSeason, rawEpisode) = parseSimklEpisodeMarker(marker) ?: return null
        val isAnime = anime != null
        val s = show ?: anime ?: return null
        val (season, episode) = if (isAnime) {
            s.ids.toCanonicalAnimeEpisode(rawSeason, rawEpisode)
        } else {
            rawSeason to rawEpisode
        }
        if (season == 0) return null // specials
        val showId = (if (isAnime) s.ids.toBestAnimeContentId() else s.ids.toBestContentId()) ?: return null
        val videoId = "$showId:$season:$episode"
        // Skip if this exact episode is already an active playback session — the in-progress
        // card is more useful, and it already serves as an implicit up-next seed.
        if (videoId in playbackVideoIds) return null
        // No fabricated "now" here, unlike an active playback session. This is a historical marker
        // from the watching list, and stamping an undateable one with the current time makes it
        // beat every dated row in the sort *and* pass the Continue Watching window unconditionally
        // — a show last touched years ago reappearing at the top of a 30-day list. Undated rows
        // sort last instead, and a window excludes them.
        val watchedMs = lastWatchedAt?.let { parseSimklTimestamp(it) } ?: 0L
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
                // SIMKL sometimes delivers anime movies under the plain `movie` node — check
                // the anime list too, or the imdb-first non-anime preference trusts SIMKL's
                // (unreliable for anime) imdb id and resolves a completely unrelated title.
                val isAnimeMovie = anime != null || movie?.ids?.isKnownAnime() == true
                // The payload node and the anime flag are independent: anime movies can arrive
                // under the plain `movie` node (isKnownAnime), so title/poster must read from
                // whichever node exists — not from the flag.
                val id = if (isAnimeMovie) {
                    (anime?.ids ?: movie?.ids)?.toBestAnimeMovieContentId()
                } else {
                    movie?.ids?.toBestContentId()
                } ?: return null
                val cachedMeta = MetaDetailsRepository.peek("movie", id)
                val posterUrl = cachedMeta?.poster
                    ?: (anime?.poster ?: movie?.poster)?.takeIf { it.isNotBlank() }?.simklPosterUrl()
                WatchProgressEntry(
                    contentType = "movie",
                    parentMetaId = id,
                    parentMetaType = "movie",
                    videoId = id,
                    title = (anime?.title ?: movie?.title)
                        ?.trim()?.takeIf(String::isNotBlank)
                        ?: cachedMeta?.name?.trim()?.takeIf(String::isNotBlank).orEmpty(),
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
                val rawSeason = ep.season ?: return null
                val rawNumber = ep.number ?: return null
                val (season, number) = if (isAnime) {
                    s.ids.toCanonicalAnimeEpisode(rawSeason, rawNumber)
                } else {
                    rawSeason to rawNumber
                }
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

/**
 * Drops cleared seeds, and forgets the suppression as soon as SIMKL reports a newer watch — which
 * is exactly what re-watching the episode produces. [suppressedByVideoId] is mutated in place.
 */
internal fun withoutSuppressedSimklSeeds(
    entries: List<WatchProgressEntry>,
    suppressedByVideoId: MutableMap<String, Long>,
): List<WatchProgressEntry> {
    if (suppressedByVideoId.isEmpty()) return entries
    return entries.filter { entry ->
        val suppressedAt = suppressedByVideoId[entry.videoId] ?: return@filter true
        val isNewerWatch = entry.lastUpdatedEpochMs > suppressedAt
        if (isNewerWatch) suppressedByVideoId.remove(entry.videoId)
        isNewerWatch
    }
}

internal fun shouldReuseSimklWatchingSeedCache(
    latestActivitiesAt: String?,
    savedActivitiesAt: String?,
    hasLoadedWatchingSeeds: Boolean,
): Boolean =
    hasLoadedWatchingSeeds &&
        latestActivitiesAt != null &&
        latestActivitiesAt == savedActivitiesAt

internal fun requireSuccessfulSimklPlaybackDelete(response: RawHttpResponse, sessionId: Int) {
    if (response.status !in 200..299) {
        error(
            "SIMKL DELETE /sync/playback/$sessionId returned ${response.status} " +
                response.body.take(200),
        )
    }
}
