package com.nuvio.app.features.addons

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.profiles.ProfileRepository
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

@Serializable
private data class AddonRow(
    val url: String,
    val name: String? = null,
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
private data class AddonPushItem(
    val url: String,
    val name: String = "",
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

object AddonRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("AddonRepository")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _uiState = MutableStateFlow(AddonsUiState())
    val uiState: StateFlow<AddonsUiState> = _uiState.asStateFlow()

    private var initialized = false
    private var pulledFromServer = false
    private var currentProfileId: Int = 1
    private val activeRefreshJobs = mutableMapOf<String, Job>()
    private var manifestCacheByUrl: Map<String, AddonManifestCacheEntry> = emptyMap()

    fun initialize() {
        val effectiveProfileId = resolveEffectiveProfileId(ProfileRepository.activeProfileId)
        if (initialized) return
        initialized = true
        currentProfileId = effectiveProfileId
        log.d { "initialize() — loading local addons for profile $currentProfileId" }

        val storedUrls = dedupeManifestUrls(AddonStorage.loadInstalledAddonUrls(currentProfileId))
        log.d { "initialize() — local addon count: ${storedUrls.size}" }
        if (storedUrls.isEmpty()) {
            manifestCacheByUrl = emptyMap()
            return
        }

        applyAddonsFromUrls(storedUrls)
    }

    fun onProfileChanged(profileId: Int) {
        val effectiveProfileId = resolveEffectiveProfileId(profileId)
        if (effectiveProfileId == currentProfileId && initialized) return
        cancelActiveRefreshes()
        currentProfileId = effectiveProfileId
        initialized = false
        pulledFromServer = false
        manifestCacheByUrl = emptyMap()
        _uiState.value = AddonsUiState()
    }

    fun clearLocalState() {
        val profileToClear = currentProfileId
        cancelActiveRefreshes()
        manifestCacheByUrl = emptyMap()
        AddonStorage.saveManifestCachePayload(profileToClear, AddonManifestCacheCodec.encode(emptyList()))
        currentProfileId = 1
        initialized = false
        pulledFromServer = false
        _uiState.value = AddonsUiState()
    }

    suspend fun pullFromServer(profileId: Int) {
        currentProfileId = resolveEffectiveProfileId(profileId)
        log.i { "pullFromServer() — profileId=$profileId, initialized=$initialized, pulledFromServer=$pulledFromServer" }
        runCatching {
            val rows = SupabaseProvider.client.postgrest
                .from("addons")
                .select {
                    filter { eq("profile_id", currentProfileId) }
                    order("sort_order", Order.ASCENDING)
                }
                .decodeList<AddonRow>()

            val namesByUrl = mutableMapOf<String, String>()
            rows.forEach { row ->
                if (!row.name.isNullOrBlank()) {
                    namesByUrl[ensureManifestSuffix(row.url)] = row.name
                }
            }

            val urls = dedupeManifestUrls(rows.map { it.url })
            log.i { "pullFromServer() — server returned ${rows.size} addons" }
            urls.forEachIndexed { i, u -> log.d { "  server[$i]: $u" } }

            if (urls.isEmpty() && !pulledFromServer) {
                val localUrls = AddonStorage.loadInstalledAddonUrls(currentProfileId)
                log.i { "pullFromServer() — server empty, local has ${localUrls.size} addons" }
                if (localUrls.isNotEmpty()) {
                    log.i { "pullFromServer() — migrating local addons to server for profile $currentProfileId" }
                    initialize()
                    pulledFromServer = true
                    val addons = localUrls.mapIndexed { index, addonUrl ->
                        AddonPushItem(
                            url = addonUrl,
                            name = _uiState.value.addons
                                .find { it.manifestUrl == addonUrl }?.manifest?.name ?: "",
                            enabled = true,
                            sortOrder = index,
                        )
                    }
                    val params = buildJsonObject {
                        put("p_profile_id", currentProfileId)
                        put("p_addons", json.encodeToJsonElement(addons))
                    }
                    SupabaseProvider.client.postgrest.rpc("sync_push_addons", params)
                    log.i { "pullFromServer() — migration push done (${addons.size} addons)" }
                    return
                }
            }

            if (urls.isEmpty()) {
                val localUrls = dedupeManifestUrls(AddonStorage.loadInstalledAddonUrls(currentProfileId))
                if (localUrls.isNotEmpty()) {
                    log.w { "pullFromServer() — remote empty while local has ${localUrls.size} addons; preserving local addons" }
                    applyAddonsFromUrls(localUrls)
                    persist()
                    pulledFromServer = true
                    initialized = true
                    return
                }
            }

            applyAddonsFromUrls(urls, namesByUrl)
            persist()
            pulledFromServer = true
            initialized = true
            log.i { "pullFromServer() — applied ${urls.size} addons to state" }
        }.onFailure { e ->
            log.e(e) { "pullFromServer() — FAILED" }
        }
    }

    suspend fun awaitManifestsLoaded() {
        if (_uiState.value.addons.isEmpty()) return
        uiState.first { state ->
            state.addons.isEmpty() || state.addons.any { it.manifest != null }
        }
    }

    suspend fun addAddon(rawUrl: String): AddAddonResult {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) {
            return AddAddonResult.Error("This profile uses primary addons.")
        }
        log.i { "addAddon() — rawUrl=$rawUrl" }
        val manifestUrl = try {
            normalizeManifestUrl(rawUrl)
        } catch (error: IllegalArgumentException) {
            return AddAddonResult.Error(error.message ?: "Enter a valid addon URL")
        }

        if (_uiState.value.addons.any { it.manifestUrl == manifestUrl }) {
            return AddAddonResult.Error("That addon is already installed.")
        }

        val fetched = try {
            withContext(Dispatchers.Default) {
                val payload = httpGetText(manifestUrl)
                val manifest = AddonManifestParser.parse(
                    manifestUrl = manifestUrl,
                    payload = payload,
                )
                payload to manifest
            }
        } catch (error: Throwable) {
            return AddAddonResult.Error(error.message ?: "Unable to load manifest")
        }
        val (payload, manifest) = fetched

        _uiState.update { current ->
            current.copy(
                addons = current.addons + ManagedAddon(
                    manifestUrl = manifestUrl,
                    manifest = manifest,
                    isRefreshing = false,
                    errorMessage = null,
                ),
            )
        }
        updateManifestCache(manifestUrl, payload)
        persist()
        pushToServer()
        return AddAddonResult.Success(manifest)
    }

    fun removeAddon(manifestUrl: String) {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) return
        log.i { "removeAddon() — $manifestUrl" }
        val normalizedUrl = ensureManifestSuffix(manifestUrl)
        _uiState.update { current ->
            current.copy(
                addons = current.addons.filterNot { it.manifestUrl == normalizedUrl },
            )
        }
        if (manifestCacheByUrl.containsKey(normalizedUrl)) {
            manifestCacheByUrl = manifestCacheByUrl - normalizedUrl
            persistManifestCache()
        }
        persist()
        pushToServer()
    }

    fun refreshAll() {
        _uiState.value.addons.distinctBy { it.manifestUrl }.forEach { addon ->
            refreshAddon(addon.manifestUrl)
        }
    }

    fun refreshAddon(manifestUrl: String) {
        val normalizedUrl = ensureManifestSuffix(manifestUrl)
        val existingJob = activeRefreshJobs[normalizedUrl]
        if (existingJob?.isActive == true) return

        markRefreshing(normalizedUrl)
        var refreshJob: Job? = null
        refreshJob = scope.launch {
            try {
                val result = runCatching {
                    val payload = httpGetText(normalizedUrl)
                    val manifest = AddonManifestParser.parse(
                        manifestUrl = normalizedUrl,
                        payload = payload,
                    )
                    payload to manifest
                }
                result.onSuccess { (payload, _) ->
                    updateManifestCache(normalizedUrl, payload)
                }

                _uiState.update { current ->
                    current.copy(
                        addons = current.addons.map { addon ->
                            if (addon.manifestUrl != normalizedUrl) {
                                addon
                            } else {
                                result.fold(
                                    onSuccess = { (_, manifest) ->
                                        addon.copy(
                                            manifest = manifest,
                                            isRefreshing = false,
                                            errorMessage = null,
                                        )
                                    },
                                    onFailure = { error ->
                                        addon.copy(
                                            isRefreshing = false,
                                            errorMessage = error.message ?: addon.errorMessage ?: "Unable to load manifest",
                                        )
                                    },
                                )
                            }
                        },
                    )
                }
            } finally {
                if (activeRefreshJobs[normalizedUrl] === refreshJob) {
                    activeRefreshJobs.remove(normalizedUrl)
                }
            }
        }
        activeRefreshJobs[normalizedUrl] = refreshJob
    }

    private fun applyAddonsFromUrls(
        urls: List<String>,
        namesByUrl: Map<String, String> = emptyMap(),
    ) {
        val normalizedUrls = dedupeManifestUrls(urls)
        val normalizedUrlSet = normalizedUrls.toSet()
        val now = addonEpochMs()
        val existingByUrl = _uiState.value.addons.associateBy(ManagedAddon::manifestUrl)
        val loadedCache = loadManifestCacheByUrl()
        val nextCache = loadedCache.filterKeys { key -> key in normalizedUrlSet }.toMutableMap()

        val addons = normalizedUrls.map { manifestUrl ->
            val existing = existingByUrl[manifestUrl]
            val cachedEntry = nextCache[manifestUrl]
            val cachedManifest = cachedEntry
                ?.takeIf { it.payload.isNotBlank() }
                ?.let { entry ->
                    runCatching {
                        AddonManifestParser.parse(
                            manifestUrl = manifestUrl,
                            payload = entry.payload,
                        )
                    }.getOrNull()
                }

            if (cachedEntry != null && cachedManifest == null) {
                nextCache.remove(manifestUrl)
            }

            val manifest = existing?.manifest ?: cachedManifest
            val shouldRefresh = when {
                manifest == null -> true
                existing?.manifest != null && !existing.isRefreshing -> false
                cachedEntry == null -> true
                cachedEntry.fetchedAtEpochMs <= 0L -> true
                now - cachedEntry.fetchedAtEpochMs >= MANIFEST_CACHE_TTL_MS -> true
                else -> false
            }

            ManagedAddon(
                manifestUrl = manifestUrl,
                manifest = manifest,
                userSetName = namesByUrl[manifestUrl] ?: existing?.userSetName,
                isRefreshing = shouldRefresh,
                errorMessage = if (manifest != null) null else existing?.errorMessage,
            )
        }

        manifestCacheByUrl = nextCache.toMap()
        persistManifestCache()

        _uiState.value = AddonsUiState(addons = addons)
        addons.filter { it.isRefreshing }.forEach { addon ->
            refreshAddon(addon.manifestUrl)
        }
    }

    private fun loadManifestCacheByUrl(): Map<String, AddonManifestCacheEntry> {
        val payload = AddonStorage.loadManifestCachePayload(currentProfileId).orEmpty()
        if (payload.isBlank()) {
            manifestCacheByUrl = emptyMap()
            return manifestCacheByUrl
        }

        val decoded = AddonManifestCacheCodec.decode(payload)
            ?.mapNotNull { entry ->
                val normalizedUrl = ensureManifestSuffix(entry.manifestUrl)
                if (entry.payload.isBlank()) {
                    null
                } else {
                    entry.copy(manifestUrl = normalizedUrl)
                }
            }
            ?.associateBy(AddonManifestCacheEntry::manifestUrl)
            .orEmpty()
        manifestCacheByUrl = decoded
        return decoded
    }

    private fun updateManifestCache(manifestUrl: String, payload: String) {
        if (payload.isBlank()) return
        val normalizedUrl = ensureManifestSuffix(manifestUrl)
        manifestCacheByUrl = manifestCacheByUrl + (
            normalizedUrl to AddonManifestCacheEntry(
                manifestUrl = normalizedUrl,
                payload = payload,
                fetchedAtEpochMs = addonEpochMs(),
            )
            )
        persistManifestCache()
    }

    private fun persistManifestCache() {
        AddonStorage.saveManifestCachePayload(
            profileId = currentProfileId,
            payload = AddonManifestCacheCodec.encode(manifestCacheByUrl.values),
        )
    }

    private fun pushToServer() {
        scope.launch {
            runCatching {
                if (isUsingPrimaryAddonsFromSecondaryProfile()) {
                    return@runCatching
                }
                val profileId = currentProfileId
                val addons = _uiState.value.addons
                    .distinctBy { it.manifestUrl }
                    .mapIndexed { index, addon ->
                    AddonPushItem(
                        url = addon.manifestUrl,
                        name = addon.userSetName?.takeIf { it.isNotBlank() } ?: addon.manifest?.name ?: "",
                        enabled = true,
                        sortOrder = index,
                    )
                }
                log.d { "pushToServer() — profileId=$profileId, pushing ${addons.size} addons" }
                val params = buildJsonObject {
                    put("p_profile_id", profileId)
                    put("p_addons", json.encodeToJsonElement(addons))
                }
                SupabaseProvider.client.postgrest.rpc("sync_push_addons", params)
                log.d { "pushToServer() — success" }
            }.onFailure { e ->
                log.e(e) { "pushToServer() — FAILED" }
            }
        }
    }

    private fun markRefreshing(manifestUrl: String) {
        _uiState.update { current ->
            current.copy(
                addons = current.addons.map { addon ->
                    if (addon.manifestUrl == manifestUrl) {
                        addon.copy(
                            isRefreshing = true,
                            errorMessage = null,
                        )
                    } else {
                        addon
                    }
                },
            )
        }
    }

    private fun persist() {
        AddonStorage.saveInstalledAddonUrls(
            currentProfileId,
            dedupeManifestUrls(_uiState.value.addons.map { it.manifestUrl }),
        )
    }

    private fun cancelActiveRefreshes() {
        activeRefreshJobs.values.forEach(Job::cancel)
        activeRefreshJobs.clear()
    }

    private fun resolveEffectiveProfileId(profileId: Int): Int {
        val active = ProfileRepository.state.value.activeProfile
        return if (active != null && active.profileIndex != 1 && active.usesPrimaryAddons) 1 else profileId
    }

    private fun isUsingPrimaryAddonsFromSecondaryProfile(): Boolean {
        val active = ProfileRepository.state.value.activeProfile
        return active != null && active.profileIndex != 1 && active.usesPrimaryAddons
    }
}

private fun dedupeManifestUrls(urls: List<String>): List<String> =
    urls.map(::ensureManifestSuffix).distinct()

private fun ensureManifestSuffix(url: String): String {
    val path = url.substringBefore("?").trimEnd('/')
    val query = url.substringAfter("?", "")
    val withSuffix = if (path.endsWith("/manifest.json")) path else "$path/manifest.json"
    return if (query.isEmpty()) withSuffix else "$withSuffix?$query"
}

private fun normalizeManifestUrl(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    require(trimmed.isNotEmpty()) { "Enter an addon URL." }

    val normalizedScheme = when {
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        trimmed.startsWith("stremio://") -> "https://${trimmed.removePrefix("stremio://")}"
        else -> "https://$trimmed"
    }

    val withoutFragment = normalizedScheme.substringBefore("#")
    val query = withoutFragment.substringAfter("?", "")
    val path = withoutFragment.substringBefore("?").trimEnd('/')
    val manifestPath = if (path.endsWith("/manifest.json")) {
        path
    } else {
        "$path/manifest.json"
    }

    return if (query.isEmpty()) manifestPath else "$manifestPath?$query"
}

private const val MANIFEST_CACHE_TTL_MS = 12L * 60L * 60L * 1000L
