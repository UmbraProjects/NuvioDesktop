package com.nuvio.app.features.tonight

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * fork:tonight - whether the Tonight picker is open (user, Nov 10 evening): it opens from the "What to watch"
 * button beside Search (and the phone remote's button), closes with its X or Back, and opens by itself only once,
 * when Nuvio starts, if "Open on Tonight" is on.
 */
object TonightController {
    private val _open = MutableStateFlow(false)
    val open: StateFlow<Boolean> = _open.asStateFlow()
    private var startHandled = false

    fun open() {
        _open.value = true
    }

    fun close() {
        _open.value = false
    }

    /** Opens it once per run, at start, when Settings asks for that. */
    fun openAtStartIfWanted() {
        if (startHandled) return
        startHandled = true
        TonightSettingsRepository.ensureLoaded()
        if (TonightSettingsRepository.state.value.enabled) _open.value = true
    }
}
