package com.nuvio.app.features.music

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.music.library.MusicLibraryRepository

/** How the player shows itself while music plays: a bar along the bottom, or a bigger card pinned bottom-left. */
@kotlinx.serialization.Serializable
enum class MusicBarStyle { Bar, Corner }

/** The person's choice, stored with the music settings. */
@Composable
fun rememberMusicBarStyle(): MusicBarStyle {
    LaunchedEffect(Unit) { MusicLibraryRepository.ensureLoaded() }
    val state by MusicLibraryRepository.state.collectAsStateWithLifecycle()
    return state.settings.barStyle
}

fun setMusicBarStyle(style: MusicBarStyle) {
    val settings = MusicLibraryRepository.state.value.settings
    if (settings.barStyle != style) MusicLibraryRepository.updateSettings(settings.copy(barStyle = style))
}

/**
 * Whether the corner card is what shows. The bottom bar takes over wherever the card would be wrong: a real video
 * draws its picture in the bar, the lyric view already fills the screen and leans on the bar's controls, and a
 * ladder wants the slim pill ([musicBarSlim]).
 */
internal fun musicBarCorner(state: MusicPlayerState, slimRequested: Boolean, nowPlayingOpen: Boolean, style: MusicBarStyle): Boolean =
    style == MusicBarStyle.Corner && !slimRequested && !nowPlayingOpen && !(state.mode == MusicPlayMode.Video && !state.lyricVideo)
