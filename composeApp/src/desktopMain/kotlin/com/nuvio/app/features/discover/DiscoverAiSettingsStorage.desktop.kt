package com.nuvio.app.features.discover

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal actual object DiscoverAiSettingsStorage {
    private const val providerKey = "discover_ai_provider"
    private const val apiKeyKey = "discover_ai_api_key"
    private const val baseUrlKey = "discover_ai_base_url"
    private const val modelKey = "discover_ai_model"
    private const val consentKey = "discover_ai_consent"
    private const val enabledKey = "discover_ai_enabled"
    private const val dailyRefreshKey = "discover_ai_daily_refresh"

    /**
     * What a sync round trip replaces. [apiKeyKey] is **not** here and must not be added — see the
     * note on the expect declaration. It is still cleared locally on a profile wipe, because
     * [DesktopStorage] scopes every key by profile.
     */
    private val syncKeys = listOf(
        providerKey,
        baseUrlKey,
        modelKey,
        consentKey,
        enabledKey,
        dailyRefreshKey,
    )
    private val store = DesktopStorage.store("nuvio_discover_ai_settings")

    actual fun loadProvider(): String? = loadString(providerKey)
    actual fun saveProvider(value: String) = saveString(providerKey, value)
    actual fun loadApiKey(): String? = loadString(apiKeyKey)
    actual fun saveApiKey(value: String) = saveString(apiKeyKey, value)
    actual fun loadBaseUrl(): String? = loadString(baseUrlKey)
    actual fun saveBaseUrl(value: String) = saveString(baseUrlKey, value)
    actual fun loadModel(): String? = loadString(modelKey)
    actual fun saveModel(value: String) = saveString(modelKey, value)
    actual fun loadConsentGiven(): Boolean? = loadBoolean(consentKey)
    actual fun saveConsentGiven(value: Boolean) = saveBoolean(consentKey, value)
    actual fun loadEnabled(): Boolean? = loadBoolean(enabledKey)
    actual fun saveEnabled(value: Boolean) = saveBoolean(enabledKey, value)
    actual fun loadDailyRefresh(): Boolean? = loadBoolean(dailyRefreshKey)
    actual fun saveDailyRefresh(value: Boolean) = saveBoolean(dailyRefreshKey, value)

    private fun loadString(key: String): String? = store.getString(ProfileScopedKey.of(key))
    private fun saveString(key: String, value: String) = store.putString(ProfileScopedKey.of(key), value)
    private fun loadBoolean(key: String): Boolean? = store.getBoolean(ProfileScopedKey.of(key))
    private fun saveBoolean(key: String, value: Boolean) = store.putBoolean(ProfileScopedKey.of(key), value)

    actual fun exportToSyncPayload(): JsonObject = buildJsonObject {
        loadProvider()?.let { put(providerKey, encodeSyncString(it)) }
        loadBaseUrl()?.let { put(baseUrlKey, encodeSyncString(it)) }
        loadModel()?.let { put(modelKey, encodeSyncString(it)) }
        loadConsentGiven()?.let { put(consentKey, encodeSyncBoolean(it)) }
        loadEnabled()?.let { put(enabledKey, encodeSyncBoolean(it)) }
        loadDailyRefresh()?.let { put(dailyRefreshKey, encodeSyncBoolean(it)) }
    }

    actual fun replaceFromSyncPayload(payload: JsonObject) {
        store.removeAll(syncKeys.map(ProfileScopedKey::of))
        payload.decodeSyncString(providerKey)?.let(::saveProvider)
        payload.decodeSyncString(baseUrlKey)?.let(::saveBaseUrl)
        payload.decodeSyncString(modelKey)?.let(::saveModel)
        payload.decodeSyncBoolean(consentKey)?.let(::saveConsentGiven)
        payload.decodeSyncBoolean(enabledKey)?.let(::saveEnabled)
        payload.decodeSyncBoolean(dailyRefreshKey)?.let(::saveDailyRefresh)
    }
}
