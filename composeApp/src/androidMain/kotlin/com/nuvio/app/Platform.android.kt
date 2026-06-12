package com.nuvio.app

import android.os.Build
import androidx.compose.runtime.Composable

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

actual fun getPlatform(): Platform = AndroidPlatform()

internal actual val isIos: Boolean = false
internal actual val isDesktop: Boolean = false

@Composable
internal actual fun isAppFullscreen(): Boolean = false

internal actual fun toggleAppFullscreen() {}
