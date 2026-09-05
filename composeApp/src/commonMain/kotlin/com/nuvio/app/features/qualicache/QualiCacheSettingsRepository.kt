package com.nuvio.app.features.qualicache

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object QualiCacheSettingsRepository {
    private val _uiState = MutableStateFlow(QualiCacheSettings())
    val uiState: StateFlow<QualiCacheSettings> = _uiState.asStateFlow()

    private var hasLoaded = false

    private var enabled = false
    private var baseUrl = ""
    private var accessKey = ""
    private var minimumTrust = QualiCacheMinimumTrust.HIGH
    private var showResolution = true
    private var showDynamicRange = true
    private var showAudio = true

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun snapshot(): QualiCacheSettings {
        ensureLoaded()
        return _uiState.value
    }

    fun setEnabled(value: Boolean) {
        ensureLoaded()
        // Same guard as MDBList's API key: there is nothing to enable without a server address, and
        // an enabled-but-unaddressed integration would just fail every lookup silently.
        if (value && baseUrl.isBlank()) return
        if (enabled == value) return
        enabled = value
        publish()
        QualiCacheSettingsStorage.saveEnabled(value)
    }

    fun setBaseUrl(value: String) {
        ensureLoaded()
        val normalized = normalizeBaseUrl(value)
        if (baseUrl == normalized) return
        baseUrl = normalized
        if (baseUrl.isBlank()) {
            enabled = false
            QualiCacheSettingsStorage.saveEnabled(false)
        }
        publish()
        QualiCacheSettingsStorage.saveBaseUrl(normalized)
        // Anything cached came from the previous server (or from a URL typo that failed); it must
        // not be attributed to the new one.
        QualiCacheQualityService.invalidate()
    }

    fun setAccessKey(value: String) {
        ensureLoaded()
        val normalized = value.trim()
        if (accessKey == normalized) return
        accessKey = normalized
        publish()
        QualiCacheSettingsStorage.saveAccessKey(normalized)
        // A wrong key produced cached 401 failures; a corrected one must be able to retry at once.
        QualiCacheQualityService.invalidate()
    }

    fun setMinimumTrust(value: QualiCacheMinimumTrust) {
        ensureLoaded()
        if (minimumTrust == value) return
        minimumTrust = value
        publish()
        QualiCacheSettingsStorage.saveMinimumTrust(value.apiValue)
        // A cached empty/high-tier answer is not authoritative for a wider policy.
        QualiCacheQualityService.invalidate()
    }

    fun setCategoryEnabled(category: QualityBadgeCategory, value: Boolean) {
        ensureLoaded()
        when (category) {
            QualityBadgeCategory.Resolution -> if (showResolution != value) {
                showResolution = value
                QualiCacheSettingsStorage.saveShowResolution(value)
            } else return
            QualityBadgeCategory.DynamicRange -> if (showDynamicRange != value) {
                showDynamicRange = value
                QualiCacheSettingsStorage.saveShowDynamicRange(value)
            } else return
            QualityBadgeCategory.Audio -> if (showAudio != value) {
                showAudio = value
                QualiCacheSettingsStorage.saveShowAudio(value)
            } else return
        }
        publish()
    }

    private fun loadFromDisk() {
        hasLoaded = true
        val storedBaseUrl = QualiCacheSettingsStorage.loadBaseUrl().orEmpty()
        val legacyMinimumTrust = minimumTrustFromUrl(storedBaseUrl)
        baseUrl = normalizeBaseUrl(storedBaseUrl)
        if (storedBaseUrl.trim() != baseUrl) {
            // Persist the canonical origin so a legacy query string cannot reappear next launch.
            QualiCacheSettingsStorage.saveBaseUrl(baseUrl)
        }
        accessKey = QualiCacheSettingsStorage.loadAccessKey().orEmpty().trim()
        val storedMinimumTrust = QualiCacheSettingsStorage.loadMinimumTrust()
        minimumTrust = QualiCacheMinimumTrust.fromStorage(storedMinimumTrust)
            ?: legacyMinimumTrust
            ?: QualiCacheMinimumTrust.HIGH
        if (storedMinimumTrust.isNullOrBlank() && legacyMinimumTrust != null) {
            QualiCacheSettingsStorage.saveMinimumTrust(minimumTrust.apiValue)
        }
        enabled = (QualiCacheSettingsStorage.loadEnabled() ?: false) && baseUrl.isNotBlank()
        showResolution = QualiCacheSettingsStorage.loadShowResolution() ?: true
        showDynamicRange = QualiCacheSettingsStorage.loadShowDynamicRange() ?: true
        showAudio = QualiCacheSettingsStorage.loadShowAudio() ?: true
        publish()
    }

    private fun publish() {
        _uiState.value = QualiCacheSettings(
            enabled = enabled,
            baseUrl = baseUrl,
            accessKey = accessKey,
            minimumTrust = minimumTrust,
            showResolution = showResolution,
            showDynamicRange = showDynamicRange,
            showAudio = showAudio,
        )
    }
}

/**
 * Canonicalises what the user typed into an origin the service can append paths to:
 * `qualicache.example:8000/` -> `http://qualicache.example:8000`.
 *
 * Normalising on save (rather than per request) keeps the stored value stable, so the settings
 * screen shows exactly what will be requested and the cache-invalidation check in
 * [QualiCacheSettingsRepository.setBaseUrl] does not fire on cosmetic edits like a trailing slash.
 */
internal fun normalizeBaseUrl(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return ""
    val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
    var base = withScheme.substringBefore('?').substringBefore('#').trimEnd('/')
    for (suffix in listOf("/v1/quality", "/v1")) {
        if (base.endsWith(suffix)) {
            base = base.removeSuffix(suffix).trimEnd('/')
            break
        }
    }
    return base
}

/** Reads the old URL-query workaround once so upgrading preserves the user's intended policy. */
internal fun minimumTrustFromUrl(value: String): QualiCacheMinimumTrust? {
    val query = value.substringAfter('?', missingDelimiterValue = "").substringBefore('#')
    val rawValue = query
        .split('&')
        .mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator < 0) return@mapNotNull null
            val key = part.substring(0, separator)
            val candidate = part.substring(separator + 1)
            candidate.takeIf { key.equals("min_trust", ignoreCase = true) }
        }
        .firstOrNull()
    return QualiCacheMinimumTrust.fromStorage(rawValue)
}
