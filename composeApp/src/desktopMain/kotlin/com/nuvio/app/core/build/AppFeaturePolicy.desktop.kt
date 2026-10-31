package com.nuvio.app.core.build

actual object AppFeaturePolicy {
    private val isWindowsHost: Boolean by lazy {
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    }

    actual val pluginsEnabled: Boolean = true
    actual val downloadsEnabled: Boolean = true
    actual val notificationsEnabled: Boolean = false
    // The bundled TorrServer binary and the Nuvio P2P engine are currently Windows-only. Keeping
    // the controls visible on macOS advertises a feature that cannot start on a clean install.
    actual val p2pEnabled: Boolean get() = isWindowsHost
    actual val trailerPlaybackMode: TrailerPlaybackMode = TrailerPlaybackMode.IN_APP
    actual val heroTrailerPlaybackSupported: Boolean = true
    // The in-place updater only knows how to replace the Windows portable app image. macOS users
    // should install updates from the signed/notarized DMG release instead.
    actual val inAppUpdaterEnabled: Boolean get() = isWindowsHost
    actual val imdbRatingLogoEnabled: Boolean = true
    actual val customServerConnectionsEnabled: Boolean = true
}
