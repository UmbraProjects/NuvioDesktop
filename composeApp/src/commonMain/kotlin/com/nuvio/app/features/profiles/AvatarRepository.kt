package com.nuvio.app.features.profiles

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.perf.PerfTelemetry
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class StoredAvatarCatalogPayload(
    val items: List<AvatarCatalogItem> = emptyList(),
)

object AvatarRepository {
    private val log = Logger.withTag("AvatarRepository")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _avatars = MutableStateFlow<List<AvatarCatalogItem>>(emptyList())
    val avatars: StateFlow<List<AvatarCatalogItem>> = _avatars.asStateFlow()

    private var loaded = false
    private var cacheHydrated = false
    private var fetchInFlight = false

    /** Wall-clock time of the last catalogue fetch that succeeded this session; 0 = none yet. */
    private var lastFetchedAtMs = 0L

    /**
     * The profile picture as a URL other watch-party members can load: a custom URL only when it
     * points into this server's storage, else the catalogue avatar's public URL. Upstream also signs
     * supporter-only avatars here; this fork has none.
     */
    fun shareableImageUrl(profile: NuvioProfile): String? {
        val storage = "${ServerConfigurationRepository.active.value.backendUrl}/storage/v1/object/"
        normalizedAvatarUrl(profile.avatarUrl)?.let { url -> return url.takeIf { it.startsWith(storage) } }
        val avatar = profile.avatarId?.let { id -> avatars.value.firstOrNull { it.id == id } } ?: return null
        return avatar.storagePath.takeIf { it.isNotBlank() }?.let(::avatarStorageUrl)
    }

    suspend fun fetchAvatars() {
        hydrateFromCacheIfNeeded()
        if (loaded && _avatars.value.isNotEmpty()) return
        doFetch()
    }

    /**
     * Re-reads the catalogue at most once per [REFRESH_INTERVAL_MS]. Every profile bar, picker and
     * edit screen calls this when it mounts, so the bar alone asked for it 10 times in a 4-minute
     * session - for a catalogue that changes when a release adds avatars, not between screens.
     */
    suspend fun refreshAvatars() {
        PerfTelemetry.count("AvatarRepository.refreshAvatars")
        hydrateFromCacheIfNeeded()
        val fresh = lastFetchedAtMs > 0L && System.currentTimeMillis() - lastFetchedAtMs < REFRESH_INTERVAL_MS
        if (fresh && _avatars.value.isNotEmpty()) return
        doFetch()
    }

    private fun hydrateFromCacheIfNeeded() {
        if (cacheHydrated) return
        cacheHydrated = true

        val payload = AvatarStorage.loadPayload().orEmpty().trim()
        if (payload.isEmpty()) return

        val stored = runCatching {
            json.decodeFromString<StoredAvatarCatalogPayload>(payload)
        }.getOrNull() ?: return

        val items = stored.items
            .filter { it.isActive }
            .sortedWith(compareBy({ it.category }, { it.sortOrder }))
        if (items.isEmpty()) return

        _avatars.value = items
        loaded = true
    }

    private const val REFRESH_INTERVAL_MS = 6L * 60L * 60L * 1000L

    private suspend fun doFetch() {
        if (fetchInFlight) return
        fetchInFlight = true
        runCatching {
            val result = SupabaseProvider.client.postgrest.rpc("get_avatar_catalog")
            val items = result.decodeList<AvatarCatalogItem>()
            val activeItems = items.filter { it.isActive }.sortedWith(
                compareBy({ it.category }, { it.sortOrder }),
            )
            _avatars.value = activeItems
            loaded = true
            lastFetchedAtMs = System.currentTimeMillis()
            AvatarStorage.savePayload(
                json.encodeToString(
                    StoredAvatarCatalogPayload(items = activeItems),
                ),
            )
        }.onFailure { e ->
            log.e(e) { "Failed to fetch avatar catalog" }
        }.also {
            fetchInFlight = false
        }
    }
}
