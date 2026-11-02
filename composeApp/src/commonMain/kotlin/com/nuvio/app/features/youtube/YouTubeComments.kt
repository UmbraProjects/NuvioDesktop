package com.nuvio.app.features.youtube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * fork:youtube-mode
 *
 * A video's comments, read-only, as the website loads them: the WEB `next` answer for the video names
 * its comments section by a continuation token, and `next` with that token answers the first page of
 * top comments plus the token for the page after.
 *
 * Two shapes are read. The current one puts only a key in each thread (`commentViewModel.commentKey`)
 * and the comment itself in `frameworkUpdates` (`commentEntityPayload`, under that key); the older
 * one has it all in `commentThreadRenderer.comment.commentRenderer`.
 */

data class YouTubeComment(
    val id: String,
    val author: String,
    val avatarUrl: String?,
    val text: String,
    /** "3 days ago", as YouTube words it. */
    val published: String?,
    /** "1.2K", as YouTube words it; null for none. */
    val likes: String?,
    /** How many replies, as YouTube words it ("12"); null for none. Replies are not loaded. */
    val replies: String?,
    val pinned: Boolean = false,
)

data class YouTubeCommentPage(val comments: List<YouTubeComment>, val continuation: String?)

private val json = Json { ignoreUnknownKeys = true }

/** The continuation that loads the comments, from the video's own `next` answer; null when they are off. */
internal fun parseYouTubeCommentsToken(body: String): String? {
    val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return null
    // The engagement panel the website opens for comments; its content, not its header (whose sort
    // menu carries tokens of its own).
    objects(root, "engagementPanelSectionListRenderer")
        .firstOrNull { it.string("panelIdentifier") == "engagement-panel-comments-section" }
        ?.get("content")
        ?.let { content -> objects(content, "continuationItemRenderer").firstNotNullOfOrNull(::continuationToken) }
        ?.let { return it }
    // The section under the player that the website scrolls into.
    return objects(root, "itemSectionRenderer")
        .firstOrNull { it.string("sectionIdentifier") == "comment-item-section" }
        ?.let { section -> objects(section, "continuationItemRenderer").firstNotNullOfOrNull(::continuationToken) }
}

/** One page of comments from a `next` continuation answer, and the token for the next page. */
internal fun parseYouTubeComments(body: String): YouTubeCommentPage {
    val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return YouTubeCommentPage(emptyList(), null)
    val payloads = objects(root, "commentEntityPayload").associateBy { it.string("key") }
    val comments = LinkedHashMap<String, YouTubeComment>()
    for (thread in objects(root, "commentThreadRenderer")) {
        val comment = thread.obj("commentViewModel")?.obj("commentViewModel")?.let { view ->
            payloads[view.string("commentKey")]?.let(::entityComment)?.let { c ->
                // The pin is shown by the view model, not the entity.
                c.copy(pinned = view["pinnedText"] != null || c.pinned)
            }
        } ?: thread.obj("comment")?.obj("commentRenderer")?.let(::rendererComment)
        comment?.let { comments.putIfAbsent(it.id, it) }
    }
    // The next page's token sits after the threads; reply tokens are inside a thread and the sort
    // menu's in the header, so both are skipped.
    val continuation = objects(root, "continuationItemRenderer", skip = setOf("commentThreadRenderer", "commentsHeaderRenderer"))
        .firstNotNullOfOrNull(::continuationToken)
    return YouTubeCommentPage(comments.values.toList(), continuation)
}

private fun entityComment(payload: JsonObject): YouTubeComment? {
    val properties = payload.obj("properties") ?: return null
    val id = properties.string("commentId") ?: return null
    val text = properties.obj("content")?.string("content")?.takeIf { it.isNotBlank() } ?: return null
    val author = payload.obj("author")
    val toolbar = payload.obj("toolbar")
    return YouTubeComment(
        id = id,
        author = author?.string("displayName") ?: "",
        avatarUrl = author?.string("avatarThumbnailUrl"),
        text = text,
        published = properties.string("publishedTime"),
        likes = toolbar?.string("likeCountNotliked")?.trim()?.takeIf { it.isNotEmpty() && it != "0" },
        replies = toolbar?.string("replyCount")?.trim()?.takeIf { it.isNotEmpty() && it != "0" },
    )
}

private fun rendererComment(renderer: JsonObject): YouTubeComment? {
    val id = renderer.string("commentId") ?: return null
    val text = runs(renderer["contentText"])?.takeIf { it.isNotBlank() } ?: return null
    return YouTubeComment(
        id = id,
        author = runs(renderer["authorText"]).orEmpty(),
        avatarUrl = renderer.obj("authorThumbnail")?.array("thumbnails")
            ?.mapNotNull { (it as? JsonObject)?.string("url") }?.lastOrNull(),
        text = text,
        published = runs(renderer["publishedTimeText"]),
        likes = runs(renderer["voteCount"])?.takeIf { it.isNotBlank() },
        replies = renderer.int("replyCount")?.takeIf { it > 0 }?.toString(),
        pinned = renderer["pinnedCommentBadge"] != null,
    )
}

private fun continuationToken(element: JsonElement): String? = when (element) {
    is JsonObject -> element.obj("continuationCommand")?.string("token")
        ?: element.values.firstNotNullOfOrNull(::continuationToken)
    is JsonArray -> element.firstNotNullOfOrNull(::continuationToken)
    else -> null
}

/** `{simpleText}` or `{runs:[{text}]}`, flattened. */
private fun runs(element: JsonElement?): String? {
    val obj = element as? JsonObject ?: return null
    obj.string("simpleText")?.let { return it }
    return obj.array("runs")?.mapNotNull { (it as? JsonObject)?.string("text") }?.joinToString("")?.takeIf { it.isNotEmpty() }
}

/** Every object under [key], in document order, not looking inside [skip] keys. */
private fun objects(element: JsonElement, key: String, skip: Set<String> = emptySet()): List<JsonObject> {
    val found = mutableListOf<JsonObject>()
    fun walk(e: JsonElement) {
        when (e) {
            is JsonObject -> for ((k, v) in e) {
                if (k in skip) continue
                if (k == key && v is JsonObject) found += v
                walk(v)
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
private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.content?.toIntOrNull()
