package com.nuvio.app.features.cloud

import com.nuvio.app.features.debrid.DebridProviderCapability
import com.nuvio.app.features.debrid.DebridProviders
import com.nuvio.app.features.debrid.DebridServiceCredential
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.supports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class CloudLibraryStore(
    private val credentialsProvider: suspend () -> List<DebridServiceCredential>,
    private val providerApis: List<CloudLibraryProviderApi>,
) {
    suspend fun refresh(): CloudLibraryUiState {
        val credentials = credentialsProvider()
            .filter { credential -> credential.provider.supports(DebridProviderCapability.CloudLibrary) }

        val providerStates = credentials.map { credential ->
            val api = providerApis.firstOrNull { it.provider.id == credential.provider.id }
            if (api == null) {
                return@map CloudLibraryProviderState(
                    provider = credential.provider,
                    errorMessage = "Cloud library is not available for ${credential.provider.displayName}.",
                )
            }

            api.listItems(credential.apiKey)
                .fold(
                    onSuccess = { items ->
                        CloudLibraryProviderState(
                            provider = credential.provider,
                            items = items,
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
        return api.resolvePlayback(
            apiKey = credential.apiKey,
            item = item,
            file = file,
        )
    }
}

object CloudLibraryRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = CloudLibraryStore(
        credentialsProvider = {
            DebridSettingsRepository.ensureLoaded()
            DebridProviders.configuredServices(DebridSettingsRepository.snapshot())
        },
        providerApis = CloudLibraryProviderApis.all(),
    )
    private val _uiState = MutableStateFlow(CloudLibraryUiState())
    private var loadedConnectionKeys: List<CloudConnectionKey> = emptyList()
    val uiState = _uiState.asStateFlow()

    fun ensureLoaded() {
        DebridSettingsRepository.ensureLoaded()
        val current = _uiState.value
        if (current.isRefreshing) return
        val connectedKeys = connectedCloudConnectionKeys()
        if (!current.isLoaded || connectedKeys != loadedConnectionKeys) {
            refresh()
        }
    }

    fun refresh() {
        _uiState.update { current ->
            current.copy(
                isRefreshing = true,
                providers = current.providers.map { it.copy(isLoading = true, errorMessage = null) },
            )
        }
        scope.launch {
            val refreshed = store.refresh()
            loadedConnectionKeys = connectedCloudConnectionKeys()
            _uiState.value = refreshed
        }
    }

    suspend fun resolvePlayback(
        item: CloudLibraryItem,
        file: CloudLibraryFile,
    ): CloudLibraryPlaybackResult =
        store.resolvePlayback(item, file)

    private fun connectedCloudCredentials(): List<DebridServiceCredential> =
        DebridProviders.configuredServices(DebridSettingsRepository.snapshot())
            .filter { credential -> credential.provider.supports(DebridProviderCapability.CloudLibrary) }

    private fun connectedCloudConnectionKeys(): List<CloudConnectionKey> =
        connectedCloudCredentials().map { credential ->
            CloudConnectionKey(
                providerId = credential.provider.id,
                apiKeyHash = credential.apiKey.hashCode(),
            )
        }.sortedBy { it.providerId }

    private data class CloudConnectionKey(
        val providerId: String,
        val apiKeyHash: Int,
    )
}
