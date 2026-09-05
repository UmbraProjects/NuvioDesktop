package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.AccentGradientDirection
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioTextField
import com.nuvio.app.core.ui.systemFontFamilies
import com.nuvio.app.core.ui.systemFontFamilyOrNull
import com.nuvio.app.core.ui.NuvioActionLabel
import com.nuvio.app.core.ui.PosterCardStyleRepository
import com.nuvio.app.core.ui.PosterCardStyleUiState
import com.nuvio.app.core.ui.ThemeColorPalette
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.labelRes
import com.nuvio.app.core.ui.ThemeColors
import com.nuvio.app.isDesktop
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_reset
import nuvio.composeapp.generated.resources.settings_appearance_card_depth
import nuvio.composeapp.generated.resources.settings_appearance_custom_theme
import nuvio.composeapp.generated.resources.settings_appearance_custom_theme_accent
import nuvio.composeapp.generated.resources.settings_appearance_accent_gradient_direction
import nuvio.composeapp.generated.resources.settings_appearance_accent_gradient_direction_diagonal
import nuvio.composeapp.generated.resources.settings_appearance_accent_gradient_direction_diagonal_reverse
import nuvio.composeapp.generated.resources.settings_appearance_accent_gradient_direction_horizontal
import nuvio.composeapp.generated.resources.settings_appearance_accent_gradient_direction_vertical
import nuvio.composeapp.generated.resources.settings_appearance_custom_theme_accent_end
import nuvio.composeapp.generated.resources.settings_appearance_custom_theme_accent_end_hint
import nuvio.composeapp.generated.resources.settings_appearance_custom_theme_background
import nuvio.composeapp.generated.resources.settings_appearance_custom_theme_card
import nuvio.composeapp.generated.resources.settings_appearance_custom_theme_description
import nuvio.composeapp.generated.resources.settings_appearance_custom_theme_hex_example
import nuvio.composeapp.generated.resources.settings_appearance_custom_theme_raised
import nuvio.composeapp.generated.resources.cd_selected
import nuvio.composeapp.generated.resources.settings_appearance_app_font
import nuvio.composeapp.generated.resources.settings_appearance_app_font_default
import nuvio.composeapp.generated.resources.settings_appearance_app_font_no_matches
import nuvio.composeapp.generated.resources.settings_appearance_app_font_none_installed
import nuvio.composeapp.generated.resources.settings_appearance_app_font_player_note
import nuvio.composeapp.generated.resources.settings_appearance_app_font_search_placeholder
import nuvio.composeapp.generated.resources.settings_appearance_app_font_sheet_title
import nuvio.composeapp.generated.resources.settings_appearance_app_language
import nuvio.composeapp.generated.resources.settings_appearance_app_language_sheet_title
import nuvio.composeapp.generated.resources.settings_appearance_amoled_black
import nuvio.composeapp.generated.resources.settings_appearance_amoled_description
import nuvio.composeapp.generated.resources.settings_appearance_app_ui_scale
import nuvio.composeapp.generated.resources.settings_appearance_app_ui_scale_details
import nuvio.composeapp.generated.resources.settings_appearance_app_ui_scale_details_description
import nuvio.composeapp.generated.resources.settings_appearance_liquid_glass
import nuvio.composeapp.generated.resources.settings_appearance_liquid_glass_description
import nuvio.composeapp.generated.resources.settings_appearance_desktop_discover_tab
import nuvio.composeapp.generated.resources.settings_appearance_desktop_discover_tab_description
import nuvio.composeapp.generated.resources.settings_appearance_desktop_navigation
import nuvio.composeapp.generated.resources.settings_appearance_desktop_top_bar_always_visible
import nuvio.composeapp.generated.resources.settings_appearance_desktop_top_bar_always_visible_description
import nuvio.composeapp.generated.resources.settings_appearance_section_display
import nuvio.composeapp.generated.resources.settings_appearance_start_windowed
import nuvio.composeapp.generated.resources.settings_appearance_start_windowed_description
import nuvio.composeapp.generated.resources.settings_appearance_close_to_tray
import nuvio.composeapp.generated.resources.settings_appearance_close_to_tray_description
import nuvio.composeapp.generated.resources.settings_appearance_section_theme
import nuvio.composeapp.generated.resources.settings_poster_card_style
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState

internal fun LazyListScope.appearanceSettingsContent(
    isTablet: Boolean,
    selectedTheme: AppTheme,
    onThemeSelected: (AppTheme) -> Unit,
    customTheme: CustomThemeSettings,
    amoledEnabled: Boolean,
    onAmoledToggle: (Boolean) -> Unit,
    liquidGlassNativeTabBarSupported: Boolean,
    liquidGlassNativeTabBarEnabled: Boolean,
    onLiquidGlassNativeTabBarToggle: (Boolean) -> Unit,
    desktopNavigationLayout: DesktopNavigationLayout = DesktopNavigationLayout.Default,
    onDesktopNavigationLayoutSelected: (DesktopNavigationLayout) -> Unit = {},
    desktopAppUiScalePercent: Int = 0,
    onDesktopAppUiScalePercentChange: (Int) -> Unit = {},
    desktopAppUiScaleAppliesToDetails: Boolean = true,
    onDesktopAppUiScaleAppliesToDetailsChange: (Boolean) -> Unit = {},
    selectedAppLanguage: AppLanguage,
    onAppLanguageSelected: (AppLanguage) -> Unit,
    posterCardStyleUiState: PosterCardStyleUiState,
) {
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_appearance_section_theme),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                val themes = listOf(AppTheme.WHITE) + AppTheme.entries.filterNot { it == AppTheme.WHITE }
                val horizontalPadding = if (isTablet) 20.dp else 16.dp
                val verticalPadding = if (isTablet) 18.dp else 14.dp
                val themeSpacing = if (isTablet) 16.dp else 12.dp
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .settingsScrollAnchor(SettingsScrollAnchor.searchKey("theme"))
                        .padding(
                            horizontal = horizontalPadding,
                            vertical = verticalPadding,
                        ),
                ) {
                    val preferredColumns = if (isTablet) 4 else 3
                    val minThemeCellWidth = if (isTablet) 92.dp else 78.dp
                    val themeColumns = ((maxWidth + themeSpacing) / (minThemeCellWidth + themeSpacing))
                        .toInt()
                        .coerceAtLeast(1)
                        .coerceAtMost(preferredColumns)

                    Column(
                        verticalArrangement = Arrangement.spacedBy(themeSpacing),
                    ) {
                        themes.chunked(themeColumns).forEach { rowThemes ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(themeSpacing),
                            ) {
                                rowThemes.forEach { theme ->
                                    ThemeChip(
                                        theme = theme,
                                        palette = if (theme == AppTheme.CUSTOM) {
                                            customTheme.palette
                                        } else {
                                            ThemeColors.getColorPalette(theme)
                                        },
                                        isSelected = theme == selectedTheme,
                                        onClick = { onThemeSelected(theme) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                repeat(themeColumns - rowThemes.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    // Its own section rather than a block nested inside the theme picker: as a nested block it
    // carried an extra layer of horizontal padding on top of the padding each settings row already
    // applies, so every colour field sat indented relative to the rows in every other section.
    if (selectedTheme == AppTheme.CUSTOM) {
        item {
            SettingsSection(
                title = stringResource(Res.string.settings_appearance_custom_theme),
                isTablet = isTablet,
                actions = {
                    NuvioActionLabel(
                        text = stringResource(Res.string.action_reset),
                        onClick = ThemeSettingsRepository::resetCustomTheme,
                    )
                },
            ) {
                SettingsGroup(isTablet = isTablet) {
                    CustomThemeEditor(
                        customTheme = customTheme,
                        isTablet = isTablet,
                    )
                }
            }
        }
    }
    item {
        var showLanguageSheet by remember { mutableStateOf(false) }
        var showFontSheet by remember { mutableStateOf(false) }
        val appFontFamily by remember { ThemeSettingsRepository.appFontFamily }.collectAsState()
        val startWindowed by remember {
            DesktopWindowStartupPreference.ensureLoaded()
            DesktopWindowStartupPreference.startWindowed
        }.collectAsState()
        val closeToTray by remember {
            DesktopWindowStartupPreference.ensureLoaded()
            DesktopWindowStartupPreference.closeToTray
        }.collectAsState()
        val desktopTopBarAlwaysVisible by remember {
            ThemeSettingsRepository.desktopTopBarAlwaysVisible
        }.collectAsState()
        val desktopDiscoverTabVisible by remember {
            ThemeSettingsRepository.desktopDiscoverTabVisible
        }.collectAsState()
        SettingsSection(
            title = stringResource(Res.string.settings_appearance_section_display),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                // App UI scale leads the section: it rescales everything below it, so it reads as
                // the parent control rather than an option buried under the colour toggles.
                if (isDesktop) {
                    SettingsSliderRow(
                        title = stringResource(Res.string.settings_appearance_app_ui_scale),
                        value = desktopAppUiScalePercent,
                        valueText = "${if (desktopAppUiScalePercent > 0) "+" else ""}$desktopAppUiScalePercent%",
                        valueTextForValue = { "${if (it > 0) "+" else ""}$it%" },
                        valueRange = -25..25,
                        step = 5,
                        isTablet = isTablet,
                        onValueChange = onDesktopAppUiScalePercentChange,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_appearance_app_ui_scale_details),
                        description = stringResource(Res.string.settings_appearance_app_ui_scale_details_description),
                        checked = desktopAppUiScaleAppliesToDetails,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("app-ui-scale-details")),
                        onCheckedChange = onDesktopAppUiScaleAppliesToDetailsChange,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    // Sits with the scale knob rather than the colour toggles: both are typography
                    // controls over the same text, and both restyle the whole app at once.
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_appearance_app_font),
                        description = if (appFontFamily.isBlank()) {
                            stringResource(Res.string.settings_appearance_app_font_default)
                        } else {
                            appFontFamily
                        },
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("app-font")),
                        onClick = { showFontSheet = true },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_appearance_start_windowed),
                        description = stringResource(Res.string.settings_appearance_start_windowed_description),
                        checked = startWindowed,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("start-windowed")),
                        onCheckedChange = DesktopWindowStartupPreference::setStartWindowed,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_appearance_close_to_tray),
                        description = stringResource(Res.string.settings_appearance_close_to_tray_description),
                        checked = closeToTray,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("close-to-tray")),
                        onCheckedChange = DesktopWindowStartupPreference::setCloseToTray,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                }
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_appearance_amoled_black),
                    description = stringResource(Res.string.settings_appearance_amoled_description),
                    checked = amoledEnabled,
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("amoled")),
                    onCheckedChange = onAmoledToggle,
                )
                if (liquidGlassNativeTabBarSupported) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_appearance_liquid_glass),
                        description = stringResource(Res.string.settings_appearance_liquid_glass_description),
                        checked = liquidGlassNativeTabBarEnabled,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("liquid-glass")),
                        onCheckedChange = onLiquidGlassNativeTabBarToggle,
                    )
                }
                if (isDesktop) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsChoiceRow(
                        title = stringResource(Res.string.settings_appearance_desktop_navigation),
                        description = stringResource(desktopNavigationLayout.labelRes),
                        options = DesktopNavigationLayout.entries.map { layout ->
                            SettingsChoiceOption(layout, stringResource(layout.labelRes))
                        },
                        selectedValue = desktopNavigationLayout,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("desktop-navigation")),
                        onSelected = onDesktopNavigationLayoutSelected,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_appearance_desktop_discover_tab),
                        description = stringResource(
                            Res.string.settings_appearance_desktop_discover_tab_description,
                        ),
                        checked = desktopDiscoverTabVisible,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(
                            SettingsScrollAnchor.searchKey("desktop-discover-tab"),
                        ),
                        onCheckedChange = ThemeSettingsRepository::setDesktopDiscoverTabVisible,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_appearance_desktop_top_bar_always_visible),
                        description = stringResource(
                            Res.string.settings_appearance_desktop_top_bar_always_visible_description,
                        ),
                        checked = desktopTopBarAlwaysVisible,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(
                            SettingsScrollAnchor.searchKey("desktop-top-bar-always-visible"),
                        ),
                        onCheckedChange = ThemeSettingsRepository::setDesktopTopBarAlwaysVisible,
                    )
                }
                SettingsGroupDivider(isTablet = isTablet)
                SettingsChoiceRow(
                    title = stringResource(Res.string.settings_appearance_app_language),
                    description = stringResource(selectedAppLanguage.labelRes),
                    options = AppLanguage.entries.map { language ->
                        SettingsChoiceOption(language, stringResource(language.labelRes))
                    },
                    selectedValue = selectedAppLanguage,
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("app-language")),
                    onSelected = onAppLanguageSelected,
                    onMoreOptionsClick = { showLanguageSheet = true },
                )
            }
        }

        if (showFontSheet) {
            AppearanceFontBottomSheet(
                selectedFontFamily = appFontFamily,
                onFontFamilySelected = {
                    ThemeSettingsRepository.setAppFontFamily(it)
                    showFontSheet = false
                },
                onDismiss = { showFontSheet = false },
            )
        }

        if (showLanguageSheet) {
            AppearanceLanguageBottomSheet(
                selectedLanguage = selectedAppLanguage,
                onLanguageSelected = {
                    onAppLanguageSelected(it)
                    showLanguageSheet = false
                },
                onDismiss = { showLanguageSheet = false },
            )
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_poster_card_style),
            isTablet = isTablet,
            actions = {
                NuvioActionLabel(
                    text = stringResource(Res.string.action_reset),
                    onClick = PosterCardStyleRepository::resetToDefaults,
                )
            },
        ) {
            SettingsGroup(isTablet = isTablet) {
                PosterCardStyleControls(
                    isTablet = isTablet,
                    widthDp = posterCardStyleUiState.widthDp,
                    cornerRadiusDp = posterCardStyleUiState.cornerRadiusDp,
                    catalogLandscapeModeEnabled = posterCardStyleUiState.catalogLandscapeModeEnabled,
                    collectionsPortraitPostersEnabled = posterCardStyleUiState.collectionsPortraitPostersEnabled,
                    landscapeTextTitlesEnabled = posterCardStyleUiState.landscapeTextTitlesEnabled,
                    landscapeRatingBadgeScale = posterCardStyleUiState.landscapeRatingBadgeScale,
                    posterHighlightMode = posterCardStyleUiState.posterHighlightMode,
                    hideLabelsEnabled = posterCardStyleUiState.hideLabelsEnabled,
                    zoomActionPreviewEnabled = posterCardStyleUiState.zoomActionPreviewEnabled,
                    onWidthSelected = PosterCardStyleRepository::setWidthDp,
                    onCornerRadiusSelected = PosterCardStyleRepository::setCornerRadiusDp,
                    onCatalogLandscapeModeChange = PosterCardStyleRepository::setCatalogLandscapeModeEnabled,
                    onCollectionsPortraitPostersChange = PosterCardStyleRepository::setCollectionsPortraitPostersEnabled,
                    onLandscapeTextTitlesChange = PosterCardStyleRepository::setLandscapeTextTitlesEnabled,
                    onLandscapeRatingBadgeScaleChange = PosterCardStyleRepository::setLandscapeRatingBadgeScale,
                    onHideLabelsChange = PosterCardStyleRepository::setHideLabelsEnabled,
                )
            }
        }
    }
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_appearance_card_depth),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                CardDepthControls(
                    isTablet = isTablet,
                    uiState = posterCardStyleUiState,
                )
            }
        }
    }
}

/**
 * The installed-font picker. Every row previews its own family, which is the only way to tell what
 * a name like "Bahnschrift" actually looks like, and the list is filtered rather than paged: a
 * Windows install can carry several hundred families.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceFontBottomSheet(
    selectedFontFamily: String,
    onFontFamilySelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val installedFonts = remember { systemFontFamilies() }
    var query by remember { mutableStateOf("") }
    val matches = remember(installedFonts, query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            installedFonts
        } else {
            installedFonts.filter { it.contains(trimmed, ignoreCase = true) }
        }
    }
    val defaultLabel = stringResource(Res.string.settings_appearance_app_font_default)

    val dismiss = {
        coroutineScope.launch {
            dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
        }
        Unit
    }

    NuvioModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = sheetState,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        ) {
            item {
                Text(
                    text = stringResource(Res.string.settings_appearance_app_font_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                )
                Text(
                    text = stringResource(Res.string.settings_appearance_app_font_player_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
                )
                NuvioTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(Res.string.settings_appearance_app_font_search_placeholder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp),
                )
            }

            // Always reachable, and never filtered out by the query — it is the way back to the
            // bundled face once a system font has been picked.
            item {
                NuvioBottomSheetDivider()
                NuvioBottomSheetActionRow(
                    title = defaultLabel,
                    onClick = {
                        onFontFamilySelected("")
                        dismiss()
                    },
                    trailingContent = {
                        if (selectedFontFamily.isBlank()) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = stringResource(Res.string.cd_selected),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                )
            }

            if (matches.isEmpty()) {
                item {
                    Text(
                        text = stringResource(
                            if (installedFonts.isEmpty()) {
                                Res.string.settings_appearance_app_font_none_installed
                            } else {
                                Res.string.settings_appearance_app_font_no_matches
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
                    )
                }
            }

            items(matches, key = { it }) { family ->
                NuvioBottomSheetDivider()
                NuvioBottomSheetActionRow(
                    title = family,
                    // Null for a family Skia will not resolve, which leaves the row in the app font
                    // — a name that cannot preview is also one that would not apply.
                    titleFontFamily = systemFontFamilyOrNull(family),
                    onClick = {
                        onFontFamilySelected(family)
                        dismiss()
                    },
                    trailingContent = {
                        if (family.equals(selectedFontFamily, ignoreCase = true)) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = stringResource(Res.string.cd_selected),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                )
            }
        }
    }
}

private data class AppLanguageSheetOption(
    val language: AppLanguage,
    val labelRes: StringResource,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceLanguageBottomSheet(
    selectedLanguage: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val options = remember {
        AppLanguage.entries.map { language ->
            AppLanguageSheetOption(
                language = language,
                labelRes = language.labelRes,
            )
        }
    }

    NuvioModalBottomSheet(
        onDismissRequest = {
            coroutineScope.launch {
                dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
            }
        },
        sheetState = sheetState,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        ) {
            item {
                Text(
                    text = stringResource(Res.string.settings_appearance_app_language_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }

            itemsIndexed(options) { index, option ->
                if (index > 0) {
                    NuvioBottomSheetDivider()
                }
                NuvioBottomSheetActionRow(
                    title = stringResource(option.labelRes),
                    onClick = {
                        onLanguageSelected(option.language)
                        coroutineScope.launch {
                            dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
                        }
                    },
                    trailingContent = {
                        if (option.language == selectedLanguage) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = stringResource(Res.string.cd_selected),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                )
            }
        }
    }
}

/**
 * The custom theme's rows, emitted straight into a [SettingsGroup] so they line up with the rows in
 * every other section. The title and the reset action are the enclosing [SettingsSection]'s header
 * — this used to draw its own heading inside a padded [Column], which is what indented the rows.
 */
@Composable
private fun ColumnScope.CustomThemeEditor(
    customTheme: CustomThemeSettings,
    isTablet: Boolean,
) {
    val accentGradientDirection by remember {
        ThemeSettingsRepository.accentGradientDirection
    }.collectAsState()
    CustomThemeColorField(
        label = stringResource(Res.string.settings_appearance_custom_theme_accent),
        value = customTheme.accentHex,
        isTablet = isTablet,
        onValidHex = ThemeSettingsRepository::setCustomThemeAccent,
    )
    SettingsGroupDivider(isTablet = isTablet)
    CustomThemeColorField(
        label = stringResource(Res.string.settings_appearance_custom_theme_accent_end),
        value = customTheme.accentEndHex,
        isTablet = isTablet,
        description = stringResource(Res.string.settings_appearance_custom_theme_accent_end_hint),
        onValidHex = ThemeSettingsRepository::setCustomThemeAccentEnd,
    )
    // Only shown once the two accent stops actually differ: with a flat accent there is no
    // gradient for a direction to apply to, and the control would do nothing visible.
    if (customTheme.palette.accentGradientEnd != null) {
        SettingsGroupDivider(isTablet = isTablet)
        SettingsChoiceRow(
            title = stringResource(Res.string.settings_appearance_accent_gradient_direction),
            description = stringResource(accentGradientDirection.labelRes()),
            options = AccentGradientDirection.entries.map { direction ->
                SettingsChoiceOption(direction, stringResource(direction.labelRes()))
            },
            selectedValue = accentGradientDirection,
            isTablet = isTablet,
            modifier = Modifier.settingsScrollAnchor(
                SettingsScrollAnchor.searchKey("accent-gradient-direction"),
            ),
            onSelected = ThemeSettingsRepository::setAccentGradientDirection,
        )
    }
    SettingsGroupDivider(isTablet = isTablet)
    CustomThemeColorField(
        label = stringResource(Res.string.settings_appearance_custom_theme_background),
        value = customTheme.backgroundHex,
        isTablet = isTablet,
        onValidHex = ThemeSettingsRepository::setCustomThemeBackground,
    )
    SettingsGroupDivider(isTablet = isTablet)
    CustomThemeColorField(
        label = stringResource(Res.string.settings_appearance_custom_theme_raised),
        value = customTheme.elevatedHex,
        isTablet = isTablet,
        onValidHex = ThemeSettingsRepository::setCustomThemeElevated,
    )
    SettingsGroupDivider(isTablet = isTablet)
    CustomThemeColorField(
        label = stringResource(Res.string.settings_appearance_custom_theme_card),
        value = customTheme.cardHex,
        isTablet = isTablet,
        onValidHex = ThemeSettingsRepository::setCustomThemeCard,
    )
}

@Composable
private fun CustomThemeColorField(
    label: String,
    value: String,
    isTablet: Boolean,
    onValidHex: (String) -> Unit,
    description: String? = null,
) {
    SettingsTextInputRow(
        title = label,
        description = description ?: stringResource(Res.string.settings_appearance_custom_theme_hex_example),
        value = value,
        placeholder = "#1E88E5",
        isTablet = isTablet,
        normalize = { draft ->
            draft.trim().removePrefix("#").let { cleaned -> "#$cleaned" }
        },
        onSave = { next ->
            if (next.isValidThemeHexInput()) onValidHex(next)
        },
    )
}

private fun String.isValidThemeHexInput(): Boolean =
    trim().removePrefix("#").let { cleaned ->
        cleaned.length == 6 && cleaned.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

@Composable
private fun ThemeChip(
    theme: AppTheme,
    palette: ThemeColorPalette,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .then(
                    if (isSelected) {
                        Modifier.border(
                            width = 1.5.dp,
                            color = palette.focusRing,
                            shape = RoundedCornerShape(14.dp),
                        )
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        palette.accentGradientEnd
                            ?.let { Brush.horizontalGradient(listOf(palette.secondary, it)) }
                            ?: SolidColor(palette.secondary),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(Res.string.cd_selected),
                        tint = palette.onSecondary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = stringResource(theme.labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = if (isSelected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(palette.focusRing),
        )
    }
}

/**
 * Kept here rather than on [AccentGradientDirection] itself: the enum lives in `core/ui` because
 * the theme tokens build the brush from it, and it has no business depending on generated string
 * resources to stay usable there.
 */
private fun AccentGradientDirection.labelRes(): StringResource = when (this) {
    AccentGradientDirection.Horizontal -> Res.string.settings_appearance_accent_gradient_direction_horizontal
    AccentGradientDirection.Vertical -> Res.string.settings_appearance_accent_gradient_direction_vertical
    AccentGradientDirection.Diagonal -> Res.string.settings_appearance_accent_gradient_direction_diagonal
    AccentGradientDirection.DiagonalReverse ->
        Res.string.settings_appearance_accent_gradient_direction_diagonal_reverse
}
