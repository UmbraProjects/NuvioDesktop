package com.nuvio.app.features.streams

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object StreamScoreStorage {
    private const val profileKey = "stream_score_profile"
    private val store = DesktopStorage.store("nuvio_stream_scoring")

    actual fun loadProfile(): String? = store.getString(ProfileScopedKey.of(profileKey))

    actual fun saveProfile(profile: String) = store.putString(ProfileScopedKey.of(profileKey), profile)
}
