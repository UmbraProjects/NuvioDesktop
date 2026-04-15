package com.nuvio.app.features.addons

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class AddonManifestCachePayload(
    val entries: List<AddonManifestCacheEntry> = emptyList(),
)

@Serializable
internal data class AddonManifestCacheEntry(
    val manifestUrl: String,
    val payload: String,
    val fetchedAtEpochMs: Long,
)

internal object AddonManifestCacheCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun decode(payload: String): List<AddonManifestCacheEntry>? =
        runCatching {
            json.decodeFromString(AddonManifestCachePayload.serializer(), payload).entries
        }.getOrNull()

    fun encode(entries: Collection<AddonManifestCacheEntry>): String =
        json.encodeToString(
            AddonManifestCachePayload.serializer(),
            AddonManifestCachePayload(entries = entries.toList()),
        )
}
