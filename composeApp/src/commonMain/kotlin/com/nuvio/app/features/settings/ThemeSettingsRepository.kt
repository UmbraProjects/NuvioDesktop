package com.nuvio.app.features.settings

import com.nuvio.app.core.ui.AccentGradientDirection
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
    // Second stop of the accent gradient. Equal to accentHex means accents stay flat.
    val accentEndHex: String = ThemeColors.DefaultCustomAccentEndHex,
    val backgroundHex: String = ThemeColors.DefaultCustomBackgroundHex,
    val elevatedHex: String = ThemeColors.DefaultCustomElevatedHex,
    val cardHex: String = ThemeColors.DefaultCustomCardHex,
) {
    val palette: ThemeColorPalette =
        ThemeColors.customPalette(
            accentHex = accentHex,
            accentEndHex = accentEndHex,
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

    private val _accentGradientDirection = MutableStateFlow(AccentGradientDirection.Default)
    val accentGradientDirection: StateFlow<AccentGradientDirection> = _accentGradientDirection.asStateFlow()

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

    // Pins the floating top bar open instead of letting it fade out until the pointer reaches the
    // activation strip. Settings is unaffected — it renders its own chrome, not this bar.
    private val _desktopTopBarAlwaysVisible = MutableStateFlow(false)
    val desktopTopBarAlwaysVisible: StateFlow<Boolean> = _desktopTopBarAlwaysVisible.asStateFlow()

    // Hides the Discover entry from the desktop navigation. Navigation to the tab is blocked while
    // it is off, so the tab can never be selected without a way back to it in the bar.
    private val _desktopDiscoverTabVisible = MutableStateFlow(true)
    val desktopDiscoverTabVisible: StateFlow<Boolean> = _desktopDiscoverTabVisible.asStateFlow()

    private val _desktopAppUiScalePercent = MutableStateFlow(0)
    val desktopAppUiScalePercent: StateFlow<Int> = _desktopAppUiScalePercent.asStateFlow()

    private val _desktopAppUiScaleAppliesToDetails = MutableStateFlow(true)
    val desktopAppUiScaleAppliesToDetails: StateFlow<Boolean> = _desktopAppUiScaleAppliesToDetails.asStateFlow()

    // "" means the bundled JetBrains Sans. Any other value is an installed system family name,
    // resolved (and validated) by the theme — nothing here checks the host has it.
    private val _appFontFamily = MutableStateFlow("")
    val appFontFamily: StateFlow<String> = _appFontFamily.asStateFlow()

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
        _accentGradientDirection.value = AccentGradientDirection.Default
        _amoledEnabled.value = false
        _liquidGlassNativeTabBarEnabled.value = false
        _desktopColumnGuidesVisible.value = true
        _wasdNavigationEnabled.value = false
        WasdNavigation.enabled = false
        _desktopNavigationLayout.value = DesktopNavigationLayout.Default
        _desktopTopBarAlwaysVisible.value = false
        _desktopDiscoverTabVisible.value = true
        _desktopAppUiScalePercent.value = 0
        _desktopAppUiScaleAppliesToDetails.value = true
        NativeTabBridge.publishAccentColor(ThemeColors.White.nativeAccentHex)
        NativeTabBridge.publishLiquidGlassEnabled(false)
        _appFontFamily.value = ""
        _selectedAppLanguage.value = AppLanguage.ENGLISH
    }

    private fun loadFromDisk() {
        hasLoaded = true
        val stored = ThemeSettingsStorage.loadSelectedTheme()
        _customTheme.value = CustomThemeSettings(
            accentHex = ThemeSettingsStorage.loadCustomThemeAccent()
                ?.normalizedThemeHex(ThemeColors.DefaultCustomAccentHex)
                ?: ThemeColors.DefaultCustomAccentHex,
            // No stored value means "no gradient": fall back to the accent, not to a fixed default,
            // so upgrades keep the flat accent the user already had.
            accentEndHex = ThemeSettingsStorage.loadCustomThemeAccentEnd()
                ?.normalizedThemeHex(ThemeColors.DefaultCustomAccentEndHex)
                ?: ThemeSettingsStorage.loadCustomThemeAccent()
                    ?.normalizedThemeHex(ThemeColors.DefaultCustomAccentEndHex)
                ?: ThemeColors.DefaultCustomAccentEndHex,
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
        _accentGradientDirection.value =
            AccentGradientDirection.fromStorageOrDefault(ThemeSettingsStorage.loadAccentGradientDirection())
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
        _desktopTopBarAlwaysVisible.value = ThemeSettingsStorage.loadDesktopTopBarAlwaysVisible() ?: false
        _desktopDiscoverTabVisible.value = ThemeSettingsStorage.loadDesktopDiscoverTabVisible() ?: true
        _desktopAppUiScalePercent.value =
            ThemeSettingsStorage.loadDesktopAppUiScalePercent()?.coerceIn(-25, 25) ?: 0
        _desktopAppUiScaleAppliesToDetails.value =
            ThemeSettingsStorage.loadDesktopAppUiScaleAppliesToDetails() ?: true
        _appFontFamily.value = ThemeSettingsStorage.loadAppFontFamily()?.trim().orEmpty()
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
        ensureLoaded()
        val next = hex.normalizedThemeHex(ThemeColors.DefaultCustomAccentHex)
        val current = _customTheme.value
        // A flat accent has both stops on the same colour. Carry the end stop along so changing the
        // accent cannot strand the old colour as a gradient the user never asked for.
        val nextEnd = if (current.accentEndHex == current.accentHex) next else current.accentEndHex
        updateCustomTheme(accentHex = next, accentEndHex = nextEnd)
    }

    fun setCustomThemeAccentEnd(hex: String) {
        updateCustomTheme(accentEndHex = hex.normalizedThemeHex(ThemeColors.DefaultCustomAccentEndHex))
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
            accentEndHex = ThemeColors.DefaultCustomAccentEndHex,
            backgroundHex = ThemeColors.DefaultCustomBackgroundHex,
            elevatedHex = ThemeColors.DefaultCustomElevatedHex,
            cardHex = ThemeColors.DefaultCustomCardHex,
        )
    }

    private fun updateCustomTheme(
        accentHex: String = _customTheme.value.accentHex,
        accentEndHex: String = _customTheme.value.accentEndHex,
        backgroundHex: String = _customTheme.value.backgroundHex,
        elevatedHex: String = _customTheme.value.elevatedHex,
        cardHex: String = _customTheme.value.cardHex,
    ) {
        ensureLoaded()
        val next = CustomThemeSettings(
            accentHex = accentHex,
            accentEndHex = accentEndHex,
            backgroundHex = backgroundHex,
            elevatedHex = elevatedHex,
            cardHex = cardHex,
        )
        if (_customTheme.value == next) return
        _customTheme.value = next
        ThemeSettingsStorage.saveCustomThemeAccent(next.accentHex)
        ThemeSettingsStorage.saveCustomThemeAccentEnd(next.accentEndHex)
        ThemeSettingsStorage.saveCustomThemeBackground(next.backgroundHex)
        ThemeSettingsStorage.saveCustomThemeElevated(next.elevatedHex)
        ThemeSettingsStorage.saveCustomThemeCard(next.cardHex)
        if (_selectedTheme.value == AppTheme.CUSTOM) {
            NativeTabBridge.publishAccentColor(next.palette.nativeAccentHex)
        }
    }

    fun setAccentGradientDirection(direction: AccentGradientDirection) {
        ensureLoaded()
        if (_accentGradientDirection.value == direction) return
        _accentGradientDirection.value = direction
        ThemeSettingsStorage.saveAccentGradientDirection(direction.name)
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

    fun setDesktopTopBarAlwaysVisible(enabled: Boolean) {
        ensureLoaded()
        if (_desktopTopBarAlwaysVisible.value == enabled) return
        _desktopTopBarAlwaysVisible.value = enabled
        ThemeSettingsStorage.saveDesktopTopBarAlwaysVisible(enabled)
    }

    fun setDesktopDiscoverTabVisible(visible: Boolean) {
        ensureLoaded()
        if (_desktopDiscoverTabVisible.value == visible) return
        _desktopDiscoverTabVisible.value = visible
        ThemeSettingsStorage.saveDesktopDiscoverTabVisible(visible)
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

    /** [fontFamily] is an installed system family name, or "" for the bundled JetBrains Sans. */
    fun setAppFontFamily(fontFamily: String) {
        ensureLoaded()
        val next = fontFamily.trim()
        if (_appFontFamily.value == next) return
        _appFontFamily.value = next
        ThemeSettingsStorage.saveAppFontFamily(next)
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
