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
