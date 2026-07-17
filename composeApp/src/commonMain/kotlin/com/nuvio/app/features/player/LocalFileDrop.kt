package com.nuvio.app.features.player

import kotlinx.coroutines.flow.SharedFlow

/**
 * Carries directly opened media from the desktop shell into player navigation: dropped local
 * paths and HTTP(S) stream URLs pasted outside playback.
 * Android/iOS actuals: no-op stubs (feature is desktop-only).
 */
expect object LocalFileDrop {
    val events: SharedFlow<String>
}
