package com.nuvio.app.core.ui

import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomPhone

private const val liquidGlassNativeTabBarEnabledKey = "NuvioLiquidGlassNativeTabBarEnabled"
private const val nativeTabBarVisibleKey = "NuvioNativeTabBarVisible"
private const val nativeSelectedTabKey = "NuvioNativeSelectedTab"
private const val nativeTabChromeDidChangeNotification = "NuvioNativeTabChromeDidChange"

internal actual fun isLiquidGlassNativeTabBarSupported(): Boolean {
    return UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPhone &&
        (UIDevice.currentDevice.systemVersion.substringBefore(".").toIntOrNull() ?: 0) >= 26
}

internal actual fun publishLiquidGlassNativeTabBarEnabled(enabled: Boolean) {
    publishBool(liquidGlassNativeTabBarEnabledKey, enabled)
}

internal actual fun publishNativeTabBarVisible(visible: Boolean) {
    publishBool(nativeTabBarVisibleKey, visible)
}

internal actual fun publishNativeSelectedTab(tabName: String) {
    NSUserDefaults.standardUserDefaults.setObject(tabName, forKey = nativeSelectedTabKey)
    notifyNativeTabChromeChanged()
}

private fun publishBool(key: String, value: Boolean) {
    NSUserDefaults.standardUserDefaults.setBool(value, forKey = key)
    notifyNativeTabChromeChanged()
}

private fun notifyNativeTabChromeChanged() {
    NSNotificationCenter.defaultCenter.postNotificationName(nativeTabChromeDidChangeNotification, null)
}
