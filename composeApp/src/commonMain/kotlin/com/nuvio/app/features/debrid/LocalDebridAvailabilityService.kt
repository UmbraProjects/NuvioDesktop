package com.nuvio.app.features.debrid

import com.nuvio.app.features.librarypvr.videoExtension
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamDebridCacheStatus
import com.nuvio.app.features.streams.StreamItem

object LocalDebridAvailabilityService {
    fun hasPendingCacheCheck(
        groups: List<AddonStreamGroup>,
        eligibleGroupIds: Set<String>? = null,
    ): Boolean {
        cacheCheckAccount() ?: return false
        return groups
            .filter { group -> eligibleGroupIds == null || group.addonId in eligibleGroupIds }
            .any { group ->
                group.streams.any { stream ->
                    stream.localAvailabilityHash() != null &&
                        stream.debridCacheStatus?.state !in FINAL_CACHE_STATES
                }
            }
    }

    fun markChecking(
        groups: List<AddonStreamGroup>,
        eligibleGroupIds: Set<String>? = null,
    ): List<AddonStreamGroup> {
        val account = cacheCheckAccount() ?: return groups
        return groups.updateAvailabilityStatus(eligibleGroupIds) { stream ->
            if (stream.localAvailabilityHash() == null || stream.debridCacheStatus?.state == StreamDebridCacheState.CACHED) {
                stream
            } else {
                stream.copy(
                    debridCacheStatus = StreamDebridCacheStatus(
                        providerId = account.provider.id,
                        providerName = account.provider.displayName,
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
        val account = cacheCheckAccount() ?: return groups
        val hashes = groups
            .filter { group -> eligibleGroupIds == null || group.addonId in eligibleGroupIds }
            .flatMap { group ->
                group.streams.mapNotNull { stream ->
                    stream.localAvailabilityHash()
                        ?.takeUnless { stream.debridCacheStatus?.state in FINAL_CACHE_STATES }
                }
            }
            .distinct()
        if (hashes.isEmpty()) return groups

        val cached = LocalDebridService.checkCached(account = account, hashes = hashes)
            ?: return groups.updateAvailabilityStatus(eligibleGroupIds) { stream ->
                val hash = stream.localAvailabilityHash()
                if (hash == null) {
                    stream
                } else {
                    stream.copy(
                        debridCacheStatus = StreamDebridCacheStatus(
                            providerId = account.provider.id,
                            providerName = account.provider.displayName,
                            state = StreamDebridCacheState.UNKNOWN,
                        ),
                    )
                }
            }

        return groups.updateAvailabilityStatus(eligibleGroupIds) { stream ->
            val hash = stream.localAvailabilityHash() ?: return@updateAvailabilityStatus stream
            if (stream.debridCacheStatus?.state in FINAL_CACHE_STATES) return@updateAvailabilityStatus stream
            val cachedItem = cached[hash]
            stream.copy(
                debridCacheStatus = StreamDebridCacheStatus(
                    providerId = account.provider.id,
                    providerName = account.provider.displayName,
                    state = if (cachedItem == null) StreamDebridCacheState.NOT_CACHED else StreamDebridCacheState.CACHED,
                    cachedName = cachedItem?.name,
                    cachedSize = cachedItem?.size,
                ),
            )
        }
    }

    /**
     * Fills in [StreamItem.resolvedTorrentName], [StreamItem.sourceVideoFileCount] and
     * [StreamItem.sourceTotalSizeBytes] for rows the availability check deliberately skips.
     *
     * [localAvailabilityHash] only covers streams that still need resolving, because a cache verdict
     * is pointless for a row that already carries a playable debrid URL. But the same `checkcached`
     * response also returns the **torrent's name and total size**, and on those already-resolved
     * rows both are the one thing nothing else supplies: the addon labels them with the single file
     * it picked and publishes that file's size, so a season pack is indistinguishable from an
     * episode, and the size of the torrent behind it invisible, until something says otherwise.
     *
     * One batched call per group, and purely additive: it never touches [StreamItem.debridCacheStatus],
     * so cache badges, autoplay readiness and P2P routing all see exactly what they saw before. Must
     * not gate publishing — a name that arrives late marks a row late, which is fine; a stream list
     * that waits on a name lookup is not.
     */
    suspend fun annotateTorrentNames(
        groups: List<AddonStreamGroup>,
        eligibleGroupIds: Set<String>? = null,
    ): List<AddonStreamGroup> {
        val eligible = groups.filter { group -> eligibleGroupIds == null || group.addonId in eligibleGroupIds }
        val account = cacheCheckAccount() ?: return groups
        val hashes = eligible
            .flatMap { group -> group.streams.mapNotNull { it.torrentNameLookupHash() } }
            .distinct()
        if (hashes.isEmpty()) return groups

        // list_files costs no extra request — same endpoint, same 300/min limit — and the listing
        // settles what the names can only suggest: how many episodes are actually in there.
        val cached = LocalDebridService.checkCached(
            account = account,
            hashes = hashes,
            listFiles = true,
        )
        if (cached == null) return groups
        if (cached.isEmpty()) return groups

        return groups.updateAvailabilityStatus(eligibleGroupIds) { stream ->
            val hash = stream.torrentNameLookupHash() ?: return@updateAvailabilityStatus stream
            val item = cached[hash] ?: return@updateAvailabilityStatus stream
            val videoCount = item.fileNames
                .count { it.videoExtension() != null }
                .takeIf { item.fileNames.isNotEmpty() }
            val name = item.name?.takeIf { it.isNotBlank() }
            val totalSize = item.size?.takeIf { it > 0L }
            if (name == null && videoCount == null && totalSize == null) return@updateAvailabilityStatus stream
            stream.copy(
                resolvedTorrentName = name ?: stream.resolvedTorrentName,
                sourceVideoFileCount = videoCount ?: stream.sourceVideoFileCount,
                sourceTotalSizeBytes = totalSize ?: stream.sourceTotalSizeBytes,
            )
        }
    }

    suspend fun isCached(hash: String): Boolean? {
        val account = cacheCheckAccount() ?: return null
        return LocalDebridService.isCached(account, hash)
    }

    private fun cacheCheckAccount(): DebridServiceCredential? {
        val settings = DebridSettingsRepository.snapshot()
        if (!settings.canResolvePlayableLinks) return null
        return settings.activeResolverCredential
            ?.takeIf { credential -> credential.provider.supports(DebridProviderCapability.LocalTorrentCacheCheck) }
    }
}

private val FINAL_CACHE_STATES = setOf(
    StreamDebridCacheState.CACHED,
    StreamDebridCacheState.NOT_CACHED,
)

internal fun StreamItem.localAvailabilityHash(): String? =
    infoHash
        ?.trim()
        ?.lowercase()
        ?.takeIf { isInstalledAddonStream && needsLocalDebridResolve && it.isNotBlank() }

/**
 * Hash to look a torrent name up by, for rows that have no torrent name of their own yet. Uses
 * [StreamItem.resolvedSourceInfoHash] so an already-resolved debrid link still yields its hash from
 * the URL path — the precise case [localAvailabilityHash] excludes.
 */
private fun StreamItem.torrentNameLookupHash(): String? {
    if (!isInstalledAddonStream || resolvedTorrentName != null) return null
    val alreadyNamed = clientResolve?.torrentName?.isNotBlank() == true ||
        clientResolve?.stream?.raw?.torrentName?.isNotBlank() == true
    if (alreadyNamed) return null
    return resolvedSourceInfoHash?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
}

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
