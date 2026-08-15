package com.nuvio.app.features.settings

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal actual object DesktopWindowStartupPreference {
    private const val StartWindowedKey = "start_windowed"
    private const val CloseToTrayKey = "close_to_tray"
    private val store = DesktopStorage.store("nuvio_window_state")
    private val mutableStartWindowed = MutableStateFlow(false)
    private val mutableCloseToTray = MutableStateFlow(false)
    private var loaded = false

    actual val startWindowed: StateFlow<Boolean> = mutableStartWindowed.asStateFlow()
    actual val closeToTray: StateFlow<Boolean> = mutableCloseToTray.asStateFlow()

    actual fun ensureLoaded() {
        if (loaded) return
        loaded = true
        mutableStartWindowed.value = store.getBoolean(StartWindowedKey) ?: false
        mutableCloseToTray.value = store.getBoolean(CloseToTrayKey) ?: false
    }

    actual fun setStartWindowed(enabled: Boolean) {
        ensureLoaded()
        if (mutableStartWindowed.value == enabled) return
        mutableStartWindowed.value = enabled
        store.putBoolean(StartWindowedKey, enabled)
    }

    actual fun setCloseToTray(enabled: Boolean) {
        ensureLoaded()
        if (mutableCloseToTray.value == enabled) return
        mutableCloseToTray.value = enabled
        store.putBoolean(CloseToTrayKey, enabled)
    }
}
