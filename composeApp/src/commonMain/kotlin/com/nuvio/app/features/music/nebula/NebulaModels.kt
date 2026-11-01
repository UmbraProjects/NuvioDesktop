package com.nuvio.app.features.music.nebula

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/*
 * The music addon protocol Nebula speaks (the Eclipse Music app's): a base URL that carries the
 * person's own configuration, and three calls under it.
 *
 *   GET {base}/search?q=...        -> { tracks, albums, artists, playlists }
 *   GET {base}/album/{albumId}     -> an album with its tracks, in order
 *   GET {base}/stream/{trackId}    -> { url, format, quality }  (a direct link; for Nebula, a TorBox one)
 *
 * Only reading and parsing live here, so they are covered by unit tests. Every parser tolerates
 * missing fields and bad JSON, and gives null or an empty list instead of an error.
 */

internal data class NebulaTrack(
    val id: String,
    val title: String,
    val artist: String,
    val durationSeconds: Int,
    val isrc: String?,
)

internal data class NebulaAlbumSummary(
    val id: String,
    val title: String,
    val artist: String,
    val trackCount: Int?,
    val year: Int?,
)

internal data class NebulaAlbum(
    val summary: NebulaAlbumSummary,
    val tracks: List<NebulaTrack>,
)

internal data class NebulaStream(
    val url: String,
    val format: String?,
    val quality: String?,
)

private fun parseObject(raw: String): JsonObject? =
    runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.trackOrNull(): NebulaTrack? {
    val id = string("id") ?: return null
    val title = string("title") ?: return null
    return NebulaTrack(
        id = id,
        title = title,
        artist = string("artist").orEmpty(),
        durationSeconds = int("duration") ?: 0,
        isrc = string("isrc"),
    )
}

private fun JsonObject.albumSummaryOrNull(): NebulaAlbumSummary? {
    val id = string("id") ?: return null
    val title = string("title") ?: return null
    return NebulaAlbumSummary(id, title, string("artist").orEmpty(), int("trackCount"), int("year"))
}

/** The albums of a search response. */
internal fun parseNebulaSearchAlbums(raw: String): List<NebulaAlbumSummary> =
    ((parseObject(raw)?.get("albums")) as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonObject)?.albumSummaryOrNull() }

internal fun parseNebulaAlbum(raw: String): NebulaAlbum? {
    val root = parseObject(raw) ?: return null
    val summary = root.albumSummaryOrNull() ?: return null
    val tracks = (root["tracks"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.trackOrNull() }
    return NebulaAlbum(summary, tracks).takeIf { tracks.isNotEmpty() }
}

internal fun parseNebulaStream(raw: String): NebulaStream? {
    val root = parseObject(raw) ?: return null
    val url = root.string("url")?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return null
    return NebulaStream(url, root.string("format"), root.string("quality"))
}

/** "FLAC · 16-bit" from what the stream call reports; null when it reports neither. */
internal fun nebulaQualityLabel(format: String?, quality: String?): String? {
    val f = format?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()
    val q = quality?.trim()?.takeIf { it.isNotEmpty() }
    val parts = listOfNotNull(f, q?.takeUnless { f != null && it.equals(f, ignoreCase = true) })
    return parts.joinToString(" · ").ifEmpty { null }
}

/** The `detail` of an error response ({"detail":"Not Found"}), when there is one. */
internal fun parseNebulaDetail(raw: String): String? = parseObject(raw)?.string("detail")

/**
 * The base URL of an addon from what a person pastes: its manifest link, with or without the
 * trailing `/manifest.json`. Null for anything that is not an http(s) address.
 */
internal fun nebulaBaseUrl(pasted: String): String? {
    val trimmed = pasted.trim().substringBefore('?').trimEnd('/')
    if (!(trimmed.startsWith("https://") || trimmed.startsWith("http://"))) return null
    val base = trimmed.removeSuffix("/manifest.json").trimEnd('/')
    return base.takeIf { it.substringAfter("://").isNotEmpty() }
}

/** One music addon the person set a link for: [base] is its address, [key] a short stable name for it (see [nebulaAddonKey]). */
internal data class NebulaAddon(val key: String, val base: String)

/** The most addon links that can be set. */
internal const val NEBULA_MAX_ADDONS = 3

/**
 * A short, stable name for an addon from its address (FNV-1a, six hex digits), kept inside the ids of its tracks so
 * that moving or removing another link never points a saved track at the wrong addon.
 */
internal fun nebulaAddonKey(base: String): String {
    var hash = 0x811C9DC5L
    for (b in base.encodeToByteArray()) {
        hash = (hash xor (b.toLong() and 0xFF)) * 0x01000193L and 0xFFFFFFFFL
    }
    return hash.toString(16).padStart(8, '0').takeLast(6)
}

/**
 * The addons for the links a person pasted, in the order they were given (the order they are asked in). A blank or
 * invalid link and a repeat of an earlier one are dropped; at most [NEBULA_MAX_ADDONS] are kept.
 */
internal fun nebulaAddons(links: List<String>): List<NebulaAddon> {
    val seen = HashSet<String>()
    val out = ArrayList<NebulaAddon>()
    for (link in links) {
        val base = nebulaBaseUrl(link) ?: continue
        if (!seen.add(base)) continue
        out += NebulaAddon(nebulaAddonKey(base), base)
        if (out.size == NEBULA_MAX_ADDONS) break
    }
    return out
}

/** The id prefix of a track that comes from a music addon. */
internal const val NEBULA_TRACK_PREFIX = "nebula:"

/** A track id of the addon's own ([id]), and the addon it belongs to ([addonKey]; null for ids made before there were several addons, which mean the first). */
internal data class NebulaTrackRef(val addonKey: String?, val id: String)

private val ADDON_KEY_SHAPE = Regex("[0-9a-f]{6}")

/** The video id a player track gets for the addon's track [id]: `nebula:<id>`, or `nebula:<key>~<id>` when [addonKey] is set. */
internal fun nebulaTrackVideoId(addonKey: String?, id: String): String =
    if (addonKey == null) NEBULA_TRACK_PREFIX + id else "$NEBULA_TRACK_PREFIX$addonKey~$id"

/** What a player track's video id carries, or null when it is not an addon track's. */
internal fun nebulaTrackRef(videoId: String): NebulaTrackRef? {
    if (!videoId.startsWith(NEBULA_TRACK_PREFIX)) return null
    val rest = videoId.removePrefix(NEBULA_TRACK_PREFIX)
    val tilde = rest.indexOf('~')
    if (tilde == 6 && ADDON_KEY_SHAPE.matches(rest.substring(0, 6)) && rest.length > 7) {
        return NebulaTrackRef(rest.substring(0, 6), rest.substring(7))
    }
    return NebulaTrackRef(null, rest)
}

/**
 * Whether a refused stream request is the addon still working (TorBox fetching the file) rather than a no: a 503 that
 * says it is preparing the track or to try again, or a request that timed out. Such a request is asked again until it is
 * ready, where any other refusal is final.
 */
internal fun nebulaStreamWaitable(status: Int?, detail: String?, timedOut: Boolean): Boolean {
    if (timedOut) return true
    if (status != 503) return false
    val text = detail.orEmpty().lowercase()
    return "prepar" in text || "shortly" in text || "try again" in text || "moment" in text
}

/** A short plain reason an addon call failed, for the album page and the log. Never includes the addon's address. */
internal fun nebulaFailureReason(status: Int?, detail: String? = null, error: String? = null): String {
    val extra = detail?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
    return when {
        status == 401 || status == 403 -> "The music addon refused the request (HTTP $status). Check the addon link in Music settings.$extra"
        status == 404 -> "The music addon doesn't have this.$extra"
        status == 429 -> "The music addon is limiting requests (HTTP 429). Wait a minute."
        status != null && status >= 500 -> "The music addon is down (HTTP $status).$extra"
        status != null -> "The music addon answered HTTP $status.$extra"
        !error.isNullOrBlank() -> "Couldn't reach the music addon ($error)."
        else -> "Couldn't reach the music addon."
    }
}
