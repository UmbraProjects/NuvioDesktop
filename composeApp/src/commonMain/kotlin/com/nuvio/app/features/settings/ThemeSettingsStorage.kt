package com.nuvio.app.features.settings

import kotlinx.serialization.json.JsonObject

internal expect object ThemeSettingsStorage {
    fun loadSelectedTheme(): String?
    fun saveSelectedTheme(themeName: String)
    fun loadCustomThemeAccent(): String?
    fun saveCustomThemeAccent(hex: String)
    fun loadCustomThemeAccentEnd(): String?
    fun saveCustomThemeAccentEnd(hex: String)
    fun loadCustomThemeBackground(): String?
    fun saveCustomThemeBackground(hex: String)
    fun loadCustomThemeElevated(): String?
    fun saveCustomThemeElevated(hex: String)
    fun loadCustomThemeCard(): String?
    fun saveCustomThemeCard(hex: String)
    fun loadAccentGradientDirection(): String?
    fun saveAccentGradientDirection(directionName: String)
    fun loadAmoledEnabled(): Boolean?
    fun saveAmoledEnabled(enabled: Boolean)
    fun loadLiquidGlassNativeTabBarEnabled(): Boolean?
    fun saveLiquidGlassNativeTabBarEnabled(enabled: Boolean)
    fun loadDesktopColumnGuidesVisible(): Boolean?
    fun saveDesktopColumnGuidesVisible(visible: Boolean)
    fun loadDesktopSettingsFullWidth(): Boolean?
    fun saveDesktopSettingsFullWidth(enabled: Boolean)
    fun loadWasdNavigationEnabled(): Boolean?
    fun saveWasdNavigationEnabled(enabled: Boolean)
    fun loadHoldToSelectEnabled(): Boolean?
    fun saveHoldToSelectEnabled(enabled: Boolean)
    fun loadDesktopNavigationLayout(): String?
    fun saveDesktopNavigationLayout(layoutName: String)
    fun loadDesktopTopBarAlwaysVisible(): Boolean?
    fun saveDesktopTopBarAlwaysVisible(enabled: Boolean)
    fun loadDesktopDiscoverTabVisible(): Boolean?
    fun saveDesktopDiscoverTabVisible(visible: Boolean)
    fun loadDesktopAppUiScalePercent(): Int?
    fun saveDesktopAppUiScalePercent(percent: Int)
    fun loadDesktopAppUiScaleAppliesToDetails(): Boolean?
    fun saveDesktopAppUiScaleAppliesToDetails(enabled: Boolean)
    fun loadAppFontFamily(): String?
    fun saveAppFontFamily(fontFamily: String)
    fun loadAppFontScalePercent(): Int?
    fun saveAppFontScalePercent(percent: Int)
    fun loadSelectedAppLanguage(): String?
    fun saveSelectedAppLanguage(languageCode: String)
    fun applySelectedAppLanguage(languageCode: String)
    fun exportToSyncPayload(): JsonObject
    fun replaceFromSyncPayload(payload: JsonObject)
}
