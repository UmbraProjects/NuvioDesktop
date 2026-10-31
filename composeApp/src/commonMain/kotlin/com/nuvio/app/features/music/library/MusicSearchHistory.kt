package com.nuvio.app.features.music.library

import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.search.applySearchHistoryEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * fork:music
 *
 * Recent music searches for the Search start view. Kept apart from the film and show search
 * history so an artist search never shows up as a recent film search, and the other way round.
 * One list per profile; it follows the active profile the next time it is read.
 */
object MusicSearchHistory {
    private const val MAX_RECENT_SEARCHES = 10

    private val json = Json { ignoreUnknownKeys = true }

    private val _recent = MutableStateFlow<List<String>>(emptyList())
    private var loadedProfileId: Int? = null

    /** Newest first. */
    val recent: StateFlow<List<String>>
        get() {
            ensureLoaded()
            return _recent.asStateFlow()
        }

    fun record(query: String) {
        ensureLoaded()
        val normalized = query.trim()
        if (normalized.length < 2) return
        val updated = applySearchHistoryEntry(_recent.value, normalized, MAX_RECENT_SEARCHES)
        if (updated == _recent.value) return
        _recent.value = updated
        loadedProfileId?.let { MusicLibraryStorage.saveSearchHistory(it, json.encodeToString(updated)) }
    }

    fun clear() {
        ensureLoaded()
        if (_recent.value.isEmpty()) return
        _recent.value = emptyList()
        loadedProfileId?.let { MusicLibraryStorage.saveSearchHistory(it, json.encodeToString(emptyList<String>())) }
    }

    private fun ensureLoaded() {
        val profileId = ProfileRepository.activeProfileId
        if (loadedProfileId == profileId) return
        loadedProfileId = profileId
        val payload = MusicLibraryStorage.loadSearchHistory(profileId).orEmpty().trim()
        _recent.value = if (payload.isEmpty()) {
            emptyList()
        } else {
            runCatching { json.decodeFromString<List<String>>(payload) }
                .getOrDefault(emptyList())
                .map { it.trim() }
                .filter { it.length >= 2 }
                .distinct()
                .take(MAX_RECENT_SEARCHES)
        }
    }
}
