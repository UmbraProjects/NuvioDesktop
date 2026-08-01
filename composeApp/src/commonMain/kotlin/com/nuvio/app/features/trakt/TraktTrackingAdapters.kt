package com.nuvio.app.features.trakt

import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibrarySection
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingAuthProvider
import com.nuvio.app.features.tracking.TrackingCapability
import com.nuvio.app.features.tracking.TrackingLibraryProvider
import com.nuvio.app.features.tracking.TrackingLibrarySnapshot
import com.nuvio.app.features.tracking.TrackingLibraryTab
import com.nuvio.app.features.tracking.TrackingLibraryTabKind
import com.nuvio.app.features.tracking.TrackingMembershipResolution
import com.nuvio.app.features.tracking.TrackingProviderDescriptor
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import com.nuvio.app.features.tracking.TrackingScrobbler
import com.nuvio.app.features.tracking.TrackingSeekScrobblePolicy
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * Ports for the existing Trakt repositories.
 *
 * These deliberately add no logic: every adapter forwards to the repository call the caller used to
 * make directly, so the emitted request bodies stay byte-identical. Trakt is unverifiable since the
 * VIP-gating of API keys, which is exactly why the migration must not also change its payloads.
 */

object TraktTrackingAuthProvider : TrackingAuthProvider {
    override val descriptor: TrackingProviderDescriptor = TrackingProviderDescriptor(
        id = TrackingProviderId.TRAKT,
        displayName = "Trakt",
        // Only advertise ports that are registered below; progress is still consumed by its
        // legacy call sites and intentionally remains absent here.
        capabilities = setOf(
            TrackingCapability.AUTHENTICATION,
            TrackingCapability.LIBRARY_READ,
            TrackingCapability.LIBRARY_WRITE,
            TrackingCapability.WATCHED_WRITE,
            TrackingCapability.SCROBBLE,
        ),
    )

    override val isAuthenticated: StateFlow<Boolean>
        get() = TraktAuthRepository.isAuthenticated

    override fun ensureLoaded() = TraktAuthRepository.ensureLoaded()

    override fun handleAuthCallback(url: String): Boolean {
        TraktAuthRepository.onAuthCallbackReceived(url)
        return true
    }

    override fun onProfileChanged() = TraktAuthRepository.onProfileChanged()

    override fun clearLocalState() {
        TraktAuthRepository.clearLocalState()
        TraktSettingsRepository.clearLocalState()
    }

    override fun removeStoredProfile(profileId: Int) {
        // Trakt auth and settings are profile-scoped in storage, but removal is currently driven by
        // the profile layer itself rather than through this port. Wiring the registry into those
        // call sites is deferred until every provider is registered.
    }
}

object TraktScrobbleAdapter : TrackingScrobbler {
    override val providerId: TrackingProviderId = TrackingProviderId.TRAKT

    /** Matches the pre-existing seek behaviour: a seek stops the scrobble and starts a new one. */
    override val seekScrobblePolicy: TrackingSeekScrobblePolicy =
        TrackingSeekScrobblePolicy.STOP_AND_RESTART

    override suspend fun scrobble(
        profileId: Int,
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent,
    ): Boolean {
        // The coordinator already gates on the active profile, and the repository resolves the
        // active profile itself when building auth headers and dedupe stamps.
        val media = event.media
        val catalog = media.catalog ?: return false
        // Null means no Trakt-supported id resolved; the repository refuses title-only matches
        // rather than risk scrobbling the wrong show.
        val item = TraktScrobbleRepository.buildItem(
            contentType = catalog.contentType,
            parentMetaId = catalog.contentId,
            videoId = catalog.videoId,
            title = media.title,
            seasonNumber = media.episode?.season,
            episodeNumber = media.episode?.number,
            episodeTitle = media.episode?.title,
        ) ?: return false
        val progressPercent = event.progressPercent.toFloat()

        return when (action) {
            TrackingScrobbleAction.START -> {
                TraktScrobbleRepository.scrobbleStart(item = item, progressPercent = progressPercent)
                true
            }
            TrackingScrobbleAction.STOP -> {
                TraktScrobbleRepository.scrobbleStop(item = item, progressPercent = progressPercent)
                true
            }
            // Trakt has a pause endpoint, but nothing emits PAUSE yet and wiring it here would
            // change what this provider sends. It is added with the players that need it.
            TrackingScrobbleAction.PAUSE -> false
        }
    }
}

/** Provider-neutral library port backed by Trakt's existing watchlist/personal-list repository. */
internal object TraktLibraryAdapter : TrackingLibraryProvider {
    private const val DEFAULT_LIST_KEY = "trakt:watchlist"

    override val providerId: TrackingProviderId = TrackingProviderId.TRAKT
    override val changes = TraktLibraryRepository.uiState.map { Unit }
    override val connectionRefreshIntent: TrackingRefreshIntent = TrackingRefreshIntent.AUTOMATIC

    override fun ensureLoaded() = TraktLibraryRepository.ensureLoaded()
    override fun prepare() = TraktLibraryRepository.preloadListTabsAsync()
    override fun onProfileChanged() = TraktLibraryRepository.onProfileChanged()
    override fun clearLocalState() = TraktLibraryRepository.clearLocalState()

    override suspend fun refresh(intent: TrackingRefreshIntent) {
        when (intent) {
            TrackingRefreshIntent.AUTOMATIC -> TraktLibraryRepository.ensureFresh()
            TrackingRefreshIntent.USER_INITIATED,
            TrackingRefreshIntent.INVALIDATED,
            -> TraktLibraryRepository.refreshNow()
        }
    }

    override fun snapshot(): TrackingLibrarySnapshot {
        val state = TraktLibraryRepository.uiState.value
        val tabs = state.listTabs.map { tab ->
            TrackingLibraryTab(
                key = tab.key,
                title = tab.title,
                providerId = providerId,
                kind = if (tab.type == TraktListType.PERSONAL) {
                    TrackingLibraryTabKind.PERSONAL
                } else {
                    TrackingLibraryTabKind.WATCHLIST
                },
            )
        }
        return TrackingLibrarySnapshot(
            items = state.allItems,
            sections = state.listTabs.mapNotNull { tab ->
                state.entriesByList[tab.key].orEmpty().takeIf(List<LibraryItem>::isNotEmpty)?.let { items ->
                    LibrarySection(type = tab.key, displayTitle = tab.title, items = items)
                }
            },
            tabs = tabs,
            hasLoaded = state.hasLoaded,
            isLoading = state.isLoading,
            errorMessage = state.errorMessage,
        )
    }

    override fun contains(contentId: String, contentType: String?): Boolean {
        val type = contentType ?: snapshot().items.firstOrNull { it.id == contentId }?.type ?: return false
        return TraktLibraryRepository.isInAnyList(contentId, type)
    }

    override fun find(contentId: String): LibraryItem? =
        TraktLibraryRepository.uiState.value.allItems.firstOrNull { it.id == contentId }

    override suspend fun membership(item: LibraryItem): Map<String, Boolean> =
        TraktLibraryRepository.getMembershipSnapshot(item).listMembership

    override fun toggledDefaultMembership(currentMembership: Map<String, Boolean>): Map<String, Boolean> =
        currentMembership.toMutableMap().apply {
            this[DEFAULT_LIST_KEY] = currentMembership[DEFAULT_LIST_KEY] != true
        }

    override suspend fun applyMembership(
        profileId: Int,
        item: LibraryItem,
        desiredMembership: Map<String, Boolean>,
        destructiveRemovalConfirmed: Boolean,
    ): TrackingMembershipResolution? {
        if (profileId != ProfileRepository.activeProfileId) return null
        val before = membership(item)
        TraktLibraryRepository.applyMembershipChanges(
            item = item,
            changes = TraktMembershipChanges(desiredMembership = desiredMembership),
        )
        val requestedKey = (before.keys + desiredMembership.keys).firstOrNull { key ->
            (before[key] == true) != (desiredMembership[key] == true)
        } ?: return null
        return TrackingMembershipResolution(providerId, requestedKey, requestedKey)
    }
}
