package com.nuvio.app.core.storage

import com.nuvio.app.exitSoon
import com.nuvio.app.relaunchAfterExit

actual object PortableMode {
    actual val isSupported: Boolean
        get() = DesktopStorage.portableInstallRoot() != null

    actual val isEnabled: Boolean
        get() = DesktopStorage.isPortable

    actual fun setEnabledAndRestart(enabled: Boolean): Boolean {
        if (!DesktopStorage.setPortable(enabled)) return false
        DesktopStorage.flushAll()
        // The data moves at the next startup, so this process must actually stop.
        if (!relaunchAfterExit("relaunch-after-portable-switch.bat")) exitSoon()
        return true
    }
}
