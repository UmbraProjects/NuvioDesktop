package com.nuvio.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntSize

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

internal expect val isIos: Boolean
internal expect val isDesktop: Boolean
internal expect val isWindows: Boolean

internal data class DesktopDisplayMetrics(
    val sizePx: IntSize,
    val density: Float,
)

internal expect fun desktopDisplayMetrics(): DesktopDisplayMetrics?

@Composable
internal expect fun isAppFullscreen(): Boolean

internal expect fun toggleAppFullscreen()
