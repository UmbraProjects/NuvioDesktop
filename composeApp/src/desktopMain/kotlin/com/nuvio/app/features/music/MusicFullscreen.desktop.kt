package com.nuvio.app.features.music

import com.nuvio.app.features.player.desktop.desktopAppFullscreenState
import com.nuvio.app.features.player.desktop.toggleDesktopAppFullscreen

actual fun isAppFullscreen(): Boolean = desktopAppFullscreenState.value

actual fun setAppFullscreen(on: Boolean) {
    if (desktopAppFullscreenState.value != on) toggleDesktopAppFullscreen()
}
