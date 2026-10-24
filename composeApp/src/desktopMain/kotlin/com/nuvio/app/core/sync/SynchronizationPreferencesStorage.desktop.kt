package com.nuvio.app.core.sync

import com.nuvio.app.core.storage.DesktopStorage

internal actual object SynchronizationPreferencesStorage {
    private const val PayloadKey = "payload"
    private const val PendingPushMarkerKey = "pending_push"
    private val store = DesktopStorage.store("nuvio_synchronization_preferences")

    /**
     * A fresh installation starts with Appearance sync on: accent colour, custom poster pattern and
     * card depth are the settings that carry cleanly between the official apps and this fork, and a
     * new user signing in should see their own. Existing installations keep whatever they had (an
     * absent payload there means "never changed", i.e. everything off), since turning it on under
     * them would let the next pull replace a desktop theme they chose.
     */
    actual fun loadPayload(): String? {
        store.getString(PayloadKey)?.let { return it }
        if (!DesktopStorage.isFreshInstall) return null
        val payload = """{"appearanceEnabled":true}"""
        store.putString(PayloadKey, payload)
        return payload
    }

    actual fun savePayload(payload: String) {
        store.putString(PayloadKey, payload)
    }

    actual fun loadPendingPushMarker(): String? = store.getString(PendingPushMarkerKey)

    actual fun savePendingPushMarker(marker: String?) {
        store.putString(PendingPushMarkerKey, marker)
    }
}
