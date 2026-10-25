package com.nuvio.app.features.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One settings search the user followed through: what they typed, and the page of the result they
 * opened (enum name of [SettingsPage]), shown under it as a hint of where it led.
 */
@Serializable
internal data class SettingsRecentSearch(
    val query: String,
    val page: String? = null,
)

@Serializable
private data class StoredSettingsSearchHistoryPayload(
    val items: List<SettingsRecentSearch> = emptyList(),
)

/**
 * Recent settings searches, shown under Favorites in the desktop settings side panel. Recorded
 * when a result is opened rather than on every keystroke, so only searches that found something
 * are kept. Newest first, one entry per query, persisted per profile.
 */
internal object SettingsSearchHistoryRepository {
    private const val MAX_ENTRIES = 8
    private val json = Json { ignoreUnknownKeys = true }

    private val _recent = MutableStateFlow<List<SettingsRecentSearch>>(emptyList())
    val recent: StateFlow<List<SettingsRecentSearch>> = _recent.asStateFlow()

    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true
        val payload = SettingsSearchHistoryStorage.loadPayload() ?: return
        _recent.value = runCatching {
            json.decodeFromString<StoredSettingsSearchHistoryPayload>(payload).items
        }.getOrNull().orEmpty()
    }

    fun onProfileChanged() {
        hasLoaded = false
        _recent.value = emptyList()
        ensureLoaded()
    }

    fun record(query: String, page: SettingsPage?) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        ensureLoaded()
        val entry = SettingsRecentSearch(query = trimmed, page = page?.name)
        _recent.value = (listOf(entry) + _recent.value.filterNot { it.query.equals(trimmed, ignoreCase = true) })
            .take(MAX_ENTRIES)
        persist()
    }

    fun remove(query: String) {
        ensureLoaded()
        val next = _recent.value.filterNot { it.query == query }
        if (next.size == _recent.value.size) return
        _recent.value = next
        persist()
    }

    private fun persist() {
        SettingsSearchHistoryStorage.savePayload(
            json.encodeToString(StoredSettingsSearchHistoryPayload(items = _recent.value)),
        )
    }
}
