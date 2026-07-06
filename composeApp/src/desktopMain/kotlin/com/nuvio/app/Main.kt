package com.nuvio.app

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import com.nuvio.app.core.ui.DesktopNavigationGestureBridge
import com.nuvio.app.features.player.DesktopRendererApi
import com.nuvio.app.features.player.PlatformPlayerSurface
import com.nuvio.app.features.player.PlayerSettingsStorage
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.applyNativeBorderlessFullscreen
import com.nuvio.app.features.player.desktop.applyNativeDesktopWindowChrome
import com.nuvio.app.features.player.desktop.desktopAppFullscreenState
import com.nuvio.app.features.player.desktop.ensureNativePlayerBridgeLoaded
import com.nuvio.app.features.player.desktop.installDesktopAppFullscreenShortcuts
import com.nuvio.app.features.player.desktop.preloadNativePlayerBridgeAsync
import com.nuvio.app.features.player.desktop.registerDesktopAppFullscreenToggle
import com.nuvio.app.features.player.desktop.toggleDesktopAppFullscreen
import com.nuvio.app.features.player.desktop.warmNativePlayerBridgeLoadAsync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.AWTEvent
import java.awt.Color as AwtColor
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import com.nuvio.app.features.player.LocalFileDrop
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

private inline fun desktopStartupStep(name: String, block: () -> Unit) {
    val startedAt = System.currentTimeMillis()
    runCatching(block)
        .onSuccess {
            System.out.println("Info: (DesktopStartup) $name completed in ${System.currentTimeMillis() - startedAt}ms")
        }
        .onFailure { error ->
            System.err.println("Error: (DesktopStartup) $name failed after ${System.currentTimeMillis() - startedAt}ms")
            error.printStackTrace(System.err)
        }
}

fun main() {
    configureDesktopFileLogging()
    val desktopStartupStartedAt = System.currentTimeMillis()
    System.out.println("Info: (DesktopStartup) main entered")
    desktopStartupStep("configure renderer") { configureDesktopRenderer() }
    desktopStartupStep("configure chrome") { configureDesktopChrome() }
    desktopStartupStep("subtitle font warm request") { com.nuvio.app.features.player.warmSubtitleFontCache() }
    // Overlaps the native bridge DLL extraction/link with Compose/Skia startup so the
    // startup fullscreen swap below never waits for it on the AWT event thread.
    desktopStartupStep("native bridge load warm request") { warmNativePlayerBridgeLoadAsync() }
    System.out.println("Info: (DesktopStartup) entering Compose application")

    application {
        remember {
            System.out.println(
                "Info: (DesktopStartup) Compose application composition entered after " +
                    "${System.currentTimeMillis() - desktopStartupStartedAt}ms"
            )
            Unit
        }
        val smokePlayerUrl = (
            System.getProperty("nuvio.desktop.smokePlayerUrl")
                ?: System.getenv("NUVIO_DESKTOP_SMOKE_PLAYER_URL")
            )
            ?.takeIf { it.isNotBlank() }
        // Explicit centered position (instead of the platform default) so the restore rect the
        // native borderless-fullscreen code captures from the still-hidden window is sane.
        val windowState = rememberWindowState(
            width = 1280.dp,
            height = 820.dp,
            position = WindowPosition.Aligned(Alignment.Center),
        )
        val restoreWindowPlacement = remember { mutableStateOf(WindowPlacement.Floating) }
        val isBorderlessFullscreen = remember { mutableStateOf(false) }
        val isWindowsHost = remember { DesktopHostOs.current == DesktopHostOs.WINDOWS }

        Window(
            onCloseRequest = ::exitApplication,
            title = if (smokePlayerUrl == null) "Nuvio" else "Nuvio Player Smoke",
            state = windowState,
            icon = painterResource(NuvioDesktopIconPath),
        ) {
            remember {
                System.out.println(
                    "Info: (DesktopStartup) Window content composed after " +
                        "${System.currentTimeMillis() - desktopStartupStartedAt}ms"
                )
                Unit
            }
            val windowSideEffectLogged = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
            SideEffect {
                if (windowSideEffectLogged.compareAndSet(false, true)) {
                    System.out.println(
                        "Info: (DesktopStartup) Window side effect after " +
                            "${System.currentTimeMillis() - desktopStartupStartedAt}ms"
                    )
                }
                window.background = NuvioDesktopNativeBackground
                window.rootPane.background = NuvioDesktopNativeBackground
                window.contentPane.background = NuvioDesktopNativeBackground
                (window.contentPane as? JComponent)?.isOpaque = true
            }
            LaunchedEffect(window) {
                delay(3_000)
                desktopStartupStep("apply native desktop chrome") {
                    applyNativeDesktopWindowChrome(window)
                }
                Thread {
                    desktopStartupStep("native player bridge preload request") {
                        preloadNativePlayerBridgeAsync()
                    }
                }.apply {
                    name = "nuvio-native-startup-warmup"
                    isDaemon = true
                    start()
                }
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
                KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(backNavigationDispatcher)
                Toolkit.getDefaultToolkit().addAWTEventListener(mouseBackButtonListener, AWTEvent.MOUSE_EVENT_MASK)
                onDispose {
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
                if (!isWindowsHost) {
                    delay(400)
                    toggleDesktopAppFullscreen(window)
                    return@LaunchedEffect
                }
                // Load the bridge off the AWT event thread (warmed from main(), so this is
                // usually already done), then wait for Compose to actually show the window.
                // Native styling must only ever touch a showing window: applying it pre-show
                // desyncs AWT's own show/bounds bookkeeping and breaks activation/focus.
                withContext(Dispatchers.IO) { runCatching { ensureNativePlayerBridgeLoaded() } }
                var shownWaitMs = 0
                while (!window.isShowing && shownWaitMs < 10_000) {
                    delay(16)
                    shownWaitMs += 16
                }
                if (window.isShowing) {
                    desktopStartupStep("apply startup borderless fullscreen") {
                        applyNativeDesktopWindowChrome(window)
                        applyNativeBorderlessFullscreen(window, true)
                        isBorderlessFullscreen.value = true
                        desktopAppFullscreenState.value = true
                    }
                }
            }

            if (smokePlayerUrl == null) {
                @Suppress("OPT_IN_USAGE")
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .dragAndDropTarget(
                            shouldStartDragAndDrop = { event ->
                                // Accept the drag hover if it carries a file list.
                                @Suppress("OPT_IN_USAGE")
                                (event.nativeEvent as? DropTargetDragEvent)
                                    ?.isDataFlavorSupported(DataFlavor.javaFileListFlavor) == true
                            },
                            target = object : DragAndDropTarget {
                                @Suppress("OPT_IN_USAGE")
                                override fun onDrop(event: DragAndDropEvent): Boolean {
                                    val drop = event.nativeEvent as? DropTargetDropEvent
                                        ?: return false
                                    drop.acceptDrop(DnDConstants.ACTION_COPY)
                                    val transferable = drop.transferable
                                    val handled = if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                                        @Suppress("UNCHECKED_CAST")
                                        val files = transferable.getTransferData(DataFlavor.javaFileListFlavor)
                                            as List<java.io.File>
                                        val videos = files.filter {
                                            it.isFile && it.extension.lowercase() in com.nuvio.app.features.player.VIDEO_EXTENSIONS
                                        }
                                        // Emit the absolute path — mpv handles Windows paths
                                        // natively and avoids URI percent-encoding issues.
                                        videos.forEach { LocalFileDrop.emit(it.absolutePath) }
                                        videos.isNotEmpty()
                                    } else false
                                    drop.dropComplete(handled)
                                    return handled
                                }
                            },
                        ),
                ) {
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

    // Allow a brief grace period for background coroutines (e.g., scrobble network requests
    // triggered by UI teardown) to complete before hard-terminating the JVM.
    Thread.sleep(400)
    kotlin.system.exitProcess(0)
}

private fun configureDesktopChrome() {
    if (System.getProperty("os.name").contains("mac", ignoreCase = true)) {
        System.setProperty("apple.awt.application.appearance", MacosDarkAquaAppearance)
    }
}

// Selects the Compose/Skiko UI graphics backend from the persisted renderer setting. Skiko
// reads the skiko.renderApi system property once, when it initializes for the first window, so
// this must run before any Compose window is shown and a change only takes effect on the next
// launch. An explicit user choice always wins; if none is saved we default to OpenGL unless
// skiko.renderApi was already set out-of-band (e.g. a JVM flag for debugging), which is left
// untouched. Best-effort — on any failure Skiko falls back to its own platform default.
private fun configureDesktopRenderer() {
    runCatching {
        val stored = PlayerSettingsStorage.loadDesktopRendererApi()
            ?.let { runCatching { DesktopRendererApi.valueOf(it) }.getOrNull() }
        val renderer = stored
            ?: DesktopRendererApi.OpenGL.takeIf { System.getProperty("skiko.renderApi").isNullOrBlank() }
        renderer?.let { System.setProperty("skiko.renderApi", it.skikoRenderApi) }
    }
}
