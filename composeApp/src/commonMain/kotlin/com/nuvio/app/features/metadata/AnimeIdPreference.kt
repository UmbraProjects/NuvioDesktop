package com.nuvio.app.features.metadata

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 * Determines which external ID is used as the canonical content ID for anime entries.
 *
 * By default (IMDB), anime entries that share the same IMDB ID get grouped together (e.g. multiple
 * MAL seasons of one franchise). Choosing MAL or KITSU gives each season its own canonical ID so
 * they appear as separate items in the library and watched state.
 *
 * Upstream calls this `SimklAnimeIdPreference` and applies it to SIMKL-derived ids only. Here it
 * also governs the local library, which upstream does not have and which works with no SIMKL
 * account at all — so it is deliberately not SIMKL-scoped in either name or storage. It cannot
 * reach addon catalogs: a row from a Kitsu-backed catalog keeps whatever id that addon returned,
 * on Home, Search and Library alike.
 */
@Serializable
enum class AnimeIdPreference {
    /** Use IMDB ID when available (groups multiple seasons under one ID). */
    IMDB,

    /** Prefer MyAnimeList ID — each MAL entry gets its own canonical ID. */
    MAL,

    /** Prefer Kitsu ID — each Kitsu entry gets its own canonical ID. */
    KITSU;

    companion object {
        fun fromStorage(value: String?): AnimeIdPreference =
            entries.firstOrNull { it.name == value } ?: DEFAULT_ANIME_ID_PREFERENCE
    }
}

val DEFAULT_ANIME_ID_PREFERENCE: AnimeIdPreference = AnimeIdPreference.IMDB

internal expect object AnimeIdPreferenceStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}

/**
 * Holds [AnimeIdPreference]. Read on every anime content-id derivation, so it loads lazily and
 * never blocks on having been observed.
 *
 * Deliberately does not refresh the surfaces it governs: this module is below both SIMKL and the
 * local library and must not depend on either. Content ids are derived rather than stored, so a
 * caller that changes the preference reloads them — see the settings page.
 *
 * The cached value is per-profile even though this object is not: [AnimeIdPreferenceStorage] keys on
 * the active profile, so the cache goes stale the moment one switches and must be invalidated by
 * [onProfileChanged] *before* any store reloads. Reading a stale IMDB here while the incoming
 * profile is on MAL/KITSU makes [migrateLegacyAnimeContentId] rewrite that profile's native ids to
 * franchise ids — and its callers persist the result.
 */
object AnimeIdPreferenceRepository {
    private val _uiState = MutableStateFlow(DEFAULT_ANIME_ID_PREFERENCE)
    val uiState: StateFlow<AnimeIdPreference> = _uiState.asStateFlow()

    private var loaded = false
    private var value: AnimeIdPreference = DEFAULT_ANIME_ID_PREFERENCE

    fun current(): AnimeIdPreference {
        if (!loaded) {
            loaded = true
            value = AnimeIdPreference.fromStorage(AnimeIdPreferenceStorage.loadPayload()?.trim()?.takeIf { it.isNotEmpty() })
            _uiState.value = value
        }
        return value
    }

    fun set(preference: AnimeIdPreference) {
        if (current() == preference) return
        value = preference
        AnimeIdPreferenceStorage.savePayload(preference.name)
        _uiState.value = preference
    }

    /**
     * Drops the cached value and re-reads the newly active profile's own.
     *
     * Eager rather than lazy: the reload this precedes runs id migrations against whatever
     * [current] answers, so leaving the reload to re-populate the cache would race it.
     */
    fun onProfileChanged() {
        loaded = false
        current()
    }
}
