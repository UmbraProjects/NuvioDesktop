package com.nuvio.app.features.debrid

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import platform.Foundation.NSUserDefaults

actual object DebridSettingsStorage {
    private const val enabledKey = "debrid_enabled"
    private const val torboxApiKeyKey = "debrid_torbox_api_key"
    private const val realDebridApiKeyKey = "debrid_real_debrid_api_key"
    private const val streamNameTemplateKey = "debrid_stream_name_template"
    private const val streamDescriptionTemplateKey = "debrid_stream_description_template"
    private val syncKeys = listOf(
        enabledKey,
        torboxApiKeyKey,
        realDebridApiKeyKey,
        streamNameTemplateKey,
        streamDescriptionTemplateKey,
    )

    actual fun loadEnabled(): Boolean? = loadBoolean(enabledKey)

    actual fun saveEnabled(enabled: Boolean) {
        saveBoolean(enabledKey, enabled)
    }

    actual fun loadTorboxApiKey(): String? = loadString(torboxApiKeyKey)

    actual fun saveTorboxApiKey(apiKey: String) {
        saveString(torboxApiKeyKey, apiKey)
    }

    actual fun loadRealDebridApiKey(): String? = loadString(realDebridApiKeyKey)

    actual fun saveRealDebridApiKey(apiKey: String) {
        saveString(realDebridApiKeyKey, apiKey)
    }

    actual fun loadStreamNameTemplate(): String? = loadString(streamNameTemplateKey)

    actual fun saveStreamNameTemplate(template: String) {
        saveString(streamNameTemplateKey, template)
    }

    actual fun loadStreamDescriptionTemplate(): String? = loadString(streamDescriptionTemplateKey)

    actual fun saveStreamDescriptionTemplate(template: String) {
        saveString(streamDescriptionTemplateKey, template)
    }

    private fun loadBoolean(key: String): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val scopedKey = ProfileScopedKey.of(key)
        return if (defaults.objectForKey(scopedKey) != null) {
            defaults.boolForKey(scopedKey)
        } else {
            null
        }
    }

    private fun saveBoolean(key: String, enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(key))
    }

    private fun loadString(key: String): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(key))

    private fun saveString(key: String, value: String) {
        NSUserDefaults.standardUserDefaults.setObject(value, forKey = ProfileScopedKey.of(key))
    }

    actual fun exportToSyncPayload(): JsonObject = buildJsonObject {
        loadEnabled()?.let { put(enabledKey, encodeSyncBoolean(it)) }
        loadTorboxApiKey()?.let { put(torboxApiKeyKey, encodeSyncString(it)) }
        loadRealDebridApiKey()?.let { put(realDebridApiKeyKey, encodeSyncString(it)) }
        loadStreamNameTemplate()?.let { put(streamNameTemplateKey, encodeSyncString(it)) }
        loadStreamDescriptionTemplate()?.let { put(streamDescriptionTemplateKey, encodeSyncString(it)) }
    }

    actual fun replaceFromSyncPayload(payload: JsonObject) {
        syncKeys.forEach { key ->
            NSUserDefaults.standardUserDefaults.removeObjectForKey(ProfileScopedKey.of(key))
        }

        payload.decodeSyncBoolean(enabledKey)?.let(::saveEnabled)
        payload.decodeSyncString(torboxApiKeyKey)?.let(::saveTorboxApiKey)
        payload.decodeSyncString(realDebridApiKeyKey)?.let(::saveRealDebridApiKey)
        payload.decodeSyncString(streamNameTemplateKey)?.let(::saveStreamNameTemplate)
        payload.decodeSyncString(streamDescriptionTemplateKey)?.let(::saveStreamDescriptionTemplate)
    }
}

