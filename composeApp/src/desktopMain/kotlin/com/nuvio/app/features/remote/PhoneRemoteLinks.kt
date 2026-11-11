package com.nuvio.app.features.remote

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * fork:phone-remote — what a link shared from the phone ("Send to Nuvio") opens. The share menu often hands over a
 * sentence with the link in it ("Check this out https://youtu.be/…"), so the first web or nuvio:// address in the
 * text is the one used.
 */
internal sealed interface SharedLink {
    /** youtube.com/watch, youtu.be, /shorts/, /live/ — played in the YouTube player. */
    data class YouTubeVideo(val videoId: String, val startSeconds: Int?) : SharedLink
    /** A song on music.youtube.com — played in the music player as its radio, the song first. */
    data class MusicSong(val videoId: String) : SharedLink
    /** A playlist, album or mix on music.youtube.com (`list=`) — played in the music player. */
    data class MusicList(val playlistId: String) : SharedLink
    /** nuvio:// links and watch-party invites, handled as if clicked on the PC. */
    data class App(val url: String) : SharedLink
    /** A direct media file or stream (an .mkv, .mp4, .m3u8 and the like), played as a pasted stream is. */
    data class Stream(val url: String) : SharedLink
}

internal object PhoneRemoteLinks {
    private val urlInText = Regex("""(?i)\b(?:https?|nuvio)://[^\s<>"']+""")
    private val videoId = Regex("^[A-Za-z0-9_-]{11}$")
    private val mediaExtensions = setOf("mkv", "mp4", "m4v", "webm", "avi", "mov", "ts", "m3u8", "mpd", "mp3", "flac", "m4a", "ogg", "opus", "wav")

    fun parse(text: String): SharedLink? {
        val raw = urlInText.find(text)?.value?.trimEnd('.', ',', ')', ']', '!', '?') ?: return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme == "nuvio") return SharedLink.App(raw)
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
        val query = queryOf(uri)
        val path = uri.path.orEmpty()
        val segments = path.split('/').filter { it.isNotBlank() }
        return when {
            host == "nuvio.tv" && segments.firstOrNull() == "party" -> SharedLink.App(raw)
            host == "music.youtube.com" -> {
                val id = query["v"]?.takeIf(videoId::matches)
                val list = query["list"]?.takeIf { it.isNotBlank() }
                when {
                    id != null -> SharedLink.MusicSong(id)
                    list != null -> SharedLink.MusicList(list)
                    // An album page shares as /browse/MPREb_…, which has no playlist id to play from here.
                    else -> null
                }
            }
            host == "youtu.be" -> segments.firstOrNull()?.takeIf(videoId::matches)
                ?.let { SharedLink.YouTubeVideo(it, startSeconds(query["t"])) }
            host == "youtube.com" || host.endsWith(".youtube.com") -> {
                val id = when (segments.firstOrNull()) {
                    "watch" -> query["v"]
                    "shorts", "live", "embed", "v" -> segments.getOrNull(1)
                    else -> null
                }?.takeIf(videoId::matches)
                id?.let { SharedLink.YouTubeVideo(it, startSeconds(query["t"] ?: query["start"])) }
            }
            (scheme == "http" || scheme == "https") &&
                segments.lastOrNull()?.substringAfterLast('.', "")?.lowercase() in mediaExtensions -> SharedLink.Stream(raw)
            else -> null
        }
    }

    /** "90", "90s", "1m30s", "1h2m3s" → seconds. */
    internal fun startSeconds(value: String?): Int? {
        val text = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        text.toIntOrNull()?.let { return it.takeIf { seconds -> seconds > 0 } }
        val parts = Regex("""(\d+)([hms])""").findAll(text).toList()
        if (parts.isEmpty() || parts.sumOf { it.value.length } != text.length) return null
        val total = parts.sumOf { match ->
            val amount = match.groupValues[1].toInt()
            when (match.groupValues[2]) {
                "h" -> amount * 3600
                "m" -> amount * 60
                else -> amount
            }
        }
        return total.takeIf { it > 0 }
    }

    private fun queryOf(uri: URI): Map<String, String> =
        uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.associate { pair ->
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            decode(key) to decode(value)
        }

    private fun decode(text: String): String =
        runCatching { URLDecoder.decode(text, StandardCharsets.UTF_8) }.getOrDefault(text)
}

/**
 * Decides when the phone needs a new STATE line. The phone moves the position on by itself while playing, so
 * position ticks are not sent; a change to anything else is, as is a jump in the position (a seek, a new track),
 * plus one every [checkEveryMs] as a check against drift.
 */
internal class RemoteStateThrottle(
    private val now: () -> Long = System::currentTimeMillis,
    private val jumpMs: Long = 2_000L,
    private val checkEveryMs: Long = 5_000L,
) {
    private var last: RemoteNowPlaying? = null
    private var lastAtMs = 0L
    private var sentOnce = false

    fun shouldSend(state: RemoteNowPlaying?): Boolean {
        val at = now()
        val previous = last
        val send = when {
            !sentOnce -> true
            state == null || previous == null -> state != previous
            state.copy(positionMs = 0L) != previous.copy(positionMs = 0L) -> true
            at - lastAtMs >= checkEveryMs && state.isPlaying -> true
            else -> {
                val expected = if (previous.isPlaying) previous.positionMs + (at - lastAtMs) else previous.positionMs
                kotlin.math.abs(state.positionMs - expected) > jumpMs
            }
        }
        if (send) {
            last = state
            lastAtMs = at
            sentOnce = true
        }
        return send
    }

    fun reset() {
        last = null
        lastAtMs = 0L
        sentOnce = false
    }
}
