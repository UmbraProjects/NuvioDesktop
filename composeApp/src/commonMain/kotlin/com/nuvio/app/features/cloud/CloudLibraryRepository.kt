package com.nuvio.app.features.cloud

import com.nuvio.app.features.catalog.FilenameMetaResolver
import com.nuvio.app.features.catalog.ResolvedName
import com.nuvio.app.features.debrid.DebridCloudLibraryWindow
import com.nuvio.app.features.debrid.DebridProviderCapability
import com.nuvio.app.features.debrid.DebridProviders
import com.nuvio.app.features.debrid.DebridServiceCredential
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.supports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.cloud_library_playback_disabled
import nuvio.composeapp.generated.resources.cloud_library_provider_unavailable
import org.jetbrains.compose.resources.getString

internal class CloudLibraryStore(
    private val credentialsProvider: suspend () -> List<DebridServiceCredential>,
    private val providerApis: List<CloudLibraryProviderApi>,
    private val windowProvider: () -> DebridCloudLibraryWindow = { DebridCloudLibraryWindow.ALL },
    private val nowEpochMs: () -> Long = CloudLibraryClock::nowEpochMs,
) {
    fun window(): DebridCloudLibraryWindow = windowProvider()

    suspend fun refresh(window: DebridCloudLibraryWindow = windowProvider()): CloudLibraryUiState {
        val credentials = credentialsProvider()
            .filter { credential -> credential.provider.supports(DebridProviderCapability.CloudLibrary) }
        val now = nowEpochMs()

        val providerStates = credentials.map { credential ->
            val api = providerApis.firstOrNull { it.provider.id == credential.provider.id }
            if (api == null) {
                return@map CloudLibraryProviderState(
                    provider = credential.provider,
                    errorMessage = getString(
                        Res.string.cloud_library_provider_unavailable,
                        credential.provider.displayName,
                    ),
                )
            }

            api.listItems(credential.apiKey)
                .fold(
                    onSuccess = { items ->
                        CloudLibraryProviderState(
                            provider = credential.provider,
                            items = items.withinWindow(window = window, nowEpochMs = now),
                        )
                    },
                    onFailure = { error ->
                        CloudLibraryProviderState(
                            provider = credential.provider,
                            errorMessage = error.message,
                        )
                    },
                )
        }

        return CloudLibraryUiState(
            isLoaded = true,
            isRefreshing = false,
            providers = providerStates,
        )
    }

    suspend fun resolvePlayback(
        item: CloudLibraryItem,
        file: CloudLibraryFile,
    ): CloudLibraryPlaybackResult {
        if (!file.playable) return CloudLibraryPlaybackResult.NotPlayable
        val credential = credentialsProvider()
            .firstOrNull { credential -> credential.provider.id == item.providerId }
            ?: return CloudLibraryPlaybackResult.MissingCredentials
        val api = providerApis.firstOrNull { it.provider.id == item.providerId }
            ?: return CloudLibraryPlaybackResult.Failed()
        file.playbackUrl?.takeIf { it.isNotBlank() }?.let { url ->
            return CloudLibraryPlaybackResult.Success(
                url = url,
                filename = file.name.takeIf { it.isNotBlank() },
                videoSizeBytes = file.sizeBytes,
            )
        }
        return api.resolvePlayback(
            apiKey = credential.apiKey,
            item = item,
            file = file,
        )
    }
}

/**
 * Newest first, then trimmed to the user's window.
 *
 * Ordering is unconditional — a provider hands its account back in whatever order it pleases (TorBox
 * oldest-first), which puts the download the user just added at the bottom of a very long list.
 */
internal fun List<CloudLibraryItem>.withinWindow(
    window: DebridCloudLibraryWindow,
    nowEpochMs: Long,
): List<CloudLibraryItem> =
    // Undated items sort last but are never filtered out; see DebridCloudLibraryWindow.includes.
    sortedByDescending { item -> item.addedAtEpochMs ?: Long.MIN_VALUE }
        .filter { item -> window.includes(addedAtEpochMs = item.addedAtEpochMs, nowEpochMs = nowEpochMs) }

object CloudLibraryRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = CloudLibraryStore(
        credentialsProvider = {
            DebridSettingsRepository.ensureLoaded()
            DebridProviders.configuredServices(DebridSettingsRepository.snapshot())
        },
        providerApis = CloudLibraryProviderApis.all(),
        windowProvider = { DebridSettingsRepository.snapshot().cloudLibraryWindow },
    )
    private val _uiState = MutableStateFlow(CloudLibraryUiState())
    private var loadedConnectionKeys: List<CloudConnectionKey> = emptyList()
    // The window is applied while listing, so changing it has to re-list rather than re-filter what
    // is already in hand — widening it needs rows the last refresh never kept.
    private var loadedWindow: DebridCloudLibraryWindow? = null
    private var resolveNamesJob: Job? = null
    val uiState = _uiState.asStateFlow()

    fun ensureLoaded() {
        DebridSettingsRepository.ensureLoaded()
        if (!DebridSettingsRepository.snapshot().cloudLibraryEnabled) {
            loadedConnectionKeys = emptyList()
            loadedWindow = null
            _uiState.value = CloudLibraryUiState(isLoaded = true, isEnabled = false)
            return
        }
        val current = _uiState.value
        if (current.isRefreshing) return
        val connectedKeys = connectedCloudConnectionKeys()
        val window = DebridSettingsRepository.snapshot().cloudLibraryWindow
        if (!current.isLoaded || connectedKeys != loadedConnectionKeys || window != loadedWindow) {
            refresh()
        }
    }

    fun refresh() {
        DebridSettingsRepository.ensureLoaded()
        if (!DebridSettingsRepository.snapshot().cloudLibraryEnabled) {
            loadedConnectionKeys = emptyList()
            loadedWindow = null
            _uiState.value = CloudLibraryUiState(isLoaded = true, isEnabled = false)
            return
        }
        _uiState.update { current ->
            current.copy(
                isEnabled = true,
                isRefreshing = true,
                providers = current.providers.map { it.copy(isLoading = true, errorMessage = null) },
            )
        }
        scope.launch {
            val refreshed = store.refresh().carryResolvedNamesFrom(_uiState.value)
            loadedConnectionKeys = connectedCloudConnectionKeys()
            loadedWindow = DebridSettingsRepository.snapshot().cloudLibraryWindow
            _uiState.value = refreshed
            resolveDisplayNames(refreshed)
        }
    }

    /**
     * Turns torrent names into real titles and posters in the background, then re-publishes.
     *
     * Deliberately after the raw state is emitted: the library appears immediately and improves a
     * moment later instead of waiting on TMDB. Every failure leaves the raw names in place.
     */
    private fun resolveDisplayNames(state: CloudLibraryUiState) {
        val names = state.filenameResolutionCandidateNames()
        if (names.isEmpty()) return
        resolveNamesJob?.cancel()
        resolveNamesJob = scope.launch {
            // The resolver caps how long one call waits and finishes the rest in the background, so
            // a large account fills in over a few passes. A pass that adds nothing new is the end.
            var published = 0
            repeat(RESOLVE_NAMES_PASSES) {
                val resolved = runCatching {
                    FilenameMetaResolver.resolveNames(names, catalogType = CloudLibraryContentType)
                }.getOrDefault(emptyMap())
                if (resolved.size <= published) return@launch
                published = resolved.size
                // update(), not assignment: a playback URL may have been stored while we resolved.
                _uiState.update { current -> current.withResolvedNames(resolved) }
            }
        }
    }

    suspend fun findPlaybackTargetForProgress(
        contentId: String,
        videoId: String,
    ): CloudLibraryPlaybackTarget? =
        when (val result = findPlaybackTargetForProgressResult(contentId = contentId, videoId = videoId)) {
            is CloudLibraryPlaybackTargetLookupResult.Found -> result.target
            CloudLibraryPlaybackTargetLookupResult.Disabled,
            is CloudLibraryPlaybackTargetLookupResult.NotConnected,
            CloudLibraryPlaybackTargetLookupResult.NotFound,
            -> null
        }

    suspend fun findPlaybackTargetForProgressResult(
        contentId: String,
        videoId: String,
    ): CloudLibraryPlaybackTargetLookupResult {
        DebridSettingsRepository.ensureLoaded()
        if (!DebridSettingsRepository.snapshot().cloudLibraryEnabled) {
            loadedConnectionKeys = emptyList()
            loadedWindow = null
            _uiState.value = CloudLibraryUiState(isLoaded = true, isEnabled = false)
            return CloudLibraryPlaybackTargetLookupResult.Disabled
        }

        val providerId = cloudLibraryProviderId(contentId)
            .ifBlank { cloudLibraryProviderId(videoId) }
        val connectedCredentials = connectedCloudCredentials()
        if (connectedCredentials.isEmpty()) {
            return CloudLibraryPlaybackTargetLookupResult.NotConnected(
                providerName = providerId.takeIf { it.isNotBlank() }?.let(DebridProviders::displayName),
            )
        }
        if (
            providerId.isNotBlank() &&
            connectedCredentials.none { credential -> credential.provider.id.equals(providerId, ignoreCase = true) }
        ) {
            return CloudLibraryPlaybackTargetLookupResult.NotConnected(
                providerName = DebridProviders.displayName(providerId),
            )
        }

        _uiState.value.findPlaybackTargetForProgress(
            contentId = contentId,
            videoId = videoId,
        )?.let { target -> return CloudLibraryPlaybackTargetLookupResult.Found(target) }

        val refreshed = refreshNow()
        refreshed.findPlaybackTargetForProgress(
            contentId = contentId,
            videoId = videoId,
        )?.let { target -> return CloudLibraryPlaybackTargetLookupResult.Found(target) }

        // A file older than the user's "added within" window is still theirs to resume — Continue
        // Watching does not expire when the listing does. Search the whole account once on a miss,
        // without publishing it: the library keeps showing the window the user asked for.
        if (store.window().isUnbounded) return CloudLibraryPlaybackTargetLookupResult.NotFound
        val unboundedTarget = store.refresh(window = DebridCloudLibraryWindow.ALL)
            .findPlaybackTargetForProgress(contentId = contentId, videoId = videoId)
        return if (unboundedTarget != null) {
            CloudLibraryPlaybackTargetLookupResult.Found(unboundedTarget)
        } else {
            CloudLibraryPlaybackTargetLookupResult.NotFound
        }
    }

    suspend fun resolvePlayback(
        item: CloudLibraryItem,
        file: CloudLibraryFile,
    ): CloudLibraryPlaybackResult {
        DebridSettingsRepository.ensureLoaded()
        if (!DebridSettingsRepository.snapshot().cloudLibraryEnabled) {
            return CloudLibraryPlaybackResult.Failed(getString(Res.string.cloud_library_playback_disabled))
        }
        val result = store.resolvePlayback(item, file)
        if (result is CloudLibraryPlaybackResult.Success) {
            rememberResolvedPlaybackUrl(item = item, file = file, url = result.url)
        }
        return result
    }

    private fun rememberResolvedPlaybackUrl(
        item: CloudLibraryItem,
        file: CloudLibraryFile,
        url: String,
    ) {
        if (url.isBlank()) return
        _uiState.update { current ->
            current.withResolvedPlaybackUrl(
                item = item,
                file = file,
                url = url,
            )
        }
    }

    private fun connectedCloudCredentials(): List<DebridServiceCredential> =
        DebridSettingsRepository.snapshot()
            .takeIf { settings -> settings.cloudLibraryEnabled }
            ?.let(DebridProviders::configuredServices)
            .orEmpty()
            .filter { credential -> credential.provider.supports(DebridProviderCapability.CloudLibrary) }

    private fun connectedCloudConnectionKeys(): List<CloudConnectionKey> =
        connectedCloudCredentials().map { credential ->
            CloudConnectionKey(
                providerId = credential.provider.id,
                apiKeyHash = credential.apiKey.hashCode(),
            )
        }.sortedBy { it.providerId }

    private suspend fun refreshNow(): CloudLibraryUiState {
        _uiState.update { current ->
            current.copy(
                isEnabled = true,
                isRefreshing = true,
                providers = current.providers.map { it.copy(isLoading = true, errorMessage = null) },
            )
        }
        val refreshed = store.refresh().carryResolvedNamesFrom(_uiState.value)
        loadedConnectionKeys = connectedCloudConnectionKeys()
        loadedWindow = DebridSettingsRepository.snapshot().cloudLibraryWindow
        _uiState.value = refreshed
        resolveDisplayNames(refreshed)
        return refreshed
    }

    private const val RESOLVE_NAMES_PASSES = 3

    private data class CloudConnectionKey(
        val providerId: String,
        val apiKeyHash: Int,
    )
}

internal fun CloudLibraryUiState.findPlaybackTargetForProgress(
    contentId: String,
    videoId: String,
): CloudLibraryPlaybackTarget? {
    val normalizedContentId = contentId.trim()
    val normalizedVideoId = videoId.trim()
    if (normalizedContentId.isBlank()) return null

    val matchingItems = items.filter { item -> item.stableKey == normalizedContentId }
    if (matchingItems.isEmpty()) return null

    for (item in matchingItems) {
        val exactFile = item.playableFiles.firstOrNull { file ->
            item.playbackVideoId(file) == normalizedVideoId
        }
        if (exactFile != null) {
            return CloudLibraryPlaybackTarget(item = item, file = exactFile)
        }
    }

    val singleItem = matchingItems.singleOrNull() ?: return null
    val singleFile = singleItem.playableFiles.singleOrNull() ?: return null
    return CloudLibraryPlaybackTarget(item = singleItem, file = singleFile)
}

/** Attaches titles/artwork recovered from torrent names, keyed by the raw name that was resolved. */
/**
 * Carries resolved display metadata from [previous] onto a freshly fetched state.
 *
 * A refresh replaces the provider lists wholesale with what the debrid API just returned, and the
 * API knows nothing about titles — so without this, **every resolved poster and title is discarded
 * on every refresh** and the rows go back to raw release names until TMDB resolution finishes
 * again. On screen that reads as posters unloading and reloading, which is exactly what a user sees
 * when a refresh happens while they are scrolling.
 *
 * Matched on [CloudLibraryItem.stableKey]. The resolution is derived from the torrent name, which
 * does not change between refreshes, so carrying it forward cannot go stale in a way re-resolution
 * would fix — and an item whose name *did* change gets a new resolution from the pass that follows.
 */
internal fun CloudLibraryUiState.carryResolvedNamesFrom(
    previous: CloudLibraryUiState,
): CloudLibraryUiState {
    val resolvedByKey = previous.providers
        .asSequence()
        .flatMap { it.items.asSequence() }
        .filter { it.resolvedName != null || it.resolvedPoster != null }
        .associateBy { it.stableKey }
    if (resolvedByKey.isEmpty()) return this

    return copy(
        providers = providers.map { providerState ->
            providerState.copy(
                items = providerState.items.map { item ->
                    // Only fills gaps: anything the refresh itself resolved wins, so this can never
                    // hold back newer information.
                    val prior = resolvedByKey[item.stableKey] ?: return@map item
                    item.copy(
                        resolvedName = item.resolvedName ?: prior.resolvedName,
                        resolvedPoster = item.resolvedPoster ?: prior.resolvedPoster,
                        resolvedBackdrop = item.resolvedBackdrop ?: prior.resolvedBackdrop,
                        resolvedDescription = item.resolvedDescription ?: prior.resolvedDescription,
                        resolvedLookupId = item.resolvedLookupId ?: prior.resolvedLookupId,
                        resolvedLookupType = item.resolvedLookupType ?: prior.resolvedLookupType,
                        resolvedImdbId = item.resolvedImdbId ?: prior.resolvedImdbId,
                    )
                },
            )
        },
    )
}

internal fun CloudLibraryUiState.withResolvedNames(
    resolved: Map<String, ResolvedName>,
): CloudLibraryUiState {
    if (resolved.isEmpty()) return this
    var didUpdate = false
    val updatedProviders = providers.map { providerState ->
        val updatedItems = providerState.items.map { item ->
            val match = resolved[item.name]
                ?: item.playableFiles.firstNotNullOfOrNull { file -> resolved[file.name] }
                ?: return@map item
            didUpdate = true
            item.copy(
                resolvedName = match.displayName,
                resolvedPoster = match.poster ?: match.posterFallback,
                resolvedBackdrop = match.backdrop,
                resolvedDescription = match.overview,
                resolvedLookupId = match.lookupId,
                resolvedLookupType = match.lookupType,
                resolvedImdbId = match.imdbId,
            )
        }
        providerState.copy(items = updatedItems)
    }
    return if (didUpdate) copy(providers = updatedProviders) else this
}

/**
 * Debrid providers often expose a cleaned torrent label (for example, `Hanna S01E07`) while the
 * playable filename still contains the release tokens needed for safe TMDB matching. Try both.
 */
internal fun CloudLibraryUiState.filenameResolutionCandidateNames(): List<String> =
    items.flatMap { item ->
        buildList {
            item.name.takeIf(String::isNotBlank)?.let(::add)
            item.playableFiles.mapTo(this) { it.name }
        }
    }.filter(String::isNotBlank).distinct()

internal fun CloudLibraryUiState.withResolvedPlaybackUrl(
    item: CloudLibraryItem,
    file: CloudLibraryFile,
    url: String,
): CloudLibraryUiState {
    val normalizedUrl = url.trim().takeIf { it.isNotBlank() } ?: return this
    val targetItemKey = item.stableKey
    val targetFileKey = file.stableKey
    var didUpdate = false
    val updatedProviders = providers.map { providerState ->
        if (providerState.providerId != item.providerId) return@map providerState
        val updatedItems = providerState.items.map { candidateItem ->
            if (candidateItem.stableKey != targetItemKey) return@map candidateItem
            val updatedFiles = candidateItem.files.map { candidateFile ->
                if (candidateFile.stableKey != targetFileKey) {
                    candidateFile
                } else {
                    didUpdate = true
                    candidateFile.copy(playbackUrl = normalizedUrl)
                }
            }
            candidateItem.copy(files = updatedFiles)
        }
        providerState.copy(items = updatedItems)
    }
    return if (didUpdate) {
        copy(providers = updatedProviders)
    } else {
        this
    }
}
