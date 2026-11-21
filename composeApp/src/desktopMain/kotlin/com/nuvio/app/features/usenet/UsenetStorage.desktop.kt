package com.nuvio.app.features.usenet

import com.nuvio.app.core.storage.DesktopStorage

internal actual object UsenetStorage {
    // Per account (the default for a store); holds provider passwords and indexer API keys.
    private val store = DesktopStorage.store("nuvio_usenet")

    actual fun loadPayload(profileId: Int): String? = store.getString("usenet_$profileId")

    actual fun savePayload(profileId: Int, payload: String) {
        store.putString("usenet_$profileId", payload)
    }

    // A store of its own: written every few seconds while something plays.
    private val stats = DesktopStorage.store("nuvio_usenet_stats")

    actual fun loadStats(): String? = stats.getString("stats")

    actual fun saveStats(payload: String) {
        stats.putString("stats", payload)
    }
}
