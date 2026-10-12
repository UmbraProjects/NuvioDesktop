package com.nuvio.app.core.auth

import com.nuvio.app.core.storage.DesktopAccountSlots

internal actual object PlatformAccountScope {
    actual fun onAuthenticated(userId: String): Boolean = DesktopAccountSlots.onAuthenticated(userId)

    actual fun onSignedOut(erase: Boolean): Boolean = DesktopAccountSlots.onSignedOut(erase)

    actual fun onServerSwitch() = DesktopAccountSlots.onServerSwitch()
}
