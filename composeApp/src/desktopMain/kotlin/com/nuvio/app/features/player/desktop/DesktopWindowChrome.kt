package com.nuvio.app.features.player.desktop

import java.awt.Window

private const val NuvioWindowBackgroundRgb = 0x0D0D0D
private const val NuvioWindowTextRgb = 0xF5F7F8

/**
 * Publishes the process AppUserModelID and a "Nuvio HTPC" Start Menu shortcut carrying it, so the
 * Windows media flyout shows the app name and icon instead of "Unknown app". The shortcut is
 * rewritten only when its target, AUMID or icon drifts, which lets a moved portable copy heal
 * itself on the next launch and undoes the official Nuvio Desktop's icon picker, which re-icons
 * any "Nuvio.lnk" it finds regardless of what the shortcut launches.
 */
internal fun registerDesktopAppIdentity() {
    if (DesktopHostOs.current != DesktopHostOs.WINDOWS) return
    // Timed in parts: the whole step was 165-533 ms before the window (Nov 24), and the report it
    // came from could not say how much was loading the bridge DLL (which has to happen here) and
    // how much the shortcut walk (which does not).
    val startedAt = System.nanoTime()
    val bridgeLoaded = runCatching { NativePlayerBridge.hashCode() }.isSuccess
    val loadedAt = System.nanoTime()
    if (!bridgeLoaded) {
        appIdentityLog.w { "App identity skipped: native bridge did not load in ${(loadedAt - startedAt) / 1_000_000}ms" }
        return
    }
    runCatching { NativePlayerBridge.setAppUserModelId() }
    val aumidAt = System.nanoTime()
    appIdentityLog.i {
        "App identity: bridge load ${(loadedAt - startedAt) / 1_000_000}ms, AUMID ${(aumidAt - loadedAt) / 1_000_000}ms"
    }
    Thread({
        val repairStartedAt = System.nanoTime()
        runCatching { NativePlayerBridge.repairAppShortcuts() }
            .onFailure { appIdentityLog.w(it) { "App shortcut repair failed" } }
        appIdentityLog.i { "App shortcuts checked in ${(System.nanoTime() - repairStartedAt) / 1_000_000}ms (background)" }
    }, "nuvio-app-shortcuts").apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
        start()
    }
}

private val appIdentityLog = co.touchlab.kermit.Logger.withTag("DesktopStartup")

internal fun applyNativeDesktopWindowChrome(window: Window) {
    if (DesktopHostOs.current != DesktopHostOs.WINDOWS || !window.isDisplayable) return

    runCatching {
        val hwnd = AwtNativeViewResolver.resolveNativeViewPointer(window)
        NativePlayerBridge.applyWindowChrome(
            windowHwnd = hwnd,
            darkMode = true,
            captionColorRgb = NuvioWindowBackgroundRgb,
            borderColorRgb = NuvioWindowBackgroundRgb,
            textColorRgb = NuvioWindowTextRgb,
        )
    }
}

/**
 * Cloaks or uncloaks the Win32 window via DWM (`DWMWA_CLOAK`). While cloaked, DWM does not
 * composite the window onto the monitor — eliminating white title-bar, unpainted Swing/GDI, and
 * windowed-to-fullscreen resize flashes — while keeping AWT, focus, and Skiko rendering active.
 */
internal fun applyNativeWindowCloaked(window: Window, cloaked: Boolean): Boolean {
    if (DesktopHostOs.current != DesktopHostOs.WINDOWS || !window.isDisplayable) return false

    return runCatching {
        val hwnd = AwtNativeViewResolver.resolveNativeViewPointer(window)
        NativePlayerBridge.setWindowCloaked(hwnd, cloaked)
        true
    }.getOrDefault(false)
}

internal fun applyNativeBorderlessFullscreen(window: Window, enabled: Boolean) {
    // isShowing (not just isDisplayable): restyling a created-but-not-yet-shown window
    // desyncs AWT's show/bounds bookkeeping and breaks activation and keyboard focus.
    if (DesktopHostOs.current != DesktopHostOs.WINDOWS || !window.isShowing) return

    runCatching {
        val hwnd = AwtNativeViewResolver.resolveNativeViewPointer(window)
        NativePlayerBridge.setBorderlessFullscreen(hwnd, enabled)
    }
    // The style change above is invisible to AWT, which keeps laying the content out inside the
    // decorated window's frame insets until something corrects it.
    DesktopBorderlessRootPaneFix.setActive(window, enabled)
}

internal fun applyNativeCompactPlayerWindow(window: Window, enabled: Boolean) {
    if (DesktopHostOs.current != DesktopHostOs.WINDOWS || !window.isShowing) return

    runCatching {
        val hwnd = AwtNativeViewResolver.resolveNativeViewPointer(window)
        NativePlayerBridge.setCompactPlayerWindow(hwnd, enabled)
    }
}

internal fun suspendNativeBorderlessFullscreen(window: Window, suspended: Boolean) {
    if (DesktopHostOs.current != DesktopHostOs.WINDOWS || !window.isShowing) return

    runCatching {
        val hwnd = AwtNativeViewResolver.resolveNativeViewPointer(window)
        NativePlayerBridge.setBorderlessFullscreenSuspended(hwnd, suspended)
    }
    DesktopBorderlessRootPaneFix.setActive(window, !suspended)
}

internal fun beginNativeCompactPlayerWindowMove(window: Window?) {
    if (DesktopHostOs.current != DesktopHostOs.WINDOWS || window?.isShowing != true) return

    runCatching {
        val hwnd = AwtNativeViewResolver.resolveNativeViewPointer(window)
        NativePlayerBridge.beginCompactPlayerWindowMove(hwnd)
    }
}

internal fun beginNativeCompactPlayerWindowResize(window: Window?, edge: Int) {
    if (DesktopHostOs.current != DesktopHostOs.WINDOWS || window?.isShowing != true || edge !in 1..8) return

    runCatching {
        val hwnd = AwtNativeViewResolver.resolveNativeViewPointer(window)
        NativePlayerBridge.beginCompactPlayerWindowResize(hwnd, edge)
    }
}

internal fun beginNativeCompactPlayerWindowInteraction(window: Window?, mode: Int) {
    if (DesktopHostOs.current != DesktopHostOs.WINDOWS || window?.isShowing != true || mode !in 1..9) return
    if (mode == 1) {
        beginNativeCompactPlayerWindowMove(window)
    } else {
        beginNativeCompactPlayerWindowResize(window, mode - 1)
    }
}

internal fun updateNativeCompactPlayerWindowInteraction(window: Window?) {
    if (DesktopHostOs.current != DesktopHostOs.WINDOWS || window?.isShowing != true) return

    runCatching {
        val hwnd = AwtNativeViewResolver.resolveNativeViewPointer(window)
        NativePlayerBridge.updateCompactPlayerWindowInteraction(hwnd)
    }
}

internal fun endNativeCompactPlayerWindowInteraction(window: Window?) {
    if (DesktopHostOs.current != DesktopHostOs.WINDOWS || window?.isShowing != true) return

    runCatching {
        val hwnd = AwtNativeViewResolver.resolveNativeViewPointer(window)
        NativePlayerBridge.endCompactPlayerWindowInteraction(hwnd)
    }
}
