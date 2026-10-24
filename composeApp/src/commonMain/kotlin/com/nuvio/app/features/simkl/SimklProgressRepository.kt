package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.trakt.parseTraktIsoDateTimeToEpochMs
import com.nuvio.app.features.watched.WatchedRepository
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

internal const val WatchProgressSourceSimkl = "simkl_playback"

/** [SimklListCacheStore] key of the watching list behind the up-next seeds. */
private const val WATCHING_LIST_CACHE_KEY = "simkl_watching_list"

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
     * the stored watching list, which the activities gate can serve for a long time). The removal is
     * carried upstream as a history removal by `WatchedRepository`; this keeps the row out of the
     * projection until SIMKL reports a *newer* watch for it, which is what a genuine re-watch looks
     * like. In memory only — by the next launch the history removal has landed and the marker has
     * moved.
     */
    private val suppressedSeedsByVideoId = mutableMapOf<String, Long>()

    private var refreshJob: Job? = null
    private var loaded = false

    /**
     * Several callers run [refreshNow] directly. Overlapping runs each made the same requests; one
     * at a time, the second finds the stamps the first saved and reads nothing.
     */
    private val fetchMutex = Mutex()

    /** The watching list the seeds are derived from, for [watchingCacheProfileId]; see [SimklListCache]. */
    private var watchingCache: SimklListCache? = null
    private var watchingCacheProfileId: Int? = null

    /** `/sync/playback` as of [cachedPlaybackStamp]; re-read only when the playback stamps move. */
    private var cachedPlaybackSessions: List<SimklPlaybackSession>? = null
    private var cachedPlaybackStamp: String? = null

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
        runCatching { fetchMutex.withLock { fetchAll() } }.fold(
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
        resetCaches()
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
    }

    suspend fun deleteSession(videoId: String) {
        val sessionId = sessionIdByVideoId[videoId]
            ?: error("Missing SIMKL playback session id for $videoId")
        if (!SimklAuthRepository.hasUsableToken()) error("SIMKL authentication is unavailable")
        val url = SimklAuthRepository.appendParams("$BASE_URL/sync/playback/$sessionId")
        val response = simklRequest(method = "DELETE", url = url, body = "")
        requireSuccessfulSimklPlaybackDelete(response, sessionId)
        if (sessionIdByVideoId[videoId] == sessionId) {
            sessionIdByVideoId.remove(videoId)
        }
        cachedPlaybackSessions = cachedPlaybackSessions?.filterNot { it.id == sessionId }
        log.d { "SIMKL playback session $sessionId deleted for $videoId" }
    }

    /** Called on disconnect: also forgets the stored watching list. */
    fun clearLocalState() {
        refreshJob?.cancel()
        loaded = false
        resetCaches()
        SimklListCacheStore.clear(ProfileRepository.activeProfileId, WATCHING_LIST_CACHE_KEY)
        sessionIdByVideoId.clear()
        suppressedSeedsByVideoId.clear()
        _uiState.value = SimklProgressUiState()
    }

    private fun resetCaches() {
        watchingCache = null
        watchingCacheProfileId = null
        cachedPlaybackSessions = null
        cachedPlaybackStamp = null
    }

    private suspend fun fetchAll(): List<WatchProgressEntry> {
        if (!SimklAuthRepository.hasUsableToken()) return emptyList()

        // Every read below is gated on /sync/activities, the sync guide's first rule. Without it
        // nothing is fetched: what is cached is served as is, and a first read fails (most often a
        // spent daily quota, which every other request would hit too).
        val activities = SimklAuthRepository.fetchActivities()
        val sessions = playbackSessions(activities)
        sessions.filter { it.type == "movie" }.forEach { session ->
            log.d {
                "SIMKL playback movie session: animeNode=${session.anime != null} " +
                    "movieIds=${session.movie?.ids} animeIds=${session.anime?.ids} title=${session.anime?.title ?: session.movie?.title}"
            }
        }
        sessionIdByVideoId.clear()
        val playbackEntries = sessions.mapNotNull { session ->
            val entry = session.toWatchProgressEntry() ?: return@mapNotNull null
            val pausedAtMs = session.pausedAtTimestamp?.let { parseSimklTimestamp(it) } ?: 0L
            if (entry.wasWatchedAfter(pausedAtMs)) {
                log.d { "SIMKL playback session dropped as stale (watched after the pause): ${entry.videoId}" }
                return@mapNotNull null
            }
            session.id?.let { sessionIdByVideoId[entry.videoId] = it }
            entry
        }

        // Shows in "watching" status with last_watched episode marker — used as completed seeds
        // for the existing up-next pipeline.
        val playbackVideoIds = playbackEntries.map { it.videoId }.toSet()
        val watching = watchingList(activities)
        // The array is the anime signal — see SimklAllItemsEntry.showMedia. Concatenating the two
        // and asking each entry what it is threw that away, and every anime came back down the
        // non-anime branch with the wrong id namespace.
        val seeds = watching.entries.mapNotNull { cached ->
            when (cached.bucket) {
                SimklListBucket.SHOWS -> cached.entry.toLastWatchedSeedEntry(playbackVideoIds, isAnime = false)
                SimklListBucket.ANIME -> cached.entry.toLastWatchedSeedEntry(playbackVideoIds, isAnime = true)
                SimklListBucket.MOVIES -> null
            }
        }.withoutSuppressedSeeds()
        return playbackEntries + seeds
    }

    /**
     * `/sync/playback?hide_watched=false`, re-read only when a `playback` stamp moved.
     *
     * hide_watched is asked for as false and applied by the caller instead. SIMKL's filter (default
     * true) drops every session whose title is on the watched list at all, so restarting a film you
     * have seen before produced a session the server stored and then refused to hand back — the
     * title never reached Continue Watching. The documented intent is narrower: exclude items
     * watched *after* the pause, i.e. sessions a later finish made stale.
     */
    private suspend fun playbackSessions(activities: SimklActivities?): List<SimklPlaybackSession> {
        val stamp = simklPlaybackActivitiesStamp(activities)
        cachedPlaybackSessions?.let { cached ->
            if (activities == null || (stamp != null && stamp == cachedPlaybackStamp)) {
                log.d { "SIMKL CW: playback activities unchanged, reusing ${cached.size} session(s)" }
                return cached
            }
        }
        if (activities == null) error("SIMKL activity state could not be read; Continue Watching not fetched")
        val playbackUrl = SimklAuthRepository.appendParams("$BASE_URL/sync/playback?hide_watched=false&limit=100")
        val playbackResponse = simklRequest(method = "GET", url = playbackUrl, body = "")
        if (playbackResponse.status !in 200..299) {
            error("SIMKL /sync/playback returned ${playbackResponse.status}")
        }
        val sessions = json.decodeFromString<List<SimklPlaybackSession>>(playbackResponse.body)
        cachedPlaybackSessions = sessions
        cachedPlaybackStamp = stamp
        return sessions
    }

    /**
     * The watching list, kept current the way SIMKL's sync guide asks: a full read of the bucket
     * once, as the baseline, then a `date_from` delta only when the gate stamp moved, and the
     * ids-only deletion diff only when `removed_from_list` moved. A failed read serves the stored
     * list. This used to re-read the whole bucket on every launch and after every history write.
     */
    private suspend fun watchingList(activities: SimklActivities?): SimklListCache {
        val profileId = ProfileRepository.activeProfileId
        val cache = watchingCache
            ?.takeIf { watchingCacheProfileId == profileId && SimklListCacheStore.isForCurrentAccount(it) }
            ?: SimklListCacheStore.load(profileId, WATCHING_LIST_CACHE_KEY).also {
                watchingCache = it
                watchingCacheProfileId = profileId
            }
        if (activities == null) return cache
        val latestStamp = simklWatchingSeedActivitiesStamp(activities)
        if (shouldReuseSimklWatchingSeedCache(latestStamp, SimklSettingsRepository.lastCwActivitiesAt(), cache.hasBaseline)) {
            log.d { "SIMKL CW: watching-list activities unchanged, skipping re-fetch" }
            return cache
        }
        val next = try {
            syncWatchingList(cache, activities)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w(error) { "SIMKL watching-list fetch failed" }
            return cache
        }
        if (profileId != ProfileRepository.activeProfileId) return cache
        watchingCache = next
        SimklListCacheStore.save(profileId, WATCHING_LIST_CACHE_KEY, next)
        if (latestStamp != null) SimklSettingsRepository.setLastCwActivitiesAt(latestStamp)
        return next
    }

    private suspend fun syncWatchingList(cache: SimklListCache, activities: SimklActivities): SimklListCache {
        val removedStamp = simklRemovedFromListStamp(activities)
        // Read before the all-items request, so a change landing while it is in flight is newer
        // than the watermark and the next delta still sees it.
        val nextDateFrom = activities.all?.takeIf(String::isNotBlank)
        val keepWatching = { bucket: SimklListBucket, entry: SimklAllItemsEntry ->
            bucket != SimklListBucket.MOVIES && entry.hasStatus("watching")
        }
        if (!cache.hasBaseline) {
            // `next_watch_info` attaches each show's next unwatched episode, air date included — the
            // Continue Watching window needs that date to keep a returning show.
            val baseline = fetchAllItems("/sync/all-items/all/watching?next_watch_info=yes")
            log.i { "SIMKL CW: watching list baseline, ${baseline.shows.size} shows, ${baseline.anime.size} anime" }
            return SimklListCache(
                dateFrom = nextDateFrom,
                removedStamp = removedStamp,
                entries = baseline.cachedEntries { bucket, _ -> bucket != SimklListBucket.MOVIES },
            )
        }
        var next = cache
        if (removedStamp != null && removedStamp != cache.removedStamp) {
            val present = SimklDeletionCheck.canonicalKeys(SimklDeletionCheck.currentLibrary(removedStamp))
            next = next.withoutRemoved(present).copy(removedStamp = removedStamp)
            log.i { "SIMKL CW: deletion check dropped ${cache.entries.size - next.entries.size} title(s)" }
        }
        val dateFrom = cache.dateFrom.orEmpty()
        if (nextDateFrom != dateFrom) {
            // Every status, not just watching: a title that left the list arrives under its new one.
            val delta = fetchAllItems("/sync/all-items?next_watch_info=yes&date_from=${simklUrlEncode(dateFrom)}")
            next = next.applyDelta(delta, keepWatching)
            log.i {
                "SIMKL CW: watching list delta since $dateFrom, " +
                    "${delta.shows.size + delta.anime.size} changed -> ${next.entries.size} watching"
            }
        }
        return next.copy(dateFrom = nextDateFrom ?: cache.dateFrom)
    }

    private suspend fun fetchAllItems(pathAndQuery: String): SimklAllItemsResponse {
        val response = simklRequest(
            method = "GET",
            url = SimklAuthRepository.appendParams("$BASE_URL$pathAndQuery"),
            body = "",
        )
        if (response.status !in 200..299) error("SIMKL ${pathAndQuery.substringBefore('?')} returned ${response.status}")
        val body = response.body.trim()
        if (body.isEmpty() || body == "null" || body == "[]") return SimklAllItemsResponse()
        return json.decodeFromString<SimklAllItemsResponse>(body)
    }

    /**
     * Whether the watched list says this title was finished after [pausedAtMs], which makes the
     * saved session a leftover rather than something to resume.
     *
     * An unknown title (nothing on the watched list, or an id namespace the watched keys do not
     * use) counts as not watched, so the session survives: showing a resumable card that could
     * have been dropped is a smaller failure than silently hiding one, which is the bug this
     * whole path exists to fix.
     */
    private fun WatchProgressEntry.wasWatchedAfter(pausedAtMs: Long): Boolean {
        if (pausedAtMs <= 0L) return false
        val watchedAtMs = WatchedRepository.watchedAtEpochMs(
            id = parentMetaId,
            type = parentMetaType,
            season = seasonNumber,
            episode = episodeNumber,
        ) ?: return false
        return watchedAtMs >= pausedAtMs
    }

    private fun List<WatchProgressEntry>.withoutSuppressedSeeds(): List<WatchProgressEntry> =
        withoutSuppressedSimklSeeds(this, suppressedSeedsByVideoId)

    private fun SimklAllItemsEntry.toLastWatchedSeedEntry(
        playbackVideoIds: Set<String>,
        isAnime: Boolean,
    ): WatchProgressEntry? {
        // Every branch below used to discard an entry in silence, which is how a title can vanish
        // from Continue Watching with the seed count still looking healthy — one dropped entry is
        // invisible in "105 up-next seeds". The title is logged with the reason so a repro says
        // which branch ate it instead of leaving it to be guessed at.
        val debugTitle = showMedia?.title ?: "<untitled>"
        val marker = lastWatched?.takeIf { it.isNotBlank() }
            ?: run {
                log.d { "SIMKL seed dropped ($debugTitle): no last_watched marker" }
                return null
            }
        val (rawSeason, rawEpisode) = parseSimklEpisodeMarker(marker)
            ?: run {
                log.d { "SIMKL seed dropped ($debugTitle): unparseable last_watched marker '$marker'" }
                return null
            }
        val s = showMedia
            ?: run {
                log.d { "SIMKL seed dropped ($debugTitle): entry carries neither a show nor an anime node" }
                return null
            }
        val showId = (if (isAnime) s.ids.toBestAnimeContentId() else s.ids.toBestContentId())
            ?: run {
                log.d {
                    "SIMKL seed dropped ($debugTitle): no usable content id " +
                        "(isAnime=$isAnime ids=${s.ids})"
                }
                return null
            }
        // The id decides the coordinate space, so it is resolved first — see episodeCoordinatesFor.
        val (season, episode) = s.ids.episodeCoordinatesFor(
            contentId = showId,
            isAnime = isAnime,
            entrySeason = rawSeason,
            entryEpisode = rawEpisode,
        )
        if (season == 0) {
            log.d { "SIMKL seed dropped ($debugTitle): specials only (season 0)" }
            return null // specials
        }
        val videoId = "$showId:$season:$episode"
        // Skip if this exact episode is already an active playback session — the in-progress
        // card is more useful, and it already serves as an implicit up-next seed.
        if (videoId in playbackVideoIds) {
            log.d { "SIMKL seed skipped ($debugTitle): $videoId already has a playback session" }
            return null
        }
        val nextAirEpochMs = nextToWatchInfo?.date?.let { parseSimklAirDate(it) }
        log.d {
            "SIMKL seed ($debugTitle): $videoId isAnime=$isAnime marker=$marker " +
                "next=${nextToWatchInfo?.let { "S${it.season}E${it.episode}" } ?: "-"} " +
                "nextAir=${nextToWatchInfo?.date ?: "-"}"
        }
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
            background = cachedMeta?.backdropOrPoster(),
            seasonNumber = season,
            episodeNumber = episode,
            lastPositionMs = 0L,
            durationMs = 0L,
            isCompleted = true,
            progressPercent = 100f,
            lastUpdatedEpochMs = watchedMs,
            source = WatchProgressSourceSimkl,
            nextEpisodeAirEpochMs = nextAirEpochMs,
        )
    }

    private fun SimklPlaybackSession.toWatchProgressEntry(): WatchProgressEntry? {
        val progress = progress ?: return null
        if (progress >= 80f) return null

        val updatedMs = pausedAtTimestamp?.let { com.nuvio.app.features.simkl.parseSimklTimestamp(it) }
            ?: System.currentTimeMillis()

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
                    background = cachedMeta?.backdropOrPoster(),
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
                val showId = (if (isAnime) s.ids.toBestAnimeContentId() else s.ids.toBestContentId()) ?: return null
                // SIMKL states the franchise (TVDB) coordinates itself; they need no anime-list
                // entry and are correct even where the mapping is thin, so they win outright for a
                // franchise id. A per-entry id keeps SIMKL's own numbering — see
                // episodeCoordinatesFor. A payload that states only the franchise pair leaves
                // nothing else to fall back to, so it stands in for both.
                val entrySeason = ep.season ?: ep.tvdbSeason ?: return null
                val entryNumber = ep.number ?: ep.tvdbNumber ?: return null
                val (season, number) = s.ids.episodeCoordinatesFor(
                    contentId = showId,
                    isAnime = isAnime,
                    entrySeason = entrySeason,
                    entryEpisode = entryNumber,
                    franchiseSeason = ep.tvdbSeason,
                    franchiseEpisode = ep.tvdbNumber,
                )
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
                    background = cachedMeta?.backdropOrPoster(),
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
 * Backdrop for a row we already have metadata for.
 *
 * Deliberately never falls back to SIMKL's own poster: `background` doubles as the "this row still
 * needs metadata resolution" signal in `WatchProgressRepository`, so filling it with a portrait
 * poster tells that pass the row is done and permanently freezes the card at a SIMKL thumbnail with
 * no backdrop, logo or plot. It bit anime movies hardest — nothing else in the app caches meta under
 * a `kitsu:` id, so their `peek` always missed. Matches `MdbListProgressRepository`, and the card
 * falls back to `poster` on its own while the backdrop is still unresolved.
 */
private fun MetaDetails.backdropOrPoster(): String? = background ?: poster

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

/**
 * The activities stamp that decides whether the cached watching-list seeds are still current.
 *
 * SIMKL reports activity per category, and the request this gates — `/sync/all-items/all/watching`
 * — is parsed as `shows + anime`. Gating on `tv_shows.watching` alone therefore went blind to every
 * anime: finishing an episode elsewhere bumps `anime.watching` and leaves `tv_shows.watching`
 * untouched, so the cache was reused and the new episode never reached Continue Watching. It
 * survived a manual resync too, because the seed cache is in memory — only a restart cleared it.
 *
 * `completed` is folded in for the same reason in the other direction: a show finished on another
 * device leaves the watching list, and nothing guarantees SIMKL bumps `watching` when an entry is
 * removed from it rather than changed within it.
 *
 * The parts are joined rather than compared, because only equality with the saved stamp matters —
 * no ordering, no timestamp parsing. A stamp saved by an older build simply mismatches once and
 * costs a single extra fetch.
 */
internal fun simklWatchingSeedActivitiesStamp(activities: SimklActivities?): String? {
    if (activities == null) return null
    val parts = listOf(
        "shows.watching" to activities.tvShows?.watching,
        "shows.completed" to activities.tvShows?.completed,
        "shows.hold" to activities.tvShows?.hold,
        "shows.dropped" to activities.tvShows?.dropped,
        "shows.plantowatch" to activities.tvShows?.planToWatch,
        "shows.removed" to activities.tvShows?.removedFromList,
        "anime.watching" to activities.anime?.watching,
        "anime.completed" to activities.anime?.completed,
        "anime.hold" to activities.anime?.hold,
        "anime.dropped" to activities.anime?.dropped,
        "anime.plantowatch" to activities.anime?.planToWatch,
        "anime.removed" to activities.anime?.removedFromList,
        // hold / dropped / plantowatch / removed: every way a title can leave the watching list,
        // so the date_from delta that follows sees it go.
    )
    if (parts.all { (_, value) -> value == null }) return null
    return parts.joinToString("|") { (name, value) -> "$name=${value.orEmpty()}" }
}

/**
 * The `playback` stamps of every category — the sync guide's gate for `/sync/playback`, which moves
 * when a paused session is saved or deleted. Null when SIMKL reports none, which re-reads.
 */
internal fun simklPlaybackActivitiesStamp(activities: SimklActivities?): String? {
    if (activities == null) return null
    val parts = listOf(
        "shows" to activities.tvShows?.playback,
        "movies" to activities.movies?.playback,
        "anime" to activities.anime?.playback,
    )
    if (parts.all { (_, value) -> value == null }) return null
    return parts.joinToString("|") { (name, value) -> "$name=${value.orEmpty()}" }
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

/**
 * `next_to_watch_info.date` carries an offset (`2023-08-07T00:00:00-05:00`), which
 * [parseSimklTimestamp] ignores — a day's error is enough to move a premiere across the window
 * edge, so the offset-aware parser goes first and the lenient one only catches odd shapes.
 */
internal fun parseSimklAirDate(iso: String): Long? =
    parseTraktIsoDateTimeToEpochMs(iso) ?: parseSimklTimestamp(iso)
