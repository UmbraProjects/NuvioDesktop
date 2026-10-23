package com.nuvio.app.features.iptv

object IptvStreamFormat {
    const val TS = "ts"
    const val HLS = "m3u8"
}

/** One way to open a channel. The player tries them in order (`ts` ⇄ `m3u8` fallback). */
data class IptvStreamCandidate(val url: String, val format: String) {
    val isHls: Boolean get() = format == IptvStreamFormat.HLS
}

/**
 * The ordered attempts for [channel]. [preferred] is the user's format setting (null = Auto).
 * [allowedFormats] is the Xtream account's `allowed_output_formats` (empty = not stated).
 *
 * Ported from GoatTV's `streamCandidates`.
 */
fun iptvStreamCandidates(
    playlist: IptvPlaylist,
    channel: IptvChannel,
    preferred: String?,
    allowedFormats: Set<String> = emptySet(),
): List<IptvStreamCandidate> {
    val order = if (preferred == IptvStreamFormat.HLS) {
        listOf(IptvStreamFormat.HLS, IptvStreamFormat.TS)
    } else {
        listOf(IptvStreamFormat.TS, IptvStreamFormat.HLS)
    }

    if (playlist.type == IptvPlaylistType.Xtream) {
        val id = channel.streamId ?: return emptyList()
        // When the server says which formats it allows, the others are not worth trying.
        return order.filter { it in allowedFormats }.ifEmpty { order }
            .map { IptvStreamCandidate(XtreamUrls.streamUrl(playlist, id, it), it) }
    }

    val url = channel.url ?: return emptyList()
    val path = url.substringBefore('?').substringBefore('#')
    return when {
        path.endsWith(".m3u8", ignoreCase = true) -> listOf(
            IptvStreamCandidate(url, IptvStreamFormat.HLS),
            IptvStreamCandidate(swapExtension(url, path, ".m3u8", ".ts"), IptvStreamFormat.TS),
        )
        path.endsWith(".ts", ignoreCase = true) -> listOf(
            IptvStreamCandidate(url, IptvStreamFormat.TS),
            IptvStreamCandidate(swapExtension(url, path, ".ts", ".m3u8"), IptvStreamFormat.HLS),
        )
        // No extension: the same URL, first as HLS (the most common in M3U) unless TS is preferred.
        preferred == IptvStreamFormat.TS -> listOf(
            IptvStreamCandidate(url, IptvStreamFormat.TS),
            IptvStreamCandidate(url, IptvStreamFormat.HLS),
        )
        else -> listOf(IptvStreamCandidate(url, IptvStreamFormat.HLS), IptvStreamCandidate(url, IptvStreamFormat.TS))
    }
}

private fun swapExtension(url: String, path: String, from: String, to: String): String =
    path.dropLast(from.length) + to + url.substring(path.length)

/** HTTP headers a channel asks for (M3U `#EXTVLCOPT`), with the IPTV user agent as the default. */
fun IptvChannel.requestHeaders(): Map<String, String> = buildMap {
    put("User-Agent", userAgent?.takeIf { it.isNotBlank() } ?: IPTV_USER_AGENT)
    referrer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
}

/** Some IPTV servers block the default OkHttp / FFmpeg user agents; GoatTV sends VLC's. */
const val IPTV_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"
