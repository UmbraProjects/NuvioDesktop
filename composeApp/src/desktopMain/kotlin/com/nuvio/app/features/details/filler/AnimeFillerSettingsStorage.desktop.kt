package com.nuvio.app.features.details.filler

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object AnimeFillerSettingsStorage {
    private val store = DesktopStorage.store("nuvio_anime_filler_settings")

    actual fun loadShowBadges(): Boolean? = store.getBoolean(ProfileScopedKey.of("show_badges"))

    actual fun saveShowBadges(value: Boolean) {
        store.putBoolean(ProfileScopedKey.of("show_badges"), value)
    }

    actual fun loadSkipFiller(): Boolean? = store.getBoolean(ProfileScopedKey.of("skip_filler"))

    actual fun saveSkipFiller(value: Boolean) {
        store.putBoolean(ProfileScopedKey.of("skip_filler"), value)
    }
}
