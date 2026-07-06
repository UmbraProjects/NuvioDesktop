package com.nuvio.app.features.player.desktop

import java.awt.Window

private const val NuvioWindowBackgroundRgb = 0x0D0D0D
private const val NuvioWindowTextRgb = 0xF5F7F8

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
}
