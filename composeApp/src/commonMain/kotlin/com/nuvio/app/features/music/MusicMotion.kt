package com.nuvio.app.features.music

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.music.library.MusicLibraryRepository

/** Whether the person turned reduced motion on for the Music section (stored with the music settings). */
@Composable
fun rememberMusicReducedMotion(): Boolean {
    LaunchedEffect(Unit) { MusicLibraryRepository.ensureLoaded() }
    val state by MusicLibraryRepository.state.collectAsStateWithLifecycle()
    return state.settings.reducedMotion
}

fun setMusicReducedMotion(reduced: Boolean) {
    val settings = MusicLibraryRepository.state.value.settings
    if (settings.reducedMotion != reduced) MusicLibraryRepository.updateSettings(settings.copy(reducedMotion = reduced))
}
