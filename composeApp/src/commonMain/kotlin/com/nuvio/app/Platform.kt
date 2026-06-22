package com.nuvio.app

import androidx.compose.runtime.Composable

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

internal expect val isIos: Boolean
internal expect val isDesktop: Boolean
internal expect val isWindows: Boolean

@Composable
internal expect fun isAppFullscreen(): Boolean

internal expect fun toggleAppFullscreen()
