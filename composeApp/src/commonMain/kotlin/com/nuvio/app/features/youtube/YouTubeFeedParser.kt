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
    /** The uploader's channel id (`UC...`), when the item links to it; a channel's own page does not. */
    val channelId: String? = null,
    /** A past live stream ("Streamed 13 hours ago"); streams still on air are left out of the feeds. */
    val wasLive: Boolean = false,
    /** What the item's own "Don't recommend channel" sends YouTube ([parseYouTubeFeed]); only recommendations carry one. */
    val dontRecommendToken: String? = null,
)

/** A channel, as the subscriptions list shows it. */
data class YouTubeChannel(
    val channelId: String,
    val name: String,
    val avatarUrl: String?,
    /** The line under the name, as YouTube words it ("@handle • 1.2M subscribers"). */
    val details: String?,
)

data class YouTubeChannelPage(val channels: List<YouTubeChannel>, val continuation: String?)

/** A playlist in the account's library ("WL" is Watch Later, "LL" Liked videos). */
data class YouTubePlaylist(
    val playlistId: String,
    val title: String,
    val thumbnailUrl: String?,
    /** "12 videos", as YouTube words it. */
    val details: String?,
)

/** A playlist offered by "Save to playlist", and whether the video is already in it. */
data class YouTubePlaylistOption(val playlistId: String, val title: String, val containsVideo: Boolean)

data class YouTubeFeedPage(
    val videos: List<YouTubeVideo>,
    /** Token for the next page, or null at the end of the feed. */
    val continuation: String?,
    /** The heading of the section each video sits under, by id: History's days ("Today", "Oct 12"). */
    val sections: Map<String, String> = emptyMap(),
)

private val feedJson = Json { ignoreUnknownKeys = true }

/**
 * fork:youtube-signin-expired — true when YouTube answered a signed request as signed out.
 *
 * Google does not refuse saved cookies it no longer accepts: it answers 200 with the signed-out page,
 * a home feed with nothing in it, and says so in `responseContext.serviceTrackingParams`
 * (`logged_in` = "0", checked from the cloud with dead cookies on Nov 2, 2026). The tab read that as
 * "no recommendations". Only an explicit "0" counts; an answer that says nothing is taken as signed in.
 */
internal fun youTubeAnsweredSignedOut(body: String): Boolean {
    val root = runCatching { feedJson.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return false
    val services = (root["responseContext"] as? JsonObject)?.get("serviceTrackingParams") as? JsonArray ?: return false
    val flags = services.flatMap { service ->
        ((service as? JsonObject)?.get("params") as? JsonArray).orEmpty().mapNotNull { param ->
            val obj = param as? JsonObject ?: return@mapNotNull null
            if ((obj["key"] as? JsonPrimitive)?.content != "logged_in") return@mapNotNull null
            (obj["value"] as? JsonPrimitive)?.content
        }
    }
    return flags.isNotEmpty() && flags.all { it == "0" }
}

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
    val sections = HashMap<String, String>()
    var section: String? = null

    fun add(video: YouTubeVideo?) {
        video ?: return
        if (videos.putIfAbsent(video.videoId, video) == null) section?.let { sections[video.videoId] = it }
    }

    fun walk(element: JsonElement) {
        when (element) {
            is JsonObject -> for ((key, value) in element) {
                when (key) {
                    in SKIPPED_KEYS -> Unit
                    // A section of a page; on History, one day's videos under its heading (which the
                    // skipped "header" key would otherwise leave out).
                    "itemSectionRenderer" -> (value as? JsonObject)?.let { sectionObject ->
                        val outer = section
                        section = sectionHeading(sectionObject) ?: outer
                        walk(sectionObject)
                        section = outer
                    }
                    "lockupViewModel" -> add((value as? JsonObject)?.let(::lockupVideo))
                    // compactVideoRenderer: the older shape of a watch page's related videos.
                    "videoRenderer", "gridVideoRenderer", "playlistVideoRenderer", "compactVideoRenderer" ->
                        add((value as? JsonObject)?.let(::rendererVideo))
                    "continuationItemRenderer" -> findContinuationToken(value)?.let { continuation = it }
                    else -> walk(value)
                }
            }
            is JsonArray -> element.forEach(::walk)
            else -> Unit
        }
    }
    walk(root)
    return YouTubeFeedPage(videos.values.toList(), continuation, sections)
}

/**
 * A watch page's related videos (a `next` answer for a video, or a continuation of its column): only
 * the side column (`secondaryResults`) is read on the first page, so the token is the column's own and
 * not the comments' (whose `continuationItemRenderer` sits in the primary results). A continuation
 * answer (`appendContinuationItemsAction`) carries nothing else and is read whole.
 */
internal fun parseYouTubeRelated(body: String): YouTubeFeedPage {
    val root = runCatching { feedJson.parseToJsonElement(body) }.getOrNull()
        ?: return YouTubeFeedPage(emptyList(), null)
    val column = collectObjects(root, "secondaryResults").firstOrNull() ?: return parseYouTubeFeed(body)
    return parseYouTubeFeed(column.toString())
}

/** A section's heading text (`itemSectionHeaderRenderer.title`, or a view model's `headline`), or null. */
private fun sectionHeading(section: JsonObject): String? {
    val header = section["header"] ?: return null
    return (collectObjects(header, "title") + collectObjects(header, "headline"))
        .firstNotNullOfOrNull(::text)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
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
        // The avatar and the channel name both open the channel; either carries its browse id.
        channelId = findChannelId(metadata),
        wasLive = rows.flatten().any(::isPastStream),
        dontRecommendToken = dontRecommendChannelToken(lockup),
    )
}

private fun rendererVideo(renderer: JsonObject): YouTubeVideo? {
    val videoId = renderer.string("videoId")?.takeIf { it.isNotBlank() } ?: return null
    val title = text(renderer["title"])?.takeIf { it.isNotBlank() } ?: return null
    if (renderer["upcomingEventData"] != null) return null
    // A playlist keeps rows for deleted and private videos; they cannot be played.
    if (renderer.boolean("isPlayable") == false) return null
    val badgeStyles = renderer.array("badges").orEmpty()
        .mapNotNull { (it as? JsonObject)?.obj("metadataBadgeRenderer")?.string("style") }
    val viewText = text(renderer["shortViewCountText"]) ?: text(renderer["viewCountText"])
    val isLive = badgeStyles.any { it.contains("LIVE") } || viewText?.contains("watching") == true
    if (isLive) return null
    val byline = renderer["ownerText"] ?: renderer["shortBylineText"] ?: renderer["longBylineText"]
    val channel = text(byline)
    // A playlist's rows word it as one `videoInfo` line ("1.2M views • 3 years ago") instead.
    val details = (listOfNotNull(viewText, text(renderer["publishedTimeText"])).takeIf { it.isNotEmpty() }
        ?: listOfNotNull(text(renderer["videoInfo"])))
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
        channelId = byline?.let(::findChannelId),
        wasLive = details?.split(" • ")?.any(::isPastStream) == true,
        dontRecommendToken = dontRecommendChannelToken(renderer),
    )
}

/**
 * The feedback token behind a feed item's "Don't recommend channel": a `feedbackEndpoint` under the
 * menu row of that name (a sheet's `listItemViewModel` today, a `menuServiceItemRenderer` in the older
 * layout). Matched by its English label, which the requests ask for (`hl=en`). Null where the menu has
 * no such row (subscriptions, a channel's page, a playlist).
 */
private fun dontRecommendChannelToken(item: JsonObject): String? =
    (collectObjects(item, "listItemViewModel").map { it.obj("title")?.let(::text) to it } +
        collectObjects(item, "menuServiceItemRenderer").map { text(it["text"]) to it })
        .firstOrNull { (label, _) -> label?.replace('\u2019', '\'')?.contains("don't recommend channel", ignoreCase = true) == true }
        ?.second
        ?.let { row -> collectObjects(row, "feedbackEndpoint").firstNotNullOfOrNull { it.string("feedbackToken") } }

/** The first channel (`UC...`) browse id under [element]. */
private fun findChannelId(element: JsonElement): String? =
    collectObjects(element, "browseEndpoint")
        .firstNotNullOfOrNull { endpoint -> endpoint.string("browseId")?.takeIf { it.startsWith("UC") } }

/**
 * The subscriptions list (`FEchannels`): its `channelRenderer` items (`gridChannelRenderer` on the
 * older grid) and the next page's token.
 */
internal fun parseYouTubeChannelList(body: String): YouTubeChannelPage {
    val root = runCatching { feedJson.parseToJsonElement(body) }.getOrNull() ?: return YouTubeChannelPage(emptyList(), null)
    val channels = LinkedHashMap<String, YouTubeChannel>()
    var continuation: String? = null
    fun walk(element: JsonElement) {
        when (element) {
            is JsonObject -> for ((key, value) in element) {
                when (key) {
                    "topbar", "frameworkUpdates" -> Unit
                    "channelRenderer", "gridChannelRenderer" -> (value as? JsonObject)?.let(::channelOf)
                        ?.let { channels.putIfAbsent(it.channelId, it) }
                    "continuationItemRenderer" -> findContinuationToken(value)?.let { continuation = it }
                    else -> walk(value)
                }
            }
            is JsonArray -> element.forEach(::walk)
            else -> Unit
        }
    }
    walk(root)
    return YouTubeChannelPage(channels.values.toList(), continuation)
}

private fun channelOf(renderer: JsonObject): YouTubeChannel? {
    val id = renderer.string("channelId")?.takeIf { it.startsWith("UC") } ?: return null
    val name = text(renderer["title"])?.takeIf { it.isNotBlank() } ?: return null
    val avatar = renderer.obj("thumbnail")?.array("thumbnails")
        ?.mapNotNull { (it as? JsonObject)?.string("url") }
        ?.lastOrNull()
        ?.let { if (it.startsWith("//")) "https:$it" else it }
    // On the subscriptions list `videoCountText` carries the @handle and `subscriberCountText` the count.
    val details = listOfNotNull(text(renderer["videoCountText"]), text(renderer["subscriberCountText"]))
        .filter { it.isNotBlank() }
        .joinToString(" • ")
        .ifEmpty { null }
    return YouTubeChannel(channelId = id, name = name, avatarUrl = avatar, details = details)
}

/**
 * The account's playlists (`FEplaylist_aggregation`, the website's Playlists page): its playlist
 * lockups, or the older `gridPlaylistRenderer` items.
 */
internal fun parseYouTubePlaylists(body: String): List<YouTubePlaylist> {
    val root = runCatching { feedJson.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
    val playlists = LinkedHashMap<String, YouTubePlaylist>()
    fun walk(element: JsonElement) {
        when (element) {
            is JsonObject -> for ((key, value) in element) {
                when (key) {
                    "topbar", "frameworkUpdates" -> Unit
                    "lockupViewModel" -> (value as? JsonObject)?.let(::playlistLockup)
                        ?.let { playlists.putIfAbsent(it.playlistId, it) }
                    "gridPlaylistRenderer", "playlistRenderer" -> (value as? JsonObject)?.let(::playlistRenderer)
                        ?.let { playlists.putIfAbsent(it.playlistId, it) }
                    else -> walk(value)
                }
            }
            is JsonArray -> element.forEach(::walk)
            else -> Unit
        }
    }
    walk(root)
    return playlists.values.toList()
}

private fun playlistLockup(lockup: JsonObject): YouTubePlaylist? {
    if (lockup.string("contentType") != "LOCKUP_CONTENT_TYPE_PLAYLIST") return null
    val id = lockup.string("contentId")?.takeIf { it.isNotBlank() } ?: return null
    val title = lockup.obj("metadata")?.obj("lockupMetadataViewModel")?.obj("title")?.string("content")
        ?.takeIf { it.isNotBlank() } ?: return null
    val image = lockup.obj("contentImage")
    val thumbnail = image?.let { collectObjects(it, "image") }
        ?.firstNotNullOfOrNull { it.array("sources")?.mapNotNull { s -> (s as? JsonObject)?.string("url") }?.lastOrNull() }
    val count = image?.let { collectObjects(it, "thumbnailBadgeViewModel") }
        ?.firstNotNullOfOrNull { it.string("text")?.takeIf { text -> text.any(Char::isDigit) } }
    return YouTubePlaylist(id, title, thumbnail, count)
}

private fun playlistRenderer(renderer: JsonObject): YouTubePlaylist? {
    val id = renderer.string("playlistId")?.takeIf { it.isNotBlank() } ?: return null
    val title = text(renderer["title"])?.takeIf { it.isNotBlank() } ?: return null
    val thumbnail = collectObjects(renderer, "thumbnail")
        .firstNotNullOfOrNull { it.array("thumbnails")?.mapNotNull { t -> (t as? JsonObject)?.string("url") }?.lastOrNull() }
    val count = text(renderer["videoCountShortText"]) ?: text(renderer["videoCountText"])
    return YouTubePlaylist(id, title, thumbnail, count)
}

/** The "Save to playlist" choices (`playlist/get_add_to_playlist`), with whether each holds the video already. */
internal fun parseYouTubePlaylistOptions(body: String): List<YouTubePlaylistOption> {
    val root = runCatching { feedJson.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
    return collectObjects(root, "playlistAddToOptionRenderer").mapNotNull { option ->
        val id = option.string("playlistId")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val title = text(option["title"])?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        YouTubePlaylistOption(id, title, containsVideo = option.string("containsSelectedVideos") == "ALL")
    }.distinctBy { it.playlistId }
}

/**
 * Whether the account has liked the video whose watch page (`next` answer) [body] is: its
 * `likeStatusEntity` (or an older button's `likeStatus`) says LIKE; null when the page says nothing.
 */
internal fun parseYouTubeLikeStatus(body: String): Boolean? {
    val root = runCatching { feedJson.parseToJsonElement(body) }.getOrNull() ?: return null
    val status = collectObjects(root, "likeStatusEntity").firstNotNullOfOrNull { it.string("likeStatus") }
        ?: findString(root, "likeStatus")
        ?: return null
    return status == "LIKE"
}

private fun findString(element: JsonElement, key: String): String? = when (element) {
    is JsonObject -> element.string(key) ?: element.values.firstNotNullOfOrNull { findString(it, key) }
    is JsonArray -> element.firstNotNullOfOrNull { findString(it, key) }
    else -> null
}

/**
 * Whether the account is subscribed to the channel whose page [body] is: the subscription state
 * entity the current page keeps in `frameworkUpdates`, else the older header button's flag; null
 * when the page says neither.
 */
internal fun parseYouTubeSubscribed(body: String): Boolean? {
    val root = runCatching { feedJson.parseToJsonElement(body) }.getOrNull() ?: return null
    collectObjects(root, "subscriptionStateEntity").firstNotNullOfOrNull { it.boolean("subscribed") }?.let { return it }
    return collectObjects(root, "subscribeButtonRenderer").firstNotNullOfOrNull { it.boolean("subscribed") }
}

/** A channel page's counts as YouTube words them ("1.2M subscribers", "345 videos"); either may be missing. */
data class YouTubeChannelStats(val subscribers: String?, val videos: String?)

/**
 * The subscriber and video counts in a channel page's header: the current page header's metadata
 * rows (`pageHeaderViewModel` … `metadataParts`, beside the @handle) or the older
 * `c4TabbedHeaderRenderer`'s own fields. Null when the page carries neither.
 */
internal fun parseYouTubeChannelStats(body: String): YouTubeChannelStats? {
    val root = runCatching { feedJson.parseToJsonElement(body) }.getOrNull() ?: return null
    val parts = collectObjects(root, "pageHeaderViewModel")
        .flatMap { collectObjects(it, "contentMetadataViewModel") }
        .flatMap { metadata ->
            metadata.array("metadataRows").orEmpty().flatMap { row ->
                (row as? JsonObject)?.array("metadataParts").orEmpty().mapNotNull { part ->
                    text((part as? JsonObject)?.get("text"))?.trim()
                }
            }
        }
    var subscribers = parts.firstOrNull { it.contains("subscriber", ignoreCase = true) }
    var videos = parts.firstOrNull { YOUTUBE_VIDEO_COUNT.matches(it) }
    if (subscribers == null || videos == null) {
        collectObjects(root, "c4TabbedHeaderRenderer").firstOrNull()?.let { header ->
            subscribers = subscribers ?: text(header["subscriberCountText"])
            videos = videos ?: text(header["videosCountText"])
        }
    }
    if (subscribers == null && videos == null) return null
    return YouTubeChannelStats(subscribers, videos)
}

private val YOUTUBE_VIDEO_COUNT = Regex("""^[\d.,\s]+[KMB]?\s+videos?$""", RegexOption.IGNORE_CASE)

private fun watchedPercent(raw: Int?): Int? = raw?.takeIf { it > 0 }?.coerceAtMost(100)

/** The age of a past live stream, as an English-language request words it ("Streamed 13 hours ago"). */
private fun isPastStream(part: String): Boolean = part.startsWith("Streamed", ignoreCase = true)

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
private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

/**
 * The video YouTube would play next after the watch page in [body] (a `next` answer): its own pick,
 * made on its side for this account, not the first related video. Read from the end-screen countdown
 * (`playerOverlayAutoplayRenderer`, which also names it) or, failing that, the watch column's
 * autoplay set (`autoplayVideo`, the id alone). Null when the answer carries neither.
 */
internal fun parseYouTubeAutoplay(body: String): YouTubeVideo? {
    val root = runCatching { feedJson.parseToJsonElement(body) }.getOrNull() ?: return null
    collectObjects(root, "playerOverlayAutoplayRenderer").firstNotNullOfOrNull { overlay ->
        val videoId = overlay.string("videoId")?.takeIf { it.isNotBlank() } ?: return@firstNotNullOfOrNull null
        YouTubeVideo(
            videoId = videoId,
            title = text(overlay["videoTitle"]).orEmpty(),
            channel = text(overlay["byline"]),
            details = null,
            duration = null,
            thumbnailUrl = overlay.obj("background")?.array("thumbnails")
                ?.mapNotNull { (it as? JsonObject)?.string("url") }
                ?.lastOrNull(),
        )
    }?.let { return it }
    return collectObjects(root, "autoplayVideo").firstNotNullOfOrNull { endpoint ->
        endpoint.obj("watchEndpoint")?.string("videoId")?.takeIf { it.isNotBlank() }
    }?.let { YouTubeVideo(videoId = it, title = "", channel = null, details = null, duration = null, thumbnailUrl = null) }
}

/** A chapter of a YouTube video: where it starts, in seconds, and its name. */
data class YouTubeChapter(val startSeconds: Double, val title: String)

/**
 * The chapters YouTube's own player marks on the seek bar, from a watch page's `next` answer [body]:
 * the player bar's markers (`multiMarkersPlayerBarRenderer`), the creator's own from the description
 * first and YouTube's automatic ones otherwise; failing those, the Chapters panel's list
 * (`macroMarkersListItemRenderer`). In order, starting from the first; empty when the video has none.
 */
internal fun parseYouTubeChapters(body: String): List<YouTubeChapter> {
    val root = runCatching { feedJson.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
    val markerSets = collectObjects(root, "multiMarkersPlayerBarRenderer")
        .flatMap { it.array("markersMap").orEmpty().mapNotNull { entry -> entry as? JsonObject } }
        .associate { entry -> entry.string("key").orEmpty() to entry.obj("value")?.array("chapters").orEmpty() }
    val fromBar = (markerSets["DESCRIPTION_CHAPTERS"] ?: markerSets["AUTO_CHAPTERS"] ?: markerSets.values.firstOrNull { it.isNotEmpty() })
        .orEmpty()
        .mapNotNull { item ->
            val chapter = (item as? JsonObject)?.obj("chapterRenderer") ?: return@mapNotNull null
            val startMs = (chapter["timeRangeStartMillis"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return@mapNotNull null
            val title = text(chapter["title"])?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            YouTubeChapter(startMs / 1000.0, title)
        }
    val chapters = fromBar.ifEmpty {
        collectObjects(root, "macroMarkersListItemRenderer").mapNotNull { item ->
            val start = item.obj("onTap")?.obj("watchEndpoint")?.int("startTimeSeconds") ?: return@mapNotNull null
            val title = text(item["title"])?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            YouTubeChapter(start.toDouble(), title)
        }
    }
    // One chapter marks nothing; and the same start twice (a list read from two places) only once.
    return chapters.distinctBy { it.startSeconds }.sortedBy { it.startSeconds }.takeIf { it.size >= 2 }.orEmpty()
}

/** What the watch page says about a video, under the player: for the windowed watch page. */
data class YouTubeVideoDetails(
    val title: String?,
    /** "1,234,567 views", as YouTube words it. */
    val views: String?,
    /** "Nov 2, 2026" or "Premiered 3 days ago", as YouTube words it. */
    val published: String?,
    val description: String?,
    val channelName: String?,
    val channelId: String?,
    val channelAvatarUrl: String?,
    /** "1.2M subscribers", as YouTube words it. */
    val subscribers: String?,
    val subscribed: Boolean?,
)

/**
 * The details from a video's WEB `next` answer: `videoPrimaryInfoRenderer` (title, views, date) and
 * `videoSecondaryInfoRenderer` (the channel, the description). Null when the answer has neither.
 */
internal fun parseYouTubeVideoDetails(body: String): YouTubeVideoDetails? {
    val root = runCatching { feedJson.parseToJsonElement(body) }.getOrNull() ?: return null
    val primary = collectObjects(root, "videoPrimaryInfoRenderer").firstOrNull()
    val secondary = collectObjects(root, "videoSecondaryInfoRenderer").firstOrNull()
    if (primary == null && secondary == null) return null
    val viewCount = primary?.obj("viewCount")?.obj("videoViewCountRenderer")
    val owner = secondary?.obj("owner")?.obj("videoOwnerRenderer")
    return YouTubeVideoDetails(
        title = text(primary?.get("title"))?.takeIf { it.isNotBlank() },
        views = (text(viewCount?.get("viewCount")) ?: text(viewCount?.get("shortViewCount")))?.takeIf { it.isNotBlank() },
        published = text(primary?.get("dateText"))?.takeIf { it.isNotBlank() },
        description = (secondary?.obj("attributedDescription")?.string("content") ?: text(secondary?.get("description")))
            ?.takeIf { it.isNotBlank() },
        channelName = text(owner?.get("title"))?.takeIf { it.isNotBlank() },
        channelId = owner?.let(::findChannelId),
        channelAvatarUrl = owner?.obj("thumbnail")?.array("thumbnails")
            ?.mapNotNull { (it as? JsonObject)?.string("url") }?.lastOrNull()
            ?.let { if (it.startsWith("//")) "https:$it" else it },
        subscribers = text(owner?.get("subscriberCountText"))?.takeIf { it.isNotBlank() },
        subscribed = parseYouTubeSubscribed(body),
    )
}
