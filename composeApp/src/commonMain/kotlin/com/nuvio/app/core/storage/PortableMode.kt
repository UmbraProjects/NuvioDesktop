package com.nuvio.app.core.storage

/**
 * Portable mode: the app keeps its data in a `data` folder beside Nuvio.exe instead of
 * %LOCALAPPDATA%. Switching moves the data on the restart it triggers.
 */
expect object PortableMode {
    /** False in a dev run, which has no install folder to be portable in. */
    val isSupported: Boolean

    val isEnabled: Boolean

    /**
     * Records the choice and restarts into it. False, without restarting, when the install folder
     * can't be written to.
     */
    fun setEnabledAndRestart(enabled: Boolean): Boolean
}
