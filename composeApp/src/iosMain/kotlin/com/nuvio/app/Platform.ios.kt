package com.nuvio.app

import androidx.compose.runtime.Composable
import platform.UIKit.UIDevice

class IOSPlatform: Platform {
    override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
}

actual fun getPlatform(): Platform = IOSPlatform()

internal actual val isIos: Boolean = true
internal actual val isDesktop: Boolean = false
internal actual val isWindows: Boolean = false

@Composable
internal actual fun isAppFullscreen(): Boolean = false

internal actual fun toggleAppFullscreen() {}
