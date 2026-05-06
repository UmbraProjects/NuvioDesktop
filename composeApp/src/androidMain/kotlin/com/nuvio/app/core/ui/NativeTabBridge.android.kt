package com.nuvio.app.core.ui

internal actual fun isLiquidGlassNativeTabBarSupported(): Boolean = false

internal actual fun publishLiquidGlassNativeTabBarEnabled(enabled: Boolean) = Unit

internal actual fun publishNativeTabBarVisible(visible: Boolean) = Unit

internal actual fun publishNativeSelectedTab(tabName: String) = Unit
