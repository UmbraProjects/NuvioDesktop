package com.nuvio.app.features.mdblist

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object MdbListSettingsRepository {
    private val _uiState = MutableStateFlow(MdbListSettings())
    val uiState: StateFlow<MdbListSettings> = _uiState.asStateFlow()

    private var hasLoaded = false

    private var enabled = false
    private var trackingEnabled = false
    private var asContinueWatchingSource = false
    private var asCalendarSource = false
    private var apiKey = ""
    private var useImdb = true
    private var useTmdb = true
    private var useTomatoes = true
    private var useMetacritic = true
    private var useTrakt = true
    private var useLetterboxd = true
    private var useAudience = true
    private var useMal = true

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun snapshot(): MdbListSettings {
        ensureLoaded()
        return _uiState.value
    }

    fun setEnabled(value: Boolean) {
        ensureLoaded()
        if (value && apiKey.isBlank()) return
        if (enabled == value) return
        enabled = value
        publish()
        MdbListSettingsStorage.saveEnabled(value)
    }

    fun setTrackingEnabled(value: Boolean) {
        ensureLoaded()
        if (value && apiKey.isBlank()) return
        if (trackingEnabled == value) return
        trackingEnabled = value
        if (!value) {
            if (asContinueWatchingSource) {
                asContinueWatchingSource = false
                MdbListSettingsStorage.saveContinueWatchingSource(false)
            }
            if (asCalendarSource) {
                asCalendarSource = false
                MdbListSettingsStorage.saveCalendarSource(false)
                MdbListCalendarRepository.clearLocalState()
            }
        }
        publish()
        MdbListSettingsStorage.saveTrackingEnabled(value)
    }

    fun setContinueWatchingSource(value: Boolean) {
        ensureLoaded()
        if (value && !(apiKey.isNotBlank() && trackingEnabled)) return
        if (asContinueWatchingSource == value) return
        asContinueWatchingSource = value
        publish()
        MdbListSettingsStorage.saveContinueWatchingSource(value)
    }

    /** True when MDBList's paused sessions should drive Continue Watching. */
    internal fun isMdbListCwSource(): Boolean {
        ensureLoaded()
        return asContinueWatchingSource && trackingEnabled && apiKey.isNotBlank()
    }

    fun setCalendarSource(value: Boolean) {
        ensureLoaded()
        if (value && !(apiKey.isNotBlank() && trackingEnabled)) return
        if (asCalendarSource == value) return
        asCalendarSource = value
        publish()
        MdbListSettingsStorage.saveCalendarSource(value)
        MdbListCalendarRepository.clearLocalState()
    }

    internal fun isMdbListCalendarSource(): Boolean {
        ensureLoaded()
        return asCalendarSource && trackingEnabled && apiKey.isNotBlank()
    }

    fun setApiKey(value: String) {
        ensureLoaded()
        val normalized = value.trim()
        if (apiKey == normalized) return
        apiKey = normalized
        if (apiKey.isBlank()) {
            enabled = false
            trackingEnabled = false
            asContinueWatchingSource = false
            asCalendarSource = false
            MdbListSettingsStorage.saveEnabled(false)
            MdbListSettingsStorage.saveTrackingEnabled(false)
            MdbListSettingsStorage.saveContinueWatchingSource(false)
            MdbListSettingsStorage.saveCalendarSource(false)
        }
        publish()
        MdbListSettingsStorage.saveApiKey(normalized)
        MdbListCalendarRepository.clearLocalState()
    }

    /** The key to authenticate tracking calls with, or null when tracking is off or unconfigured. */
    internal fun trackingApiKey(): String? {
        ensureLoaded()
        return apiKey.takeIf { it.isNotBlank() && trackingEnabled }
    }

    fun setProviderEnabled(providerId: String, value: Boolean) {
        ensureLoaded()
        when (providerId) {
            MdbListMetadataService.PROVIDER_IMDB -> if (useImdb != value) {
                useImdb = value
                MdbListSettingsStorage.saveUseImdb(value)
            } else return
            MdbListMetadataService.PROVIDER_TMDB -> if (useTmdb != value) {
                useTmdb = value
                MdbListSettingsStorage.saveUseTmdb(value)
            } else return
            MdbListMetadataService.PROVIDER_TOMATOES -> if (useTomatoes != value) {
                useTomatoes = value
                MdbListSettingsStorage.saveUseTomatoes(value)
            } else return
            MdbListMetadataService.PROVIDER_METACRITIC -> if (useMetacritic != value) {
                useMetacritic = value
                MdbListSettingsStorage.saveUseMetacritic(value)
            } else return
            MdbListMetadataService.PROVIDER_TRAKT -> if (useTrakt != value) {
                useTrakt = value
                MdbListSettingsStorage.saveUseTrakt(value)
            } else return
            MdbListMetadataService.PROVIDER_LETTERBOXD -> if (useLetterboxd != value) {
                useLetterboxd = value
                MdbListSettingsStorage.saveUseLetterboxd(value)
            } else return
            MdbListMetadataService.PROVIDER_AUDIENCE -> if (useAudience != value) {
                useAudience = value
                MdbListSettingsStorage.saveUseAudience(value)
            } else return
            MdbListMetadataService.PROVIDER_MAL -> if (useMal != value) {
                useMal = value
                MdbListSettingsStorage.saveUseMal(value)
            } else return
            else -> return
        }
        publish()
    }

    private fun loadFromDisk() {
        hasLoaded = true
        apiKey = MdbListSettingsStorage.loadApiKey().orEmpty().trim()
        enabled = (MdbListSettingsStorage.loadEnabled() ?: false) && apiKey.isNotBlank()
        trackingEnabled = (MdbListSettingsStorage.loadTrackingEnabled() ?: false) && apiKey.isNotBlank()
        asContinueWatchingSource =
            (MdbListSettingsStorage.loadContinueWatchingSource() ?: false) && trackingEnabled
        asCalendarSource = (MdbListSettingsStorage.loadCalendarSource() ?: false) && trackingEnabled
        useImdb = MdbListSettingsStorage.loadUseImdb() ?: true
        useTmdb = MdbListSettingsStorage.loadUseTmdb() ?: true
        useTomatoes = MdbListSettingsStorage.loadUseTomatoes() ?: true
        useMetacritic = MdbListSettingsStorage.loadUseMetacritic() ?: true
        useTrakt = MdbListSettingsStorage.loadUseTrakt() ?: true
        useLetterboxd = MdbListSettingsStorage.loadUseLetterboxd() ?: true
        useAudience = MdbListSettingsStorage.loadUseAudience() ?: true
        useMal = MdbListSettingsStorage.loadUseMal() ?: true
        publish()
    }

    private fun publish() {
        _uiState.value = MdbListSettings(
            enabled = enabled,
            trackingEnabled = trackingEnabled,
            asContinueWatchingSource = asContinueWatchingSource,
            asCalendarSource = asCalendarSource,
            apiKey = apiKey,
            useImdb = useImdb,
            useTmdb = useTmdb,
            useTomatoes = useTomatoes,
            useMetacritic = useMetacritic,
            useTrakt = useTrakt,
            useLetterboxd = useLetterboxd,
            useAudience = useAudience,
            useMal = useMal,
        )
        // The key and the tracking toggle together are what "connected" means for MDBList, so the
        // registry's view of it has to move whenever either does.
        MdbListTrackingAuthProvider.refreshAuthenticationState()
    }
}
