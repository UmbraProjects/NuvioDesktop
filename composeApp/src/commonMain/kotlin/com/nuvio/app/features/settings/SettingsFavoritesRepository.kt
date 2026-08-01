package com.nuvio.app.features.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A pinned settings heading. [anchor] is the stable scroll-anchor id the heading registers
 * (see [SettingsSection]); [page] is the enum name of the [SettingsPage] it lives on so we can
 * navigate back to it; [title] is the display label shown in the favorites panel.
 */
@Serializable
internal data class SettingsFavorite(
    val page: String,
    val anchor: String,
    val title: String,
)

@Serializable
private data class StoredSettingsFavoritesPayload(
    val items: List<SettingsFavorite> = emptyList(),
)

/**
 * User-pinned settings headings, shown in the desktop settings side panel. Persisted per profile.
 *
 * A curated starter set is applied only when the app starts without an existing data directory.
 * The initial value is immediately persisted, so removing a default never makes it reappear.
 */
internal object SettingsFavoritesRepository {
    private val json = Json { ignoreUnknownKeys = true }

    private val DEFAULT_FAVORITES = listOf(
        SettingsFavorite(
            page = SettingsPage.Homescreen.name,
            anchor = "heading:Homescreen:Display Mode",
            title = "Display Mode",
        ),
        SettingsFavorite(
            page = SettingsPage.Homescreen.name,
            anchor = "heading:Homescreen:Home Layout",
            title = "Home Layout",
        ),
        SettingsFavorite(
            page = SettingsPage.Appearance.name,
            anchor = "heading:Appearance:DISPLAY",
            title = "Display",
        ),
        SettingsFavorite(
            page = SettingsPage.MetaScreen.name,
            anchor = "heading:MetaScreen:APPEARANCE",
            title = "Appearance",
        ),
        SettingsFavorite(
            page = SettingsPage.Playback.name,
            anchor = "heading:Playback:PLAYER",
            title = "Player",
        ),
        SettingsFavorite(
            page = SettingsPage.Integrations.name,
            anchor = "heading:Integrations:Integrations",
            title = "Integrations",
        ),
        SettingsFavorite(
            page = SettingsPage.KeyboardShortcuts.name,
            anchor = "heading:KeyboardShortcuts:Navigation",
            title = "Navigation",
        ),
    )

    private val _favorites = MutableStateFlow<List<SettingsFavorite>>(emptyList())
    val favorites: StateFlow<List<SettingsFavorite>> = _favorites.asStateFlow()

    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true

        val payload = SettingsFavoritesStorage.loadPayload()
        if (payload == null) {
            _favorites.value = if (SettingsFavoritesStorage.shouldSeedDefaults()) {
                DEFAULT_FAVORITES
            } else {
                emptyList()
            }
            persist()
            return
        }
        val parsed = runCatching {
            json.decodeFromString<StoredSettingsFavoritesPayload>(payload)
        }.getOrNull()
        _favorites.value = parsed?.items.orEmpty()
    }

    fun onProfileChanged() {
        hasLoaded = false
        _favorites.value = emptyList()
        ensureLoaded()
    }

    fun isFavorite(anchor: String): Boolean {
        ensureLoaded()
        return _favorites.value.any { it.anchor == anchor }
    }

    fun add(favorite: SettingsFavorite) {
        ensureLoaded()
        if (_favorites.value.any { it.anchor == favorite.anchor }) return
        _favorites.value = _favorites.value + favorite
        persist()
    }

    fun remove(anchor: String) {
        ensureLoaded()
        val next = _favorites.value.filterNot { it.anchor == anchor }
        if (next.size == _favorites.value.size) return
        _favorites.value = next
        persist()
    }

    fun moveByIndex(fromIndex: Int, toIndex: Int) {
        ensureLoaded()
        val current = _favorites.value
        if (fromIndex !in current.indices || toIndex !in current.indices || fromIndex == toIndex) return
        val next = current.toMutableList()
        next.add(toIndex, next.removeAt(fromIndex))
        _favorites.value = next
        persist()
    }

    fun toggle(favorite: SettingsFavorite) {
        if (isFavorite(favorite.anchor)) remove(favorite.anchor) else add(favorite)
    }

    private fun persist() {
        SettingsFavoritesStorage.savePayload(
            json.encodeToString(StoredSettingsFavoritesPayload(items = _favorites.value)),
        )
    }
}
