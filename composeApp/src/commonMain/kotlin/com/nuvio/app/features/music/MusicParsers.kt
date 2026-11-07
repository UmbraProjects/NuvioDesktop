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

/** A track length as YouTube Music writes it: "3:43", "12:05", "1:02:09". */
private val TRACK_LENGTH = Regex("""^\d{1,2}(:\d{2}){1,2}$""")

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

/**
 * The "Top result" card of an unfiltered search: YouTube Music's own pick, which weighs popularity
 * (the filtered album and artist searches match titles only, so covers outrank the original).
 * [kind] is the card's first subtitle segment ("Song", "Album", "Artist", "Video"...). [artists]
 * are the linked artists of a song, video or album, or the artist itself. [albumBrowseId] is set
 * when the card is an album, [videoId] when it is a song or video (its album is then found with
 * [parseTrackAlbum]). Null when the response has no such card.
 */
data class MusicTopResult(
    val title: String,
    val kind: String?,
    val artists: List<MusicArtistRef>,
    val albumBrowseId: String?,
    val thumbnailUrl: String?,
    val videoId: String? = null,
    /** fork:music-radio - a playlist or station top match: its id without the `VL` page prefix. */
    val playlistId: String? = null,
)

internal fun parseTopResult(body: String): MusicTopResult? {
    val root = parseRoot(body) ?: return null
    val card = findAll(root, "musicCardShelfRenderer").firstOrNull() ?: return null
    val titleRuns = runsOf(card["title"])
    val title = titleRuns.joinToString("") { it["text"].str().orEmpty() }.trim().ifEmpty { return null }
    val titleBrowseId = titleRuns.firstNotNullOfOrNull { it.runBrowseId() }
    val subtitleRuns = runsOf(card["subtitle"])
    val kind = segments(subtitleRuns).firstOrNull()?.let(::segmentText)?.takeIf { it.isNotEmpty() }
    val artists = when {
        titleBrowseId != null && titleBrowseId.startsWith(ARTIST_PREFIX) -> listOf(MusicArtistRef(title, titleBrowseId))
        else -> artistsFrom(subtitleRuns)
    }
    val videoId = titleRuns.firstNotNullOfOrNull { it.at("navigationEndpoint", "watchEndpoint", "videoId").str() }
    // fork:music-radio - a playlist's page link (`VL...`), or, for a card that links no artist, album or song, the list its
    // Play button starts (a station). An artist card's Shuffle and Radio buttons also start lists, so those are not taken.
    val playlistId = titleBrowseId?.takeIf { it.startsWith("VL") }?.removePrefix("VL")
        ?: if (titleBrowseId == null && videoId == null) findAll(card, "watchPlaylistEndpoint").firstNotNullOfOrNull { it["playlistId"].str() } else null
    return MusicTopResult(
        title = title,
        kind = kind,
        artists = artists,
        albumBrowseId = titleBrowseId?.takeIf { it.startsWith(ALBUM_PREFIX) },
        thumbnailUrl = thumbnailOf(card["thumbnail"]),
        videoId = videoId,
        playlistId = playlistId,
    )
}

/**
 * The album the track [videoId] is on, from a watch-next ("next") response: the track's byline
 * reads "Artist • Album • Year" with the album linked. Null when the byline links no album (a
 * video that is not on one).
 */
internal fun parseTrackAlbum(body: String, videoId: String): MusicAlbumSummary? {
    val root = parseRoot(body) ?: return null
    val track = findAll(root, "playlistPanelVideoRenderer").firstOrNull { it["videoId"].str() == videoId } ?: return null
    val runs = runsOf(track["longBylineText"])
    val albumRun = runs.firstOrNull { it.runBrowseId()?.startsWith(ALBUM_PREFIX) == true } ?: return null
    val title = albumRun["text"].str()?.trim()?.ifEmpty { null } ?: return null
    val year = segments(runs).map(::segmentText).firstOrNull(::isYear)
    return MusicAlbumSummary(albumRun.runBrowseId()!!, title, artistsFrom(runs), year, kind = null, thumbnailOf(track["thumbnail"]))
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
    var explicit = header?.let(::hasExplicitBadge) == true
    findAll(root, "musicResponsiveListItemRenderer").forEach { item ->
        val videoId = item.trackVideoId() ?: return@forEach
        if (hasExplicitBadge(item)) explicit = true
        val name = textOf(item.flexText(0)).trim().ifEmpty { return@forEach }
        // The web layout puts the length in a fixed column; the layout sent to the app's TV user agent has no fixed
        // columns and gives it as the last flex column instead ("6.6M plays", "3:43"). Either way it reads "m:ss".
        val fixed = textOf(item["fixedColumns"].arr()?.getOrNull(0).at("musicResponsiveListItemFixedColumnRenderer", "text")).trim()
        val lengthText = fixed.takeIf { TRACK_LENGTH.matches(it) }
            ?: item["flexColumns"].arr().orEmpty().indices.reversed()
                .map { textOf(item.flexText(it)).trim() }
                .firstOrNull { TRACK_LENGTH.matches(it) }
            ?: fixed
        val duration = parseDurationSeconds(lengthText)
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
        otherVersionIds = otherVersionIds(root, browseId),
        wikipediaBlurb = albumWikipediaBlurb(root),
        explicit = explicit,
    )
}

/** fork:lenses - whether [node] carries YouTube Music's explicit ("E") badge anywhere inside it. */
internal fun hasExplicitBadge(node: JsonElement): Boolean =
    findAll(node, "musicInlineBadgeRenderer").any { badge ->
        badge.at("icon", "iconType").str()?.contains("EXPLICIT", ignoreCase = true) == true
    }

/**
 * The album page's description (`musicDescriptionShelfRenderer`) when its footer credits Wikipedia ("From Wikipedia"),
 * else null: only then is it Wikipedia text we may show as such (CC BY-SA 4.0). Null for a page with no description.
 */
internal fun albumWikipediaBlurb(root: JsonElement): String? {
    val shelf = findAll(root, "musicDescriptionShelfRenderer").firstOrNull() ?: return null
    if (!textOf(shelf["footer"]).contains("wikipedia", ignoreCase = true)) return null
    return textOf(shelf["description"]).replace(Regex("""\s+"""), " ").trim().takeIf { it.length > 40 }
}

/** The albums under an album page's "Other versions" shelf, other than the page itself. */
private fun otherVersionIds(root: JsonElement, selfId: String): List<String> =
    findAll(root, "musicCarouselShelfRenderer")
        .filter { shelf ->
            textOf(shelf.at("header", "musicCarouselShelfBasicHeaderRenderer", "title")).trim()
                .equals("Other versions", ignoreCase = true)
        }
        .flatMap { shelf -> findAll(shelf, "musicTwoRowItemRenderer") }
        .mapNotNull { item ->
            (item.runBrowseId() ?: runsOf(item["title"]).firstNotNullOfOrNull { it.runBrowseId() })
                ?.takeIf { it.startsWith(ALBUM_PREFIX) && it != selfId }
        }
        .distinct()

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
/**
 * The token for the next page of an artist's "more" grid, or null on the last page. YouTube Music
 * sends a grid 100 releases at a time (a prolific artist's singles run to several pages); the token
 * sits in the grid's own `continuations`, first in `gridRenderer`, then in each `gridContinuation`.
 */
internal fun parseGridContinuation(body: String): String? {
    val root = parseRoot(body) ?: return null
    val grids = findAll(root, "gridRenderer") + findAll(root, "gridContinuation")
    return grids.firstNotNullOfOrNull { grid ->
        grid["continuations"].arr()?.firstNotNullOfOrNull { it.at("nextContinuationData", "continuation").str() }
    }
}

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
    // fork:music-radio - the header's Radio button (or Shuffle's list, which is the same kind of mix).
    val radio = findAll(header ?: root, "watchPlaylistEndpoint").mapNotNull { it["playlistId"].str() }
        .firstOrNull { it.startsWith("RDEM") || it.startsWith("RDAO") || it.startsWith("RDAMPL") }
    return MusicArtist(browseId, name, thumbnailOf(header?.get("thumbnail")), albums, topSongsOf(root), radioPlaylistId = radio)
}

/**
 * fork:top-songs — the artist page's first list shelf ("Songs"), in the order YouTube Music gives: its most-played
 * tracks. Each row names the track and, in one of its columns, the album it belongs to.
 */
private fun topSongsOf(root: JsonElement): List<MusicTopSong> {
    val shelf = findAll(root, "musicShelfRenderer").firstOrNull() ?: return emptyList()
    return findAll(shelf, "musicResponsiveListItemRenderer").mapNotNull { item ->
        val videoId = item.trackVideoId() ?: return@mapNotNull null
        val title = textOf(item.flexText(0)).trim().ifEmpty { return@mapNotNull null }
        val album = (0..3).flatMap { runsOf(item.flexText(it)) }
            .firstNotNullOfOrNull { run -> run.runBrowseId()?.takeIf { it.startsWith(ALBUM_PREFIX) } }
        MusicTopSong(videoId, title, album)
    }
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

/**
 * fork:music-radio - one page of a radio or mix (a watch-next response, or its continuation): the queue's tracks with their
 * artists, album and cover from each row's byline ("Artist • Album • Year"; a video reads "Channel • views • likes"), and the
 * token for the next page. Null when nothing parses.
 */
internal fun parseRadioPage(body: String, firstNumber: Int = 1): MusicPlaylistPage? {
    val root = parseRoot(body) ?: return null
    val tracks = findAll(root, "playlistPanelVideoRenderer").mapIndexedNotNull { i, item ->
        val videoId = item["videoId"].str()?.takeIf { it.length == 11 } ?: return@mapIndexedNotNull null
        val title = textOf(item["title"]).trim().ifEmpty { return@mapIndexedNotNull null }
        val runs = runsOf(item["longBylineText"]).ifEmpty { runsOf(item["shortBylineText"]) }
        val artists = artistsFrom(runs).ifEmpty {
            segments(runs).firstOrNull()?.let(::segmentText)?.takeIf { it.isNotEmpty() }?.let { listOf(MusicArtistRef(it)) }.orEmpty()
        }
        val albumRun = runs.firstOrNull { it.runBrowseId()?.startsWith(ALBUM_PREFIX) == true }
        val length = textOf(item["lengthText"]).trim()
        MusicTrack(
            videoId = videoId,
            title = title,
            artists = artists,
            durationSeconds = if (TRACK_LENGTH.matches(length)) parseDurationSeconds(length) else 0,
            trackNumber = firstNumber + i,
            isAvailable = true,
            coverUrl = thumbnailOf(item["thumbnail"]),
            albumTitle = albumRun?.get("text").str()?.trim(),
            albumBrowseId = albumRun?.runBrowseId(),
        )
    }.distinctBy { it.videoId }
    val continuation = findAll(root, "nextRadioContinuationData").firstNotNullOfOrNull { it["continuation"].str() }
        ?: findAll(root, "nextContinuationData").firstNotNullOfOrNull { it["continuation"].str() }
    if (tracks.isEmpty()) return null
    val title = findAll(root, "playlistPanelRenderer").firstNotNullOfOrNull { textOf(it["title"]).trim().ifEmpty { null } }
    return MusicPlaylistPage(title, tracks, continuation)
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

// region fork:music-playlists - songs and playlists

/** One page of a YouTube Music playlist: its title (first page only), its tracks, and the token for the next page. */
data class MusicPlaylistPage(val title: String?, val tracks: List<MusicTrack>, val continuation: String?)

/**
 * A song row of a search or a playlist page. The second column holds the artists and, in a search, the album and the
 * length; a playlist puts the album in the third column and the length in a fixed column. Every run is read for an
 * artist (a `UC` link) or an album (an `MPREb_` link) wherever it sits, so either layout parses.
 */
private fun JsonObject.toListTrack(number: Int): MusicTrack? {
    val videoId = trackVideoId()
        ?: runsOf(flexText(0)).firstNotNullOfOrNull { it.at("navigationEndpoint", "watchEndpoint", "videoId").str() }
        ?: return null
    val title = textOf(flexText(0)).trim().ifEmpty { return null }
    val columns = this["flexColumns"].arr().orEmpty().indices.drop(1)
    val runs = columns.flatMap { runsOf(flexText(it)) }
    val artists = artistsFrom(runs).ifEmpty {
        // An uploaded track names its channel without a link: the first plain segment of the second column.
        segments(runsOf(flexText(1))).firstOrNull()?.let(::segmentText)?.takeIf { it.isNotEmpty() }
            ?.let { listOf(MusicArtistRef(it)) }.orEmpty()
    }
    val albumRun = runs.firstOrNull { it.runBrowseId()?.startsWith(ALBUM_PREFIX) == true }
    val fixed = textOf(this["fixedColumns"].arr()?.getOrNull(0).at("musicResponsiveListItemFixedColumnRenderer", "text")).trim()
    val lengthText = fixed.takeIf { TRACK_LENGTH.matches(it) }
        ?: runs.mapNotNull { it["text"].str()?.trim() }.firstOrNull { TRACK_LENGTH.matches(it) }
        ?: ""
    val greyedOut = this["musicItemRendererDisplayPolicy"].str()?.contains("GREY_OUT") == true
    return MusicTrack(
        videoId = videoId,
        title = title,
        artists = artists,
        durationSeconds = if (lengthText.isEmpty()) 0 else parseDurationSeconds(lengthText),
        trackNumber = number,
        isAvailable = !greyedOut,
        coverUrl = thumbnailOf(this["thumbnail"]),
        albumTitle = albumRun?.get("text").str()?.trim(),
        albumBrowseId = albumRun?.runBrowseId(),
    )
}

/** Songs in a search response made with the songs filter, best match first. */
internal fun parseSongSearch(body: String): List<MusicTrack> {
    val root = parseRoot(body) ?: return emptyList()
    return findAll(root, "musicResponsiveListItemRenderer")
        .mapIndexedNotNull { i, item -> item.toListTrack(i + 1) }
        .distinctBy { it.videoId }
}

/**
 * fork:music-home - everything one unfiltered search answer holds, sorted by kind: the Top result card, then songs,
 * albums (singles and EPs too), artists and music videos, each in the answer's order. Playlists, episodes, podcasts
 * and user profiles are left out.
 *
 * Two layouts are read. The classic one groups results in titled shelves ("Songs", "Albums", ...) whose rows do not
 * repeat the kind; the newer flat one puts each row in its own section and starts its second column with the kind
 * ("Video • Yelawolf & Caskey • 19M views"). A row's own kind word wins, then its shelf's title, then what its links
 * say (an album browse id, an artist page, the music video type).
 */
data class MusicSearchEverything(
    val top: MusicTopResult?,
    val songs: List<MusicTrack>,
    val albums: List<MusicAlbumSummary>,
    val artists: List<MusicArtistSummary>,
    val videos: List<MusicTrack>,
    /** True when no request got an answer (offline, YouTube Music unreachable), as opposed to an answer with nothing in it. */
    val failed: Boolean = false,
    /** fork:music-radio - the playlists and stations in the answer (community playlists, YouTube Music's own, radios). */
    val playlists: List<MusicPlaylistSummary> = emptyList(),
) {
    val isEmpty: Boolean get() = top == null && songs.isEmpty() && albums.isEmpty() && artists.isEmpty() && videos.isEmpty()

    companion object {
        val EMPTY = MusicSearchEverything(null, emptyList(), emptyList(), emptyList(), emptyList())
    }
}

private enum class SearchRowKind { Song, Video, Album, Artist, Other }

/** The kind a row's first word or a shelf's title names; null for anything else (an artist's name, a view count). */
private fun rowKindOfWord(word: String?): SearchRowKind? = when (word?.trim()?.lowercase()) {
    "song", "songs" -> SearchRowKind.Song
    "video", "videos" -> SearchRowKind.Video
    "album", "albums", "single", "singles", "ep", "eps" -> SearchRowKind.Album
    "artist", "artists" -> SearchRowKind.Artist
    "playlist", "playlists", "community playlists", "featured playlists", "episode", "episodes", "podcast", "podcasts",
    "profile", "profiles", "station", "stations", "top result" -> SearchRowKind.Other
    else -> null
}

private fun JsonObject.musicPageType(): String? =
    at("navigationEndpoint", "browseEndpoint", "browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType").str()

private fun JsonObject.searchRowKind(shelfTitle: String?): SearchRowKind {
    val firstWord = segments(runsOf(flexText(1))).firstOrNull()?.let(::segmentText)
    rowKindOfWord(firstWord)?.let { return it }
    rowKindOfWord(shelfTitle)?.let { return it }
    val browseId = runBrowseId() ?: runsOf(flexText(0)).firstNotNullOfOrNull { it.runBrowseId() }
    return when {
        browseId?.startsWith(ALBUM_PREFIX) == true -> SearchRowKind.Album
        musicPageType() == "MUSIC_PAGE_TYPE_ARTIST" -> SearchRowKind.Artist
        trackVideoId() != null -> when (findAll(this, "watchEndpointMusicConfig").firstNotNullOfOrNull { it["musicVideoType"].str() }) {
            "MUSIC_VIDEO_TYPE_ATV" -> SearchRowKind.Song
            null -> SearchRowKind.Song
            else -> SearchRowKind.Video
        }
        else -> SearchRowKind.Other
    }
}

/**
 * A song or video row as a track. In the flat layout the row starts "Video • channel", so an unlinked channel is the
 * segment after the kind word, not the kind word itself.
 */
private fun JsonObject.toSearchTrack(number: Int): MusicTrack? {
    val track = toListTrack(number) ?: return null
    val parts = segments(runsOf(flexText(1))).map(::segmentText)
    val startsWithKind = rowKindOfWord(parts.firstOrNull()) != null
    if (!startsWithKind || track.artists.any { it.browseId != null }) return track
    val channel = parts.getOrNull(1)?.takeIf { it.isNotEmpty() && !TRACK_LENGTH.matches(it) } ?: return track.copy(artists = emptyList())
    return track.copy(artists = listOf(MusicArtistRef(channel)))
}

private fun JsonObject.toSearchArtist(): MusicArtistSummary? {
    if (musicPageType() == "MUSIC_PAGE_TYPE_USER_CHANNEL") return null
    val name = textOf(flexText(0)).trim().ifEmpty { return null }
    val id = (runBrowseId() ?: runsOf(flexText(0)).firstNotNullOfOrNull { it.runBrowseId() })
        ?.takeIf { it.startsWith(ARTIST_PREFIX) } ?: return null
    return MusicArtistSummary(id, name, thumbnailOf(this["thumbnail"]))
}

/**
 * fork:music-radio - a playlist or station row of a search: a link to a playlist page (`VL...`, page type PLAYLIST), or a
 * row that only starts a list. Podcasts, episodes and profiles are left out (they are not music lists).
 */
private fun JsonObject.toPlaylistSummary(): MusicPlaylistSummary? {
    val pageType = musicPageType()
    if (pageType != null && pageType != "MUSIC_PAGE_TYPE_PLAYLIST") return null
    val browseId = runBrowseId() ?: runsOf(flexText(0)).firstNotNullOfOrNull { it.runBrowseId() }
    val id = browseId?.takeIf { it.startsWith("VL") }?.removePrefix("VL")
        ?: findAll(this, "watchPlaylistEndpoint").firstNotNullOfOrNull { it["playlistId"].str() }
        ?: return null
    val title = textOf(flexText(0)).trim().ifEmpty { return null }
    val subtitle = textOf(flexText(1)).trim().ifEmpty { null }
    return MusicPlaylistSummary(id, title, subtitle, thumbnailOf(this["thumbnail"]))
}

internal fun parseSearchEverything(body: String): MusicSearchEverything {
    val root = parseRoot(body) ?: return MusicSearchEverything.EMPTY
    val rows = ArrayList<Pair<JsonObject, String?>>()
    fun walk(node: JsonElement?, shelfTitle: String?) {
        when (node) {
            is JsonObject -> node.forEach { (key, value) ->
                when {
                    key == "musicResponsiveListItemRenderer" && value is JsonObject -> rows += value to shelfTitle
                    key == "musicShelfRenderer" && value is JsonObject -> walk(value, textOf(value["title"]).trim().ifEmpty { null })
                    else -> walk(value, shelfTitle)
                }
            }
            is JsonArray -> node.forEach { walk(it, shelfTitle) }
            else -> Unit
        }
    }
    walk(root, null)
    val songs = ArrayList<MusicTrack>()
    val videos = ArrayList<MusicTrack>()
    val albums = ArrayList<MusicAlbumSummary>()
    val artists = ArrayList<MusicArtistSummary>()
    val playlists = ArrayList<MusicPlaylistSummary>()
    for ((row, shelf) in rows) {
        when (row.searchRowKind(shelf)) {
            SearchRowKind.Song -> row.toSearchTrack(songs.size + 1)?.let { songs += it }
            SearchRowKind.Video -> row.toSearchTrack(videos.size + 1)?.let { videos += it }
            SearchRowKind.Album -> row.toAlbumSummary()?.let { albums += it }
            SearchRowKind.Artist -> row.toSearchArtist()?.let { artists += it }
            SearchRowKind.Other -> row.toPlaylistSummary()?.let { playlists += it }
        }
    }
    return MusicSearchEverything(
        top = parseTopResult(body),
        songs = songs.distinctBy { it.videoId },
        albums = albums.distinctBy { it.browseId },
        artists = artists.distinctBy { it.browseId },
        videos = videos.distinctBy { it.videoId },
        playlists = playlists.distinctBy { it.playlistId },
    )
}

/**
 * fork:music-home - the completions YouTube Music offers for what has been typed so far ("yela" gives "yelawolf",
 * "yelawolf best friend", ...), in its order, lower-cased as it sends them, without repeats.
 */
internal fun parseSearchSuggestions(body: String): List<String> {
    val root = parseRoot(body) ?: return emptyList()
    return findAll(root, "searchSuggestionRenderer").mapNotNull { item ->
        (item.at("navigationEndpoint", "searchEndpoint", "query").str() ?: textOf(item["suggestion"]))
            .trim().ifEmpty { null }
    }.distinct()
}

/** A playlist page (`browse` with `VL<id>`), or one of its continuation pages (no title). Null when nothing parses. */
internal fun parsePlaylistPage(body: String, firstNumber: Int = 1): MusicPlaylistPage? {
    val root = parseRoot(body) ?: return null
    val header = findAll(root, "musicResponsiveHeaderRenderer").firstOrNull()
        ?: findAll(root, "musicDetailHeaderRenderer").firstOrNull()
    val title = header?.let { textOf(it["title"]).trim() }?.takeIf { it.isNotEmpty() }
    val tracks = findAll(root, "musicResponsiveListItemRenderer")
        .mapIndexedNotNull { i, item -> item.toListTrack(firstNumber + i) }
        .distinctBy { it.videoId }
    val continuation = findAll(root, "continuationCommand").firstNotNullOfOrNull { it["token"].str() }
        ?: findAll(root, "nextContinuationData").firstNotNullOfOrNull { it["continuation"].str() }
    if (title == null && tracks.isEmpty()) return null
    return MusicPlaylistPage(title, tracks, continuation)
}

// endregion
