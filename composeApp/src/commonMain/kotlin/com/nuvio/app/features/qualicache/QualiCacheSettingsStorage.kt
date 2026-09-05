package com.nuvio.app.features.qualicache

internal expect object QualiCacheSettingsStorage {
    fun loadEnabled(): Boolean?
    fun saveEnabled(enabled: Boolean)
    fun loadBaseUrl(): String?
    fun saveBaseUrl(baseUrl: String)
    fun loadAccessKey(): String?
    fun saveAccessKey(accessKey: String)
    fun loadMinimumTrust(): String?
    fun saveMinimumTrust(minimumTrust: String)
    fun loadShowResolution(): Boolean?
    fun saveShowResolution(enabled: Boolean)
    fun loadShowDynamicRange(): Boolean?
    fun saveShowDynamicRange(enabled: Boolean)
    fun loadShowAudio(): Boolean?
    fun saveShowAudio(enabled: Boolean)
}
