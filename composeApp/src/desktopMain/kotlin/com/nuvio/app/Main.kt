package com.nuvio.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DesktopNavigationGestureBridge
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
import java.awt.AWTEvent
import java.awt.Color as AwtColor
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JComponent

/** Equivalent to [KeyEvent.VK_BROWSER_BACK] (0xA6); referenced by code to avoid relying on JDK version-specific constants. */
private const val VK_BROWSER_BACK = 0xA6

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
        var reloadKey by remember { mutableStateOf(0) }

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
                val reloadDispatcher = KeyEventDispatcher { event ->
                    if (event.id != KeyEvent.KEY_PRESSED || event.keyCode != KeyEvent.VK_R) {
                        return@KeyEventDispatcher false
                    }
                    val modifiers = event.modifiersEx
                    val hasReloadModifier =
                        modifiers and KeyEvent.CTRL_DOWN_MASK != 0 ||
                            modifiers and KeyEvent.META_DOWN_MASK != 0
                    if (!hasReloadModifier) return@KeyEventDispatcher false
                    reloadKey++
                    true
                }
                val backNavigationDispatcher = KeyEventDispatcher { event ->
                    if (event.id != KeyEvent.KEY_PRESSED) {
                        return@KeyEventDispatcher false
                    }
                    val isBrowserBackKey = event.keyCode == VK_BROWSER_BACK
                    val isAltLeftArrow = event.keyCode == KeyEvent.VK_LEFT &&
                        event.modifiersEx and KeyEvent.ALT_DOWN_MASK != 0
                    if (!isBrowserBackKey && !isAltLeftArrow) {
                        return@KeyEventDispatcher false
                    }
                    DesktopNavigationGestureBridge.requestBack()
                    true
                }
                val mouseBackButtonListener = AWTEventListener { event ->
                    if (event is MouseEvent && event.id == MouseEvent.MOUSE_PRESSED && event.button == 4) {
                        DesktopNavigationGestureBridge.requestBack()
                    }
                }
                KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(reloadDispatcher)
                KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(backNavigationDispatcher)
                Toolkit.getDefaultToolkit().addAWTEventListener(mouseBackButtonListener, AWTEvent.MOUSE_EVENT_MASK)
                onDispose {
                    KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(reloadDispatcher)
                    KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(backNavigationDispatcher)
                    Toolkit.getDefaultToolkit().removeAWTEventListener(mouseBackButtonListener)
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
                key(reloadKey) {
                    App()
                }
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
