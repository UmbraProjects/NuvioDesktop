package com.nuvio.app.features.tmdb

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object TmdbSettingsRepository {
    private val _uiState = MutableStateFlow(TmdbSettings())
    val uiState: StateFlow<TmdbSettings> = _uiState.asStateFlow()

    private var hasLoaded = false

    private var enabled = false
    private var apiKey = ""
    private var language = "en"
    private var useTrailers = true
    private var useArtwork = true
    private var useBasicInfo = true
    private var useDetails = true
    private var useCredits = true
    private var useProductions = true
    private var useNetworks = true
    private var useEpisodes = true
    private var useSeasonPosters = true
    private var useMoreLikeThis = true
    private var useCollections = true
    private var libraryPosterEnabled = false
    private var libraryPosterUrlTemplate = ""
    private var resolveFilenameCatalogs = true
    private var heroImageSource = HeroImageSource.Addon

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun snapshot(): TmdbSettings {
        ensureLoaded()
        return _uiState.value
    }

    fun setEnabled(value: Boolean) {
        ensureLoaded()
        if (value && apiKey.isBlank()) return
        if (enabled == value) return
        enabled = value
        publish()
        TmdbSettingsStorage.saveEnabled(value)
    }

    fun setApiKey(value: String) {
        ensureLoaded()
        val normalized = value.trim()
        if (apiKey == normalized) return
        val wasBlank = apiKey.isBlank()
        apiKey = normalized
        if (apiKey.isBlank()) {
            enabled = false
            TmdbSettingsStorage.saveEnabled(false)
        } else if (wasBlank) {
            // Entering a TMDB key turns enrichment on and defaults hero art to TMDB for
            // everything. Many search/meta providers (Trakt via AIOMetadata, TVDB search)
            // return no backdrop at all and fall back to a stretched poster, so TMDB art is
            // the consistent choice once a key exists. Users can switch either setting after;
            // both are only touched on a blank → non-blank key transition. (Previously only
            // the onboarding popup auto-enabled — the settings panel path did not.)
            enabled = true
            TmdbSettingsStorage.saveEnabled(true)
            if (heroImageSource != HeroImageSource.TmdbOnly) {
                heroImageSource = HeroImageSource.TmdbOnly
                TmdbSettingsStorage.saveHeroImageSource(HeroImageSource.TmdbOnly.name)
                TmdbHeroImageService.clearCache()
            }
        }
        publish()
        TmdbSettingsStorage.saveApiKey(normalized)
    }

    fun setLanguage(value: String) {
        ensureLoaded()
        val normalized = normalizeLanguage(value)
        if (language == normalized) return
        language = normalized
        publish()
        TmdbSettingsStorage.saveLanguage(normalized)
    }

    fun setUseTrailers(value: Boolean) = setBoolean(
        current = useTrailers,
        next = value,
        update = { useTrailers = it },
        persist = TmdbSettingsStorage::saveUseTrailers,
    )

    fun setUseArtwork(value: Boolean) = setBoolean(
        current = useArtwork,
        next = value,
        update = { useArtwork = it },
        persist = TmdbSettingsStorage::saveUseArtwork,
    )

    fun setUseBasicInfo(value: Boolean) = setBoolean(
        current = useBasicInfo,
        next = value,
        update = { useBasicInfo = it },
        persist = TmdbSettingsStorage::saveUseBasicInfo,
    )

    fun setUseDetails(value: Boolean) = setBoolean(
        current = useDetails,
        next = value,
        update = { useDetails = it },
        persist = TmdbSettingsStorage::saveUseDetails,
    )

    fun setUseCredits(value: Boolean) = setBoolean(
        current = useCredits,
        next = value,
        update = { useCredits = it },
        persist = TmdbSettingsStorage::saveUseCredits,
    )

    fun setUseProductions(value: Boolean) = setBoolean(
        current = useProductions,
        next = value,
        update = { useProductions = it },
        persist = TmdbSettingsStorage::saveUseProductions,
    )

    fun setUseNetworks(value: Boolean) = setBoolean(
        current = useNetworks,
        next = value,
        update = { useNetworks = it },
        persist = TmdbSettingsStorage::saveUseNetworks,
    )

    fun setUseEpisodes(value: Boolean) = setBoolean(
        current = useEpisodes,
        next = value,
        update = { useEpisodes = it },
        persist = TmdbSettingsStorage::saveUseEpisodes,
    )

    fun setUseSeasonPosters(value: Boolean) = setBoolean(
        current = useSeasonPosters,
        next = value,
        update = { useSeasonPosters = it },
        persist = TmdbSettingsStorage::saveUseSeasonPosters,
    )

    fun setUseMoreLikeThis(value: Boolean) = setBoolean(
        current = useMoreLikeThis,
        next = value,
        update = { useMoreLikeThis = it },
        persist = TmdbSettingsStorage::saveUseMoreLikeThis,
    )

    fun setUseCollections(value: Boolean) = setBoolean(
        current = useCollections,
        next = value,
        update = { useCollections = it },
        persist = TmdbSettingsStorage::saveUseCollections,
    )

    fun setLibraryPosterEnabled(value: Boolean) {
        ensureLoaded()
        if (libraryPosterEnabled == value) return
        libraryPosterEnabled = value
        publish()
        TmdbSettingsStorage.saveLibraryPosterEnabled(value)
    }

    fun setResolveFilenameCatalogs(value: Boolean) = setBoolean(
        current = resolveFilenameCatalogs,
        next = value,
        update = { resolveFilenameCatalogs = it },
        persist = TmdbSettingsStorage::saveResolveFilenameCatalogs,
    )

    fun setHeroImageSource(source: HeroImageSource) {
        ensureLoaded()
        if (heroImageSource == source) return
        heroImageSource = source
        publish()
        TmdbSettingsStorage.saveHeroImageSource(source.name)
        TmdbHeroImageService.clearCache()
    }

    fun setLibraryPosterUrlTemplate(value: String) {
        ensureLoaded()
        val normalized = value.trim()
        if (libraryPosterUrlTemplate == normalized) return
        libraryPosterUrlTemplate = normalized
        publish()
        TmdbSettingsStorage.saveLibraryPosterUrlTemplate(normalized)
    }

    private fun setBoolean(
        current: Boolean,
        next: Boolean,
        update: (Boolean) -> Unit,
        persist: (Boolean) -> Unit,
    ) {
        ensureLoaded()
        if (current == next) return
        update(next)
        publish()
        persist(next)
    }

    private fun loadFromDisk() {
        hasLoaded = true
        apiKey = TmdbSettingsStorage.loadApiKey()?.trim().orEmpty()
        enabled = (TmdbSettingsStorage.loadEnabled() ?: false) && apiKey.isNotBlank()
        val storedLanguage = TmdbSettingsStorage.loadLanguage()
        language = if (storedLanguage == null) "en" else normalizeLanguage(storedLanguage)
        useTrailers = TmdbSettingsStorage.loadUseTrailers() ?: true
        useArtwork = TmdbSettingsStorage.loadUseArtwork() ?: true
        useBasicInfo = TmdbSettingsStorage.loadUseBasicInfo() ?: true
        useDetails = TmdbSettingsStorage.loadUseDetails() ?: true
        useCredits = TmdbSettingsStorage.loadUseCredits() ?: true
        useProductions = TmdbSettingsStorage.loadUseProductions() ?: true
        useNetworks = TmdbSettingsStorage.loadUseNetworks() ?: true
        useEpisodes = TmdbSettingsStorage.loadUseEpisodes() ?: true
        useSeasonPosters = TmdbSettingsStorage.loadUseSeasonPosters() ?: true
        useMoreLikeThis = TmdbSettingsStorage.loadUseMoreLikeThis() ?: true
        useCollections = TmdbSettingsStorage.loadUseCollections() ?: true
        libraryPosterUrlTemplate = TmdbSettingsStorage.loadLibraryPosterUrlTemplate()?.trim().orEmpty()
        libraryPosterEnabled = (TmdbSettingsStorage.loadLibraryPosterEnabled() ?: false) &&
            libraryPosterUrlTemplate.isNotBlank()
        resolveFilenameCatalogs = TmdbSettingsStorage.loadResolveFilenameCatalogs() ?: true
        heroImageSource = TmdbSettingsStorage.loadHeroImageSource()
            ?.let { name -> HeroImageSource.entries.firstOrNull { it.name == name } }
            ?: HeroImageSource.Addon
        publish()
    }

    private fun publish() {
        _uiState.value = TmdbSettings(
            enabled = enabled,
            apiKey = apiKey,
            language = language,
            useTrailers = useTrailers,
            useArtwork = useArtwork,
            useBasicInfo = useBasicInfo,
            useDetails = useDetails,
            useCredits = useCredits,
            useProductions = useProductions,
            useNetworks = useNetworks,
            useEpisodes = useEpisodes,
            useSeasonPosters = useSeasonPosters,
            useMoreLikeThis = useMoreLikeThis,
            useCollections = useCollections,
            libraryPosterEnabled = libraryPosterEnabled,
            libraryPosterUrlTemplate = libraryPosterUrlTemplate,
            resolveFilenameCatalogs = resolveFilenameCatalogs,
            heroImageSource = heroImageSource,
        )
    }
}

internal fun normalizeLanguage(value: String?): String {
    val trimmed = value?.trim()?.replace('_', '-') ?: return ""
    return trimmed.takeIf { it.isNotBlank() } ?: ""
}
