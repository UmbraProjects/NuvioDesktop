package com.nuvio.app.features.tmdb

import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.serialization.json.Json

internal actual object TmdbExternalIdCacheStorage {
    private const val ENTRIES_KEY = "tmdb_external_ids"
    private val log = Logger.withTag("TmdbExternalIds")
    private val json = Json { ignoreUnknownKeys = true }
    private val store = DesktopStorage.store("nuvio_tmdb_external_ids")

    actual fun load(): Map<String, String> {
        val raw = store.getString(ENTRIES_KEY) ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, String>>(raw) }
            .onFailure {
                // Deleted rather than ignored: a payload that will not parse would otherwise be
                // re-read and re-failed on every launch forever.
                log.w(it) { "Discarding unreadable external-id cache" }
                store.remove(ENTRIES_KEY)
            }
            .getOrDefault(emptyMap())
    }

    actual fun save(entries: Map<String, String>) {
        if (entries.isEmpty()) {
            store.remove(ENTRIES_KEY)
            return
        }
        val capped = capExternalIdEntries(entries)
        runCatching { store.putString(ENTRIES_KEY, json.encodeToString(capped)) }
            .onFailure { log.w(it) { "Could not persist external-id cache" } }
    }
}
