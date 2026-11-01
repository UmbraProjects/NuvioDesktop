package com.nuvio.app.features.whatsnew

internal expect object WhatsNewStorage {
    /** The highest entry number the user has been shown, or null when none has been recorded. */
    fun lastSeen(): Int?

    fun setLastSeen(number: Int)

    /**
     * Whether this account was created in this very run. True only in the process that opened the
     * account's folder new, so a fresh account is seeded at its first launch and never again.
     */
    fun isFreshAccount(): Boolean
}
