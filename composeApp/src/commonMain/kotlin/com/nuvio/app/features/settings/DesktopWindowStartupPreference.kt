package com.nuvio.app.features.settings

import kotlinx.coroutines.flow.StateFlow

internal expect object DesktopWindowStartupPreference {
    val startWindowed: StateFlow<Boolean>
    val closeToTray: StateFlow<Boolean>

    fun ensureLoaded()
    fun setStartWindowed(enabled: Boolean)
    fun setCloseToTray(enabled: Boolean)
}
