package com.nuvio.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.PlatformPlayerSurface
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.applyNativeBorderlessFullscreen
import com.nuvio.app.features.player.desktop.applyNativeDesktopWindowChrome
import com.nuvio.app.features.player.desktop.desktopAppFullscreenState
import com.nuvio.app.features.player.desktop.installDesktopAppFullscreenShortcuts
import com.nuvio.app.features.player.desktop.preloadNativePlayerBridgeAsync
import com.nuvio.app.features.player.desktop.registerDesktopAppFullscreenToggle
import com.nuvio.app.features.player.desktop.toggleDesktopAppFullscreen
import kotlinx.coroutines.delay
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
        val windowState = rememberWindowState(width = 1280.dp, height = 820.dp)
        val restoreWindowPlacement = remember { mutableStateOf(WindowPlacement.Floating) }
        val isBorderlessFullscreen = remember { mutableStateOf(false) }

        Window(
            onCloseRequest = ::exitApplication,
            title = if (smokePlayerUrl == null) "Nuvio" else "Nuvio Player Smoke",
            state = windowState,
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
            DisposableEffect(window, windowState) {
                val unregisterFullscreenToggle = registerDesktopAppFullscreenToggle { targetWindow ->
                    if (targetWindow != null && targetWindow !== window) return@registerDesktopAppFullscreenToggle
                    if (DesktopHostOs.current == DesktopHostOs.WINDOWS) {
                        val nextFullscreen = !isBorderlessFullscreen.value
                        applyNativeBorderlessFullscreen(window, nextFullscreen)
                        isBorderlessFullscreen.value = nextFullscreen
                        desktopAppFullscreenState.value = nextFullscreen
                    } else if (windowState.placement == WindowPlacement.Fullscreen) {
                        windowState.placement = restoreWindowPlacement.value
                        desktopAppFullscreenState.value = false
                    } else {
                        restoreWindowPlacement.value = windowState.placement
                            .takeUnless { it == WindowPlacement.Fullscreen }
                            ?: WindowPlacement.Floating
                        windowState.placement = WindowPlacement.Fullscreen
                        desktopAppFullscreenState.value = true
                    }
                }
                val uninstallFullscreenShortcuts = installDesktopAppFullscreenShortcuts(window)
                onDispose {
                    uninstallFullscreenShortcuts()
                    unregisterFullscreenToggle()
                    if (isBorderlessFullscreen.value) {
                        applyNativeBorderlessFullscreen(window, false)
                        isBorderlessFullscreen.value = false
                    }
                    desktopAppFullscreenState.value = false
                }
            }

            LaunchedEffect(window) {
                delay(400)
                toggleDesktopAppFullscreen(window)
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
