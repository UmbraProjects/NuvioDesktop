package com.nuvio.app.features.music.library
// FORK UI (Spyder): Nick's album page lenses, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.music.TrackMapBook
import com.nuvio.app.features.music.visualiser.SoundPrint
import com.nuvio.app.features.music.visualiser.TrackMap
import com.nuvio.app.features.music.visualiser.decodeSoundPrint
import com.nuvio.app.features.music.visualiser.encodeSoundPrint
import com.nuvio.app.features.music.visualiser.soundPrint

private val printStore by lazy { DesktopStorage.store("nuvio_music_prints") }

internal actual fun loadSoundPrint(key: String): SoundPrint? {
    decodeSoundPrint(printStore.getString(key))?.let { return it }
    val map = TrackMapBook.get(key)?.takeIf { it.complete } ?: return null
    val print = soundPrint(map) ?: return null
    printStore.putString(key, encodeSoundPrint(print))
    return print
}

/** fork:sonic-map - keeps the print of a song whose map has just been finished (the map files are dropped after 400 songs). */
internal fun keepSoundPrint(key: String, map: TrackMap) {
    val print = soundPrint(map) ?: return
    printStore.putString(key, encodeSoundPrint(print))
    SoundPrintEvents.bump()
}
