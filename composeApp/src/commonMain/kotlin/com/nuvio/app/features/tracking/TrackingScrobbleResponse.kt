package com.nuvio.app.features.tracking

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

private val watchedResponseJson = Json { ignoreUnknownKeys = true }

/**
 * True only when a provider response explicitly says that the scrobble became a watched item.
 * A successful HTTP response alone is deliberately insufficient: providers can accept a stop as
 * resumable progress (`pause`) without recording it in history.
 */
internal fun trackingScrobbleResponseConfirmsWatched(responseBody: String): Boolean {
    val response = runCatching {
        watchedResponseJson.parseToJsonElement(responseBody).jsonObject
    }.getOrNull() ?: return false

    fun stringValue(key: String): String? =
        (response[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

    if (stringValue("action").equals("scrobble", ignoreCase = true)) return true
    if (stringValue("status")?.lowercase() in setOf("watched", "completed", "scrobbled")) return true
    if ((response["watched"] as? JsonPrimitive)?.booleanOrNull == true) return true
    if ((response["completed"] as? JsonPrimitive)?.booleanOrNull == true) return true
    return stringValue("watched_at") != null
}
