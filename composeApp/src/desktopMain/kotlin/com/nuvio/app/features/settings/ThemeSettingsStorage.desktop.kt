package com.nuvio.app.features.settings

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

internal actual object ThemeSettingsStorage {
    private const val selectedThemeKey = "selected_theme"
    private const val customThemeAccentKey = "custom_theme_accent"
    private const val customThemeAccentEndKey = "custom_theme_accent_end"
    private const val customThemeBackgroundKey = "custom_theme_background"
    private const val customThemeElevatedKey = "custom_theme_elevated"
    private const val customThemeCardKey = "custom_theme_card"
    private const val accentGradientDirectionKey = "accent_gradient_direction"
    private const val amoledEnabledKey = "amoled_enabled"
    private const val liquidGlassNativeTabBarEnabledKey = "liquid_glass_native_tab_bar_enabled"
    private const val desktopColumnGuidesVisibleKey = "desktop_column_guides_visible"
    private const val wasdNavigationEnabledKey = "wasd_navigation_enabled"
    private const val desktopNavigationLayoutKey = "desktop_navigation_layout"
    private const val desktopTopBarAlwaysVisibleKey = "desktop_top_bar_always_visible"
    private const val desktopDiscoverTabVisibleKey = "desktop_discover_tab_visible"
    private const val desktopAppUiScalePercentKey = "desktop_app_ui_scale_percent"
    private const val desktopAppUiScaleAppliesToDetailsKey = "desktop_app_ui_scale_applies_to_details"
    private const val appFontFamilyKey = "app_font_family"
    private const val selectedAppLanguageKey = "selected_app_language"
    // Only keys understood by the official applications belong in the shared mobile payload.
    // Desktop layout, scaling, navigation, and native-tab-bar flags remain device-local. The
    // accent gradient stop is fork-only, so it is never exported — it is listed here purely so an
    // incoming replace clears it and a synced accent cannot inherit a stale local gradient.
    private val portableSyncKeys = listOf(
        selectedThemeKey,
        customThemeAccentKey,
        customThemeAccentEndKey,
        customThemeBackgroundKey,
        customThemeElevatedKey,
        customThemeCardKey,
        amoledEnabledKey,
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

    actual fun loadCustomThemeAccentEnd(): String? =
        store.getString(ProfileScopedKey.of(customThemeAccentEndKey))

    actual fun saveCustomThemeAccentEnd(hex: String) {
        store.putString(ProfileScopedKey.of(customThemeAccentEndKey), hex)
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

    actual fun loadAccentGradientDirection(): String? =
        store.getString(ProfileScopedKey.of(accentGradientDirectionKey))

    actual fun saveAccentGradientDirection(directionName: String) {
        store.putString(ProfileScopedKey.of(accentGradientDirectionKey), directionName)
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

    actual fun loadDesktopTopBarAlwaysVisible(): Boolean? {
        store.getBoolean(ProfileScopedKey.of(desktopTopBarAlwaysVisibleKey))?.let { return it }
        // Opt in only genuine first-time installations. An absent key on an existing install means
        // the user had the old auto-hiding behavior and must not be changed by an upgrade.
        if (!DesktopStorage.isFreshInstall) return null
        saveDesktopTopBarAlwaysVisible(true)
        return true
    }

    actual fun saveDesktopTopBarAlwaysVisible(enabled: Boolean) {
        store.putBoolean(ProfileScopedKey.of(desktopTopBarAlwaysVisibleKey), enabled)
    }

    actual fun loadDesktopDiscoverTabVisible(): Boolean? =
        store.getBoolean(ProfileScopedKey.of(desktopDiscoverTabVisibleKey))

    actual fun saveDesktopDiscoverTabVisible(visible: Boolean) {
        store.putBoolean(ProfileScopedKey.of(desktopDiscoverTabVisibleKey), visible)
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

    // Device-local, and deliberately outside the portable sync payload: a family installed on this
    // machine says nothing about what the next device has, and a name that resolves to nothing
    // there would silently fall back to the bundled face.
    actual fun loadAppFontFamily(): String? =
        store.getString(ProfileScopedKey.of(appFontFamilyKey))

    actual fun saveAppFontFamily(fontFamily: String) {
        store.putString(ProfileScopedKey.of(appFontFamilyKey), fontFamily)
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
    }

    actual fun replaceFromSyncPayload(payload: JsonObject) {
        store.removeAll(portableSyncKeys.map(ProfileScopedKey::of))
        payload.decodeSyncString(selectedThemeKey)?.let(::saveSelectedTheme)
        payload.decodeSyncString(customThemeAccentKey)?.let(::saveCustomThemeAccent)
        payload.decodeSyncString(customThemeBackgroundKey)?.let(::saveCustomThemeBackground)
        payload.decodeSyncString(customThemeElevatedKey)?.let(::saveCustomThemeElevated)
        payload.decodeSyncString(customThemeCardKey)?.let(::saveCustomThemeCard)
        payload.decodeSyncBoolean(amoledEnabledKey)?.let(::saveAmoledEnabled)
        applySelectedAppLanguage(loadSelectedAppLanguage() ?: AppLanguage.ENGLISH.code)
    }
}
