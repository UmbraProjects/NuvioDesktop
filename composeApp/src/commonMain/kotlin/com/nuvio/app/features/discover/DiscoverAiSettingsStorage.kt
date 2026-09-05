package com.nuvio.app.features.discover

import kotlinx.serialization.json.JsonObject

/**
 * Per-profile storage for the AI provider settings.
 *
 * **The API key is deliberately absent from the sync payload.** The settings UI tells the user the
 * key is stored locally on this PC; uploading it to a Nuvio account would make that copy false. Its
 * a billing credential for a third-party service, and the cost of re-entering it on another machine
 * is far smaller than the cost of it travelling somewhere the UI said it would not.
 */
internal expect object DiscoverAiSettingsStorage {
    fun loadProvider(): String?
    fun saveProvider(value: String)
    fun loadApiKey(): String?
    fun saveApiKey(value: String)
    fun loadBaseUrl(): String?
    fun saveBaseUrl(value: String)
    fun loadModel(): String?
    fun saveModel(value: String)
    fun loadConsentGiven(): Boolean?
    fun saveConsentGiven(value: Boolean)
    fun loadEnabled(): Boolean?
    fun saveEnabled(value: Boolean)
    fun loadDailyRefresh(): Boolean?
    fun saveDailyRefresh(value: Boolean)
    fun exportToSyncPayload(): JsonObject
    fun replaceFromSyncPayload(payload: JsonObject)
}
