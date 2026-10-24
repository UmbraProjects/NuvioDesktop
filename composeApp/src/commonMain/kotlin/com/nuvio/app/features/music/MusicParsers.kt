package com.nuvio.app.features.music

import com.nuvio.app.features.trailer.parseDurationSeconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Parsers for YouTube Music's InnerTube responses. They are pure (string in, models out) so they can
 * be tested against saved responses. Like the YouTube search parser they find objects by their
 * renderer name anywhere in the tree instead of following one layout path, because the layout is
 * rearranged between site versions. Anything that does not parse is skipped, never thrown.
 */

private val MUSIC_JSON = Json { ignoreUnknownKeys = true }

private const val ALBUM_PREFIX = "MPREb_"
private const val ARTIST_PREFIX = "UC"
private const val SEPARATOR = "•"

private fun parseRoot(body: String): JsonElement? = runCatching { MUSIC_JSON.parseToJsonElement(body) }.getOrNull()

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement?.at(vararg keys: String): JsonElement? {
    var current: JsonElement? = this
    for (key in keys) current = (current as? JsonObject)?.get(key) ?: return null
    return current
}

/** Every value stored under [key], anywhere below [node]. A match is not searched further. */
private fun findAll(node: JsonElement?, key: String): List<JsonObject> {
    val found = ArrayList<JsonObject>()
    fun walk(n: JsonElement?) {
        when (n) {
            is JsonObject -> n.forEach { (k, v) ->
                if (k == key && v is JsonObject) found += v else walk(v)
            }
            is JsonArray -> n.forEach(::walk)
            else -> Unit
        }
    }
    walk(node)
    return found
}

private fun runsOf(text: JsonElement?): List<JsonObject> = text.at("runs").arr()?.mapNotNull { it.obj() }.orEmpty()

private fun textOf(text: JsonElement?): String {
    text.at("simpleText").str()?.let { return it }
    return runsOf(text).joinToString("") { it["text"].str().orEmpty() }
}

private fun JsonObject.runBrowseId(): String? = at("navigationEndpoint", "browseEndpoint", "browseId").str()

/** The largest thumbnail under [node]: a `thumbnails` list runs small to large. */
private fun thumbnailOf(node: JsonElement?): String? {
    var best: String? = null
    fun walk(n: JsonElement?) {
        if (best != null) return
        when (n) {
            is JsonObject -> n.forEach { (k, v) ->
                if (k == "thumbnails" && v is JsonArray) {
                    best = v.lastOrNull().obj()?.get("url").str()
                } else {
                    walk(v)
                }
            }
            is JsonArray -> n.forEach(::walk)
            else -> Unit
        }
    }
    walk(node)
    return best
}

/** Splits a subtitle's runs into the segments between the bullet separators. */
private fun segments(runs: List<JsonObject>): List<List<JsonObject>> {
    val out = ArrayList<MutableList<JsonObject>>()
    var current = ArrayList<JsonObject>()
    for (run in runs) {
        if (run["text"].str()?.trim() == SEPARATOR) {
            if (current.isNotEmpty()) out += current
            current = ArrayList()
        } else {
            current += run
        }
    }
    if (current.isNotEmpty()) out += current
    return out
}

private fun segmentText(segment: List<JsonObject>): String = segment.joinToString("") { it["text"].str().orEmpty() }.trim()

private fun isYear(text: String) = text.length == 4 && text.all(Char::isDigit)

private fun artistsFrom(runs: List<JsonObject>): List<MusicArtistRef> =
    runs.mapNotNull { run ->
        val id = run.runBrowseId()
        val name = run["text"].str()?.trim().orEmpty()
        if (name.isEmpty()) null
        else if (id != null && id.startsWith(ARTIST_PREFIX)) MusicArtistRef(name, id)
        else null
    }.distinctBy { it.browseId }

private fun JsonObject.flexText(index: Int): JsonElement? =
    this["flexColumns"].arr()?.getOrNull(index).at("musicResponsiveListItemFlexColumnRenderer", "text")

/** Parses an album or artist list item; null when it is neither an album nor carries a title. */
private fun JsonObject.toAlbumSummary(): MusicAlbumSummary? {
    val title = textOf(flexText(0)).trim().ifEmpty { return null }
    val browseId = (runBrowseId() ?: runsOf(flexText(0)).firstNotNullOfOrNull { it.runBrowseId() })
        ?.takeIf { it.startsWith(ALBUM_PREFIX) } ?: return null
    val subtitleRuns = runsOf(flexText(1))
    val parts = segments(subtitleRuns)
    val kind = parts.firstOrNull()?.let(::segmentText)?.takeIf { it.isNotEmpty() && !isYear(it) }
    var artists = artistsFrom(subtitleRuns)
    if (artists.isEmpty()) {
        val name = parts.getOrNull(1)?.let(::segmentText)?.takeIf { it.isNotEmpty() && !isYear(it) }
        if (name != null) artists = listOf(MusicArtistRef(name))
    }
    val year = parts.map(::segmentText).firstOrNull(::isYear)
    return MusicAlbumSummary(browseId, title, artists, year, kind, thumbnailOf(this["thumbnail"]))
}

/** Albums in a search response made with the albums filter. */
internal fun parseAlbumSearch(body: String): List<MusicAlbumSummary> {
    val root = parseRoot(body) ?: return emptyList()
    return findAll(root, "musicResponsiveListItemRenderer").mapNotNull { it.toAlbumSummary() }.distinctBy { it.browseId }
}

/** Artists in a search response made with the artists filter. */
internal fun parseArtistSearch(body: String): List<MusicArtistSummary> {
    val root = parseRoot(body) ?: return emptyList()
    return findAll(root, "musicResponsiveListItemRenderer").mapNotNull { item ->
        val name = textOf(item.flexText(0)).trim().ifEmpty { return@mapNotNull null }
        val id = (item.runBrowseId() ?: runsOf(item.flexText(0)).firstNotNullOfOrNull { it.runBrowseId() })
            ?.takeIf { it.startsWith(ARTIST_PREFIX) } ?: return@mapNotNull null
        MusicArtistSummary(id, name, thumbnailOf(item["thumbnail"]))
    }.distinctBy { it.browseId }
}

private fun JsonObject.trackVideoId(): String? =
    at("playlistItemData", "videoId").str()
        ?: at("overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint", "watchEndpoint", "videoId").str()

/** An album page. Null when it has no title or no tracks. */
internal fun parseAlbum(body: String, browseId: String): MusicAlbum? {
    val root = parseRoot(body) ?: return null
    val header = findAll(root, "musicResponsiveHeaderRenderer").firstOrNull()
        ?: findAll(root, "musicDetailHeaderRenderer").firstOrNull()
    val title = textOf(header?.get("title")).trim().ifEmpty { return null }
    val subtitleRuns = runsOf(header?.get("subtitle")) + runsOf(header?.get("straplineTextOne"))
    val parts = segments(runsOf(header?.get("subtitle")))
    val kind = parts.firstOrNull()?.let(::segmentText)?.takeIf { it.isNotEmpty() && !isYear(it) }
    val year = parts.map(::segmentText).firstOrNull(::isYear)
    val artists = artistsFrom(subtitleRuns)

    val tracks = ArrayList<MusicTrack>()
    findAll(root, "musicResponsiveListItemRenderer").forEach { item ->
        val videoId = item.trackVideoId() ?: return@forEach
        val name = textOf(item.flexText(0)).trim().ifEmpty { return@forEach }
        val duration = parseDurationSeconds(
            textOf(item["fixedColumns"].arr()?.getOrNull(0).at("musicResponsiveListItemFixedColumnRenderer", "text")).trim(),
        )
        val number = textOf(item["index"]).trim().toIntOrNull() ?: (tracks.size + 1)
        val trackArtists = artistsFrom(runsOf(item.flexText(1))).ifEmpty { artists }
        val greyedOut = item["musicItemRendererDisplayPolicy"].str()?.contains("GREY_OUT") == true
        tracks += MusicTrack(videoId, name, trackArtists, duration, number, isAvailable = !greyedOut)
    }
    if (tracks.isEmpty()) return null

    val audioPlaylistId = (findAll(root, "watchEndpoint") + findAll(root, "watchPlaylistEndpoint"))
        .firstNotNullOfOrNull { it["playlistId"].str()?.takeIf { id -> id.startsWith("OLAK5uy") } }
    return MusicAlbum(
        browseId = browseId,
        title = title,
        artists = artists,
        year = year,
        kind = kind,
        thumbnailUrl = thumbnailOf(header?.get("thumbnail")),
        tracks = tracks.distinctBy { it.videoId },
        audioPlaylistId = audioPlaylistId,
    )
}

/** An album, single or EP tile of an artist page or of its "more" grid; null for playlists, videos and artists. */
private fun JsonObject.toTwoRowAlbum(self: MusicArtistRef, fallbackKind: String?): MusicAlbumSummary? {
    val id = (runBrowseId() ?: runsOf(this["title"]).firstNotNullOfOrNull { it.runBrowseId() })
        ?.takeIf { it.startsWith(ALBUM_PREFIX) } ?: return null
    val title = textOf(this["title"]).trim().ifEmpty { return null }
    val parts = segments(runsOf(this["subtitle"]))
    val kind = parts.firstOrNull()?.let(::segmentText)?.takeIf { it.isNotEmpty() && !isYear(it) } ?: fallbackKind
    val year = parts.map(::segmentText).firstOrNull(::isYear)
    return MusicAlbumSummary(id, title, listOf(self), year, kind, thumbnailOf(this["thumbnailRenderer"]))
}

/** One carousel of an artist page ("Albums", "Singles & EPs"), with the endpoint of its full list when it has one. */
internal data class ArtistShelf(
    val title: String,
    val items: List<MusicAlbumSummary>,
    val moreBrowseId: String?,
    val moreParams: String?,
)

private fun kindOfShelf(title: String): String? = when {
    title.contains("single", ignoreCase = true) -> "Single"
    title.equals("albums", ignoreCase = true) -> "Album"
    else -> null
}

/**
 * The album carousels of an artist page. A carousel shows only its first few items; when it has a
 * "more" button, [ArtistShelf.moreBrowseId] and [ArtistShelf.moreParams] name the page with all of them.
 */
internal fun parseArtistShelves(body: String, self: MusicArtistRef): List<ArtistShelf> {
    val root = parseRoot(body) ?: return emptyList()
    return findAll(root, "musicCarouselShelfRenderer").mapNotNull { shelf ->
        val header = shelf.at("header", "musicCarouselShelfBasicHeaderRenderer")
        val title = textOf(header.at("title")).trim()
        val fallback = kindOfShelf(title)
        val items = findAll(shelf, "musicTwoRowItemRenderer").mapNotNull { it.toTwoRowAlbum(self, fallback) }
        if (items.isEmpty()) return@mapNotNull null
        val more = header.at("moreContentButton", "buttonRenderer", "navigationEndpoint", "browseEndpoint")
        ArtistShelf(title, items, more.at("browseId").str(), more.at("params").str())
    }
}

/** The full list behind a carousel's "more" button: a grid of album tiles. */
internal fun parseAlbumGrid(body: String, self: MusicArtistRef, shelfTitle: String): List<MusicAlbumSummary> {
    val root = parseRoot(body) ?: return emptyList()
    val fallback = kindOfShelf(shelfTitle)
    return findAll(root, "musicTwoRowItemRenderer").mapNotNull { it.toTwoRowAlbum(self, fallback) }
}

/** An artist page: its name and the albums, singles and EPs it lists. Null when it has no name. */
internal fun parseArtist(body: String, browseId: String): MusicArtist? {
    val root = parseRoot(body) ?: return null
    val header = findAll(root, "musicImmersiveHeaderRenderer").firstOrNull()
        ?: findAll(root, "musicVisualHeaderRenderer").firstOrNull()
        ?: findAll(root, "musicHeaderRenderer").firstOrNull()
    val name = textOf(header?.get("title")).trim().ifEmpty { return null }
    val self = MusicArtistRef(name, browseId)
    val albums = findAll(root, "musicTwoRowItemRenderer").mapNotNull { it.toTwoRowAlbum(self, fallbackKind = null) }
        .distinctBy { it.browseId }
    return MusicArtist(browseId, name, thumbnailOf(header?.get("thumbnail")), albums)
}

private fun videoTypeOf(raw: String?): MusicVideoType = when (raw) {
    "MUSIC_VIDEO_TYPE_ATV" -> MusicVideoType.ArtTrack
    "MUSIC_VIDEO_TYPE_OMV" -> MusicVideoType.OfficialVideo
    "MUSIC_VIDEO_TYPE_UGC" -> MusicVideoType.UserGenerated
    else -> MusicVideoType.Other
}

private fun JsonObject.toWatchTrack(): MusicWatchTrack? {
    val id = this["videoId"].str()?.takeIf { it.length == 11 } ?: return null
    val type = videoTypeOf(
        at("navigationEndpoint", "watchEndpoint", "watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig", "musicVideoType").str(),
    )
    val counterpart = this["counterpart"].arr()?.firstOrNull()
        .at("counterpartRenderer", "playlistPanelVideoRenderer").obj()?.toWatchTrack()
    return MusicWatchTrack(
        videoId = id,
        title = textOf(this["title"]).trim(),
        durationSeconds = parseDurationSeconds(textOf(this["lengthText"]).trim()),
        videoType = type,
        counterpart = counterpart,
    )
}

/** The tracks in a watch-next ("next") response, each with its counterpart when YouTube Music links one. */
internal fun parseWatchNext(body: String): List<MusicWatchTrack> {
    val root = parseRoot(body) ?: return emptyList()
    return findAll(root, "playlistPanelVideoRenderer").mapNotNull { it.toWatchTrack() }
}

/**
 * The counterpart of [videoId] in a watch-next response: the linked video when there is one, or the
 * track itself when it already is an official video. Null when neither.
 */
internal fun parseCounterpart(body: String, videoId: String): MusicCounterpart? {
    val track = parseWatchNext(body).firstOrNull { it.videoId == videoId } ?: return null
    track.counterpart?.let {
        return MusicCounterpart(videoId, it.videoId, it.videoType, it.title, it.durationSeconds)
    }
    if (track.videoType == MusicVideoType.OfficialVideo) {
        return MusicCounterpart(videoId, track.videoId, track.videoType, track.title, track.durationSeconds)
    }
    return null
}
