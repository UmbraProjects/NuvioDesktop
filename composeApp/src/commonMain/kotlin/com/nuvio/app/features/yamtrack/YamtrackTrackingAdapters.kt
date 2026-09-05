package com.nuvio.app.features.yamtrack

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.tracking.TrackingAuthProvider
import com.nuvio.app.features.tracking.TrackingCapability
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingProviderDescriptor
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import com.nuvio.app.features.tracking.TrackingScrobbleResult
import com.nuvio.app.features.tracking.TrackingScrobbler
import com.nuvio.app.features.tracking.TrackingSeekScrobblePolicy
import com.nuvio.app.features.tracking.TrackingWatchedProvider
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watched.WatchedItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal object YamtrackTrackingAuthProvider : TrackingAuthProvider {
    private val log = Logger.withTag("Yamtrack")
    private val json = Json { ignoreUnknownKeys = true }

    override val descriptor: TrackingProviderDescriptor = TrackingProviderDescriptor(
        id = TrackingProviderId.YAMTRACK,
        displayName = "Floppy (Yamtrack Fork)",
        capabilities = setOf(
            TrackingCapability.AUTHENTICATION,
            TrackingCapability.SCROBBLE,
            TrackingCapability.WATCHED_READ,
            TrackingCapability.WATCHED_WRITE,
            TrackingCapability.RATINGS_WRITE,
            TrackingCapability.LIBRARY_READ,
            TrackingCapability.LIBRARY_WRITE,
        ),
    )

    private val _isAuthenticated = MutableStateFlow(false)
    override val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    override fun ensureLoaded() {
        YamtrackSettingsRepository.ensureLoaded()
        refreshAuthenticationState()
    }

    internal fun refreshAuthenticationState() {
        _isAuthenticated.value = YamtrackSettingsRepository.activeCredentials() != null
    }

    override fun onProfileChanged() {
        YamtrackSettingsRepository.onProfileChanged()
        refreshAuthenticationState()
    }

    override fun clearLocalState() {
        YamtrackSettingsRepository.clearLocalState()
        refreshAuthenticationState()
    }

    override fun removeStoredProfile(profileId: Int) = Unit

    /**
     * Checks the instance in two steps so the failure can be named.
     *
     * `/api/v1/info` needs no token, so reaching it proves the URL; `/api/v1/media` then proves the
     * token. Collapsing the two would report every failure as "check your settings".
     */
    suspend fun testConnection(baseUrl: String, apiToken: String): YamtrackConnectionState {
        val normalized = normalizeBaseUrl(baseUrl)
        if (normalized.isBlank() || apiToken.isBlank()) {
            return YamtrackConnectionState.Unreachable("Enter both a URL and a token")
        }
        YamtrackSettingsRepository.setConnectionState(YamtrackConnectionState.Testing)

        val info = runCatching {
            httpRequestRaw(
                method = "GET",
                url = "$normalized/api/v1/info",
                headers = mapOf("Accept" to "application/json"),
                body = "",
            )
        }.onFailure { if (it is CancellationException) throw it }.getOrNull()

        if (info == null || info.status !in 200..299) {
            val detail = info?.let { "HTTP ${it.status}" } ?: "could not reach the server"
            log.w { "Yamtrack connection test failed at /info: $detail" }
            return YamtrackConnectionState.Unreachable(detail).also {
                YamtrackSettingsRepository.setConnectionState(it)
            }
        }
        val version = runCatching {
            json.decodeFromString(YamtrackInfo.serializer(), info.body).version
        }.getOrNull()

        val authed = runCatching {
            httpRequestRaw(
                method = "GET",
                url = "$normalized/api/v1/media?limit=1",
                headers = mapOf("Accept" to "application/json", "Authorization" to "Bearer $apiToken"),
                body = "",
            )
        }.onFailure { if (it is CancellationException) throw it }.getOrNull()

        val state = when {
            authed == null -> YamtrackConnectionState.Unreachable("token check failed")
            authed.status in 200..299 -> YamtrackConnectionState.Connected(version)
            authed.status == 401 || authed.status == 403 -> YamtrackConnectionState.Unauthorized
            else -> YamtrackConnectionState.Unreachable("HTTP ${authed.status}")
        }
        YamtrackSettingsRepository.setConnectionState(state)
        return state
    }

    @Serializable
    private data class YamtrackInfo(val version: String? = null)
}

internal object YamtrackScrobbleAdapter : TrackingScrobbler {
    override val providerId: TrackingProviderId = TrackingProviderId.YAMTRACK

    /**
     * Only `stop` writes durable history here, so a seek must never send one — it would record a
     * watch. The restart alone refreshes the instance's live card at the new position.
     */
    override val seekScrobblePolicy: TrackingSeekScrobblePolicy =
        TrackingSeekScrobblePolicy.RESTART_ONLY

    /**
     * Two minutes: this is the user's own server with no configured rate limiting, and the value
     * being kept fresh is a live "Now Playing" card, where staleness is immediately visible.
     */
    override val progressRefreshIntervalMs: Long = 2 * 60 * 1000L

    override suspend fun scrobble(
        profileId: Int,
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent,
    ): TrackingScrobbleResult {
        val media = event.media
        val catalog = media.catalog ?: return TrackingScrobbleResult.Declined
        val item = YamtrackScrobbleRepository.buildItem(
            contentType = catalog.contentType,
            parentMetaId = catalog.contentId,
            videoId = catalog.videoId,
            title = media.title,
            episodeTitle = media.episode?.title,
            seasonNumber = media.episode?.season,
            episodeNumber = media.episode?.number,
            isAnime = media.kind == TrackingMediaKind.ANIME,
        ) ?: return TrackingScrobbleResult.Declined

        // Only `stop` writes durable history here, so a pause must not be reported as one.
        val wireAction = if (
            action == TrackingScrobbleAction.STOP && event.isPauseRatherThanStop
        ) {
            TrackingScrobbleAction.PAUSE.wireValue
        } else {
            action.wireValue
        }

        return YamtrackScrobbleRepository.scrobble(
            action = wireAction,
            item = item,
            progressPercent = event.progressPercent.toFloat(),
            positionSeconds = event.positionSeconds,
            durationSeconds = event.durationSeconds,
        )
    }
}

internal object YamtrackWatchedAdapter : TrackingWatchedProvider {
    override val providerId: TrackingProviderId = TrackingProviderId.YAMTRACK

    override suspend fun pull(profileId: Int, pageSize: Int): List<WatchedItem> {
        if (profileId != ProfileRepository.activeProfileId) return emptyList()
        return YamtrackHistoryRepository.watchedItems()
    }

    override suspend fun push(profileId: Int, items: Collection<WatchedItem>) {
        // Read-only, for the same reason as MDBList: history reaches Yamtrack through the scrobble
        // stop, and pushing the local set as well would write the same episodes by two routes.
    }

    override suspend fun delete(profileId: Int, items: Collection<WatchedItem>) = Unit
}
