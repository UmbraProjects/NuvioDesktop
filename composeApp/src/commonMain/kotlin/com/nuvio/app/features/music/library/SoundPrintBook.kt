package com.nuvio.app.features.music.library
// FORK UI (Spyder): Nick's album page lenses, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import com.nuvio.app.features.music.visualiser.SoundPrint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * fork:sonic-map - the song's kept sound print for a track key (`yt:<video id>`, `addon:<track id>`), or, the first time,
 * one worked out from its kept track map; null when the song has never been analysed. Can read a file: call it off the
 * main thread. (Desktop: the `nuvio_music_prints` store, no cap.)
 */
internal expect fun loadSoundPrint(key: String): SoundPrint?

/** Goes up whenever a new print is kept, so the Sonic map reads its prints again. */
internal object SoundPrintEvents {
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()
    fun bump() = _changes.update { it + 1 }
}
