package com.nuvio.app.core.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Diagnostics switches from Settings > Advanced that are about this PC, not the profile. */
internal expect object DiagnosticsSettingsStorage {
    fun loadMemoryOverlayEnabled(): Boolean
    fun saveMemoryOverlayEnabled(value: Boolean)
}

object DiagnosticsSettings {
    private val _memoryOverlayEnabled = MutableStateFlow(
        runCatching { DiagnosticsSettingsStorage.loadMemoryOverlayEnabled() }.getOrDefault(false),
    )

    /** A live breakdown of the app's memory drawn over everything, the player included. */
    val memoryOverlayEnabled: StateFlow<Boolean> = _memoryOverlayEnabled.asStateFlow()

    fun setMemoryOverlayEnabled(enabled: Boolean) {
        if (_memoryOverlayEnabled.value == enabled) return
        _memoryOverlayEnabled.value = enabled
        DiagnosticsSettingsStorage.saveMemoryOverlayEnabled(enabled)
    }
}

/**
 * The memory overlay while [DiagnosticsSettings.memoryOverlayEnabled] is on: drawn here over the
 * app, and handed to the player's HUD, since the video surface covers anything Compose draws.
 */
@Composable
internal expect fun MemoryDiagnosticsOverlay(modifier: Modifier = Modifier)
