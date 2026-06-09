package com.nuvio.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.PlatformPlayerSurface
import com.nuvio.app.features.player.desktop.applyNativeDesktopWindowChrome
import com.nuvio.app.features.player.desktop.preloadNativePlayerBridgeAsync
import java.awt.Color as AwtColor
import javax.swing.JComponent

private val NuvioDesktopNativeBackground = AwtColor(0x0D, 0x0D, 0x0D)
private const val NuvioDesktopIconPath = "icons/nuvio-app-icon.png"
private const val MacosDarkAquaAppearance = "NSAppearanceNameDarkAqua"

fun main() {
    configureDesktopChrome()
    preloadNativePlayerBridgeAsync()

    application {
        val smokePlayerUrl = (
            System.getProperty("nuvio.desktop.smokePlayerUrl")
                ?: System.getenv("NUVIO_DESKTOP_SMOKE_PLAYER_URL")
            )
            ?.takeIf { it.isNotBlank() }

        Window(
            onCloseRequest = ::exitApplication,
            title = if (smokePlayerUrl == null) "Nuvio" else "Nuvio Player Smoke",
            state = WindowState(width = 1280.dp, height = 820.dp),
            icon = painterResource(NuvioDesktopIconPath),
        ) {
            SideEffect {
                window.background = NuvioDesktopNativeBackground
                window.rootPane.background = NuvioDesktopNativeBackground
                window.contentPane.background = NuvioDesktopNativeBackground
                (window.contentPane as? JComponent)?.isOpaque = true
            }
            LaunchedEffect(window) {
                applyNativeDesktopWindowChrome(window)
            }

            if (smokePlayerUrl == null) {
                App()
            } else {
                PlatformPlayerSurface(
                    sourceUrl = smokePlayerUrl,
                    modifier = Modifier.fillMaxSize(),
                    onControllerReady = {},
                    onSnapshot = {},
                    onError = {},
                )
            }
        }
    }
}

private fun configureDesktopChrome() {
    if (System.getProperty("os.name").contains("mac", ignoreCase = true)) {
        System.setProperty("apple.awt.application.appearance", MacosDarkAquaAppearance)
    }
}
