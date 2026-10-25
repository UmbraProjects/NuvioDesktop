package com.nuvio.app.features.streams

import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.CoalescingCachePersister
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.parseReleaseDateToEpochMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/** One gzipped JSON file per profile, in the account's folder. */
internal expect object CwStreamCacheStorage {
    fun <T> read(profileId: Int, decode: (InputStream) -> T): T?
    fun write(profileId: Int, encode: (OutputStream) -> Unit)
    fun clearAll()
}

/**
 * The per-provider store behind [CwStreamCache], with the clock and fingerprints passed in so it can
 * be tested on its own.
 *
 * Entries are per provider, like [StreamPrefetchCache], so each provider block in
 * [StreamsRepository.load] reads its own slot and goes from cached to live on its own. Each entry
 * carries the provider's fingerprint (addon manifest version, scraper version) from when it was
 * written: an updated addon or scraper misses for its own entries and nobody else's.
 */
internal class CwStreamStore {

    @Serializable
    data class ProviderEntry(
        val fingerprint: String,
        val fetchedAtMs: Long,
        val streams: List<StreamItem>,
    )

    @Serializable
    data class TargetEntry(
        val lastUsedMs: Long,
        val lastSeenInContinueWatchingMs: Long,
        val providers: Map<String, ProviderEntry> = emptyMap(),
    )

    @Serializable
    data class Snapshot(
        val version: Int = 1,
        val targets: Map<String, TargetEntry> = emptyMap(),
    )

    private val lock = Any()
    private val targets = mutableMapOf<String, TargetEntry>()

    /**
     * A fresh entry, or null. Null too when the provider's fingerprint changed, the entry is past
     * [CwStreamCachePolicy.MAX_AGE_DAYS], or its time is ahead of [nowMs] (a clock that went back).
     */
    fun get(contentKey: String, providerId: String, fingerprint: String, nowMs: Long): ProviderEntry? =
        synchronized(lock) {
            val target = targets[contentKey] ?: return@synchronized null
            val entry = target.providers[providerId] ?: return@synchronized null
            val age = nowMs - entry.fetchedAtMs
            if (entry.fingerprint != fingerprint || age !in 0..CwStreamCachePolicy.MAX_AGE_DAYS * CwStreamCachePolicy.DAY_MS) {
                return@synchronized null
            }
            targets[contentKey] = target.copy(lastUsedMs = nowMs)
            entry
        }

    /** Stores answers that already passed the gates. Returns false when there was nothing to store. */
    fun put(contentKey: String, answers: Map<String, ProviderEntry>, nowMs: Long): Boolean {
        if (answers.isEmpty()) return false
        synchronized(lock) {
            val current = targets[contentKey]
            targets[contentKey] = TargetEntry(
                lastUsedMs = nowMs,
                lastSeenInContinueWatchingMs = maxOf(current?.lastSeenInContinueWatchingMs ?: 0L, nowMs),
                providers = (current?.providers.orEmpty()) + answers,
            )
            trimLocked(nowMs)
        }
        return true
    }

    /**
     * Stamps the targets still in Continue Watching and drops the ones gone for longer than
     * [CwStreamCachePolicy.LEFT_CONTINUE_WATCHING_GRACE_MS]. Returns whether anything changed.
     */
    fun onContinueWatching(keys: Set<String>, nowMs: Long): Boolean = synchronized(lock) {
        var changed = false
        targets.keys.toList().forEach { key ->
            val target = targets.getValue(key)
            if (key in keys) {
                targets[key] = target.copy(lastSeenInContinueWatchingMs = nowMs)
            } else if (nowMs - target.lastSeenInContinueWatchingMs > CwStreamCachePolicy.LEFT_CONTINUE_WATCHING_GRACE_MS) {
                targets.remove(key)
                changed = true
            }
        }
        changed = trimLocked(nowMs) || changed
        // Stamps alone are not worth a write; they are refreshed on every Home publish anyway.
        changed
    }

    private fun trimLocked(nowMs: Long): Boolean {
        var changed = false
        val maxAgeMs = CwStreamCachePolicy.MAX_AGE_DAYS * CwStreamCachePolicy.DAY_MS
        targets.keys.toList().forEach { key ->
            val target = targets.getValue(key)
            val kept = target.providers.filterValues { nowMs - it.fetchedAtMs in 0..maxAgeMs }
            when {
                kept.isEmpty() -> {
                    targets.remove(key)
                    changed = true
                }
                kept.size != target.providers.size -> {
                    targets[key] = target.copy(providers = kept)
                    changed = true
                }
            }
        }
        val overflow = targets.size - CwStreamCachePolicy.MAX_TARGETS
        if (overflow > 0) {
            targets.entries.sortedBy { it.value.lastUsedMs }.take(overflow).forEach { targets.remove(it.key) }
            changed = true
        }
        return changed
    }

    fun snapshot(): Snapshot = synchronized(lock) { Snapshot(targets = targets.toMap()) }

    fun restore(snapshot: Snapshot, nowMs: Long) {
        synchronized(lock) {
            targets.clear()
            targets.putAll(snapshot.targets)
            trimLocked(nowMs)
        }
    }

    fun clear() {
        synchronized(lock) { targets.clear() }
    }

    fun targetCount(): Int = synchronized(lock) { targets.size }
}

/**
 * Long-lived stream answers for Continue Watching and Next Up (Docs/plans/cw-stream-cache-plan.md).
 *
 * Opening a CW card shows the source list from what the providers answered last time, up to
 * [CwStreamCachePolicy.MAX_AGE_DAYS] ago, while the live search runs; an answer younger than
 * [CwStreamCachePolicy.SKIP_REFRESH_UNDER_MS] is used as it is. This is not the hover prefetch
 * ([StreamPrefetchCache], ten minutes in memory): it lasts days, lives on disk per profile, and only
 * keeps answers that pass [CwStreamCachePolicy].
 *
 * Filling is write-through only: any search for a target currently in Continue Watching offers its
 * answers, and the gates decide. Nothing here sends a request of its own.
 */
object CwStreamCache {
    private val log = Logger.withTag("CwStreamCache")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = CwStreamStore()
    private val loadLock = Any()

    @Volatile
    private var loadedProfileId: Int? = null

    /** Continue Watching targets by resolved content key, as Home last drew them. */
    @Volatile
    private var continueWatching: Map<String, CwStreamTarget> = emptyMap()

    /** Last skip reason logged per target, so a load of sixty providers logs it once. */
    private val loggedSkips = mutableMapOf<String, String>()

    private val persister = CoalescingCachePersister(tag = "cwStreamCache") {
        val profileId = loadedProfileId ?: return@CoalescingCachePersister
        val snapshot = store.snapshot()
        CwStreamCacheStorage.write(profileId) { output ->
            output.write(json.encodeToString(CwStreamStore.Snapshot.serializer(), snapshot).toByteArray(Charsets.UTF_8))
        }
    }

    private fun isEnabled(): Boolean {
        PlayerSettingsRepository.ensureLoaded()
        return PlayerSettingsRepository.uiState.value.streamCwCacheEnabled
    }

    private fun ensureLoaded() {
        val profileId = ProfileRepository.activeProfileId
        if (loadedProfileId == profileId) return
        synchronized(loadLock) {
            if (loadedProfileId == profileId) return
            val snapshot = runCatching {
                CwStreamCacheStorage.read(profileId) { input ->
                    json.decodeFromString(CwStreamStore.Snapshot.serializer(), input.readBytes().toString(Charsets.UTF_8))
                }
            }.onFailure { log.w { "unreadable cache file, starting empty: ${it.message}" } }.getOrNull()
            store.restore(snapshot ?: CwStreamStore.Snapshot(), System.currentTimeMillis())
            loadedProfileId = profileId
            log.d { "loaded ${store.targetCount()} targets for profile $profileId" }
        }
    }

    /**
     * What identifies the code behind a provider: an addon's manifest version (its id already holds
     * the manifest URL), a plugin scraper's version. Null for a provider that is no longer installed,
     * which is never served or stored.
     */
    internal fun fingerprintOf(providerId: String): String? = when {
        providerId.startsWith("plugin-scraper:") -> {
            val scraperId = providerId.removePrefix("plugin-scraper:")
            PluginRepository.uiState.value.scrapers.firstOrNull { it.id == scraperId }?.let { "scraper:${it.version}" }
        }
        providerId.startsWith("addon:") -> AddonRepository.uiState.value.addons.firstNotNullOfOrNull { addon ->
            val manifest = addon.manifest ?: return@firstNotNullOfOrNull null
            if (addon.streamAddonInstanceId(manifest.id) == providerId) "addon:${manifest.version}" else null
        }
        else -> null
    }

    /** A cached answer for one provider, or null. Never consulted by a forced refresh. */
    internal fun get(contentKey: String, providerId: String): CwStreamStore.ProviderEntry? {
        if (!isEnabled()) return null
        ensureLoaded()
        val fingerprint = fingerprintOf(providerId) ?: return null
        return store.get(contentKey, providerId, fingerprint, System.currentTimeMillis())
    }

    internal fun isFreshEnoughToSkipRefresh(entry: CwStreamStore.ProviderEntry): Boolean =
        System.currentTimeMillis() - entry.fetchedAtMs in 0 until CwStreamCachePolicy.SKIP_REFRESH_UNDER_MS

    /**
     * Offers every answer a search has so far for one target. [answers] is by provider id, empty
     * lists included (a provider that answered "nothing" is worth not asking again); failed
     * providers must be left out. Returns whether the target passed the gates and was stored.
     *
     * [gatesPassed] is for a search that already had this target accepted and is adding a later
     * provider's answer: the gates judge the whole target, never one provider alone.
     */
    internal fun offer(
        contentKey: String,
        answers: Map<String, List<StreamItem>>,
        gatesPassed: Boolean = false,
    ): Boolean {
        if (answers.isEmpty()) return false
        // Not in Continue Watching is the common case for every other search: out before any work.
        val target = continueWatching[contentKey] ?: return false
        if (!isEnabled()) return false
        val now = System.currentTimeMillis()
        val trimmed = answers.mapValues { (_, rows) -> CwStreamCachePolicy.storableRows(rows) }
        if (!gatesPassed) {
            val decision = CwStreamCachePolicy.evaluate(target, trimmed.values.flatten(), now)
            if (decision is CwStreamCachePolicy.Decision.Skip) {
                logSkipOnce(contentKey, decision.reason)
                return false
            }
        }
        ensureLoaded()
        val entries = trimmed.mapNotNull { (providerId, rows) ->
            val fingerprint = fingerprintOf(providerId) ?: return@mapNotNull null
            providerId to CwStreamStore.ProviderEntry(fingerprint = fingerprint, fetchedAtMs = now, streams = rows)
        }.toMap()
        if (store.put(contentKey, entries, now)) {
            synchronized(loggedSkips) { loggedSkips.remove(contentKey) }
            log.d { "stored ${entries.size} providers (${entries.values.sumOf { it.streams.size }} rows) for $contentKey" }
            persister.schedule()
        }
        return true
    }

    private fun logSkipOnce(contentKey: String, reason: String) {
        val isNew = synchronized(loggedSkips) { loggedSkips.put(contentKey, reason) != reason }
        if (isNew) log.d { "CwStreamCache skip: $reason ($contentKey)" }
    }

    /**
     * The Continue Watching and Next Up rows as Home drew them: which targets may be stored, with
     * their release dates, and which stored targets the viewer has moved on from.
     */
    fun onContinueWatchingRows(items: List<ContinueWatchingItem>) {
        scope.launch {
            val now = System.currentTimeMillis()
            val index = items.asSequence()
                .filterNot { it.isReleaseAlert }
                .associate { item ->
                    val key = StreamPrefetchCache.resolvedContentKey(
                        type = item.parentMetaType,
                        parentMetaId = item.parentMetaId,
                        videoId = item.videoId,
                        title = item.title,
                        season = item.seasonNumber,
                        episode = item.episodeNumber,
                    )
                    key to CwStreamTarget(
                        releasedEpochMs = parseReleaseDateToEpochMs(item.released),
                        isEpisode = item.seasonNumber != null || item.episodeNumber != null,
                    )
                }
            continueWatching = index
            if (!isEnabled()) return@launch
            ensureLoaded()
            if (store.onContinueWatching(index.keys, now)) persister.schedule()
        }
    }

    /** Profile switch: what the old profile had is written out, and the next read loads the new one's. */
    fun onProfileChanged() {
        val (previousProfileId, previous) = synchronized(loadLock) {
            val outgoing = loadedProfileId to store.snapshot()
            loadedProfileId = null
            store.clear()
            outgoing
        }
        continueWatching = emptyMap()
        if (previousProfileId != null && previous.targets.isNotEmpty()) {
            scope.launch {
                runCatching {
                    CwStreamCacheStorage.write(previousProfileId) { output ->
                        output.write(json.encodeToString(CwStreamStore.Snapshot.serializer(), previous).toByteArray(Charsets.UTF_8))
                    }
                }
            }
        }
    }

    /** Settings toggle off, or an account wipe: nothing kept in memory or on disk. */
    fun clear() {
        synchronized(loadLock) {
            store.clear()
            loadedProfileId = null
            runCatching { CwStreamCacheStorage.clearAll() }
        }
    }

    suspend fun flushPendingWrites() {
        persister.flush()
    }
}
