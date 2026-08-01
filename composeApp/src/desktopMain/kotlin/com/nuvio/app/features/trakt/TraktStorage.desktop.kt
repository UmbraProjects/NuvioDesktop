package com.nuvio.app.features.trakt

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object TraktAuthStorage {
    private val store = DesktopStorage.store("nuvio_trakt_auth")
    private const val legacyPayloadKey = "trakt_auth"
    private const val payloadKey = "trakt_auth_payload"

    actual fun loadPayload(profileId: Int): String? =
        store.getString(ProfileScopedKey.of(payloadKey, profileId))
            ?: migrateLegacyPrimaryProfilePayload(profileId)

    actual fun savePayload(profileId: Int, payload: String) {
        store.putString(ProfileScopedKey.of(payloadKey, profileId), payload)
    }

    private fun migrateLegacyPrimaryProfilePayload(profileId: Int): String? {
        if (profileId != 1) return null
        val payload = store.getString(legacyPayloadKey)?.takeIf { it.isNotBlank() } ?: return null
        store.putString(ProfileScopedKey.of(payloadKey, profileId), payload)
        store.remove(legacyPayloadKey)
        return payload
    }
}

internal actual object TraktLibraryStorage {
    private val store = DesktopStorage.store("nuvio_trakt_library")

    actual fun loadPayload(): String? =
        store.getString(ProfileScopedKey.of("trakt_library"))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of("trakt_library"), payload)
    }
}

internal actual object TraktSettingsStorage {
    private val store = DesktopStorage.store("nuvio_trakt_settings")

    actual fun loadPayload(): String? =
        store.getString(ProfileScopedKey.of("trakt_settings"))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of("trakt_settings"), payload)
    }
}
