package com.nuvio.app.features.music.visualiser
// FORK UI (Spyder): Nick's Now Playing visualiser, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

/*
 * fork:visualiser - how the Song Map (and the loudness read) gets at a playing track's audio. Two ways, chosen by where the
 * stream comes from:
 *
 * - A YouTube stream (googlevideo) is read straight from the stream, slice by slice, as it always was. YouTube's servers
 *   cut a plain whole-file download short or reset it (the HTPC log: 637 KB, 4 MB and 5 MB "kept" for 5 to 9 minute
 *   songs, and "Connection reset"), so a download there leaves the map unfinished.
 * - Anything else (the music addon's TorBox links) is downloaded once and read from disk, because that server answers
 *   the many slice connections with HTTP 429.
 *
 * Pure, so it is tested without the network.
 */

/** Hosts read straight from the stream: YouTube's video servers and anything else of YouTube's. */
private val STREAM_READ_HOSTS = listOf("googlevideo.com", "youtube.com", "youtu.be", "ytimg.com")

/** True when [url]'s audio should be downloaded once for analysis; false when it is read from the stream instead. */
fun spotDownloadWanted(url: String?): Boolean {
    val lower = url?.trim()?.lowercase() ?: return false
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
    if (".m3u8" in lower || "manifest" in lower) return false
    val host = lower.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':')
    return STREAM_READ_HOSTS.none { host == it || host.endsWith(".$it") }
}

/**
 * Whether a download that ended after [received] bytes is the whole file. A server that named its length must have sent
 * all of it; one that named none ([expected] negative) is trusted when it sent anything at all.
 */
fun spotDownloadComplete(received: Long, expected: Long): Boolean =
    if (expected > 0) received == expected else received > 0

/**
 * The key the album audio of a YouTube Music track keeps its map under (`yt:` and its id), so its music video can
 * borrow that map. Null for an addon track (`nebula:...`), which has no video version.
 */
fun audioMapKey(trackVideoId: String?): String? =
    trackVideoId?.trim()?.takeIf { it.isNotEmpty() && ':' !in it }?.let { "yt:$it" }

/**
 * Whether a map read over [mapMs] fits a version playing for [playingMs]: the same length within [slackMs] means the
 * same recording (a music video with an intro or a skit runs longer and gets its own map).
 */
fun sameLengthForMap(mapMs: Long, playingMs: Long, slackMs: Long = 2_000): Boolean =
    mapMs > 0 && playingMs > 0 && kotlin.math.abs(mapMs - playingMs) <= slackMs
