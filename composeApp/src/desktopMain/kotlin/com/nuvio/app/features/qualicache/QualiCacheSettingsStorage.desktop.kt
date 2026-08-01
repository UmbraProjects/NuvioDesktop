package com.nuvio.app.features.qualicache

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object QualiCacheSettingsStorage {
    private const val enabledKey = "qualicache_enabled"
    private const val baseUrlKey = "qualicache_base_url"
    private const val accessKeyKey = "qualicache_access_key"
    private const val showResolutionKey = "qualicache_show_resolution"
    private const val showDynamicRangeKey = "qualicache_show_dynamic_range"
    private const val showAudioKey = "qualicache_show_audio"
    private val store = DesktopStorage.store("nuvio_qualicache_settings")

    actual fun loadEnabled(): Boolean? = loadBoolean(enabledKey)
    actual fun saveEnabled(enabled: Boolean) = saveBoolean(enabledKey, enabled)
    actual fun loadBaseUrl(): String? = loadString(baseUrlKey)
    actual fun saveBaseUrl(baseUrl: String) = saveString(baseUrlKey, baseUrl)
    actual fun loadAccessKey(): String? = loadString(accessKeyKey)
    actual fun saveAccessKey(accessKey: String) = saveString(accessKeyKey, accessKey)
    actual fun loadShowResolution(): Boolean? = loadBoolean(showResolutionKey)
    actual fun saveShowResolution(enabled: Boolean) = saveBoolean(showResolutionKey, enabled)
    actual fun loadShowDynamicRange(): Boolean? = loadBoolean(showDynamicRangeKey)
    actual fun saveShowDynamicRange(enabled: Boolean) = saveBoolean(showDynamicRangeKey, enabled)
    actual fun loadShowAudio(): Boolean? = loadBoolean(showAudioKey)
    actual fun saveShowAudio(enabled: Boolean) = saveBoolean(showAudioKey, enabled)

    private fun loadString(key: String): String? = store.getString(ProfileScopedKey.of(key))
    private fun saveString(key: String, value: String) = store.putString(ProfileScopedKey.of(key), value)
    private fun loadBoolean(key: String): Boolean? = store.getBoolean(ProfileScopedKey.of(key))
    private fun saveBoolean(key: String, value: Boolean) = store.putBoolean(ProfileScopedKey.of(key), value)
}
