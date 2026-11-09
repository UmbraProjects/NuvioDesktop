package com.nuvio.app.features.remote

import co.touchlab.kermit.Logger
import com.nuvio.app.features.music.Lyrics
import com.nuvio.app.features.music.LyricsRepository
import com.nuvio.app.features.music.MusicPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * fork:phone-remote music (phase 8) — the music player's queue and the playing song's lyrics, for the phone's music
 * screen. Reads and drives the same player the dock and the stage do, on Main. Called on the connection threads.
 *
 * ```
 * phone: QUEUE | QUEUE_PLAY <videoId> | QUEUE_NEXT <videoId> | QUEUE_REMOVE <videoId>
 * pc:    QUEUE <playing index> <tracks>        each: videoId, title, artist, seconds, cover, upcoming 0|1
 * phone: LYRICS
 * pc:    LYRICS synced|plain|none <title> <lines>   each: time in ms (or -), text
 * ```
 */
internal object PhoneRemoteMusic {
    private val log = Logger.withTag("PhoneRemote")

    private fun <T> onMain(work: () -> T): T? = runCatching {
        runBlocking { withTimeoutOrNull(3_000L) { withContext(Dispatchers.Main) { work() } } }
    }.getOrNull()

    fun queueLine(): String = onMain {
        val player = MusicPlayback.player
        val state = player.state.value
        val (entries, playing) = player.queueView()
        val albumCover = state.album?.thumbnailUrl
        PhoneRemoteLibraryLines.queueLine(
            playing = playing,
            tracks = entries.mapIndexed { index, entry ->
                val track = entry.track
                RemoteQueueTrack(
                    videoId = track.videoId,
                    title = track.title,
                    artist = track.artists.joinToString(", ") { it.name },
                    seconds = track.durationSeconds,
                    cover = track.coverUrl ?: albumCover,
                    upcoming = index > playing,
                )
            },
        )
    } ?: PhoneRemoteLibraryLines.queueLine(-1, emptyList())

    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Plays, moves next or removes the queued track with [videoId], as the dock's Up next does, then sends the queue. */
    fun queueAction(action: String, videoId: String, reply: (String) -> Unit) {
        background.launch {
            act(action, videoId)
            // Playing another track takes a moment to land in the queue's order.
            delay(if (action == "play") 700L else 150L)
            reply(queueLine())
        }
    }

    private fun act(action: String, videoId: String) {
        onMain {
            val player = MusicPlayback.player
            val (entries, playing) = player.queueView()
            val at = entries.indexOfFirst { it.track.videoId == videoId }
            if (at < 0) return@onMain
            val entry = entries[at]
            log.i { "Phone remote: queue $action ${entry.track.title}" }
            when (action) {
                "play" -> when {
                    at > playing -> player.playUpNext(entry.track)
                    entry.inAlbum && at != playing -> player.jumpTo(entry.albumIndex)
                }
                "next" -> if (at > playing) player.playTrackNext(entry.track)
                "remove" -> if (at > playing) player.removeUpNext(entry.track)
            }
        }
    }

    private var lyricsJob: Job? = null

    /** The playing song's lyrics, looked up as the stage does (cached after the first time), handed to [reply]. */
    fun lyrics(reply: (String) -> Unit) {
        lyricsJob?.cancel()
        lyricsJob = background.launch { reply(lyricsLine()) }
    }

    private fun lyricsLine(): String {
        val (track, album) = onMain { MusicPlayback.player.state.value.let { it.track to it.album } } ?: (null to null)
        if (track == null) return PhoneRemoteLibraryLines.lyricsLine("none", null, emptyList())
        val lyrics = runCatching { runBlocking { withTimeoutOrNull(8_000L) { LyricsRepository.forTrack(track, album) } } }.getOrNull()
        return when (lyrics) {
            is Lyrics.Synced -> PhoneRemoteLibraryLines.lyricsLine("synced", track.title, lyrics.lines.map { it.timeMs to it.text })
            is Lyrics.Plain -> PhoneRemoteLibraryLines.lyricsLine("plain", track.title, lyrics.lines.map { null to it })
            else -> PhoneRemoteLibraryLines.lyricsLine("none", track.title, emptyList())
        }
    }
}
