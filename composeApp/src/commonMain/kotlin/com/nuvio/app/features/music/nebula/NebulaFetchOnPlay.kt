package com.nuvio.app.features.music.nebula

import co.touchlab.kermit.Logger
import com.nuvio.app.features.music.MusicAlbum
import com.nuvio.app.features.music.MusicPlayMode
import com.nuvio.app.features.music.MusicPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val log = Logger.withTag("NebulaMusic")

/**
 * fork:nebula - fetch on Play. An album that starts playing from YouTube Music and that the music addon has is quietly
 * asked for from the addon (see [NebulaMusicSource.fetchAhead]: one track's stream, which makes the addon fetch the album
 * when it is not ready). When the addon answers, the tracks still to come swap to its lossless copy at the next track,
 * never mid-song. YouTube Music playback and resolving are untouched: it plays at once and carries on if the addon never
 * answers. Only the latest album is followed (starting another cancels the wait). Not in video mode: the addon has audio
 * only, so a video would keep playing from YouTube Music anyway and the fetch would be for nothing.
 */
internal object NebulaFetchOnPlay {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    fun start(album: MusicAlbum, player: MusicPlayer) {
        job?.cancel()
        val state = player.state.value
        if (state.mode == MusicPlayMode.Video) return
        val afterIndex = state.trackIndex
        job = scope.launch {
            val ready = try {
                NebulaMusicSource.fetchAhead(album, afterIndex)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(e) { "Fetch on Play failed for \"${album.title}\"" }
                null
            }
            if (ready != null) player.swapUpcoming(album.browseId, ready)
        }
    }
}
