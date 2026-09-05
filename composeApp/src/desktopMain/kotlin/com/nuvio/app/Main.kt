package com.nuvio.app

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import com.nuvio.app.core.build.AppVersionPolicy
import com.nuvio.app.core.ui.DesktopNavigationGestureBridge
import com.nuvio.app.core.ui.DesktopBackRequestSource
import com.nuvio.app.core.ui.DesktopTrayMenu
import com.nuvio.app.core.ui.DesktopTrayMenuEntry
import com.nuvio.app.core.ui.loadDesktopTrayIconImage
import com.nuvio.app.features.p2p.P2pStreamingEngine
import com.nuvio.app.features.player.DesktopRendererApi
import com.nuvio.app.features.player.PlatformPlayerSurface
import com.nuvio.app.features.player.PlayerSettingsStorage
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.mdblist.HeroCastMetadataService
import com.nuvio.app.features.mdblist.MdbListMetadataService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import com.nuvio.app.features.player.desktop.DesktopIdleHeapTrim
import com.nuvio.app.features.player.desktop.DesktopWindowGeometry
import com.nuvio.app.features.player.desktop.DesktopWindowMinHeight
import com.nuvio.app.features.player.desktop.DesktopWindowMinWidth
import com.nuvio.app.features.player.desktop.DesktopWindowGeometryDiagnostics
import com.nuvio.app.features.player.desktop.DesktopWindowModeStorage
import com.nuvio.app.features.player.desktop.applyNativeBorderlessFullscreen
import com.nuvio.app.features.player.desktop.applyNativeDesktopWindowChrome
import com.nuvio.app.features.player.desktop.applyNativeCompactPlayerWindow
import com.nuvio.app.features.player.desktop.desktopAppFullscreenState
import com.nuvio.app.features.player.desktop.desktopPictureInPictureState
import com.nuvio.app.features.player.desktop.ensureNativePlayerBridgeLoaded
import com.nuvio.app.features.player.desktop.installDesktopAppFullscreenShortcuts
import com.nuvio.app.features.player.desktop.registerDesktopAppFullscreenToggle
import com.nuvio.app.features.player.desktop.registerDesktopPictureInPictureHandler
import com.nuvio.app.features.player.desktop.setDesktopPictureInPicture
import com.nuvio.app.features.player.desktop.suspendNativeBorderlessFullscreen
import com.nuvio.app.features.player.desktop.toggleDesktopAppFullscreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.awt.AWTEvent
import java.awt.Color as AwtColor
import java.awt.EventQueue
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import com.nuvio.app.features.player.LocalFileDrop
import com.nuvio.app.features.player.AppShortcutAction
import com.nuvio.app.features.player.AppShortcutBridge
import com.nuvio.app.features.player.AppShortcutsRepository
import com.nuvio.app.core.ui.TextInputFocusTracker
import com.nuvio.app.features.settings.DesktopWindowStartupPreference
import java.awt.Toolkit
import java.awt.Frame
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.InputEvent
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.event.WindowStateListener
import javax.swing.JComponent

/** Equivalent to [KeyEvent.VK_BROWSER_BACK] (0xA6); referenced by code to avoid relying on JDK version-specific constants. */
private const val VK_BROWSER_BACK = 0xA6
/** Equivalent to the Windows browser-forward virtual key generated by some Mouse 5 drivers. */
private const val VK_BROWSER_FORWARD = 0xA7

/**
 * Turns Mouse 5 into an app-local Shift key before Skiko/Compose receives the native pointer event.
 *
 * An AWT event listener is notified during dispatch, which is too late to hide the pressed mouse
 * button from Compose. Filtering at the event-queue boundary keeps hover behavior identical to
 * holding Shift: the Mouse 5 lifecycle is removed, its otherwise-drag motion becomes hover motion,
 * and wheel events carry Shift without carrying the Mouse 5 button bit.
 */
private class MouseFiveShiftEventQueue : EventQueue() {
    private val mouseFiveMask = runCatching { InputEvent.getMaskForButton(5) }.getOrDefault(0)
    private val otherMouseButtonMasks =
        InputEvent.BUTTON1_DOWN_MASK or
            InputEvent.BUTTON2_DOWN_MASK or
            InputEvent.BUTTON3_DOWN_MASK or
            runCatching { InputEvent.getMaskForButton(4) }.getOrDefault(0)

    override fun dispatchEvent(event: AWTEvent) {
        if (event is MouseWheelEvent && DesktopNavigationGestureBridge.horizontalScrollModifierActive.value) {
            super.dispatchEvent(
                MouseWheelEvent(
                    event.component,
                    event.id,
                    event.`when`,
                    translatedModifiers(event.modifiersEx),
                    event.x,
                    event.y,
                    event.xOnScreen,
                    event.yOnScreen,
                    event.clickCount,
                    event.isPopupTrigger,
                    event.scrollType,
                    event.scrollAmount,
                    event.wheelRotation,
                    event.preciseWheelRotation,
                ),
            )
            return
        }

        if (event is MouseEvent) {
            if (
                event.button == 5 &&
                event.id in setOf(
                    MouseEvent.MOUSE_PRESSED,
                    MouseEvent.MOUSE_RELEASED,
                    MouseEvent.MOUSE_CLICKED,
                )
            ) {
                when (event.id) {
                    MouseEvent.MOUSE_PRESSED ->
                        DesktopNavigationGestureBridge.setHorizontalScrollModifierActive(true)
                    MouseEvent.MOUSE_RELEASED ->
                        DesktopNavigationGestureBridge.setHorizontalScrollModifierActive(false)
                }
                return
            }

            if (DesktopNavigationGestureBridge.horizontalScrollModifierActive.value) {
                val translatedId =
                    if (
                        event.id == MouseEvent.MOUSE_DRAGGED &&
                        event.modifiersEx and otherMouseButtonMasks == 0
                    ) {
                        MouseEvent.MOUSE_MOVED
                    } else {
                        event.id
                    }
                super.dispatchEvent(
                    MouseEvent(
                        event.component,
                        translatedId,
                        event.`when`,
                        translatedModifiers(event.modifiersEx),
                        event.x,
                        event.y,
                        event.xOnScreen,
                        event.yOnScreen,
                        if (translatedId == MouseEvent.MOUSE_MOVED) 0 else event.clickCount,
                        event.isPopupTrigger,
                        if (translatedId == MouseEvent.MOUSE_MOVED) MouseEvent.NOBUTTON else event.button,
                    ),
                )
                return
            }
        }

        super.dispatchEvent(event)
    }

    private fun translatedModifiers(modifiersEx: Int): Int =
        (modifiersEx and mouseFiveMask.inv()) or InputEvent.SHIFT_DOWN_MASK

    fun uninstall() {
        pop()
    }
}

private val NuvioDesktopNativeBackground = AwtColor(0x0D, 0x0D, 0x0D)
private const val NuvioDesktopIconPath = "icons/nuvio-app-icon.png"
private const val MacosDarkAquaAppearance = "NSAppearanceNameDarkAqua"

private fun installDesktopTray(
    window: java.awt.Window,
    onExit: () -> Unit,
): (() -> Unit)? {
    if (!SystemTray.isSupported()) return null
    val iconUrl = Thread.currentThread().contextClassLoader.getResource(NuvioDesktopIconPath)
        ?: return null
    val tray = SystemTray.getSystemTray()
    // Pre-rendered variants beat isImageAutoSize, which scales the 1080px master down to 16px with
    // the toolkit's fast path and leaves the icon visibly aliased in the tray.
    val renderedIcon = loadDesktopTrayIconImage(iconUrl, tray.trayIconSize)
    val trayIcon = TrayIcon(
        renderedIcon ?: Toolkit.getDefaultToolkit().getImage(iconUrl),
        "Nuvio",
    ).apply {
        isImageAutoSize = renderedIcon == null
    }
    val restoreWindow = {
        EventQueue.invokeLater {
            window.isVisible = true
            if (window is Frame && window.extendedState and Frame.ICONIFIED != 0) {
                window.extendedState = Frame.NORMAL
            }
            window.toFront()
            window.requestFocus()
            DesktopIdleHeapTrim.onWindowVisibilityChanged(visible = true)
        }
    }
    // No PopupMenu is attached, so the right-click reaches us as a plain mouse event and the menu
    // is ours to draw. See DesktopTrayMenu for why the native one had to go.
    val menuEntries = listOf(
        DesktopTrayMenuEntry.Action("Open Nuvio") { restoreWindow() },
        DesktopTrayMenuEntry.Separator,
        DesktopTrayMenuEntry.Info("Nuvio ${AppVersionPolicy.displayVersionName}"),
        DesktopTrayMenuEntry.Separator,
        DesktopTrayMenuEntry.Action("Exit", onExit),
    )
    trayIcon.addMouseListener(object : MouseAdapter() {
        override fun mouseReleased(e: MouseEvent) {
            if (!e.isPopupTrigger && e.button != MouseEvent.BUTTON3) return
            val pointer = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
                ?: Point(e.x, e.y)
            DesktopTrayMenu.show(menuEntries, pointer.x, pointer.y)
        }
    })
    trayIcon.addActionListener { restoreWindow() }
    return runCatching {
        tray.add(trayIcon)
        val uninstall: () -> Unit = {
            DesktopTrayMenu.dismiss()
            tray.remove(trayIcon)
        }
        uninstall
    }.getOrNull()
}

// How long a window rect must hold still before it is written to disk. Long enough that a
// fullscreen/PiP transition, or a drag across the desktop, settles into a single write.
private const val WindowGeometrySettleDelayMs = 400L

private data class DesktopWindowGeometrySample(
    val placement: WindowPlacement,
    val isMinimized: Boolean,
    val position: WindowPosition,
    val size: DpSize,
    val borderlessFullscreen: Boolean,
    val pictureInPicture: Boolean,
) {
    /** Null while the window is in a state whose bounds are not worth remembering. */
    fun toWindowedGeometry(): DesktopWindowGeometry? {
        if (isMinimized || borderlessFullscreen || pictureInPicture) return null
        if (placement == WindowPlacement.Fullscreen) return null
        if (!position.isSpecified) return null
        // While maximized, Compose stops syncing position/size from AWT, so these still hold the
        // floating rect to un-maximize back to.
        if (size.width.value < DesktopWindowMinWidth || size.height.value < DesktopWindowMinHeight) {
            return null
        }
        return DesktopWindowGeometry(
            x = position.x.value,
            y = position.y.value,
            width = size.width.value,
            height = size.height.value,
            maximized = placement == WindowPlacement.Maximized,
        )
    }
}

private data class DesktopPictureInPictureRestore(
    val bounds: Rectangle,
    val placement: WindowPlacement,
    val extendedState: Int,
    val alwaysOnTop: Boolean,
    val borderlessFullscreen: Boolean,
)

// Session-scoped (lives until the client process exits) memory of the last Picture-in-Picture
// window bounds. Re-entering PiP restores wherever the user last moved/sized the window instead of
// snapping back to the default bottom-right. Intentionally not persisted to disk — forgotten on
// restart, which matches the "for the session" scope the feature is meant to have.
private var lastPictureInPictureBounds: Rectangle? = null

private fun pictureInPictureDefaultBounds(window: java.awt.Window): Rectangle {
    val configuration = window.graphicsConfiguration
        ?: java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
    val screen = configuration.bounds
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
    val usableLeft = screen.x + insets.left
    val usableTop = screen.y + insets.top
    val usableWidth = (screen.width - insets.left - insets.right).coerceAtLeast(320)
    val usableHeight = (screen.height - insets.top - insets.bottom).coerceAtLeast(180)
    val margin = 20
    val width = minOf(480, usableWidth - margin * 2).coerceAtLeast(320)
    val height = minOf((width * 9f / 16f).toInt(), usableHeight - margin * 2).coerceAtLeast(180)
    return Rectangle(
        usableLeft + usableWidth - width - margin,
        usableTop + usableHeight - height - margin,
        width,
        height,
    )
}

// A remembered rect is only reused if it is still sane and its centre lands on a currently
// connected screen — otherwise (e.g. a monitor was unplugged since it was captured) the window
// would open off-screen, so we fall back to the default bottom-right placement.
private fun pictureInPictureBoundsAreUsable(bounds: Rectangle): Boolean {
    if (bounds.width < 240 || bounds.height < 135) return false
    val centerX = bounds.x + bounds.width / 2
    val centerY = bounds.y + bounds.height / 2
    return java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { device ->
        device.defaultConfiguration.bounds.contains(centerX, centerY)
    }
}

private fun pictureInPictureBounds(window: java.awt.Window): Rectangle {
    lastPictureInPictureBounds
        ?.takeIf { pictureInPictureBoundsAreUsable(it) }
        ?.let { return Rectangle(it) }
    return pictureInPictureDefaultBounds(window)
}

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
    // Opt-in: dumps raw addon stream payloads so unparsed fields are visible. See
    // StreamPayloadDiagnostics — off by default, since it logs whole stream objects.
    //
    // Two ways in, because an env var only reaches the app when it is launched from the same shell
    // that set it (and `set` does nothing in PowerShell): the env var, or simply creating a file
    // named `stream-payload-log` in the Nuvio data directory, which survives any launch method.
    val payloadLogMarker = runCatching {
        com.nuvio.app.core.storage.DesktopStorage.rootDir.resolve("stream-payload-log").toFile().exists()
    }.getOrDefault(false)
    val payloadLogEnv = !System.getenv("NUVIO_STREAM_PAYLOAD_LOG").isNullOrBlank()
    com.nuvio.app.features.streams.StreamPayloadDiagnostics.enabled = payloadLogEnv || payloadLogMarker
    // Always report the decision, so a missing dump is never ambiguous between "flag did not take"
    // and "the hook never ran".
    System.out.println(
        "Info: (DesktopStartup) stream payload diagnostics enabled=" +
            "${payloadLogEnv || payloadLogMarker} (env=$payloadLogEnv, marker=$payloadLogMarker)"
    )
    val desktopStartupStartedAt = System.currentTimeMillis()
    System.out.println("Info: (DesktopStartup) main entered")
    // Before any window exists: Windows caches the process AppUserModelID early, and the media
    // session created later is labelled "Unknown app" without it.
    desktopStartupStep("app identity") {
        runCatching { com.nuvio.app.features.player.desktop.registerDesktopAppIdentity() }
    }
    desktopStartupStep("configure renderer") { configureDesktopRenderer() }
    desktopStartupStep("configure chrome") { configureDesktopChrome() }
    desktopStartupStep("subtitle font warm request") { com.nuvio.app.features.player.warmSubtitleFontCache() }
    desktopStartupStep("app font warm request") { com.nuvio.app.core.ui.warmSystemFontCache() }
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
        val startWindowed = remember {
            DesktopWindowStartupPreference.ensureLoaded()
            DesktopWindowStartupPreference.startWindowed.value
        }
        // Explicit centered position (instead of the platform default) so the restore rect the
        // native borderless-fullscreen code captures from the still-hidden window is sane.
        //
        // The remembered rect is reconciled with the currently connected displays first, so a
        // monitor that has been unplugged or moved in the desktop layout since the last run can
        // never reopen Nuvio somewhere off-screen; it falls back to the centered default instead.
        val savedWindowGeometry = remember { DesktopWindowModeStorage.restoredWindowedGeometry() }
        val windowState = rememberWindowState(
            // Restoring maximized only applies when starting windowed: the borderless-fullscreen
            // startup path expects a plain floating window underneath it, and its exit rect is
            // taken from these bounds.
            placement = if (startWindowed && savedWindowGeometry?.maximized == true) {
                WindowPlacement.Maximized
            } else {
                WindowPlacement.Floating
            },
            width = savedWindowGeometry?.width?.dp ?: 1280.dp,
            height = savedWindowGeometry?.height?.dp ?: 820.dp,
            position = savedWindowGeometry?.let { WindowPosition.Absolute(it.x.dp, it.y.dp) }
                ?: WindowPosition.Aligned(Alignment.Center),
        )
        val restoreWindowPlacement = remember { mutableStateOf(WindowPlacement.Floating) }
        val isBorderlessFullscreen = remember { mutableStateOf(false) }
        val isWindowsHost = remember { DesktopHostOs.current == DesktopHostOs.WINDOWS }
        val closeToTray by remember {
            DesktopWindowStartupPreference.ensureLoaded()
            DesktopWindowStartupPreference.closeToTray
        }.collectAsState()
        val desktopWindow = remember { mutableStateOf<java.awt.Window?>(null) }
        val trayInstalled = remember { mutableStateOf(false) }

        val flushWindowGeometry = {
            DesktopWindowGeometrySample(
                placement = windowState.placement,
                isMinimized = windowState.isMinimized,
                position = windowState.position,
                size = windowState.size,
                borderlessFullscreen = isBorderlessFullscreen.value,
                pictureInPicture = desktopPictureInPictureState.value,
            ).toWindowedGeometry()?.let(DesktopWindowModeStorage::saveWindowedGeometry)
        }
        val exitDesktopApplication = {
            flushWindowGeometry()
            P2pStreamingEngine.shutdown()
            exitApplication()
        }

        Window(
            onCloseRequest = {
                // Close-to-tray only handles a deliberate window-close request. Keeping it here,
                // rather than treating ordinary iconification as a close, avoids false hides when
                // Windows minimizes the app during display/fullscreen transitions.
                if (closeToTray && trayInstalled.value) {
                    flushWindowGeometry()
                    desktopWindow.value?.isVisible = false
                    DesktopIdleHeapTrim.onWindowVisibilityChanged(visible = false)
                } else {
                    exitDesktopApplication()
                }
            },
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
                desktopWindow.value = window
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
            DisposableEffect(window, closeToTray) {
                val uninstallTray = if (closeToTray) {
                    installDesktopTray(window, exitDesktopApplication)
                } else {
                    null
                }
                trayInstalled.value = uninstallTray != null
                onDispose {
                    trayInstalled.value = false
                    uninstallTray?.invoke()
                }
            }
            // Remembering where the user left the window means only persisting rects that are
            // genuinely the windowed placement. Compose keeps syncing state.position/size from AWT
            // while borderless fullscreen or PiP is driving the bounds natively, and an iconified
            // window reports an off-screen origin on Windows, so those states are excluded outright.
            // collectLatest then swallows the intermediate rects reported while a transition is
            // still settling — only the rect that survives the delay reaches storage.
            LaunchedEffect(window, windowState) {
                snapshotFlow {
                    DesktopWindowGeometrySample(
                        placement = windowState.placement,
                        isMinimized = windowState.isMinimized,
                        position = windowState.position,
                        size = windowState.size,
                        borderlessFullscreen = isBorderlessFullscreen.value,
                        pictureInPicture = desktopPictureInPictureState.value,
                    )
                }
                    .distinctUntilChanged()
                    .collectLatest { sample ->
                        val geometry = sample.toWindowedGeometry() ?: return@collectLatest
                        delay(WindowGeometrySettleDelayMs)
                        DesktopWindowModeStorage.saveWindowedGeometry(geometry)
                    }
            }
            DisposableEffect(window, windowState) {
                val uninstallDisplayMetricsTracking = installDesktopDisplayMetricsTracking(window)
                var pictureInPictureRestore: DesktopPictureInPictureRestore? = null
                val unregisterPictureInPicture = registerDesktopPictureInPictureHandler { active, targetWindow ->
                    if (targetWindow != null && targetWindow !== window) return@registerDesktopPictureInPictureHandler
                    if (active) {
                        if (pictureInPictureRestore != null) return@registerDesktopPictureInPictureHandler
                        pictureInPictureRestore = DesktopPictureInPictureRestore(
                            bounds = Rectangle(window.bounds),
                            placement = windowState.placement,
                            extendedState = window.extendedState,
                            alwaysOnTop = window.isAlwaysOnTop,
                            borderlessFullscreen = isBorderlessFullscreen.value,
                        )
                        if (isBorderlessFullscreen.value) {
                            suspendNativeBorderlessFullscreen(window, true)
                            isBorderlessFullscreen.value = false
                        }
                        desktopAppFullscreenState.value = false
                        windowState.placement = WindowPlacement.Floating
                        window.extendedState = Frame.NORMAL
                        applyNativeCompactPlayerWindow(window, true)
                        window.isAlwaysOnTop = true
                        window.bounds = pictureInPictureBounds(window)
                        window.toFront()
                    } else {
                        val restore = pictureInPictureRestore ?: return@registerDesktopPictureInPictureHandler
                        pictureInPictureRestore = null
                        // Remember where the user left the PiP window (after any native move/resize)
                        // so the next enter reopens there instead of the default corner.
                        lastPictureInPictureBounds = Rectangle(window.bounds)
                        applyNativeCompactPlayerWindow(window, false)
                        window.isAlwaysOnTop = restore.alwaysOnTop
                        windowState.placement = WindowPlacement.Floating
                        window.extendedState = Frame.NORMAL
                        window.bounds = restore.bounds
                        if (restore.borderlessFullscreen && DesktopHostOs.current == DesktopHostOs.WINDOWS) {
                            suspendNativeBorderlessFullscreen(window, false)
                            isBorderlessFullscreen.value = true
                            desktopAppFullscreenState.value = true
                        } else {
                            window.extendedState = restore.extendedState
                            windowState.placement = restore.placement
                            desktopAppFullscreenState.value = restore.placement == WindowPlacement.Fullscreen
                        }
                        window.toFront()
                    }
                }
                val unregisterFullscreenToggle = registerDesktopAppFullscreenToggle { targetWindow ->
                    if (targetWindow != null && targetWindow !== window) return@registerDesktopAppFullscreenToggle
                    if (desktopPictureInPictureState.value) {
                        setDesktopPictureInPicture(false, window)
                        return@registerDesktopAppFullscreenToggle
                    }
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
                    if (event.keyCode == VK_BROWSER_FORWARD) {
                        when (event.id) {
                            KeyEvent.KEY_PRESSED ->
                                DesktopNavigationGestureBridge.setHorizontalScrollModifierActive(true)
                            KeyEvent.KEY_RELEASED ->
                                DesktopNavigationGestureBridge.setHorizontalScrollModifierActive(false)
                        }
                        return@KeyEventDispatcher true
                    }
                    if (event.id != KeyEvent.KEY_PRESSED) return@KeyEventDispatcher false
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
                    if (
                        event is MouseEvent &&
                        event.id == MouseEvent.MOUSE_PRESSED &&
                        event.button == 4
                    ) {
                        DesktopNavigationGestureBridge.requestBack(DesktopBackRequestSource.Mouse)
                    }
                }
                val mouseFiveShiftEventQueue = MouseFiveShiftEventQueue()
                // Diagnostic: minimize/restore fires on the AWT thread regardless of Compose's
                // render state, so these timestamps can be lined up against the BingeAdvance logs to
                // confirm whether binge auto-advance stalls specifically while the window is iconified
                // (a paused frame clock stops recomposition, which the advance path currently rides on).
                val windowStateListener = WindowStateListener { event ->
                    val wasIconified = event.oldState and Frame.ICONIFIED != 0
                    val isIconified = event.newState and Frame.ICONIFIED != 0
                    if (wasIconified != isIconified) {
                        com.nuvio.app.features.player.BingeAdvanceLog.i {
                            if (isIconified) "window minimized" else "window restored"
                        }
                        DesktopIdleHeapTrim.onWindowVisibilityChanged(visible = !isIconified)
                    }
                }
                window.addWindowStateListener(windowStateListener)
                KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(backNavigationDispatcher)
                Toolkit.getDefaultToolkit().systemEventQueue.push(mouseFiveShiftEventQueue)
                Toolkit.getDefaultToolkit().addAWTEventListener(
                    mouseBackButtonListener,
                    AWTEvent.MOUSE_EVENT_MASK,
                )
                onDispose {
                    DesktopNavigationGestureBridge.setHorizontalScrollModifierActive(false)
                    uninstallDisplayMetricsTracking()
                    window.removeWindowStateListener(windowStateListener)
                    KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(backNavigationDispatcher)
                    Toolkit.getDefaultToolkit().removeAWTEventListener(mouseBackButtonListener)
                    mouseFiveShiftEventQueue.uninstall()
                    uninstallFullscreenShortcuts()
                    unregisterFullscreenToggle()
                    setDesktopPictureInPicture(false, window)
                    unregisterPictureInPicture()
                    if (isBorderlessFullscreen.value) {
                        applyNativeBorderlessFullscreen(window, false)
                        isBorderlessFullscreen.value = false
                    }
                    desktopAppFullscreenState.value = false
                    desktopPictureInPictureState.value = false
                }
            }

            // Diagnostics for the "white lines / black bars along the fullscreen edges" report.
            // Borderless fullscreen resizes and restyles the HWND natively, so every AWT-side
            // consumer of the window's geometry (frame insets, content pane, Skia layer, and the
            // SwingPanel-positioned mpv surface) has to re-derive itself afterwards. Sampling the
            // whole chain right after the transition and again once it has settled shows which
            // layer, if any, kept the pre-transition numbers.
            LaunchedEffect(window) {
                snapshotFlow { desktopAppFullscreenState.value }
                    .collectLatest { fullscreen ->
                        val label = if (fullscreen) "fullscreen" else "windowed"
                        DesktopWindowGeometryDiagnostics.log(window, "$label +0ms")
                        delay(500)
                        DesktopWindowGeometryDiagnostics.log(window, "$label +500ms")
                        delay(2_500)
                        DesktopWindowGeometryDiagnostics.log(window, "$label +3000ms")
                    }
            }

            LaunchedEffect(window) {
                if (startWindowed) {
                    if (isWindowsHost) {
                        withContext(Dispatchers.IO) { runCatching { ensureNativePlayerBridgeLoaded() } }
                        var shownWaitMs = 0
                        while (!window.isShowing && shownWaitMs < 10_000) {
                            delay(16)
                            shownWaitMs += 16
                        }
                        if (window.isShowing) applyNativeDesktopWindowChrome(window)
                    }
                    desktopAppFullscreenState.value = false
                    return@LaunchedEffect
                }
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
                        // Bubble-phase handling means focused text fields retain ordinary paste.
                        // Elsewhere, a copied HTTP(S) media URL opens through the same direct-play
                        // path as a dropped local file; App rejects it while playback is active.
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            if (event.isCtrlPressed && event.key == Key.V) {
                                val clipboardText = runCatching {
                                    Toolkit.getDefaultToolkit().systemClipboard
                                        .getData(DataFlavor.stringFlavor) as? String
                                }.getOrNull()?.trim().orEmpty()
                                val webStream = clipboardText.takeIf {
                                    it.startsWith("https://", ignoreCase = true) ||
                                        it.startsWith("http://", ignoreCase = true)
                                } ?: return@onKeyEvent false
                                LocalFileDrop.emit(webStream)
                                return@onKeyEvent true
                            }
                            if (event.isCtrlPressed) return@onKeyEvent false
                            // Compose text fields may leave ordinary key-down events unconsumed.
                            // Do not turn content searches or Settings edits into global navigation.
                            if (TextInputFocusTracker.active.value) return@onKeyEvent false
                            AppShortcutsRepository.ensureLoaded()
                            when (val action = AppShortcutsRepository.actionForKeyCode(event.key.nativeKeyCode)) {
                                AppShortcutAction.GoHome,
                                AppShortcutAction.OpenSearch,
                                AppShortcutAction.OpenLibrary,
                                AppShortcutAction.OpenDiscover,
                                AppShortcutAction.OpenCalendar,
                                // Routed through App so it inherits the same guard the other
                                // navigation shortcuts get: nothing fires while the player is up.
                                AppShortcutAction.ToggleGameMode -> {
                                    AppShortcutBridge.emit(action)
                                    true
                                }
                                AppShortcutAction.ToggleFullscreen -> {
                                    toggleDesktopAppFullscreen(window)
                                    true
                                }
                                AppShortcutAction.GoBack -> {
                                    DesktopNavigationGestureBridge.requestBack()
                                    true
                                }
                                else -> false
                            }
                        }
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

    // The hero cast and MDBList ratings caches batch their writes rather than rewriting a 3.4 MB
    // file per title (see CoalescingCachePersister), so anything from the last few seconds is still
    // only in memory at this point. Both are re-fetchable, but re-fetching costs API budget, so
    // spend a moment here rather than throw them away. Bounded, because this is the last thing
    // between the user and the process going away.
    runBlocking {
        withTimeoutOrNull(CACHE_FLUSH_TIMEOUT_MS) {
            runCatching { HeroCastMetadataService.flushPendingWrites() }
            runCatching { MdbListMetadataService.flushPendingWrites() }
        }
    }

    // Allow a brief grace period for background coroutines (e.g., scrobble network requests
    // triggered by UI teardown) to complete before hard-terminating the JVM.
    Thread.sleep(400)
    kotlin.system.exitProcess(0)
}

/** Cap on the exit-path cache flush: a stuck disk must not stop the app from closing. */
private const val CACHE_FLUSH_TIMEOUT_MS = 2_000L

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
