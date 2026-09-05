package com.nuvio.app.features.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class StoredSettingsHiddenCategoriesPayload(
    val pages: List<String> = emptyList(),
    // Defaults to true so an install that predates the option keeps the icon it already had.
    val configureIconVisible: Boolean = true,
)

/**
 * Settings categories the user has hidden from the desktop sidebar list. Persisted per profile.
 *
 * Hiding is presentation only: the page still exists, still has its settings, and is still
 * reachable through settings search and through in-page links. This exists so the sidebar can be
 * trimmed down to the features a given install actually uses — Games, Discover, Local Library and
 * Licenses are dead weight for someone who never touches them.
 *
 * Entries are [SettingsPage] enum names. Unknown names are kept as-is rather than dropped, so a
 * page hidden on a newer build is not silently un-hidden by rolling back and forward again.
 */
internal object SettingsHiddenCategoriesRepository {
    private val json = Json { ignoreUnknownKeys = true }

    private val _hiddenPages = MutableStateFlow<Set<String>>(emptySet())
    val hiddenPages: StateFlow<Set<String>> = _hiddenPages.asStateFlow()

    /**
     * Whether the Categories heading shows its configure affordance.
     *
     * The icon is discovery, not the control: the heading itself is what opens the dialog, and it
     * stays clickable with the icon off. Once someone has been in here they know that, so the mark
     * has done its job and can go.
     */
    private val _configureIconVisible = MutableStateFlow(true)
    val configureIconVisible: StateFlow<Boolean> = _configureIconVisible.asStateFlow()

    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true

        val payload = SettingsHiddenCategoriesStorage.loadPayload()
        val parsed = payload?.let {
            runCatching {
                json.decodeFromString<StoredSettingsHiddenCategoriesPayload>(it)
            }.getOrNull()
        }
        _hiddenPages.value = parsed?.pages.orEmpty().toSet()
        _configureIconVisible.value = parsed?.configureIconVisible ?: true
    }

    fun onProfileChanged() {
        hasLoaded = false
        _hiddenPages.value = emptySet()
        _configureIconVisible.value = true
        ensureLoaded()
    }

    fun isHidden(page: SettingsPage): Boolean {
        ensureLoaded()
        return page.name in _hiddenPages.value
    }

    fun setHidden(page: SettingsPage, hidden: Boolean) {
        ensureLoaded()
        val current = _hiddenPages.value
        val next = if (hidden) current + page.name else current - page.name
        if (next == current) return
        _hiddenPages.value = next
        persist()
    }

    fun setConfigureIconVisible(visible: Boolean) {
        ensureLoaded()
        if (_configureIconVisible.value == visible) return
        _configureIconVisible.value = visible
        persist()
    }

    fun showAll() {
        ensureLoaded()
        if (_hiddenPages.value.isEmpty()) return
        _hiddenPages.value = emptySet()
        persist()
    }

    private fun persist() {
        SettingsHiddenCategoriesStorage.savePayload(
            json.encodeToString(
                StoredSettingsHiddenCategoriesPayload(
                    pages = _hiddenPages.value.sorted(),
                    configureIconVisible = _configureIconVisible.value,
                ),
            ),
        )
    }
}
