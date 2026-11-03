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
    /** How many replies, as YouTube words it ("12"); null for none. */
    val replies: String?,
    val pinned: Boolean = false,
    /** The continuation that loads its replies ([parseYouTubeCommentReplies]); null for none. */
    val repliesToken: String? = null,
    /** What a reply to it sends YouTube (`createReplyParams`); null when it cannot be replied to. */
    val replyParams: String? = null,
    /** The account likes it, as the page said. */
    val liked: Boolean = false,
    /** The `performCommentActionEndpoint` actions that like it and take the like back; null when it cannot be liked (signed out). */
    val likeAction: String? = null,
    val unlikeAction: String? = null,
    /** [likes] as YouTube words it with the account's like and without it, when the page gave both. */
    val likesLiked: String? = null,
    val likesNotLiked: String? = null,
)

/** The likes to show for [comment] when the account's like is [liked]: YouTube's own wording when known, else counted on. */
internal fun youTubeCommentLikes(comment: YouTubeComment, liked: Boolean): String? {
    if (liked == comment.liked) return comment.likes
    val worded = if (liked) comment.likesLiked else comment.likesNotLiked
    if (worded != null) return worded.trim().takeIf { it.isNotEmpty() && it != "0" }
    // A plain number counts on; a worded one ("1.2K") does not move for one like.
    val count = comment.likes?.trim()?.toIntOrNull() ?: if (comment.likes == null) 0 else return comment.likes
    val next = count + if (liked) 1 else -1
    return next.takeIf { it > 0 }?.toString()
}

/** [createParams]: what a new comment on the video sends YouTube (`createCommentParams`), from the first page; null when none can be made. */
data class YouTubeCommentPage(val comments: List<YouTubeComment>, val continuation: String?, val createParams: String? = null)

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
    val surfaces = entityPayloads(root)
    val comments = LinkedHashMap<String, YouTubeComment>()
    for (thread in objects(root, "commentThreadRenderer")) {
        val view = thread.obj("commentViewModel")?.obj("commentViewModel")
        val comment = view?.let { v ->
            payloads[v.string("commentKey")]?.let(::entityComment)?.let { c ->
                // The pin is shown by the view model, not the entity.
                c.copy(pinned = v["pinnedText"] != null || c.pinned).withEntityLike(v, surfaces)
            }
        } ?: thread.obj("comment")?.obj("commentRenderer")?.let(::rendererComment)
        comment?.let {
            comments.putIfAbsent(
                it.id,
                it.copy(
                    // The thread's replies section holds the token that loads them.
                    repliesToken = thread["replies"]?.let(::continuationToken),
                    replyParams = replyParams(thread, view, surfaces),
                ),
            )
        }
    }
    // The next page's token sits after the threads; reply tokens are inside a thread and the sort
    // menu's in the header, so both are skipped.
    val continuation = objects(root, "continuationItemRenderer", skip = setOf("commentThreadRenderer", "commentsHeaderRenderer"))
        .firstNotNullOfOrNull(::continuationToken)
    return YouTubeCommentPage(comments.values.toList(), continuation, createParams = findText(root, "createCommentParams"))
}

/**
 * A page of a comment's replies, from `next` with its [YouTubeComment.repliesToken]: each reply is a
 * bare `commentViewModel` (its comment under the same key in `frameworkUpdates`) or, in the older
 * shape, a `commentRenderer`. The token, when there is one, loads more of them.
 */
internal fun parseYouTubeCommentReplies(body: String): YouTubeCommentPage {
    val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return YouTubeCommentPage(emptyList(), null)
    val payloads = objects(root, "commentEntityPayload").associateBy { it.string("key") }
    val surfaces = entityPayloads(root)
    val replies = LinkedHashMap<String, YouTubeComment>()
    objects(root, "commentViewModel")
        .mapNotNull { view -> view.string("commentKey")?.let { payloads[it] }?.let(::entityComment)?.withEntityLike(view, surfaces) }
        .forEach { replies.putIfAbsent(it.id, it) }
    objects(root, "commentRenderer").mapNotNull(::rendererComment).forEach { replies.putIfAbsent(it.id, it) }
    val continuation = objects(root, "continuationItemRenderer").firstNotNullOfOrNull(::continuationToken)
    return YouTubeCommentPage(replies.values.toList(), continuation)
}

/** Every `frameworkUpdates` entity's payload, by its key: where the current shape keeps a comment's toolbar. */
private fun entityPayloads(root: JsonElement): Map<String, JsonObject> {
    val found = HashMap<String, JsonObject>()
    fun walk(e: JsonElement) {
        when (e) {
            is JsonObject -> {
                val key = e.string("entityKey")
                val payload = e.obj("payload")
                if (key != null && payload != null) found[key] = payload
                e.values.forEach(::walk)
            }
            is JsonArray -> e.forEach(::walk)
            else -> Unit
        }
    }
    walk(root)
    return found
}

/** A thread's `createReplyParams`: in its own reply button (older shape) or its toolbar entity (current shape). */
private fun replyParams(thread: JsonObject, view: JsonObject?, surfaces: Map<String, JsonObject>): String? =
    findText(thread, "createReplyParams")
        ?: view?.string("toolbarSurfaceKey")?.let { surfaces[it] }?.let { findText(it, "createReplyParams") }

/**
 * The current shape's like: the toolbar surface entity holds the like and unlike commands, the toolbar
 * state entity whether the account likes it.
 */
private fun YouTubeComment.withEntityLike(view: JsonObject, surfaces: Map<String, JsonObject>): YouTubeComment {
    val surface = view.string("toolbarSurfaceKey")?.let { surfaces[it] }
    val state = view.string("toolbarStateKey")?.let { surfaces[it] }
    val likedNow = state?.let { findText(it, "likeState") } == "TOOLBAR_LIKE_STATE_LIKED" || liked
    return copy(
        liked = likedNow,
        // The toolbar words the count both ways; the one shown is the one with the account's like as it stands.
        likes = if (likedNow) likesLiked?.trim()?.takeIf { it.isNotEmpty() && it != "0" } ?: likes else likes,
        // Each entity's payload is wrapped in its type's name, so the commands are looked for under it.
        likeAction = surface?.let { objects(it, "likeCommand").firstNotNullOfOrNull(::commentAction) } ?: likeAction,
        unlikeAction = surface?.let { objects(it, "unlikeCommand").firstNotNullOfOrNull(::commentAction) } ?: unlikeAction,
    )
}

/** The `performCommentActionEndpoint.action` under [element]; null for a sign-in prompt in its place. */
private fun commentAction(element: JsonElement): String? = objects(element, "performCommentActionEndpoint")
    .firstNotNullOfOrNull { it.string("action") }

private fun findText(element: JsonElement, key: String): String? = when (element) {
    is JsonObject -> element.string(key) ?: element.values.firstNotNullOfOrNull { findText(it, key) }
    is JsonArray -> element.firstNotNullOfOrNull { findText(it, key) }
    else -> null
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
        likesLiked = toolbar?.string("likeCountLiked"),
        likesNotLiked = toolbar?.string("likeCountNotliked"),
    )
}

private fun rendererComment(renderer: JsonObject): YouTubeComment? {
    val id = renderer.string("commentId") ?: return null
    val text = runs(renderer["contentText"])?.takeIf { it.isNotBlank() } ?: return null
    val likeButton = renderer.obj("actionButtons")?.obj("commentActionButtonsRenderer")
        ?.obj("likeButton")?.obj("toggleButtonRenderer")
    val liked = renderer["isLiked"]?.let { (it as? JsonPrimitive)?.content == "true" }
        ?: (likeButton?.get("isToggled") as? JsonPrimitive)?.content.equals("true")
    return YouTubeComment(
        liked = liked,
        likeAction = likeButton?.obj("defaultServiceEndpoint")?.let(::commentAction),
        unlikeAction = likeButton?.obj("toggledServiceEndpoint")?.let(::commentAction),
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
