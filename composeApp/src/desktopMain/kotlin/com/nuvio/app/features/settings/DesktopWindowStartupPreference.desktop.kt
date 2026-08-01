package com.nuvio.app.features.settings

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal actual object DesktopWindowStartupPreference {
    private const val StartWindowedKey = "start_windowed"
    private val store = DesktopStorage.store("nuvio_window_state")
    private val mutableStartWindowed = MutableStateFlow(false)
    private var loaded = false

    actual val startWindowed: StateFlow<Boolean> = mutableStartWindowed.asStateFlow()

    actual fun ensureLoaded() {
        if (loaded) return
        loaded = true
        mutableStartWindowed.value = store.getBoolean(StartWindowedKey) ?: false
    }

    actual fun setStartWindowed(enabled: Boolean) {
        ensureLoaded()
        if (mutableStartWindowed.value == enabled) return
        mutableStartWindowed.value = enabled
        store.putBoolean(StartWindowedKey, enabled)
    }
}
