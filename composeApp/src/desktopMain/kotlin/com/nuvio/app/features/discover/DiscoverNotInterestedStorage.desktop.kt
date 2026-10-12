package com.nuvio.app.features.discover

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object DiscoverNotInterestedStorage {
    private val store = DesktopStorage.store("nuvio_discover_not_interested")

    actual fun load(): Set<String> =
        store.getString(ProfileScopedKey.of("keys"))
            ?.split('\n')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toSet()
            .orEmpty()

    actual fun save(keys: Set<String>) {
        store.putString(ProfileScopedKey.of("keys"), keys.sorted().joinToString("\n"))
    }
}
