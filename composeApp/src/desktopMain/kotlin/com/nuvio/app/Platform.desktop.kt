package com.nuvio.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntSize
import com.nuvio.app.features.player.desktop.desktopAppFullscreenState
import com.nuvio.app.features.player.desktop.toggleDesktopAppFullscreen
import java.awt.Toolkit

class DesktopPlatform : Platform {
    override val name: String = "Desktop ${System.getProperty("os.name").orEmpty()}".trim()
}

actual fun getPlatform(): Platform = DesktopPlatform()

internal actual val isIos: Boolean = false
internal actual val isDesktop: Boolean = true
internal actual val isWindows: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win")

internal actual fun desktopDisplaySizePx(): IntSize? =
    runCatching {
        val size = Toolkit.getDefaultToolkit().screenSize
        IntSize(size.width, size.height)
    }.getOrNull()

@Composable
internal actual fun isAppFullscreen(): Boolean = desktopAppFullscreenState.value

internal actual fun toggleAppFullscreen() = toggleDesktopAppFullscreen()
