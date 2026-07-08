package com.nuvio.app.features.settings

import kotlinx.serialization.json.JsonObject

internal expect object ThemeSettingsStorage {
    fun loadSelectedTheme(): String?
    fun saveSelectedTheme(themeName: String)
    fun loadCustomThemeAccent(): String?
    fun saveCustomThemeAccent(hex: String)
    fun loadCustomThemeBackground(): String?
    fun saveCustomThemeBackground(hex: String)
    fun loadCustomThemeElevated(): String?
    fun saveCustomThemeElevated(hex: String)
    fun loadCustomThemeCard(): String?
    fun saveCustomThemeCard(hex: String)
    fun loadAmoledEnabled(): Boolean?
    fun saveAmoledEnabled(enabled: Boolean)
    fun loadLiquidGlassNativeTabBarEnabled(): Boolean?
    fun saveLiquidGlassNativeTabBarEnabled(enabled: Boolean)
    fun loadDesktopColumnGuidesVisible(): Boolean?
    fun saveDesktopColumnGuidesVisible(visible: Boolean)
    fun loadWasdNavigationEnabled(): Boolean?
    fun saveWasdNavigationEnabled(enabled: Boolean)
    fun loadDesktopNavigationLayout(): String?
    fun saveDesktopNavigationLayout(layoutName: String)
    fun loadSelectedAppLanguage(): String?
    fun saveSelectedAppLanguage(languageCode: String)
    fun applySelectedAppLanguage(languageCode: String)
    fun exportToSyncPayload(): JsonObject
    fun replaceFromSyncPayload(payload: JsonObject)
}
