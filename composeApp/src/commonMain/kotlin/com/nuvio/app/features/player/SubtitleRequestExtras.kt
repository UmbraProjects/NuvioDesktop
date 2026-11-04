package com.nuvio.app.features.player

import com.nuvio.app.features.addons.encodeAddonPathSegment
import com.nuvio.app.features.streams.StreamBehaviorHints

/**
 * What a subtitle addon is told about the file playing, as the Stremio protocol's extra path segment
 * (`/subtitles/<type>/<id>/videoHash=…&videoSize=…&filename=….json`). OpenSubtitles-style addons
 * use it to return subtitles timed to that exact release instead of any release of the title.
 * Ported from upstream (NuvioTV parity).
 */
data class SubtitleRequestExtras(
    val filename: String? = null,
    val videoSize: Long? = null,
    val videoHash: String? = null,
) {
    fun toPathSegment(): String? = buildList {
        videoHash?.trim()?.takeIf(String::isNotBlank)?.let { add("videoHash=${it.encodeAddonPathSegment()}") }
        videoSize?.takeIf { it > 0L }?.let { add("videoSize=$it") }
        filename?.trim()?.takeIf(String::isNotBlank)?.let { add("filename=${it.encodeAddonPathSegment()}") }
    }.joinToString(separator = "&").ifBlank { null }

    companion object {
        /** From the stream's hints, with the filename taken from [sourceUrl]'s last path segment when the addon gave none. */
        fun from(hints: StreamBehaviorHints?, sourceUrl: String?): SubtitleRequestExtras? =
            SubtitleRequestExtras(
                filename = hints?.filename?.takeIf { it.isNotBlank() } ?: sourceFilenameFromUrl(sourceUrl),
                videoSize = hints?.videoSize,
                videoHash = hints?.videoHash,
            ).takeIf { it.toPathSegment() != null }
    }
}

/**
 * The OpenSubtitles hash of a remote file (`size + sum of the first and last 64 KiB as little-endian
 * longs`), and its size: one HEAD and two range reads. Null when the server does not do ranges, the
 * file is under 128 KiB, or the platform has no implementation.
 */
internal expect suspend fun computeVideoHash(url: String, headers: Map<String, String>): Pair<String, Long>?

/** A URL's last path segment, without the query, when it looks like a file name (has a dot). */
internal fun sourceFilenameFromUrl(url: String?): String? =
    url?.substringBefore('?')?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() && it.contains('.') }
