package com.nuvio.app.features.yamtrack

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class YamtrackStoredSettings(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val apiToken: String = "",
)

private val yamtrackJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal fun decodeYamtrackSettingsPayload(payload: String): YamtrackStoredSettings {
    if (payload.isBlank()) return YamtrackStoredSettings()
    return runCatching {
        yamtrackJson.decodeFromString(YamtrackStoredSettings.serializer(), payload)
    }.getOrDefault(YamtrackStoredSettings())
}

internal fun encodeYamtrackSettingsPayload(
    enabled: Boolean,
    baseUrl: String,
    apiToken: String,
): String = yamtrackJson.encodeToString(
    YamtrackStoredSettings(enabled = enabled, baseUrl = baseUrl, apiToken = apiToken),
)
