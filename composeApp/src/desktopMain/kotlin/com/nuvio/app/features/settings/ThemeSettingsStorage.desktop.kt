package com.nuvio.app.features.settings

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.decodeSyncInt
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncInt
import com.nuvio.app.core.sync.encodeSyncString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

internal actual object ThemeSettingsStorage {
    private const val selectedThemeKey = "selected_theme"
    private const val customThemeAccentKey = "custom_theme_accent"
    private const val customThemeBackgroundKey = "custom_theme_background"
    private const val customThemeElevatedKey = "custom_theme_elevated"
    private const val customThemeCardKey = "custom_theme_card"
    private const val amoledEnabledKey = "amoled_enabled"
    private const val liquidGlassNativeTabBarEnabledKey = "liquid_glass_native_tab_bar_enabled"
    private const val desktopColumnGuidesVisibleKey = "desktop_column_guides_visible"
    private const val wasdNavigationEnabledKey = "wasd_navigation_enabled"
    private const val desktopNavigationLayoutKey = "desktop_navigation_layout"
    private const val desktopAppUiScalePercentKey = "desktop_app_ui_scale_percent"
    private const val desktopAppUiScaleAppliesToDetailsKey = "desktop_app_ui_scale_applies_to_details"
    private const val selectedAppLanguageKey = "selected_app_language"
    private val profileScopedSyncKeys = listOf(
        selectedThemeKey,
        customThemeAccentKey,
        customThemeBackgroundKey,
        customThemeElevatedKey,
        customThemeCardKey,
        amoledEnabledKey,
        liquidGlassNativeTabBarEnabledKey,
        desktopColumnGuidesVisibleKey,
        desktopNavigationLayoutKey,
        desktopAppUiScalePercentKey,
        desktopAppUiScaleAppliesToDetailsKey,
    )
    private val store = DesktopStorage.store("nuvio_theme_settings")

    actual fun loadSelectedTheme(): String? =
        store.getString(ProfileScopedKey.of(selectedThemeKey))

    actual fun saveSelectedTheme(themeName: String) {
        store.putString(ProfileScopedKey.of(selectedThemeKey), themeName)
    }

    actual fun loadCustomThemeAccent(): String? =
        store.getString(ProfileScopedKey.of(customThemeAccentKey))

    actual fun saveCustomThemeAccent(hex: String) {
        store.putString(ProfileScopedKey.of(customThemeAccentKey), hex)
    }

    actual fun loadCustomThemeBackground(): String? =
        store.getString(ProfileScopedKey.of(customThemeBackgroundKey))

    actual fun saveCustomThemeBackground(hex: String) {
        store.putString(ProfileScopedKey.of(customThemeBackgroundKey), hex)
    }

    actual fun loadCustomThemeElevated(): String? =
        store.getString(ProfileScopedKey.of(customThemeElevatedKey))

    actual fun saveCustomThemeElevated(hex: String) {
        store.putString(ProfileScopedKey.of(customThemeElevatedKey), hex)
    }

    actual fun loadCustomThemeCard(): String? =
        store.getString(ProfileScopedKey.of(customThemeCardKey))

    actual fun saveCustomThemeCard(hex: String) {
        store.putString(ProfileScopedKey.of(customThemeCardKey), hex)
    }

    actual fun loadAmoledEnabled(): Boolean? =
        store.getBoolean(ProfileScopedKey.of(amoledEnabledKey))

    actual fun saveAmoledEnabled(enabled: Boolean) {
        store.putBoolean(ProfileScopedKey.of(amoledEnabledKey), enabled)
    }

    actual fun loadLiquidGlassNativeTabBarEnabled(): Boolean? =
        store.getBoolean(ProfileScopedKey.of(liquidGlassNativeTabBarEnabledKey))

    actual fun saveLiquidGlassNativeTabBarEnabled(enabled: Boolean) {
        store.putBoolean(ProfileScopedKey.of(liquidGlassNativeTabBarEnabledKey), enabled)
    }

    actual fun loadDesktopColumnGuidesVisible(): Boolean? =
        store.getBoolean(ProfileScopedKey.of(desktopColumnGuidesVisibleKey))

    actual fun saveDesktopColumnGuidesVisible(visible: Boolean) {
        store.putBoolean(ProfileScopedKey.of(desktopColumnGuidesVisibleKey), visible)
    }

    actual fun loadWasdNavigationEnabled(): Boolean? =
        store.getBoolean(ProfileScopedKey.of(wasdNavigationEnabledKey))

    actual fun saveWasdNavigationEnabled(enabled: Boolean) {
        store.putBoolean(ProfileScopedKey.of(wasdNavigationEnabledKey), enabled)
    }

    actual fun loadDesktopNavigationLayout(): String? =
        store.getString(ProfileScopedKey.of(desktopNavigationLayoutKey))

    actual fun saveDesktopNavigationLayout(layoutName: String) {
        store.putString(ProfileScopedKey.of(desktopNavigationLayoutKey), layoutName)
    }

    actual fun loadDesktopAppUiScalePercent(): Int? =
        store.getInt(ProfileScopedKey.of(desktopAppUiScalePercentKey))

    actual fun saveDesktopAppUiScalePercent(percent: Int) {
        store.putInt(ProfileScopedKey.of(desktopAppUiScalePercentKey), percent)
    }

    actual fun loadDesktopAppUiScaleAppliesToDetails(): Boolean? =
        store.getBoolean(ProfileScopedKey.of(desktopAppUiScaleAppliesToDetailsKey))

    actual fun saveDesktopAppUiScaleAppliesToDetails(enabled: Boolean) {
        store.putBoolean(ProfileScopedKey.of(desktopAppUiScaleAppliesToDetailsKey), enabled)
    }

    actual fun loadSelectedAppLanguage(): String? =
        store.getString(selectedAppLanguageKey)
            ?: Locale.getDefault().toLanguageTag().takeIf { it.isNotBlank() }

    actual fun saveSelectedAppLanguage(languageCode: String) {
        store.putString(selectedAppLanguageKey, languageCode)
    }

    actual fun applySelectedAppLanguage(languageCode: String) {
        Locale.setDefault(Locale.forLanguageTag(languageCode))
    }

    actual fun exportToSyncPayload(): JsonObject = buildJsonObject {
        loadSelectedTheme()?.let { put(selectedThemeKey, encodeSyncString(it)) }
        loadCustomThemeAccent()?.let { put(customThemeAccentKey, encodeSyncString(it)) }
        loadCustomThemeBackground()?.let { put(customThemeBackgroundKey, encodeSyncString(it)) }
        loadCustomThemeElevated()?.let { put(customThemeElevatedKey, encodeSyncString(it)) }
        loadCustomThemeCard()?.let { put(customThemeCardKey, encodeSyncString(it)) }
        loadAmoledEnabled()?.let { put(amoledEnabledKey, encodeSyncBoolean(it)) }
        loadLiquidGlassNativeTabBarEnabled()?.let { put(liquidGlassNativeTabBarEnabledKey, encodeSyncBoolean(it)) }
        loadDesktopColumnGuidesVisible()?.let { put(desktopColumnGuidesVisibleKey, encodeSyncBoolean(it)) }
        loadDesktopNavigationLayout()?.let { put(desktopNavigationLayoutKey, encodeSyncString(it)) }
        loadDesktopAppUiScalePercent()?.let { put(desktopAppUiScalePercentKey, encodeSyncInt(it)) }
        loadDesktopAppUiScaleAppliesToDetails()?.let {
            put(desktopAppUiScaleAppliesToDetailsKey, encodeSyncBoolean(it))
        }
    }

    actual fun replaceFromSyncPayload(payload: JsonObject) {
        store.removeAll(profileScopedSyncKeys.map(ProfileScopedKey::of))
        payload.decodeSyncString(selectedThemeKey)?.let(::saveSelectedTheme)
        payload.decodeSyncString(customThemeAccentKey)?.let(::saveCustomThemeAccent)
        payload.decodeSyncString(customThemeBackgroundKey)?.let(::saveCustomThemeBackground)
        payload.decodeSyncString(customThemeElevatedKey)?.let(::saveCustomThemeElevated)
        payload.decodeSyncString(customThemeCardKey)?.let(::saveCustomThemeCard)
        payload.decodeSyncBoolean(amoledEnabledKey)?.let(::saveAmoledEnabled)
        payload.decodeSyncBoolean(liquidGlassNativeTabBarEnabledKey)?.let(::saveLiquidGlassNativeTabBarEnabled)
        payload.decodeSyncBoolean(desktopColumnGuidesVisibleKey)?.let(::saveDesktopColumnGuidesVisible)
        payload.decodeSyncString(desktopNavigationLayoutKey)?.let(::saveDesktopNavigationLayout)
        payload.decodeSyncInt(desktopAppUiScalePercentKey)?.let(::saveDesktopAppUiScalePercent)
        payload.decodeSyncBoolean(desktopAppUiScaleAppliesToDetailsKey)
            ?.let(::saveDesktopAppUiScaleAppliesToDetails)
        applySelectedAppLanguage(loadSelectedAppLanguage() ?: AppLanguage.ENGLISH.code)
    }
}
