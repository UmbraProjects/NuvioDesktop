package com.nuvio.app.features.mdblist

import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibrarySection
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingAuthProvider
import com.nuvio.app.features.tracking.TrackingCapability
import com.nuvio.app.features.tracking.TrackingLibraryProvider
import com.nuvio.app.features.tracking.TrackingLibrarySnapshot
import com.nuvio.app.features.tracking.TrackingLibraryTab
import com.nuvio.app.features.tracking.TrackingLibraryTabKind
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMembershipResolution
import com.nuvio.app.features.tracking.TrackingProviderDescriptor
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import com.nuvio.app.features.tracking.TrackingScrobbleResult
import com.nuvio.app.features.tracking.TrackingScrobbler
import com.nuvio.app.features.tracking.TrackingSeekScrobblePolicy
import com.nuvio.app.features.tracking.TrackingWatchedProvider
import com.nuvio.app.features.watched.WatchedItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object MdbListTrackingAuthProvider : TrackingAuthProvider {
    override val descriptor: TrackingProviderDescriptor = TrackingProviderDescriptor(
        id = TrackingProviderId.MDBLIST,
        displayName = "MDBList",
        capabilities = setOf(
            TrackingCapability.AUTHENTICATION,
            TrackingCapability.LIBRARY_READ,
            TrackingCapability.LIBRARY_WRITE,
            TrackingCapability.RATINGS_WRITE,
            TrackingCapability.SCROBBLE,
            TrackingCapability.WATCHED_READ,
            TrackingCapability.WATCHED_WRITE,
        ),
    )

    private val _isAuthenticated = MutableStateFlow(false)
    override val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    /**
     * MDBList has no session to establish: the API key the user already supplies for ratings also
     * authorises tracking, so "authenticated" is simply "a key exists and tracking is switched on".
     * Keeping it a StateFlow lets the registry react when either changes.
     */
    override fun ensureLoaded() {
        MdbListSettingsRepository.ensureLoaded()
        refreshAuthenticationState()
    }

    internal fun refreshAuthenticationState() {
        _isAuthenticated.value = MdbListSettingsRepository.trackingApiKey() != null
    }

    override fun onProfileChanged() {
        MdbListSettingsRepository.onProfileChanged()
        MdbListSyncRepository.clearLocalState()
        MdbListCalendarRepository.onProfileChanged()
        refreshAuthenticationState()
    }

    override fun clearLocalState() {
        MdbListSyncRepository.clearLocalState()
        MdbListCalendarRepository.clearLocalState()
        refreshAuthenticationState()
    }

    override fun removeStoredProfile(profileId: Int) = Unit
}

internal object MdbListScrobbleAdapter : TrackingScrobbler {
    override val providerId: TrackingProviderId = TrackingProviderId.MDBLIST

    /**
     * MDBList documents `start` as "start or update a scrobble session… replaces any existing
     * session for the same movie/episode", so the restart alone carries a seek — no teardown
     * needed, and none wanted, since `stop` ends the session rather than repositioning it.
     *
     * `NONE` was wrong here: nothing else re-sends a position mid-playback, so the server kept
     * extrapolating from wherever playback began and seeks had no effect on the recorded progress.
     */
    override val seekScrobblePolicy: TrackingSeekScrobblePolicy =
        TrackingSeekScrobblePolicy.RESTART_ONLY

    /**
     * Five minutes, chosen against the shared daily budget rather than for precision: a two-hour
     * watch costs ~24 extra requests out of 1,000, and bounds how far MDBList's extrapolated
     * position can drift from the real one — the visible symptom at playback speeds above 1x.
     */
    override val progressRefreshIntervalMs: Long = 5 * 60 * 1000L

    override suspend fun scrobble(
        profileId: Int,
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent,
    ): TrackingScrobbleResult {
        val media = event.media
        val catalog = media.catalog ?: return TrackingScrobbleResult.Declined
        // Null means no imdb/tmdb/tvdb/trakt id resolved — anime with only native ids lands here,
        // and is skipped rather than guessed.
        val item = MdbListScrobbleRepository.buildItem(
            contentType = catalog.contentType,
            parentMetaId = catalog.contentId,
            videoId = catalog.videoId,
            title = media.title,
            seasonNumber = media.episode?.season,
            episodeNumber = media.episode?.number,
            isAnime = media.kind == TrackingMediaKind.ANIME,
        ) ?: return TrackingScrobbleResult.Declined
        val progressPercent = event.progressPercent.toFloat()

        return when (action) {
            TrackingScrobbleAction.START ->
                MdbListScrobbleRepository.scrobbleStart(item, progressPercent)
            TrackingScrobbleAction.PAUSE ->
                MdbListScrobbleRepository.scrobblePause(item, progressPercent)
            // A pause keeps the session resumable; a stop ends it.
            TrackingScrobbleAction.STOP -> if (event.isPauseRatherThanStop) {
                MdbListScrobbleRepository.scrobblePause(item, progressPercent)
            } else {
                MdbListScrobbleRepository.scrobbleStop(item, progressPercent)
            }
        }
    }
}

internal object MdbListWatchedAdapter : TrackingWatchedProvider {
    override val providerId: TrackingProviderId = TrackingProviderId.MDBLIST

    override suspend fun pull(profileId: Int, pageSize: Int): List<WatchedItem> {
        if (profileId != ProfileRepository.activeProfileId) return emptyList()
        return MdbListSyncRepository.watchedItems()
    }

    override suspend fun push(profileId: Int, items: Collection<WatchedItem>) {
        // Read-only by design for now: playback history reaches MDBList through the scrobble stop,
        // and pushing the local watched set as well would write the same episodes twice by two
        // different routes. Explicit in-app actions use MdbListHistoryWriter instead.
    }

    override suspend fun delete(profileId: Int, items: Collection<WatchedItem>) {
        // See push(): removal needs the same decision about who owns the history and is not part
        // of the read-only capability declared in the descriptor.
    }
}

internal object MdbListLibraryAdapter : TrackingLibraryProvider {
    private const val WATCHLIST_KEY = MDBLIST_WATCHLIST_KEY

    override val providerId: TrackingProviderId = TrackingProviderId.MDBLIST
    override val changes = MdbListLibraryRepository.changes
    override val connectionRefreshIntent: TrackingRefreshIntent = TrackingRefreshIntent.AUTOMATIC

    override fun ensureLoaded() = MdbListLibraryRepository.ensureLoaded()
    override fun onProfileChanged() = MdbListLibraryRepository.clearLocalState()
    override fun clearLocalState() = MdbListLibraryRepository.clearLocalState()
    override suspend fun refresh(intent: TrackingRefreshIntent) {
        MdbListLibraryRepository.refresh(force = intent != TrackingRefreshIntent.AUTOMATIC)
    }

    override fun snapshot(): TrackingLibrarySnapshot {
        val state = MdbListLibraryRepository.state.value
        val movies = state.watchlist.filter { it.type.equals("movie", ignoreCase = true) }
        val shows = state.watchlist.filterNot { it.type.equals("movie", ignoreCase = true) }
        val visibleLists = state.visibleLists
        return TrackingLibrarySnapshot(
            items = state.items,
            sections = buildList {
                if (shows.isNotEmpty()) add(LibrarySection("mdblist_shows", "My Shows", shows))
                if (movies.isNotEmpty()) add(LibrarySection("mdblist_movies", "My Movies", movies))
                // One section per list, in the order MDBList returned them (own, liked, external).
                visibleLists.forEach { list ->
                    val items = state.itemsByList[list.key].orEmpty()
                    if (items.isNotEmpty()) add(LibrarySection(list.key, list.name, items))
                }
            },
            tabs = listOf(
                TrackingLibraryTab(
                    key = WATCHLIST_KEY,
                    title = "MDBList Watchlist",
                    providerId = providerId,
                    kind = TrackingLibraryTabKind.WATCHLIST,
                ),
            ) + visibleLists.map { list ->
                TrackingLibraryTab(
                    key = list.key,
                    title = list.name,
                    providerId = providerId,
                    kind = TrackingLibraryTabKind.PERSONAL,
                    supportedContentTypes = when (list.mediaType) {
                        "movie" -> setOf("movie")
                        "show" -> setOf("series", "show", "tv", "anime")
                        else -> null
                    },
                    // Only the user's static lists take items; dynamic, liked and external are read-only.
                    isMembershipDestination = list.acceptsItems,
                )
            },
            hasLoaded = state.hasLoaded,
            isLoading = state.isLoading,
            errorMessage = state.errorMessage,
        )
    }

    override fun contains(contentId: String, contentType: String?): Boolean =
        MdbListLibraryRepository.contains(contentId, contentType)

    override fun find(contentId: String): LibraryItem? = MdbListLibraryRepository.find(contentId)

    override suspend fun membership(item: LibraryItem): Map<String, Boolean> {
        val state = MdbListLibraryRepository.state.value
        return buildMap {
            put(WATCHLIST_KEY, MdbListLibraryRepository.isInWatchlist(item.id, item.type))
            state.visibleLists.filter { it.acceptsItems }.forEach { list ->
                put(list.key, MdbListLibraryRepository.isInList(list.key, item.id))
            }
        }
    }

    override fun toggledDefaultMembership(currentMembership: Map<String, Boolean>): Map<String, Boolean> =
        mapOf(WATCHLIST_KEY to (currentMembership[WATCHLIST_KEY] != true))

    override suspend fun applyMembership(
        profileId: Int,
        item: LibraryItem,
        desiredMembership: Map<String, Boolean>,
        destructiveRemovalConfirmed: Boolean,
    ): TrackingMembershipResolution? {
        if (profileId != ProfileRepository.activeProfileId) return null
        val current = membership(item)
        desiredMembership.forEach { (key, desired) ->
            if (current[key] == desired) return@forEach
            if (key == WATCHLIST_KEY) {
                MdbListLibraryRepository.setWatchlistMembership(item, desired)
            } else if (current.containsKey(key)) {
                MdbListLibraryRepository.setListMembership(item, key, desired)
            }
        }
        return TrackingMembershipResolution(providerId, WATCHLIST_KEY, WATCHLIST_KEY)
    }
}
