package com.nuvio.app.features.streams

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

object StreamParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(
        payload: String,
        addonName: String,
        addonId: String,
    ): List<StreamItem> {
        val root = json.parseToJsonElement(payload).jsonObject
        val streamsArray = root["streams"] as? JsonArray ?: return emptyList()
        return streamsArray.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val url = obj.string("url")
            val infoHash = obj.string("infoHash")
            val externalUrl = obj.string("externalUrl")

            if (url == null && infoHash == null && externalUrl == null) return@mapNotNull null

            val hintsObj = obj["behaviorHints"] as? JsonObject
            val proxyHeaders = hintsObj
                ?.objectValue("proxyHeaders")
                ?.toProxyHeaders()
            StreamItem(
                name = obj.string("name"),
                title = obj.string("title"),
                description = obj.string("description") ?: obj.string("title"),
                url = url,
                infoHash = infoHash,
                fileIdx = obj.int("fileIdx"),
                externalUrl = externalUrl,
                sources = obj.stringList("sources"),
                addonName = addonName,
                addonId = addonId,
                behaviorHints = StreamBehaviorHints(
                    bingeGroup = hintsObj?.string("bingeGroup"),
                    notWebReady = (hintsObj?.boolean("notWebReady") ?: false) || proxyHeaders != null,
                    videoHash = hintsObj?.string("videoHash"),
                    videoSize = hintsObj?.long("videoSize"),
                    filename = hintsObj?.string("filename"),
                    proxyHeaders = proxyHeaders,
                ),
            )
        }
    }

    private fun JsonObject.string(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(name: String): Int? =
        this[name]?.jsonPrimitive?.let { primitive ->
            primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
        }

    private fun JsonObject.long(name: String): Long? =
        this[name]?.jsonPrimitive?.let { primitive ->
            primitive.longOrNull ?: primitive.contentOrNull?.toLongOrNull()
        }

    private fun JsonObject.boolean(name: String): Boolean? =
        this[name]?.jsonPrimitive?.booleanOrNull

    private fun JsonObject.objectValue(name: String): JsonObject? =
        this[name] as? JsonObject

    private fun JsonObject.stringList(name: String): List<String> =
        (this[name] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
            .orEmpty()

    private fun JsonObject.stringMap(): Map<String, String> =
        entries.mapNotNull { (key, value) ->
            (value as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { key to it }
        }.toMap()

    private fun JsonObject.toProxyHeaders(): StreamProxyHeaders? {
        val requestHeaders = objectValue("request")?.stringMap().orEmpty().takeIf { it.isNotEmpty() }
        val responseHeaders = objectValue("response")?.stringMap().orEmpty().takeIf { it.isNotEmpty() }
        if (requestHeaders == null && responseHeaders == null) {
            return null
        }
        return StreamProxyHeaders(
            request = requestHeaders,
            response = responseHeaders,
        )
    }

}
