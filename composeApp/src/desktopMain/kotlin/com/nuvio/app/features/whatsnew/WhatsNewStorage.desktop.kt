package com.nuvio.app.features.whatsnew

import com.nuvio.app.core.storage.DesktopStorage

/**
 * Per account, like the setup wizard's marker, never synced and left alone by sign-out: which cards
 * this PC has shown is a fact about this PC, not about the account's data.
 */
private const val whatsNewPreferencesName = "nuvio_whats_new"
private const val lastSeenKey = "last_seen_number"

internal actual object WhatsNewStorage {
    private val store = DesktopStorage.store(whatsNewPreferencesName)

    actual fun lastSeen(): Int? = store.getInt(lastSeenKey)

    actual fun setLastSeen(number: Int) {
        store.putInt(lastSeenKey, number)
    }

    actual fun isFreshAccount(): Boolean = DesktopStorage.isFreshAccount
}
