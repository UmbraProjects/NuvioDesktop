package com.nuvio.app.features.iptv

import java.io.File

/** How long mpv waits on a silent IPTV connection before FFmpeg reconnects or the open fails. */
internal const val IPTV_NETWORK_TIMEOUT_SECONDS = 20

/**
 * The same for an HLS channel, whose requests are a playlist or one short segment (5-10 s of
 * video): ten silent seconds on one of those means it hung, typically a new connection through a
 * flaky proxy, and the sooner FFmpeg gives up on it the less of the buffer the wait eats. A `.ts`
 * channel is a single long response that some providers are slow to start, so it keeps the longer
 * wait.
 */
internal const val IPTV_HLS_NETWORK_TIMEOUT_SECONDS = 10

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
 *   response) reconnect after a dropped connection instead of ending. The native bridge already
 *   sets it (with `reconnect` and `reconnect_delay_max`) in `stream-lavf-o` for every network
 *   source, so it is not repeated here; mpv's client API also rejects the `-append` suffix. The
 *   player's own reconnect (PlayerScreenRuntimeIptv) is the fallback when that fails.
 * - With an IPTV proxy set, mpv goes through [IptvProxyRelay] (mpv has no SOCKS support, and the
 *   relay keeps the proxy's password out of mpv's logged options).
 * - A rewind buffer longer than mpv's default goes to disk (`cache-on-disk`), so an hour of
 *   rewind costs disk space rather than gigabytes of RAM. mpv deletes the file when it closes.
 * - `ytdl=no`: youtube-dl has nothing to say about an IPTV stream, and its failure would bury the
 *   real error in the log.
 * - `force-seekable` + `demuxer-seekable-cache`: a live `.ts` channel is one endless response with
 *   no range support, so mpv marks it unseekable and refused every seek ("Cannot seek in this
 *   stream"), even into data it already held. Go live (a jump to near the end of what has been
 *   received) and the rewind window are both seeks within the demuxer cache, which these allow.
 * - `sub-lavc-o=txt_format=text`: DVB teletext subtitles decode to text rather than FFmpeg's
 *   default bitmap, a screenshot of the teletext page drawn as a blocky pixel font that ignored
 *   the subtitle style settings. As text they go through libass like any other subtitle.
 */
internal fun iptvMpvOptions(sourceUrl: String): List<String> {
    if (!IptvPlaybackSession.isIptvUrl(sourceUrl)) return emptyList()
    val settings = IptvRepository.settings.value
    return buildList {
        add("ytdl=no")
        add("network-timeout=${if (sourceUrl.isHlsPlaylistUrl()) IPTV_HLS_NETWORK_TIMEOUT_SECONDS else IPTV_NETWORK_TIMEOUT_SECONDS}")
        add(iptvDemuxerOption(sourceUrl))
        add("force-seekable=yes")
        add("demuxer-seekable-cache=yes")
        add("sub-lavc-o=txt_format=text")
        if (settings.proxy.enabled) add("http-proxy=${IptvProxyRelay.mpvProxyUrl()}")
    }
}

/**
 * Seconds of stream mpv collects before resuming from a stall. The buffer presets use 0.25s, right
 * for a file that refills at line speed; live TV refills only in real time, so resuming on a quarter
 * second meant stalling again a moment later. Waiting this long turns one delivery gap into one
 * stall, and leaves a cushion for the next. Also the initial fill, which a provider's connect burst
 * covers at once.
 */
internal const val IPTV_CACHE_PAUSE_WAIT_SECONDS = 3

/**
 * FFmpeg's `merge_pmt_versions` for the stream's MPEG-TS reader. Stitched live channels (Pluto TV's
 * ad and programme changes) can carry the next clip under new stream IDs; without this FFmpeg adds
 * them as new streams, mpv stays on the old ones, which go silent, and the picture freezes while
 * the playlist keeps refreshing. With it the new IDs feed the same streams. An HLS stream reads
 * each segment with its own MPEG-TS reader, so there it goes through `seg_format_options`
 * (bracketed: the value holds an `=`); a `.ts` stream is read by MPEG-TS directly.
 */
internal fun iptvDemuxerOption(sourceUrl: String): String {
    return if (sourceUrl.isHlsPlaylistUrl()) {
        "demuxer-lavf-o=seg_format_options=[merge_pmt_versions=1]"
    } else {
        "demuxer-lavf-o=merge_pmt_versions=1"
    }
}

/** Whether this URL's path names an HLS playlist (`.m3u8` / `.m3u`) rather than a stream. */
internal fun String.isHlsPlaylistUrl(): Boolean {
    val path = substringBefore('?').substringBefore('#').lowercase()
    return path.endsWith(".m3u8") || path.endsWith(".m3u")
}

/** Whether [sourceUrl] is live TV, whose buffer options outrank the preset's. */
internal fun iptvLiveBufferActive(sourceUrl: String?): Boolean = IptvPlaybackSession.isIptvUrl(sourceUrl)

/** Live TV's own buffer options; added after the buffer preset's, which also sets them. */
internal fun iptvLiveBufferMpvOptions(sourceUrl: String): List<String> {
    if (!iptvLiveBufferActive(sourceUrl)) return emptyList()
    return listOf("cache-pause-wait=$IPTV_CACHE_PAUSE_WAIT_SECONDS")
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
    )
}
