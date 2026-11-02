package com.nuvio.app.features.youtube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * fork:youtube-mode
 *
 * Reads the video grid out of a YouTube web (WEB client) `browse` response: the home feed
 * (`FEwhat_to_watch`), the subscriptions feed (`FEsubscriptions`) and their continuation pages.
 *
 * YouTube serves two item shapes and moves feeds between them without notice, so both are read:
 * the current `lockupViewModel` and the older `videoRenderer`. Shorts, live streams, premieres and
 * the shelves mixed into the grid (`richSectionRenderer`: Shorts, news, "Breaking") are left out —
 * the first version plays ordinary uploads only.
 */

data class YouTubeVideo(
    val videoId: String,
    val title: String,
    val channel: String?,
    /** The line under the channel, as YouTube words it ("1.2M views • 3 days ago"). */
    val details: String?,
    /** "12:34", or null when the item carries no length. */
    val duration: String?,
    val thumbnailUrl: String?,
    /** How much of it this account has watched, 1-100, from YouTube's own history; null when unwatched. */
    val watchedPercent: Int? = null,
)

data class YouTubeFeedPage(
    val videos: List<YouTubeVideo>,
    /** Token for the next page, or null at the end of the feed. */
    val continuation: String?,
)

private val feedJson = Json { ignoreUnknownKeys = true }

/** Keys whose subtree never holds an ordinary upload worth listing. */
private val SKIPPED_KEYS = setOf(
    "richSectionRenderer",
    "reelShelfRenderer",
    "reelItemRenderer",
    "shortsLockupViewModel",
    "adSlotRenderer",
    // Page chrome: the masthead, the left guide and the hotkey dialog carry no videos.
    "topbar",
    "frameworkUpdates",
    "header",
)

private val DURATION_PATTERN = Regex("""^\d{1,2}(:\d{2}){1,2}$""")

internal fun parseYouTubeFeed(body: String): YouTubeFeedPage {
    val root = runCatching { feedJson.parseToJsonElement(body) }.getOrNull()
        ?: return YouTubeFeedPage(emptyList(), null)
    val videos = LinkedHashMap<String, YouTubeVideo>()
    var continuation: String? = null

    fun walk(element: JsonElement) {
        when (element) {
            is JsonObject -> for ((key, value) in element) {
                when (key) {
                    in SKIPPED_KEYS -> Unit
                    "lockupViewModel" -> (value as? JsonObject)?.let(::lockupVideo)
                        ?.let { videos.putIfAbsent(it.videoId, it) }
                    "videoRenderer", "gridVideoRenderer" -> (value as? JsonObject)?.let(::rendererVideo)
                        ?.let { videos.putIfAbsent(it.videoId, it) }
                    "continuationItemRenderer" -> findContinuationToken(value)?.let { continuation = it }
                    else -> walk(value)
                }
            }
            is JsonArray -> element.forEach(::walk)
            else -> Unit
        }
    }
    walk(root)
    return YouTubeFeedPage(videos.values.toList(), continuation)
}

private fun lockupVideo(lockup: JsonObject): YouTubeVideo? {
    if (lockup.string("contentType") != "LOCKUP_CONTENT_TYPE_VIDEO") return null
    val videoId = lockup.string("contentId")?.takeIf { it.isNotBlank() } ?: return null
    val metadata = lockup.obj("metadata")?.obj("lockupMetadataViewModel") ?: return null
    val title = metadata.obj("title")?.string("content")?.takeIf { it.isNotBlank() } ?: return null

    val image = lockup.obj("contentImage")?.obj("thumbnailViewModel")
    val badges = image?.let { collectObjects(it, "thumbnailBadgeViewModel") }.orEmpty()
    val isLive = badges.any { badge ->
        badge.string("badgeStyle")?.contains("LIVE") == true ||
            badge.string("text")?.equals("LIVE", ignoreCase = true) == true
    }
    if (isLive) return null
    val duration = badges.firstNotNullOfOrNull { badge ->
        badge.string("text")?.trim()?.takeIf { DURATION_PATTERN.matches(it) }
    }
    val thumbnail = image?.obj("image")?.array("sources")
        ?.mapNotNull { (it as? JsonObject)?.string("url") }
        ?.lastOrNull()
    // `thumbnailOverlayProgressBarViewModel.startPercent`; read wherever it sits under the image, as the
    // overlay wrappers around it have changed before.
    val watched = image?.let { findInt(it, "startPercent") }

    val rows = metadata.obj("metadata")?.obj("contentMetadataViewModel")?.array("metadataRows")
        ?.mapNotNull { row ->
            (row as? JsonObject)?.array("metadataParts")
                ?.mapNotNull { part -> (part as? JsonObject)?.obj("text")?.string("content")?.trim() }
                ?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }
        }
        .orEmpty()
    if (rows.flatten().any(::isUpcoming)) return null
    // Feeds put the channel on its own first row; a channel's own page has only the views row.
    val channel = if (rows.size >= 2) rows.first().joinToString(" • ") else null
    val details = (if (rows.size >= 2) rows.drop(1) else rows).flatten().joinToString(" • ").ifEmpty { null }

    return YouTubeVideo(
        videoId = videoId,
        title = title,
        channel = channel,
        details = details,
        duration = duration,
        thumbnailUrl = thumbnail,
        watchedPercent = watchedPercent(watched),
    )
}

private fun rendererVideo(renderer: JsonObject): YouTubeVideo? {
    val videoId = renderer.string("videoId")?.takeIf { it.isNotBlank() } ?: return null
    val title = text(renderer["title"])?.takeIf { it.isNotBlank() } ?: return null
    if (renderer["upcomingEventData"] != null) return null
    val badgeStyles = renderer.array("badges").orEmpty()
        .mapNotNull { (it as? JsonObject)?.obj("metadataBadgeRenderer")?.string("style") }
    val viewText = text(renderer["shortViewCountText"]) ?: text(renderer["viewCountText"])
    val isLive = badgeStyles.any { it.contains("LIVE") } || viewText?.contains("watching") == true
    if (isLive) return null
    val channel = text(renderer["ownerText"]) ?: text(renderer["shortBylineText"]) ?: text(renderer["longBylineText"])
    val details = listOfNotNull(viewText, text(renderer["publishedTimeText"]))
        .filter { it.isNotBlank() }
        .joinToString(" • ")
        .ifEmpty { null }
    val thumbnail = renderer.obj("thumbnail")?.array("thumbnails")
        ?.mapNotNull { (it as? JsonObject)?.string("url") }
        ?.lastOrNull()
    return YouTubeVideo(
        videoId = videoId,
        title = title,
        channel = channel,
        details = details,
        duration = text(renderer["lengthText"])?.trim()?.takeIf { DURATION_PATTERN.matches(it) },
        thumbnailUrl = thumbnail,
        watchedPercent = watchedPercent(
            collectObjects(renderer, "thumbnailOverlayResumePlaybackRenderer")
                .firstNotNullOfOrNull { it.int("percentDurationWatched") },
        ),
    )
}

private fun watchedPercent(raw: Int?): Int? = raw?.takeIf { it > 0 }?.coerceAtMost(100)

private fun isUpcoming(part: String): Boolean =
    part.startsWith("Scheduled for", ignoreCase = true) || part.startsWith("Premieres", ignoreCase = true)

/** `{simpleText}`, `{runs:[{text}]}` or `{content}`, flattened to a string. */
private fun text(element: JsonElement?): String? {
    val obj = element as? JsonObject ?: return null
    obj.string("simpleText")?.let { return it }
    obj.string("content")?.let { return it }
    return obj.array("runs")
        ?.mapNotNull { (it as? JsonObject)?.string("text") }
        ?.joinToString("")
        ?.takeIf { it.isNotEmpty() }
}

private fun findContinuationToken(element: JsonElement): String? = when (element) {
    is JsonObject -> element.obj("continuationCommand")?.string("token")
        ?: element.values.firstNotNullOfOrNull(::findContinuationToken)
    is JsonArray -> element.firstNotNullOfOrNull(::findContinuationToken)
    else -> null
}

private fun findInt(element: JsonElement, key: String): Int? = when (element) {
    is JsonObject -> element.int(key) ?: element.values.firstNotNullOfOrNull { findInt(it, key) }
    is JsonArray -> element.firstNotNullOfOrNull { findInt(it, key) }
    else -> null
}

private fun collectObjects(element: JsonElement, key: String): List<JsonObject> {
    val found = mutableListOf<JsonObject>()
    fun walk(e: JsonElement) {
        when (e) {
            is JsonObject -> for ((k, v) in e) {
                if (k == key && v is JsonObject) found += v else walk(v)
            }
            is JsonArray -> e.forEach(::walk)
            else -> Unit
        }
    }
    walk(element)
    return found
}

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()
private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
