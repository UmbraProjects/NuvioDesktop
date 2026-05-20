package com.nuvio.app.features.debrid

import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamDebridCacheStatus
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CancellationException

object TorboxAvailabilityService {
    fun markChecking(
        groups: List<AddonStreamGroup>,
        eligibleGroupIds: Set<String>? = null,
    ): List<AddonStreamGroup> {
        val settings = DebridSettingsRepository.snapshot()
        if (!settings.enabled || settings.torboxApiKey.isBlank()) return groups
        return groups.updateAvailabilityStatus(eligibleGroupIds) { stream ->
            if (stream.torboxAvailabilityHash() == null || stream.debridCacheStatus?.state == StreamDebridCacheState.CACHED) {
                stream
            } else {
                stream.copy(
                    debridCacheStatus = StreamDebridCacheStatus(
                        providerId = DebridProviders.TORBOX_ID,
                        providerName = DebridProviders.Torbox.displayName,
                        state = StreamDebridCacheState.CHECKING,
                    ),
                )
            }
        }
    }

    suspend fun annotateCachedAvailability(
        groups: List<AddonStreamGroup>,
        eligibleGroupIds: Set<String>? = null,
    ): List<AddonStreamGroup> {
        val settings = DebridSettingsRepository.snapshot()
        val apiKey = settings.torboxApiKey.trim()
        if (!settings.enabled || apiKey.isBlank()) return groups

        val hashes = groups
            .filter { group -> eligibleGroupIds == null || group.addonId in eligibleGroupIds }
            .flatMap { group ->
                group.streams.mapNotNull { stream ->
                    stream.torboxAvailabilityHash()
                        ?.takeUnless { stream.debridCacheStatus?.state in FINAL_CACHE_STATES }
                }
            }
            .distinct()
        if (hashes.isEmpty()) return groups

        val cached = checkCached(apiKey = apiKey, hashes = hashes)
            ?: return groups.updateAvailabilityStatus(eligibleGroupIds) { stream ->
                val hash = stream.torboxAvailabilityHash()
                if (hash == null) {
                    stream
                } else {
                    stream.copy(
                        debridCacheStatus = StreamDebridCacheStatus(
                            providerId = DebridProviders.TORBOX_ID,
                            providerName = DebridProviders.Torbox.displayName,
                            state = StreamDebridCacheState.UNKNOWN,
                        ),
                    )
                }
            }

        return groups.updateAvailabilityStatus(eligibleGroupIds) { stream ->
            val hash = stream.torboxAvailabilityHash() ?: return@updateAvailabilityStatus stream
            if (stream.debridCacheStatus?.state in FINAL_CACHE_STATES) return@updateAvailabilityStatus stream
            val cachedItem = cached[hash]
            stream.copy(
                debridCacheStatus = StreamDebridCacheStatus(
                    providerId = DebridProviders.TORBOX_ID,
                    providerName = DebridProviders.Torbox.displayName,
                    state = if (cachedItem == null) StreamDebridCacheState.NOT_CACHED else StreamDebridCacheState.CACHED,
                    cachedName = cachedItem?.name,
                    cachedSize = cachedItem?.size,
                ),
            )
        }
    }

    suspend fun isCached(hash: String): Boolean? {
        val settings = DebridSettingsRepository.snapshot()
        val apiKey = settings.torboxApiKey.trim()
        val normalizedHash = hash.trim().lowercase().takeIf { it.isNotBlank() } ?: return null
        if (!settings.enabled || apiKey.isBlank()) return null
        return checkCached(apiKey = apiKey, hashes = listOf(normalizedHash))?.containsKey(normalizedHash)
    }

    private suspend fun checkCached(
        apiKey: String,
        hashes: List<String>,
    ): Map<String, TorboxCachedItemDto>? =
        try {
            val response = TorboxApiClient.checkCached(apiKey = apiKey, hashes = hashes)
            if (!response.isSuccessful || response.body?.success == false) {
                null
            } else {
                response.body?.data.orEmpty().mapKeys { it.key.lowercase() }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }
}

private val FINAL_CACHE_STATES = setOf(
    StreamDebridCacheState.CACHED,
    StreamDebridCacheState.NOT_CACHED,
)

internal fun StreamItem.torboxAvailabilityHash(): String? =
    infoHash
        ?.trim()
        ?.lowercase()
        ?.takeIf { isInstalledAddonStream && needsLocalDebridResolve && it.isNotBlank() }

private fun List<AddonStreamGroup>.updateAvailabilityStatus(
    eligibleGroupIds: Set<String>?,
    transform: (StreamItem) -> StreamItem,
): List<AddonStreamGroup> =
    map { group ->
        if (eligibleGroupIds != null && group.addonId !in eligibleGroupIds) return@map group
        var changed = false
        val updatedStreams = group.streams.map { stream ->
            val updated = transform(stream)
            if (updated != stream) changed = true
            updated
        }
        if (changed) group.copy(streams = updatedStreams) else group
    }
