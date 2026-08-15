package com.nuvio.app.features.player.desktop

import java.awt.Window

private const val NuvioWindowBackgroundRgb = 0x0D0D0D
private const val NuvioWindowTextRgb = 0xF5F7F8

/**
 * Publishes the process AppUserModelID and a Start Menu shortcut carrying it, so the Windows
 * media flyout shows "Nuvio" and the app icon instead of "Unknown app". The shortcut is rewritten
 * only when its target drifts, which lets a moved portable copy heal itself on the next launch.
 */
internal fun registerDesktopAppIdentity() {
    if (DesktopHostOs.current != DesktopHostOs.WINDOWS) return
    runCatching { NativePlayerBridge.initializeAppIdentity() }
}

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
