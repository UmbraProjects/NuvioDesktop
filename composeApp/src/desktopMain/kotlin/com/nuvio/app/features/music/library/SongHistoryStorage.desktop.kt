package com.nuvio.app.features.music.library
// FORK UI (Spyder): Nick's album page lenses, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import com.nuvio.app.core.storage.DesktopStorage

internal actual object SongHistoryStorage {
    private val store = DesktopStorage.store("nuvio_music_history")

    actual fun get(key: String): String? = store.getString(key)

    actual fun put(key: String, value: String?) {
        store.putString(key, value)
    }
}
