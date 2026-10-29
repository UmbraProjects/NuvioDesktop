package com.nuvio.app.features.music

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.preroll.OWNER_MUSIC
import com.nuvio.app.features.preroll.PreshowAudioKeepAlive
import com.nuvio.app.features.preroll.PreshowSettings

/**
 * How long the silent stream outlives playback. Long enough to bridge a track change or an
 * audio/video switch (the player is "loading" then, which also holds it), short enough that a
 * paused album lets the receiver go back to standby.
 */
private const val MUSIC_KEEP_ALIVE_TAIL_MS = 20_000L

/** Whether music should be holding the audio output open right now. Pure, so it is testable. */
internal fun musicKeepAliveWanted(enabled: Boolean, state: MusicPlayerState, hidden: Boolean): Boolean =
    enabled && !hidden && state.isActive && state.playWhenReady

/**
 * Holds the audio output open while music plays, when [PreshowSettings.musicKeepAudioAlive] is on.
 *
 * The pre-show needed this because every trailer is a separate player that closes the output, and
 * an HDMI receiver treats the gap as the end of the stream. Music changes track and switches
 * between audio and video the same way, so it can show the same dropout. Shares the one stream
 * with the pre-show through separate owners, so neither can stop the other's hold.
 */
@Composable
internal fun MusicAudioKeepAlive(hidden: Boolean) {
    val enabled by PreshowSettings.musicKeepAudioAlive.collectAsStateWithLifecycle()
    val state by MusicPlayback.player.state.collectAsStateWithLifecycle()
    val wanted = musicKeepAliveWanted(enabled, state, hidden)
    LaunchedEffect(wanted) {
        if (wanted) PreshowAudioKeepAlive.start(OWNER_MUSIC)
        else PreshowAudioKeepAlive.stopAfter(MUSIC_KEEP_ALIVE_TAIL_MS, OWNER_MUSIC)
    }
}
