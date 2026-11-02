package com.nuvio.app.features.youtube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/*
 * fork:youtube-mode
 *
 * The channels one Google sign-in can act as, from `account/accounts_list` (the website's "Switch
 * account" list). The Google account's own profile carries no page id; each brand channel does, and
 * requests name it to act as that channel (see `webRequest` in YouTubeInnerTube.kt).
 */

data class YouTubeChannelIdentity(
    val name: String,
    /** "@handle", or null for a profile without a channel. */
    val handle: String?,
    /** The brand channel's page id, or null for the Google account's own profile. */
    val pageId: String?,
    val hasChannel: Boolean,
    val photoUrl: String?,
)

private val channelsJson = Json { ignoreUnknownKeys = true }

internal fun parseYouTubeChannels(body: String): List<YouTubeChannelIdentity> {
    val root = runCatching { channelsJson.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
    val found = LinkedHashMap<String, YouTubeChannelIdentity>()
    fun walk(element: JsonElement) {
        when (element) {
            is JsonObject -> for ((key, value) in element) {
                // `accountItemRenderer` in the channel switcher, `accountItem` in the account menu.
                if ((key == "accountItemRenderer" || key == "accountItem") && value is JsonObject) {
                    accountIdentity(value)?.let { found.putIfAbsent(it.pageId ?: "", it) }
                } else {
                    walk(value)
                }
            }
            is JsonArray -> element.forEach(::walk)
            else -> Unit
        }
    }
    walk(root)
    return found.values.toList()
}

private fun accountIdentity(item: JsonObject): YouTubeChannelIdentity? {
    val name = text(item["accountName"])?.takeIf { it.isNotBlank() } ?: return null
    val handle = (text(item["channelHandle"]) ?: text(item["accountByline"])?.takeIf { it.startsWith("@") })
        ?.takeIf { it.isNotBlank() }
    val pageId = findString(item, "pageIdToken", "pageId")
    val hasChannel = findBoolean(item, "hasChannel") ?: (handle != null)
    val photo = (item["accountPhoto"] as? JsonObject)?.get("thumbnails")
        ?.let { it as? JsonArray }
        ?.mapNotNull { ((it as? JsonObject)?.get("url") as? JsonPrimitive)?.content }
        ?.lastOrNull()
    return YouTubeChannelIdentity(
        name = name,
        handle = handle,
        pageId = pageId,
        hasChannel = hasChannel,
        photoUrl = photo,
    )
}

/**
 * The channel to act as when the user has not chosen one: null to keep the account's own profile
 * (it has a channel, or there is nothing better), else the only brand channel. With several brand
 * channels and a channel-less profile there is no safe guess, so the user is asked instead.
 */
internal fun autoChannelPageId(channels: List<YouTubeChannelIdentity>): String? {
    val own = channels.firstOrNull { it.pageId == null }
    if (own?.hasChannel == true) return null
    return channels.filter { it.pageId != null && it.hasChannel }.singleOrNull()?.pageId
}

/** Several brand channels and no channel on the profile (or no profile entry at all): only the user can pick. */
internal fun channelChoiceNeeded(channels: List<YouTubeChannelIdentity>): Boolean {
    val own = channels.firstOrNull { it.pageId == null }
    return own?.hasChannel != true && channels.count { it.pageId != null && it.hasChannel } > 1
}

/** For a log line when nothing parsed: which renderers the body holds (names only, no account data). */
internal fun youTubeRendererNames(body: String): String =
    Regex(""""([A-Za-z]+(?:Renderer|ViewModel|Action))"""").findAll(body)
        .map { it.groupValues[1] }
        .distinct()
        .take(25)
        .joinToString()

private fun text(element: JsonElement?): String? {
    val obj = element as? JsonObject ?: return null
    (obj["simpleText"] as? JsonPrimitive)?.content?.let { return it }
    return (obj["runs"] as? JsonArray)
        ?.mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.content }
        ?.joinToString("")
}

/** [field] inside the first object under [container] anywhere in [element]. */
private fun findString(element: JsonElement, container: String, field: String): String? = when (element) {
    is JsonObject -> ((element[container] as? JsonObject)?.get(field) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        ?: element.values.firstNotNullOfOrNull { findString(it, container, field) }
    is JsonArray -> element.firstNotNullOfOrNull { findString(it, container, field) }
    else -> null
}

private fun findBoolean(element: JsonElement, key: String): Boolean? = when (element) {
    is JsonObject -> (element[key] as? JsonPrimitive)?.booleanOrNull
        ?: element.values.firstNotNullOfOrNull { findBoolean(it, key) }
    is JsonArray -> element.firstNotNullOfOrNull { findBoolean(it, key) }
    else -> null
}
