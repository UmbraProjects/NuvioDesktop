package com.nuvio.app.features.watchprogress

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.ui.ImageCacheSettingsRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Up Next seeds that were resolved and produced no card - the series is finished or caught up, or
 * the next episode is not in the addon's list - remembered across launches.
 *
 * Only cards were ever saved ([ContinueWatchingEnrichmentCache]), so a seed that resolves to
 * nothing was resolved again on every launch, each time with the details page's full fetch (every
 * season's episodes decorated, every season's trailers). On 2026-10-14 that was all 32 of the
 * launch's resolution budget - the same 32 finished shows, every launch - which also meant the
 * seeds after them were never looked at.
 *
 * An entry holds only while the seed is unchanged (finishing another episode moves the seed and
 * re-resolves at once), the settings that decide the outcome are unchanged, and it is younger than
 * the Metadata Cache setting - the bound on how late a newly aired episode of a caught-up show can
 * turn up. With that setting Off nothing is remembered.
 */
internal object NextUpNoCardCache {
    private val json = Json { ignoreUnknownKeys = true }
    private const val STORAGE_KEY = "cw_next_up_no_card"
    private const val HOUR_MS = 60L * 60L * 1000L

    @Serializable
    private data class Entry(
        val seedSeason: Int? = null,
        val seedEpisode: Int? = null,
        val settingsKey: String,
        val checkedAtMs: Long,
    )

    private val lock = Any()
    private var entries: MutableMap<String, Entry>? = null

    /** The profile-scoped key [entries] was read under; a profile switch reads the other one. */
    private var entriesKey: String? = null

    private fun lifetimeMs(): Long {
        ImageCacheSettingsRepository.ensureLoaded()
        return ImageCacheSettingsRepository.settings.value.metadataCacheHours * HOUR_MS
    }

    fun isKnownNoCard(
        contentId: String,
        seedSeason: Int?,
        seedEpisode: Int?,
        settingsKey: String,
        nowMs: Long = WatchProgressClock.nowEpochMs(),
    ): Boolean {
        val lifetime = lifetimeMs()
        if (lifetime <= 0L) return false
        val entry = synchronized(lock) { loaded()[contentId] } ?: return false
        return entry.seedSeason == seedSeason &&
            entry.seedEpisode == seedEpisode &&
            entry.settingsKey == settingsKey &&
            nowMs - entry.checkedAtMs in 0..lifetime
    }

    fun record(
        contentId: String,
        seedSeason: Int?,
        seedEpisode: Int?,
        settingsKey: String,
        nowMs: Long = WatchProgressClock.nowEpochMs(),
    ) {
        val lifetime = lifetimeMs()
        if (lifetime <= 0L) return
        val payload = synchronized(lock) {
            val map = loaded()
            map[contentId] = Entry(seedSeason, seedEpisode, settingsKey, nowMs)
            // Expired entries are only ever misses; dropping them keeps the payload to live ones.
            map.entries.removeAll { nowMs - it.value.checkedAtMs > lifetime }
            json.encodeToString(map.toMap())
        }
        ContinueWatchingEnrichmentStorage.savePayload(ProfileScopedKey.of(STORAGE_KEY), payload)
    }

    /** With the Continue Watching cache: a clear or resync re-resolves everything. */
    fun clear() {
        val key = ProfileScopedKey.of(STORAGE_KEY)
        synchronized(lock) {
            entries = mutableMapOf()
            entriesKey = key
        }
        ContinueWatchingEnrichmentStorage.removePayload(key)
    }

    private fun loaded(): MutableMap<String, Entry> {
        val key = ProfileScopedKey.of(STORAGE_KEY)
        entries?.takeIf { entriesKey == key }?.let { return it }
        entriesKey = key
        val restored = ContinueWatchingEnrichmentStorage.loadPayload(key)
            ?.let { raw -> runCatching { json.decodeFromString<Map<String, Entry>>(raw) }.getOrNull() }
            .orEmpty()
        return restored.toMutableMap().also { entries = it }
    }
}
