package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import com.nuvio.app.features.streams.StreamItem
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal enum class StreamFailoverTrigger(val wireName: String) {
    PlaybackError("playback_error"),
    StartupTimeout("startup_timeout"),
    ProviderDiagnosticVideo("provider_diagnostic_video"),
}

/** Valid JSON support events. Source URLs, headers, hashes, and raw error text are omitted. */
internal object StreamFailoverLog {
    private val log = Logger.withTag("StreamFailover")

    fun event(name: String, fields: JsonObject = buildJsonObject {}) {
        log.i {
            Json.encodeToString(buildJsonObject {
                put("event", name)
                fields.forEach { (key, value) -> put(key, value) }
            })
        }
    }

    fun sourceFields(stream: StreamItem, sourceIndex: Int): JsonObject = buildJsonObject {
        put("sourceIndex", sourceIndex)
        put("addonId", stream.addonId)
        put("addonName", stream.addonName)
        stream.streamType?.takeIf { it.isNotBlank() }?.let { put("streamType", it) }
        put("sourceKind", if (stream.needsLocalDebridResolve) "local_resolve" else "direct")
    }
}
