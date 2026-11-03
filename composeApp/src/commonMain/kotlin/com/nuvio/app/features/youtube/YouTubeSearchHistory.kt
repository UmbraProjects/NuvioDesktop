package com.nuvio.app.features.youtube

import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.search.applySearchHistoryEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * fork:youtube-mode - the YouTube tab's recent searches, newest first, offered under the top bar's
 * YouTube box like the video search's and the Music box's. Kept per profile.
 */
object YouTubeSearchHistory {
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
        loadedProfileId?.let { YouTubeModeStorage.saveSearchHistory(it, json.encodeToString(updated)) }
    }

    fun clear() {
        ensureLoaded()
        if (_recent.value.isEmpty()) return
        _recent.value = emptyList()
        loadedProfileId?.let { YouTubeModeStorage.saveSearchHistory(it, null) }
    }

    private fun ensureLoaded() {
        val profileId = ProfileRepository.activeProfileId
        if (loadedProfileId == profileId) return
        loadedProfileId = profileId
        val payload = YouTubeModeStorage.loadSearchHistory(profileId).orEmpty().trim()
        _recent.value = if (payload.isEmpty()) {
            emptyList()
        } else {
            runCatching { json.decodeFromString<List<String>>(payload) }.getOrDefault(emptyList())
        }
    }
}
