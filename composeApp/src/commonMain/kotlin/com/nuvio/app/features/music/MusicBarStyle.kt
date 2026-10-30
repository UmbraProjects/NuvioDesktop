package com.nuvio.app.features.music

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.music.library.MusicLibraryRepository

/**
 * How the player shows itself while music plays: the dock (a small notch that opens on hover, the default), a bar along
 * the bottom, or a bigger card pinned bottom-left.
 */
@kotlinx.serialization.Serializable
enum class MusicBarStyle { Bar, Corner, Dock }

/** The style after [this] in the More panel's cycle. */
internal fun MusicBarStyle.next(): MusicBarStyle = when (this) {
    MusicBarStyle.Dock -> MusicBarStyle.Bar
    MusicBarStyle.Bar -> MusicBarStyle.Corner
    MusicBarStyle.Corner -> MusicBarStyle.Dock
}

internal val MusicBarStyle.label: String
    get() = when (this) {
        MusicBarStyle.Dock -> "Dock"
        MusicBarStyle.Bar -> "Bottom bar"
        MusicBarStyle.Corner -> "Corner card"
    }

/** The person's choice, stored with the music settings. */
@Composable
fun rememberMusicBarStyle(): MusicBarStyle {
    LaunchedEffect(Unit) { MusicLibraryRepository.ensureLoaded() }
    val state by MusicLibraryRepository.state.collectAsStateWithLifecycle()
    return state.settings.barStyle
}

fun setMusicBarStyle(style: MusicBarStyle) {
    val settings = MusicLibraryRepository.state.value.settings
    if (settings.barStyle != style || !settings.barStyleChosen) {
        MusicLibraryRepository.updateSettings(settings.copy(barStyle = style, barStyleChosen = true))
    }
}

/**
 * Whether the corner card is what shows. The bottom bar takes over where the card would be wrong: a real video draws
 * its picture in the bar, and a ladder wants the slim pill ([musicBarSlim]). The lyric view keeps the person's style
 * (it has its own Collapse; [nowPlayingOpen] is kept for the callers).
 */
@Suppress("UNUSED_PARAMETER")
internal fun musicBarCorner(state: MusicPlayerState, slimRequested: Boolean, nowPlayingOpen: Boolean, style: MusicBarStyle): Boolean =
    style == MusicBarStyle.Corner && !slimRequested && !(state.mode == MusicPlayMode.Video && !state.lyricVideo)

/**
 * Whether the dock is the player: always, in the Dock style. The lyric view and a ladder keep it, and so does a real
 * video: its picture waits off screen while the dock plays it, and the dock's Expand video opens it.
 */
@Suppress("UNUSED_PARAMETER")
internal fun musicPlayerDocked(state: MusicPlayerState, nowPlayingOpen: Boolean, style: MusicBarStyle): Boolean =
    style == MusicBarStyle.Dock
