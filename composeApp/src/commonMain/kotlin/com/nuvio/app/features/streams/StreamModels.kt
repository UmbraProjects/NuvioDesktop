package com.nuvio.app.features.streams

import com.nuvio.app.core.build.AppFeaturePolicy
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

data class StreamItem(
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val externalUrl: String? = null,
    val sources: List<String> = emptyList(),
    val sourceName: String? = null,
    /** Audio languages advertised by the source. Empty means the addon did not provide metadata. */
    val audioLanguages: List<String> = emptyList(),
    val addonName: String,
    val addonId: String,
    val addonLogo: String? = null,
    val streamType: String? = null,
    val behaviorHints: StreamBehaviorHints = StreamBehaviorHints(),
    val clientResolve: StreamClientResolve? = null,
    val streamData: StreamAddonData? = null,
    val debridCacheStatus: StreamDebridCacheStatus? = null,
    /**
     * Name of the *torrent* behind an already-resolved debrid row, looked up from the provider's
     * cache check (see `LocalDebridAvailabilityService.annotateTorrentNames`).
     *
     * Kept separate from [StreamDebridCacheStatus.cachedName] on purpose: that field belongs to a
     * cache *verdict* the app computed, and these rows arrive already cached and already resolved,
     * so they never get one. Their only visible name is the single file the addon picked out —
     * which is exactly why a season pack among them looks like an episode.
     */
    val resolvedTorrentName: String? = null,
    /**
     * How many video files the source actually contains, when the provider listed them without
     * being asked to add anything (Torbox `checkcached?list_files=true`).
     *
     * This is the only pack signal that is a *fact* rather than a reading of a name: more than one
     * video file in a series torrent is a pack, whatever anything is called. Null means nobody
     * listed the contents, not that there is one file.
     */
    val sourceVideoFileCount: Int? = null,
    /**
     * How big the whole source is, from the same provider cache check that fills
     * [sourceVideoFileCount] — the torrent, not the file the addon picked out of it.
     *
     * Rides along at no extra cost and is the only folder size these rows ever get: an addon that
     * resolved one file publishes that file's size and nothing else, so without this the pack
     * indicator has a number for structured AIOStreams rows and none for already-resolved ones.
     */
    val sourceTotalSizeBytes: Long? = null,
    val badges: List<StreamBadge> = emptyList(),
) {
    val streamLabel: String
        get() = name ?: runBlocking { getString(Res.string.stream_default_name) }

    val streamSubtitle: String?
        get() = description

    val directPlaybackUrl: String?
        get() = url ?: externalUrl

    /**
     * URL supplied by the addon as media and safe to hand directly to a player.
     *
     * `externalUrl` is deliberately excluded: in the addon protocol it is a web page to open
     * (diagnostics, release notices, addon homepages), not a media resource. Treating it as a
     * fallback sent AIOStreams' GitHub homepage to mpv during autoplay/failover.
     */
    val playableDirectUrl: String?
        get() = url?.takeIf {
            it.isNotBlank() && !it.isMagnetLink() && !it.isTorrentSchemeUrl()
        }

    val torrentMagnetUri: String?
        get() = listOfNotNull(url, externalUrl, clientResolve?.magnetUri)
            .firstOrNull { it.isMagnetLink() }

    val torrentSchemeUri: String?
        get() = listOfNotNull(url, externalUrl)
            .firstOrNull { it.isTorrentSchemeUrl() }

    val isDirectDebridStream: Boolean
        get() = clientResolve?.isDirectDebridCandidate == true

    val isInstalledAddonStream: Boolean
        get() = addonId.startsWith("addon:")

    val isTorrentStream: Boolean
        get() = !isDirectDebridStream && (
            !infoHash.isNullOrBlank() ||
            url.isMagnetLink() ||
            externalUrl.isMagnetLink() ||
            url.isTorrentSchemeUrl() ||
            externalUrl.isTorrentSchemeUrl()
        )

    val isCachedDebridTorrentStream: Boolean
        get() = isTorrentStream && debridCacheStatus?.state == StreamDebridCacheState.CACHED

    val needsLocalDebridResolve: Boolean
        get() = isTorrentStream && playableDirectUrl == null

    val p2pInfoHash: String?
        get() = infoHash.normalizedInfoHash()
            ?: clientResolve?.infoHash.normalizedInfoHash()
            ?: streamData?.torrent?.infoHash.normalizedInfoHash()
            ?: torrentMagnetUri.extractBtihInfoHash()
            ?: torrentSchemeUri.extractTorrentSchemeInfoHash()

    /**
     * Infohash for re-opening the *source* behind this row, including the hash some resolvers leave
     * in an already-resolved playback URL's path — e.g. AIOStreams' `torz` links, shaped
     * `.../strem/<contentId>/<store>/<40-hex-infohash>/<fileIdx>/<filename>`. Broader than
     * [p2pInfoHash] (which reads only the structured torrent fields, so a resolved debrid link looks
     * hashless), and kept separate from it precisely so source-merge dedup, autoplay and P2P routing
     * are unaffected. Used by the season-pack grab, which needs the pack magnet even for streams the
     * addon has already turned into a direct debrid URL.
     */
    val resolvedSourceInfoHash: String?
        get() = p2pInfoHash ?: playableDirectUrl.extractPathInfoHash()

    val p2pFileIdx: Int?
        get() = fileIdx
            ?: clientResolve?.fileIdx
            ?: streamData?.torrent?.fileIdx
            ?: torrentSchemeUri.extractTorrentSchemeFileIdx()

    val p2pTrackers: List<String>
        get() = sources
            .asSequence()
            .filter { it.startsWith("tracker:") }
            .map { it.removePrefix("tracker:").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

    /**
     * The torrent's own display name, from the magnet's `dn` parameter.
     *
     * This is the name of the **torrent**, which is exactly what a pack detector wants and what an
     * addon often supplies nowhere else: rows that carry a magnet frequently label themselves with
     * the selected file, leaving `dn` as the only field that says the backing torrent is `Show.S01`.
     */
    val torrentDisplayName: String?
        get() = torrentMagnetUri?.magnetParameter("dn")

    /**
     * Total size of the torrent's contents, from the magnet's `xl` ("exact length") parameter.
     * Optional and frequently absent, but when present it plays the same role as an addon-supplied
     * folder size: much larger than the selected file means the file is not alone in there.
     */
    val torrentTotalSizeBytes: Long?
        get() = torrentMagnetUri?.magnetParameter("xl")?.toLongOrNull()?.takeIf { it > 0L }

    val isAddonDebridCandidate: Boolean
        get() = isInstalledAddonStream && (needsLocalDebridResolve || isDirectDebridStream)

    val hasPlayableSource: Boolean
        get() = url != null || infoHash != null || externalUrl != null || clientResolve != null
}

data class StreamBadge(
    val name: String,
    val imageURL: String = "",
    val tagColor: String = "",
    val tagStyle: String = "",
    val textColor: String = "",
    val borderColor: String = "",
)

fun normalizeStreamType(raw: String?): String? =
    raw?.trim()?.lowercase()?.takeIf { it.isNotBlank() }

private fun String?.isMagnetLink(): Boolean =
    this?.trimStart()?.startsWith("magnet:", ignoreCase = true) == true

private fun String?.isTorrentSchemeUrl(): Boolean =
    this?.trimStart()?.startsWith("torrent://", ignoreCase = true) == true

private fun String?.extractTorrentSchemeInfoHash(): String? {
    val raw = this?.trimStart()?.takeIf { it.isTorrentSchemeUrl() } ?: return null
    return raw.removeRange(0, "torrent://".length)
        .substringBefore('/')
        .substringBefore('?')
        .trim()
        .takeIf { it.isValidInfoHash() }
}

private fun String?.extractTorrentSchemeFileIdx(): Int? {
    val raw = this?.trimStart()?.takeIf { it.isTorrentSchemeUrl() } ?: return null
    val path = raw.removeRange(0, "torrent://".length).substringBefore('?')
    if ('/' !in path) return null
    return path.substringAfter('/')
        .trim()
        .takeIf { segment -> segment.isNotEmpty() && segment.all { it.isDigit() } }
        ?.toIntOrNull()
}

private fun String.isValidInfoHash(): Boolean =
    (length == 40 && all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) ||
        (length == 32 && all { it in '2'..'7' || it.lowercaseChar() in 'a'..'z' })

/**
 * The torrent infohash when a resolved playback URL carries it as a path segment. Scans path
 * segments only (query/fragment stripped) for the first that is a bare 40-hex or 32-char base32
 * hash — in these URLs the config blob, content id and filename never take that exact shape, so a
 * match is unambiguous.
 */
private fun String?.extractPathInfoHash(): String? {
    val path = this?.substringBefore('?')?.substringBefore('#') ?: return null
    return path.split('/')
        .firstOrNull { it.isValidInfoHash() }
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

private fun String?.normalizedInfoHash(): String? =
    this
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/** Reads a single magnet query parameter, percent-decoded. Null when absent or empty. */
private fun String.magnetParameter(name: String): String? =
    substringAfter('?', "")
        .substringBefore('#')
        .split('&')
        .firstOrNull { it.startsWith("$name=", ignoreCase = true) }
        ?.substringAfter('=')
        ?.decodePercentEncoding()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/**
 * Percent- and plus-decoding for magnet parameters. Kotlin's common stdlib has no URLDecoder, so
 * this assembles raw bytes before decoding them together — decoding each `%XX` on its own would
 * split a multi-byte UTF-8 character into replacement chars and corrupt non-English titles.
 * Malformed escapes are left as literal text rather than dropped.
 */
private fun String.decodePercentEncoding(): String {
    if ('%' !in this && '+' !in this) return this
    val bytes = ArrayList<Byte>(length)
    var index = 0
    while (index < length) {
        val char = this[index]
        val hex = if (char == '%' && index + 3 <= length) {
            substring(index + 1, index + 3).toIntOrNull(16)
        } else {
            null
        }
        when {
            hex != null -> {
                bytes += hex.toByte()
                index += 3
            }
            char == '+' -> {
                bytes += ' '.code.toByte()
                index++
            }
            else -> {
                char.toString().encodeToByteArray().forEach { bytes += it }
                index++
            }
        }
    }
    return bytes.toByteArray().decodeToString()
}

private fun String?.extractBtihInfoHash(): String? {
    val raw = this?.trim()?.takeIf { it.startsWith("magnet:", ignoreCase = true) } ?: return null
    val marker = "btih:"
    val markerIndex = raw.indexOf(marker, ignoreCase = true)
    if (markerIndex < 0) return null
    val start = markerIndex + marker.length
    val end = raw.indexOf('&', start).takeIf { it >= 0 } ?: raw.length
    return raw.substring(start, end)
        .trim()
        .takeIf { it.isNotEmpty() }
}

/**
 * True when this row is an actual video stream, and so worth scoring and ranking.
 *
 * Addons routinely inject non-stream rows into a stream response: AIOStreams' diagnostics panel, a
 * "common sense" age-rating notice, an Elfhosted "notify me when this releases" hyperlink. In the
 * addon protocol those carry only an [externalUrl] — a web page to *open*, not something to play —
 * and no video metadata at all. Scoring them yields a meaningless number, and sorting by it shuffles
 * them into the middle of the real results.
 *
 * Keyed on what the row **is** (does it have something playable behind it) rather than on how well
 * it is labelled: a genuinely badly-named release still has a url or infohash and must keep being
 * scored — the model has traits for poor labelling and they should stay able to fire.
 */
val StreamItem.isScorableStream: Boolean
    get() = url != null ||
        !infoHash.isNullOrBlank() ||
        clientResolve != null ||
        externalUrl.isMagnetLink() ||
        externalUrl.isTorrentSchemeUrl()

fun StreamItem.isSelectableForPlayback(debridEnabled: Boolean): Boolean =
    playableDirectUrl != null ||
        (AppFeaturePolicy.p2pEnabled && needsLocalDebridResolve && p2pInfoHash != null) ||
        (debridEnabled && isAddonDebridCandidate)

data class StreamBehaviorHints(
    val bingeGroup: String? = null,
    val notWebReady: Boolean = false,
    val videoHash: String? = null,
    val videoSize: Long? = null,
    val filename: String? = null,
    val proxyHeaders: StreamProxyHeaders? = null,
)

data class StreamProxyHeaders(
    val request: Map<String, String>? = null,
    val response: Map<String, String>? = null,
)

enum class StreamDebridCacheState {
    CHECKING,
    CACHED,
    NOT_CACHED,
    UNKNOWN,
}

data class StreamDebridCacheStatus(
    val providerId: String,
    val providerName: String,
    val state: StreamDebridCacheState,
    val cachedName: String? = null,
    val cachedSize: Long? = null,
)

/**
 * The part of an addon's `streamData` object the app understands.
 *
 * AIOStreams (and addons following its shape) attach structured metadata here — it is where cache
 * status actually lives, since `clientResolve` is only emitted when the addon is configured for it.
 * Without this AIOStreams exposes cache status only through its user-configurable display text,
 * which must not be parsed. Fixed-format addons may have provider-scoped compatibility detectors,
 * but those never apply to AIOStreams.
 *
 * Deliberately kept separate from [StreamDebridCacheStatus] rather than folded into it: that field
 * drives playback routing and `DebridStreamPresentation.isUncachedDebridStream`, which *removes*
 * addon streams marked NOT_CACHED. Feeding an addon's own flag into it would silently drop every
 * uncached result from the picker.
 */
data class StreamAddonData(
    /** Addon's own classification, e.g. "debrid", "usenet", "p2p". */
    val type: String? = null,
    /** Debrid service the addon resolved through, e.g. "torbox", "realdebrid". */
    val serviceId: String? = null,
    /**
     * Whether the service reports this as already cached — "instantly playable for everyone",
     * not "saved in your cloud library".
     */
    val serviceCached: Boolean? = null,
    /** Stable Usenet release URL supplied by AIOStreams when stream metadata is requested. */
    val nzbUrl: String? = null,
    /** AIOStreams' provider-independent release identity (when one is available). */
    val releaseKey: String? = null,
    /** Torrent identity nested inside AIOStreams' `streamData`. */
    val torrent: StreamAddonTorrentData? = null,
)

data class StreamAddonTorrentData(
    val infoHash: String? = null,
    val fileIdx: Int? = null,
)

data class StreamClientResolve(
    val type: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val magnetUri: String? = null,
    val sources: List<String> = emptyList(),
    val torrentName: String? = null,
    val filename: String? = null,
    val mediaType: String? = null,
    val mediaId: String? = null,
    val mediaOnlyId: String? = null,
    val title: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val service: String? = null,
    val serviceIndex: Int? = null,
    val serviceExtension: String? = null,
    val isCached: Boolean? = null,
    val stream: StreamClientResolveStream? = null,
) {
    val isDirectDebridCandidate: Boolean
        get() = type.equals("debrid", ignoreCase = true) &&
            !service.isNullOrBlank() &&
            isCached == true
}

data class StreamClientResolveStream(
    val raw: StreamClientResolveRaw? = null,
)

data class StreamClientResolveRaw(
    val torrentName: String? = null,
    val filename: String? = null,
    val size: Long? = null,
    val folderSize: Long? = null,
    val tracker: String? = null,
    val indexer: String? = null,
    val network: String? = null,
    val parsed: StreamClientResolveParsed? = null,
)

data class StreamClientResolveParsed(
    val rawTitle: String? = null,
    val parsedTitle: String? = null,
    val year: Int? = null,
    val resolution: String? = null,
    val seasons: List<Int> = emptyList(),
    val episodes: List<Int> = emptyList(),
    /**
     * The producer's own season-pack verdict, when it publishes one. AIOStreams computes this as
     * "the folder names a season and no episode, OR the file does" — folder and file are parsed
     * separately and the two verdicts are OR-ed, so a pack stays a pack even once one episode is
     * selected out of it. Authoritative where present: it is the same question our name heuristics
     * answer, already answered by whoever had the folder listing in hand.
     */
    val seasonPack: Boolean? = null,
    /**
     * Seasons and episodes parsed from the *folder* rather than the selected file, kept separate by
     * producers that parse both. A folder naming a season with no episode is a pack outright.
     */
    val folderSeasons: List<Int> = emptyList(),
    val folderEpisodes: List<Int> = emptyList(),
    val quality: String? = null,
    val hdr: List<String> = emptyList(),
    val codec: String? = null,
    val audio: List<String> = emptyList(),
    val channels: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val group: String? = null,
    val network: String? = null,
    val edition: String? = null,
    val duration: Long? = null,
    val bitDepth: String? = null,
    val extended: Boolean? = null,
    val theatrical: Boolean? = null,
    val remastered: Boolean? = null,
    val unrated: Boolean? = null,
)

data class AddonStreamGroup(
    val addonName: String,
    val addonId: String,
    val streams: List<StreamItem>,
    val isLoading: Boolean = false,
    val error: String? = null,
)

enum class StreamsEmptyStateReason {
    NoAddonsInstalled,
    NoCompatibleAddons,
    NoStreamsFound,
    StreamFetchFailed,
}

data class StreamsUiState(
    val requestToken: String? = null,
    val groups: List<AddonStreamGroup> = emptyList(),
    val activeAddonIds: Set<String> = emptySet(),
    val selectedFilter: String? = null,
    val isAnyLoading: Boolean = false,
    val emptyStateReason: StreamsEmptyStateReason? = null,
    val autoPlayStream: StreamItem? = null,
    val autoPlayCandidates: List<StreamItem> = emptyList(),
    val isDirectAutoPlayFlow: Boolean = false,
    val showDirectAutoPlayOverlay: Boolean = false,
    val overlayMessage: String? = null,
) {
    val filteredGroups: List<AddonStreamGroup>
        get() = if (selectedFilter == null) groups
                else groups.filter { it.addonId == selectedFilter }

    val allStreams: List<StreamItem>
        get() = filteredGroups.flatMap { it.streams }

    val hasAnyStreams: Boolean
        get() = groups.any { it.streams.isNotEmpty() }
}
