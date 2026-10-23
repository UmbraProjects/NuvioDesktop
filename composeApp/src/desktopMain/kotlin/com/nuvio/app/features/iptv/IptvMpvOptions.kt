package com.nuvio.app.features.iptv

import java.io.File

/** How long mpv waits on a silent IPTV connection before FFmpeg reconnects or the open fails. */
internal const val IPTV_NETWORK_TIMEOUT_SECONDS = 20

/**
 * Bytes of already-played stream kept per minute of rewind. Live HD runs 4-10 Mbit/s, so 12 Mbit/s
 * (90 MB a minute) leaves headroom; mpv stops at whichever runs out first, bytes or stream.
 */
private const val IPTV_REWIND_BYTES_PER_MINUTE = 90L * 1024 * 1024

/**
 * mpv options for an IPTV stream ([IptvPlaybackSession] knows which URLs those are); empty for
 * anything else.
 *
 * - FFmpeg's `reconnect_streamed` lets a live MPEG-TS stream (a single endless, unseekable
 *   response) reconnect after a dropped connection instead of ending; the player's own reconnect
 *   (PlayerScreenRuntimeIptv) is the fallback when that fails.
 * - With an IPTV proxy set, mpv goes through [IptvProxyRelay] (mpv has no SOCKS support, and the
 *   relay keeps the proxy's password out of mpv's logged options).
 * - A rewind buffer longer than mpv's default goes to disk (`cache-on-disk`), so an hour of
 *   rewind costs disk space rather than gigabytes of RAM. mpv deletes the file when it closes.
 * - `ytdl=no`: youtube-dl has nothing to say about an IPTV stream, and its failure would bury the
 *   real error in the log.
 */
internal fun iptvMpvOptions(sourceUrl: String): List<String> {
    if (!IptvPlaybackSession.isIptvUrl(sourceUrl)) return emptyList()
    val settings = IptvRepository.settings.value
    return buildList {
        add("ytdl=no")
        add("network-timeout=$IPTV_NETWORK_TIMEOUT_SECONDS")
        add("stream-lavf-o-append=reconnect=1")
        add("stream-lavf-o-append=reconnect_streamed=1")
        add("stream-lavf-o-append=reconnect_delay_max=5")
        if (settings.proxy.enabled) add("http-proxy=${IptvProxyRelay.mpvProxyUrl()}")
    }
}

/** Whether [sourceUrl] is live TV with a rewind buffer longer than mpv's default. */
internal fun iptvRewindActive(sourceUrl: String?): Boolean =
    IptvPlaybackSession.isIptvUrl(sourceUrl) && IptvRepository.settings.value.rewindMinutes > 0

/**
 * The rewind buffer's options. Added after the buffer preset's (which also sets
 * `demuxer-max-back-bytes`), so they win; the runtime re-apply of the preset skips that property
 * while [iptvRewindActive].
 */
internal fun iptvRewindMpvOptions(sourceUrl: String): List<String> {
    if (!iptvRewindActive(sourceUrl)) return emptyList()
    val minutes = IptvRepository.settings.value.rewindMinutes
    val cacheDir = File(System.getProperty("java.io.tmpdir"), "nuvio-iptv-cache").apply { mkdirs() }
    return listOf(
        "cache=yes",
        "cache-on-disk=yes",
        "demuxer-cache-dir=${cacheDir.absolutePath}",
        "demuxer-max-back-bytes=${minutes * IPTV_REWIND_BYTES_PER_MINUTE}",
        "demuxer-seekable-cache=yes",
    )
}
