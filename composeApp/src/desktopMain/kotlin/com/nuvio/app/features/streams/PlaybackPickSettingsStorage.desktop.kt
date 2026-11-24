package com.nuvio.app.features.streams

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object PlaybackPickSettingsStorage {
    private const val key = "playback_pick_settings"
    private val store = DesktopStorage.store("nuvio_playback_pick")

    actual fun load(): String? = store.getString(ProfileScopedKey.of(key))

    actual fun save(value: String) = store.putString(ProfileScopedKey.of(key), value)
}
