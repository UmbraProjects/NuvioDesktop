package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
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
import com.nuvio.app.features.tracking.isPauseThatStopWouldRecordAsWatched
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.WatchedRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import nuvio.composeapp.generated.resources.*
import com.nuvio.app.core.i18n.localizedString

/**
 * Ports for the existing SIMKL repositories. Like the Trakt adapters, these forward without adding
 * logic so the request bodies are unchanged by the migration.
 */

internal object SimklTrackingAuthProvider : TrackingAuthProvider {
    override val descriptor: TrackingProviderDescriptor = TrackingProviderDescriptor(
        id = TrackingProviderId.SIMKL,
        displayName = "Simkl",
        capabilities = setOf(
            TrackingCapability.AUTHENTICATION,
            TrackingCapability.LIBRARY_READ,
            TrackingCapability.LIBRARY_WRITE,
            TrackingCapability.WATCHED_WRITE,
            TrackingCapability.WATCHED_READ,
            TrackingCapability.RATINGS_WRITE,
            TrackingCapability.SCROBBLE,
        ),
    )

    override val isAuthenticated: StateFlow<Boolean>
        get() = SimklAuthRepository.isAuthenticated

    override fun ensureLoaded() = SimklAuthRepository.ensureLoaded()

    override fun onProfileChanged() {
        SimklAuthRepository.onProfileChanged()
    }

    override fun clearLocalState() {
        // Deliberately not calling `onDisconnectRequested()`. `LocalAccountDataCleaner` currently
        // clears Trakt but not SIMKL, and silently widening account deletion is not a port change.
    }

    override fun removeStoredProfile(profileId: Int) = Unit
}

internal object SimklWatchedAdapter : TrackingWatchedProvider {
    override val providerId: TrackingProviderId = TrackingProviderId.SIMKL

    override suspend fun pull(profileId: Int, pageSize: Int): List<WatchedItem> {
        if (profileId != ProfileRepository.activeProfileId) return emptyList()
        return SimklWatchedRepository.watchedItems(profileId)
    }

    override fun invalidateChangeDetection() = SimklWatchedRepository.invalidate()

    override fun consumeHistoryResets() = SimklWatchedRepository.consumeHistoryResets()

    // Manual mutations use SimklHistoryWriter. These are deliberately no-ops here so a local
    // playback completion cannot be written twice through both the scrobbler and watched sync.
    override suspend fun push(profileId: Int, items: Collection<WatchedItem>) = Unit
    override suspend fun delete(profileId: Int, items: Collection<WatchedItem>) = Unit
}

/** SIMKL's own cut-off: a scrobble `stop` at or above this marks the item watched. */
private const val SIMKL_SCROBBLE_WATCHED_PERCENT = 80f

internal object SimklScrobbleAdapter : TrackingScrobbler {
    private val log = Logger.withTag("SimklScrobble")
    override val providerId: TrackingProviderId = TrackingProviderId.SIMKL

    /** Matches the pre-existing seek behaviour: a seek stops the scrobble and starts a new one. */
    override val seekScrobblePolicy: TrackingSeekScrobblePolicy =
        TrackingSeekScrobblePolicy.STOP_AND_RESTART

    /**
     * Five minutes, matching MDBList. SIMKL extrapolates the position of a running session from the
     * last `start` it received at 1x, so without this nothing re-sends a position mid-playback and
     * its "watching" card falls further behind for as long as playback continues at a higher speed
     * — the reported symptom at 2x.
     *
     * The 20-second per-user lock on SIMKL's scrobble endpoints is an overlap guard, not a budget:
     * refreshes this far apart never collide with themselves, and a refresh that does land beside a
     * real start/stop is dropped with a 429 and simply retried on the next tick.
     */
    override val progressRefreshIntervalMs: Long = 5 * 60 * 1000L

    override suspend fun scrobble(
        profileId: Int,
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent,
    ): TrackingScrobbleResult {
        val media = event.media
        val catalog = media.catalog ?: return TrackingScrobbleResult.Declined
        // Null means no SIMKL-usable id resolved. For anime this can be transient — the repository
        // enriches ids over the network and only caches successes — so declining here (rather than
        // reporting a send) is what lets a later attempt for the same item try again.
        val item = SimklScrobbleRepository.buildItem(
            contentType = catalog.contentType,
            parentMetaId = catalog.contentId,
            videoId = catalog.videoId,
            title = media.title,
            seasonNumber = media.episode?.season,
            episodeNumber = media.episode?.number,
            isAnime = media.kind == TrackingMediaKind.ANIME,
        ) ?: return TrackingScrobbleResult.Declined
        val progressPercent = event.progressPercent.toFloat()
        val inActiveRewatch = item is SimklScrobbleItem.Episode &&
            SimklRewatchRepository.hasActiveRewatchFor(item)

        return when (action) {
            // No /scrobble call can address a rewatch session. During a rewatch the finishing stop is
            // diverted below, so a plain start here opened a SIMKL playback session nothing ever
            // stopped; per SIMKL's rewatch guide the next /scrobble/start "tidies up" such a stale
            // session first, which matured it into a watch on the completed original — at every new
            // episode, showing as "Seen All" over the rewatch. Report starts (including progress
            // refreshes) and resumable stops handled without sending anything.
            TrackingScrobbleAction.START -> if (inActiveRewatch) {
                TrackingScrobbleResult.Handled
            } else {
                SimklScrobbleRepository.scrobbleStart(item = item, progressPercent = progressPercent).copy(handled = true)
            }
            // A pause is reported as a stop here, and this stop records 80%+ as a watch.
            TrackingScrobbleAction.STOP -> {
                if (isPauseThatStopWouldRecordAsWatched(event)) {
                    TrackingScrobbleResult.Declined
                } else if (inActiveRewatch && progressPercent < SIMKL_SCROBBLE_WATCHED_PERCENT) {
                    TrackingScrobbleResult.Handled
                } else if (inActiveRewatch && item is SimklScrobbleItem.Episode) {
                    // A plain completing stop marks the episode watched on the title's original
                    // watch, so during an active rewatch it reset the original to the rewatched
                    // episodes. Scrobble's own allow_rewatch cannot pin a session id and forks a new
                    // session per write, so record the episode on the pinned rewatch session instead
                    // and leave the original untouched.
                    val recorded = try {
                        SimklRewatchRepository.recordEpisode(item)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        // Not retried as a plain stop: that is exactly the write that resets the
                        // original watch. The playback-completion edge retries the pinned write.
                        log.w(error) { "SIMKL rewatch append failed for ${item.itemKey}" }
                        false
                    }
                    log.i {
                        "SIMKL scrobble stop for ${item.itemKey} @ ${"%.1f".format(progressPercent)}% " +
                            "diverted to the active rewatch (recorded=$recorded); original watch left untouched"
                    }
                    TrackingScrobbleResult(handled = true, confirmsWatched = recorded)
                } else {
                    SimklScrobbleRepository.scrobbleStop(item = item, progressPercent = progressPercent)
                        .copy(handled = true)
                }
            }
            // SIMKL's scrobble API has no pause action.
            TrackingScrobbleAction.PAUSE -> TrackingScrobbleResult.Declined
        }
    }
}

internal object SimklLibraryAdapter : TrackingLibraryProvider {
    private const val PLAN_TO_WATCH_KEY = "simkl:plantowatch"

    override val providerId: TrackingProviderId = TrackingProviderId.SIMKL
    override val changes = SimklLibraryRepository.uiState.map { Unit }
    override val connectionRefreshIntent: TrackingRefreshIntent = TrackingRefreshIntent.AUTOMATIC

    override fun ensureLoaded() = SimklLibraryRepository.ensureLoaded()
    override fun onProfileChanged() = SimklLibraryRepository.onProfileChanged()
    override fun clearLocalState() = SimklLibraryRepository.clearLocalState()
    override suspend fun refresh(intent: TrackingRefreshIntent) = SimklLibraryRepository.refreshNow()

    override fun snapshot(): TrackingLibrarySnapshot {
        val state = SimklLibraryRepository.uiState.value
        return TrackingLibrarySnapshot(
            items = state.allItems,
            sections = buildList {
                if (state.shows.isNotEmpty()) add(LibrarySection("simkl_shows", localizedString(Res.string.tracking_library_my_shows_2), state.shows))
                if (state.movies.isNotEmpty()) add(LibrarySection("simkl_movies", localizedString(Res.string.tracking_library_my_movies_2), state.movies))
                if (state.anime.isNotEmpty()) add(LibrarySection("simkl_anime", localizedString(Res.string.tracking_library_my_anime), state.anime))
            },
            tabs = listOf(
                TrackingLibraryTab(
                    key = PLAN_TO_WATCH_KEY,
                    title = localizedString(Res.string.tracking_library_simkl_plan_to_watch),
                    providerId = providerId,
                    kind = TrackingLibraryTabKind.WATCHLIST,
                ),
            ),
            hasLoaded = state.hasLoaded,
            isLoading = state.isLoading,
            errorMessage = state.errorMessage,
        )
    }

    override fun contains(contentId: String, contentType: String?): Boolean =
        SimklLibraryRepository.contains(contentId, contentType)

    override fun find(contentId: String): LibraryItem? = SimklLibraryRepository.find(contentId)

    override suspend fun membership(item: LibraryItem): Map<String, Boolean> =
        mapOf(PLAN_TO_WATCH_KEY to contains(item.id, item.type))

    override fun toggledDefaultMembership(currentMembership: Map<String, Boolean>): Map<String, Boolean> =
        mapOf(PLAN_TO_WATCH_KEY to (currentMembership[PLAN_TO_WATCH_KEY] != true))

    override suspend fun applyMembership(
        profileId: Int,
        item: LibraryItem,
        desiredMembership: Map<String, Boolean>,
        destructiveRemovalConfirmed: Boolean,
    ): TrackingMembershipResolution? {
        if (profileId != ProfileRepository.activeProfileId) return null
        val desired = desiredMembership[PLAN_TO_WATCH_KEY] == true
        // SIMKL has no "remove from Plan to Watch" that spares history: the removal is
        // /sync/history/remove for the whole title, which also erases every watched episode and
        // any rewatch. That is not what a bookmark toggle means, so it is refused for anything
        // with watch history unless the caller has had the user confirm exactly that.
        if (
            !desired &&
            !destructiveRemovalConfirmed &&
            contains(item.id, item.type) &&
            WatchedRepository.hasAnyWatched(item.id, item.type)
        ) {
            error(
                localizedString(
                    Res.string.simkl_remove_plan_to_watch_blocked,
                    item.name.ifBlank { localizedString(Res.string.simkl_this_title) },
                ),
            )
        }
        if (desired != contains(item.id, item.type)) {
            SimklLibraryRepository.setPlanToWatch(item, desired)
        }
        return TrackingMembershipResolution(providerId, PLAN_TO_WATCH_KEY, PLAN_TO_WATCH_KEY)
    }
}
