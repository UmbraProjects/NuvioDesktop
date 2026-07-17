package com.nuvio.app.features.settings

import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NativeTabBridge
import com.nuvio.app.core.ui.WasdNavigation
import com.nuvio.app.core.ui.ThemeColorPalette
import com.nuvio.app.core.ui.ThemeColors
import com.nuvio.app.core.ui.normalizedThemeHex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CustomThemeSettings(
    val accentHex: String = ThemeColors.DefaultCustomAccentHex,
    val backgroundHex: String = ThemeColors.DefaultCustomBackgroundHex,
    val elevatedHex: String = ThemeColors.DefaultCustomElevatedHex,
    val cardHex: String = ThemeColors.DefaultCustomCardHex,
) {
    val palette: ThemeColorPalette =
        ThemeColors.customPalette(
            accentHex = accentHex,
            backgroundHex = backgroundHex,
            elevatedHex = elevatedHex,
            cardHex = cardHex,
        )
}

object ThemeSettingsRepository {
    private val _selectedTheme = MutableStateFlow(AppTheme.WHITE)
    val selectedTheme: StateFlow<AppTheme> = _selectedTheme.asStateFlow()

    private val _customTheme = MutableStateFlow(CustomThemeSettings())
    val customTheme: StateFlow<CustomThemeSettings> = _customTheme.asStateFlow()

    private val _amoledEnabled = MutableStateFlow(false)
    val amoledEnabled: StateFlow<Boolean> = _amoledEnabled.asStateFlow()

    private val _liquidGlassNativeTabBarEnabled = MutableStateFlow(false)
    val liquidGlassNativeTabBarEnabled: StateFlow<Boolean> = _liquidGlassNativeTabBarEnabled.asStateFlow()

    private val _desktopColumnGuidesVisible = MutableStateFlow(true)
    val desktopColumnGuidesVisible: StateFlow<Boolean> = _desktopColumnGuidesVisible.asStateFlow()

    private val _wasdNavigationEnabled = MutableStateFlow(false)
    val wasdNavigationEnabled: StateFlow<Boolean> = _wasdNavigationEnabled.asStateFlow()

    private val _desktopNavigationLayout = MutableStateFlow(DesktopNavigationLayout.Default)
    val desktopNavigationLayout: StateFlow<DesktopNavigationLayout> = _desktopNavigationLayout.asStateFlow()

    private val _desktopAppUiScalePercent = MutableStateFlow(0)
    val desktopAppUiScalePercent: StateFlow<Int> = _desktopAppUiScalePercent.asStateFlow()

    private val _desktopAppUiScaleAppliesToDetails = MutableStateFlow(true)
    val desktopAppUiScaleAppliesToDetails: StateFlow<Boolean> = _desktopAppUiScaleAppliesToDetails.asStateFlow()

    private val _selectedAppLanguage = MutableStateFlow(AppLanguage.ENGLISH)
    val selectedAppLanguage: StateFlow<AppLanguage> = _selectedAppLanguage.asStateFlow()

    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun clearLocalState() {
        hasLoaded = false
        _selectedTheme.value = AppTheme.WHITE
        _customTheme.value = CustomThemeSettings()
        _amoledEnabled.value = false
        _liquidGlassNativeTabBarEnabled.value = false
        _desktopColumnGuidesVisible.value = true
        _wasdNavigationEnabled.value = false
        WasdNavigation.enabled = false
        _desktopNavigationLayout.value = DesktopNavigationLayout.Default
        _desktopAppUiScalePercent.value = 0
        _desktopAppUiScaleAppliesToDetails.value = true
        NativeTabBridge.publishAccentColor(ThemeColors.White.nativeAccentHex)
        NativeTabBridge.publishLiquidGlassEnabled(false)
        _selectedAppLanguage.value = AppLanguage.ENGLISH
    }

    private fun loadFromDisk() {
        hasLoaded = true
        val stored = ThemeSettingsStorage.loadSelectedTheme()
        _customTheme.value = CustomThemeSettings(
            accentHex = ThemeSettingsStorage.loadCustomThemeAccent()
                ?.normalizedThemeHex(ThemeColors.DefaultCustomAccentHex)
                ?: ThemeColors.DefaultCustomAccentHex,
            backgroundHex = ThemeSettingsStorage.loadCustomThemeBackground()
                ?.normalizedThemeHex(ThemeColors.DefaultCustomBackgroundHex)
                ?: ThemeColors.DefaultCustomBackgroundHex,
            elevatedHex = ThemeSettingsStorage.loadCustomThemeElevated()
                ?.normalizedThemeHex(ThemeColors.DefaultCustomElevatedHex)
                ?: ThemeColors.DefaultCustomElevatedHex,
            cardHex = ThemeSettingsStorage.loadCustomThemeCard()
                ?.normalizedThemeHex(ThemeColors.DefaultCustomCardHex)
                ?: ThemeColors.DefaultCustomCardHex,
        )
        val theme = if (stored != null) {
            try {
                AppTheme.valueOf(stored)
            } catch (_: IllegalArgumentException) {
                AppTheme.WHITE
            }
        } else {
            AppTheme.WHITE
        }
        _selectedTheme.value = theme
        NativeTabBridge.publishAccentColor(theme.nativeTabAccentHex(_customTheme.value))
        _amoledEnabled.value = ThemeSettingsStorage.loadAmoledEnabled() ?: false
        val liquidGlassEnabled = ThemeSettingsStorage.loadLiquidGlassNativeTabBarEnabled() ?: false
        _liquidGlassNativeTabBarEnabled.value = liquidGlassEnabled
        NativeTabBridge.publishLiquidGlassEnabled(liquidGlassEnabled)
        _desktopColumnGuidesVisible.value = ThemeSettingsStorage.loadDesktopColumnGuidesVisible() ?: true
        val wasdEnabled = ThemeSettingsStorage.loadWasdNavigationEnabled() ?: false
        _wasdNavigationEnabled.value = wasdEnabled
        WasdNavigation.enabled = wasdEnabled
        _desktopNavigationLayout.value = DesktopNavigationLayout.fromName(
            ThemeSettingsStorage.loadDesktopNavigationLayout(),
        )
        _desktopAppUiScalePercent.value =
            ThemeSettingsStorage.loadDesktopAppUiScalePercent()?.coerceIn(-25, 25) ?: 0
        _desktopAppUiScaleAppliesToDetails.value =
            ThemeSettingsStorage.loadDesktopAppUiScaleAppliesToDetails() ?: true
        val appLanguage = AppLanguage.fromCode(ThemeSettingsStorage.loadSelectedAppLanguage())
        ThemeSettingsStorage.applySelectedAppLanguage(appLanguage.code)
        _selectedAppLanguage.value = appLanguage
    }

    fun setTheme(theme: AppTheme) {
        ensureLoaded()
        if (_selectedTheme.value == theme) return
        _selectedTheme.value = theme
        ThemeSettingsStorage.saveSelectedTheme(theme.name)
        NativeTabBridge.publishAccentColor(theme.nativeTabAccentHex(_customTheme.value))
    }

    fun setCustomThemeAccent(hex: String) {
        updateCustomTheme(accentHex = hex.normalizedThemeHex(ThemeColors.DefaultCustomAccentHex))
    }

    fun setCustomThemeBackground(hex: String) {
        updateCustomTheme(backgroundHex = hex.normalizedThemeHex(ThemeColors.DefaultCustomBackgroundHex))
    }

    fun setCustomThemeElevated(hex: String) {
        updateCustomTheme(elevatedHex = hex.normalizedThemeHex(ThemeColors.DefaultCustomElevatedHex))
    }

    fun setCustomThemeCard(hex: String) {
        updateCustomTheme(cardHex = hex.normalizedThemeHex(ThemeColors.DefaultCustomCardHex))
    }

    fun resetCustomTheme() {
        updateCustomTheme(
            accentHex = ThemeColors.DefaultCustomAccentHex,
            backgroundHex = ThemeColors.DefaultCustomBackgroundHex,
            elevatedHex = ThemeColors.DefaultCustomElevatedHex,
            cardHex = ThemeColors.DefaultCustomCardHex,
        )
    }

    private fun updateCustomTheme(
        accentHex: String = _customTheme.value.accentHex,
        backgroundHex: String = _customTheme.value.backgroundHex,
        elevatedHex: String = _customTheme.value.elevatedHex,
        cardHex: String = _customTheme.value.cardHex,
    ) {
        ensureLoaded()
        val next = CustomThemeSettings(
            accentHex = accentHex,
            backgroundHex = backgroundHex,
            elevatedHex = elevatedHex,
            cardHex = cardHex,
        )
        if (_customTheme.value == next) return
        _customTheme.value = next
        ThemeSettingsStorage.saveCustomThemeAccent(next.accentHex)
        ThemeSettingsStorage.saveCustomThemeBackground(next.backgroundHex)
        ThemeSettingsStorage.saveCustomThemeElevated(next.elevatedHex)
        ThemeSettingsStorage.saveCustomThemeCard(next.cardHex)
        if (_selectedTheme.value == AppTheme.CUSTOM) {
            NativeTabBridge.publishAccentColor(next.palette.nativeAccentHex)
        }
    }

    fun setAmoled(enabled: Boolean) {
        ensureLoaded()
        if (_amoledEnabled.value == enabled) return
        _amoledEnabled.value = enabled
        ThemeSettingsStorage.saveAmoledEnabled(enabled)
    }

    fun setLiquidGlassNativeTabBar(enabled: Boolean) {
        ensureLoaded()
        if (_liquidGlassNativeTabBarEnabled.value == enabled) return
        _liquidGlassNativeTabBarEnabled.value = enabled
        ThemeSettingsStorage.saveLiquidGlassNativeTabBarEnabled(enabled)
        NativeTabBridge.publishLiquidGlassEnabled(enabled)
    }

    fun setDesktopColumnGuidesVisible(visible: Boolean) {
        ensureLoaded()
        if (_desktopColumnGuidesVisible.value == visible) return
        _desktopColumnGuidesVisible.value = visible
        ThemeSettingsStorage.saveDesktopColumnGuidesVisible(visible)
    }

    fun setWasdNavigationEnabled(enabled: Boolean) {
        ensureLoaded()
        if (_wasdNavigationEnabled.value == enabled) return
        _wasdNavigationEnabled.value = enabled
        WasdNavigation.enabled = enabled
        ThemeSettingsStorage.saveWasdNavigationEnabled(enabled)
    }

    fun setDesktopNavigationLayout(layout: DesktopNavigationLayout) {
        ensureLoaded()
        if (_desktopNavigationLayout.value == layout) return
        _desktopNavigationLayout.value = layout
        ThemeSettingsStorage.saveDesktopNavigationLayout(layout.name)
    }

    fun setDesktopAppUiScalePercent(percent: Int) {
        ensureLoaded()
        val clamped = percent.coerceIn(-25, 25)
        if (_desktopAppUiScalePercent.value == clamped) return
        _desktopAppUiScalePercent.value = clamped
        ThemeSettingsStorage.saveDesktopAppUiScalePercent(clamped)
    }

    fun setDesktopAppUiScaleAppliesToDetails(enabled: Boolean) {
        ensureLoaded()
        if (_desktopAppUiScaleAppliesToDetails.value == enabled) return
        _desktopAppUiScaleAppliesToDetails.value = enabled
        ThemeSettingsStorage.saveDesktopAppUiScaleAppliesToDetails(enabled)
    }

    fun setAppLanguage(language: AppLanguage) {
        ensureLoaded()
        if (_selectedAppLanguage.value == language) return
        ThemeSettingsStorage.saveSelectedAppLanguage(language.code)
        ThemeSettingsStorage.applySelectedAppLanguage(language.code)
        _selectedAppLanguage.value = language
    }
}

private fun AppTheme.nativeTabAccentHex(customTheme: CustomThemeSettings): String =
    if (this == AppTheme.CUSTOM) {
        customTheme.palette.nativeAccentHex
    } else {
        ThemeColors.getColorPalette(this).nativeAccentHex
    }
