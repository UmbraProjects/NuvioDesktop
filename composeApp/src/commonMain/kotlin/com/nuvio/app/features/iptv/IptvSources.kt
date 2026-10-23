package com.nuvio.app.features.iptv

import java.io.Reader
import java.net.URI
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** Loads a playlist's channels and its XMLTV guide from the provider. */
internal object IptvSources {

    suspend fun loadPlaylist(playlist: IptvPlaylist): IptvPlaylistContent = when (playlist.type) {
        IptvPlaylistType.Xtream -> XtreamClient.load(playlist)
        IptvPlaylistType.M3u -> loadM3u(playlist)
    }

    private suspend fun loadM3u(playlist: IptvPlaylist): IptvPlaylistContent {
        val url = playlist.url?.trim().orEmpty()
        if (url.isEmpty()) throw IptvSourceException(IptvSourceException.Kind.NoUrl, "No M3U URL")
        if (!isHttpUrl(url)) throw IptvSourceException(IptvSourceException.Kind.BadUrl, "Bad M3U URL")
        val result = IptvHttp.get(url, IPTV_DEFAULT_HEADERS) { input ->
            input.gunzipIfNeeded().bufferedReader(Charsets.UTF_8).useLines { M3uParser.parse(it) }
        }
        return IptvPlaylistContent.build(
            playlist = playlist,
            categories = result.categories,
            channels = result.channels,
            guideUrl = playlist.epgUrl?.takeIf { it.isNotBlank() } ?: result.epgUrl,
        )
    }

    /** Downloads and reads an XMLTV guide (`.xml` or `.xml.gz`), keeping only [channels]' programmes. */
    suspend fun loadGuide(url: String, channels: List<IptvChannel>): XmltvGuide {
        if (!isHttpUrl(url)) throw IptvSourceException(IptvSourceException.Kind.BadUrl, "Bad guide URL")
        val context = coroutineContext
        return IptvHttp.get(url, IPTV_DEFAULT_HEADERS) { input ->
            val now = System.currentTimeMillis()
            XmltvGuide.build(url, channels, now - XmltvGuide.WINDOW_BEFORE_MS, now + XmltvGuide.WINDOW_AFTER_MS, now) { parser ->
                // Parsing a big guide takes seconds; stop promptly when the caller is cancelled.
                val reader = CancellableReader(input.gunzipIfNeeded().reader(Charsets.UTF_8)) { context.ensureActive() }
                parser.parse(reader.buffered(64 * 1024))
            }
        }
    }

    private fun isHttpUrl(url: String): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        return (scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty()
    }

    private class CancellableReader(private val inner: Reader, private val check: () -> Unit) : Reader() {
        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            check()
            return inner.read(cbuf, off, len)
        }

        override fun close() = inner.close()
    }
}

/** When to fetch playlists and guides again. Ported from GoatTV's `RefreshPolicy`. */
object IptvRefreshPolicy {
    /** 0 = every app launch; otherwise hours. */
    val OPTIONS = listOf(0, 3, 6, 8, 12, 24)

    const val DEFAULT_HOURS = 12

    /** Between failed attempts, so a provider that is down (or answering 429) is not hammered. */
    const val RETRY_MS = 30 * 60_000L

    fun isDue(hours: Int, fetchedAt: Long, now: Long, checkedSinceOpen: Boolean): Boolean = when {
        fetchedAt <= 0L -> true
        hours <= 0 -> !checkedSinceOpen
        else -> now - fetchedAt >= hours * 3600_000L
    }
}
