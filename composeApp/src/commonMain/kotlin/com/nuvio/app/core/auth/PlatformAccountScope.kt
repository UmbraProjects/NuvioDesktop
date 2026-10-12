package com.nuvio.app.core.auth

/**
 * Keeps each account's local settings apart on a shared PC.
 *
 * On desktop every account has its own settings folder; signing in as a different account, or
 * signing out, restarts Nuvio into the right one. See DesktopAccountSlots.
 */
internal expect object PlatformAccountScope {
    /**
     * Auth has settled on [userId]. True when the app is restarting into that account's own
     * settings, in which case the caller must not publish the sign-in to this session: everything
     * loaded so far belongs to another account's folder.
     */
    fun onAuthenticated(userId: String): Boolean

    /**
     * The server session has ended. [erase] also removes this account's settings, sign-ins and
     * keys from the PC. True when the platform is restarting; false when it keeps one set of local
     * data for everyone and the caller should wipe it the old way.
     */
    fun onSignedOut(erase: Boolean): Boolean

    /** The next account to sign in takes over this one's settings (moving to another server). */
    fun onServerSwitch()
}
