package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioModalBottomSheet
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
import nuvio.composeapp.generated.resources.cd_selected
import nuvio.composeapp.generated.resources.settings_appearance_app_language
import nuvio.composeapp.generated.resources.settings_appearance_app_language_sheet_title
import nuvio.composeapp.generated.resources.settings_appearance_amoled_black
import nuvio.composeapp.generated.resources.settings_appearance_amoled_description
import nuvio.composeapp.generated.resources.settings_appearance_liquid_glass
import nuvio.composeapp.generated.resources.settings_appearance_liquid_glass_description
import nuvio.composeapp.generated.resources.settings_appearance_desktop_navigation
import nuvio.composeapp.generated.resources.settings_appearance_section_display
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
                if (selectedTheme == AppTheme.CUSTOM) {
                    SettingsGroupDivider(isTablet = isTablet)
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
        SettingsSection(
            title = stringResource(Res.string.settings_appearance_section_display),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
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
                    hideLabelsEnabled = posterCardStyleUiState.hideLabelsEnabled,
                    onWidthSelected = PosterCardStyleRepository::setWidthDp,
                    onCornerRadiusSelected = PosterCardStyleRepository::setCornerRadiusDp,
                    onCatalogLandscapeModeChange = PosterCardStyleRepository::setCatalogLandscapeModeEnabled,
                    onHideLabelsChange = PosterCardStyleRepository::setHideLabelsEnabled,
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

@Composable
private fun CustomThemeEditor(
    customTheme: CustomThemeSettings,
    isTablet: Boolean,
) {
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Custom theme",
                    style = if (isTablet) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Use six-digit hex colors. Changes apply as soon as a value is valid.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            NuvioActionLabel(
                text = stringResource(Res.string.action_reset),
                onClick = ThemeSettingsRepository::resetCustomTheme,
            )
        }
        CustomThemeColorField(
            label = "Accent",
            value = customTheme.accentHex,
            onValidHex = ThemeSettingsRepository::setCustomThemeAccent,
        )
        CustomThemeColorField(
            label = "Background",
            value = customTheme.backgroundHex,
            onValidHex = ThemeSettingsRepository::setCustomThemeBackground,
        )
        CustomThemeColorField(
            label = "Raised surface",
            value = customTheme.elevatedHex,
            onValidHex = ThemeSettingsRepository::setCustomThemeElevated,
        )
        CustomThemeColorField(
            label = "Card surface",
            value = customTheme.cardHex,
            onValidHex = ThemeSettingsRepository::setCustomThemeCard,
        )
    }
}

@Composable
private fun CustomThemeColorField(
    label: String,
    value: String,
    onValidHex: (String) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value) }
    val parsedColor = text.themeHexColorOrNull()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(parsedColor ?: MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.72f),
                    shape = CircleShape,
                )
                .clickable {
                    pickCustomThemeColor(text)?.let { pickedHex ->
                        text = pickedHex
                        onValidHex(pickedHex)
                    }
                },
        )
        OutlinedTextField(
            value = text,
            onValueChange = { next ->
                text = next
                if (next.isValidThemeHexInput()) {
                    onValidHex(next)
                }
            },
            modifier = Modifier.weight(1f),
            singleLine = true,
            label = { Text(label) },
            supportingText = if (parsedColor == null) {
                { Text("Example: #1E88E5") }
            } else {
                null
            },
            isError = parsedColor == null,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f),
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
        )
    }
}

private fun String.isValidThemeHexInput(): Boolean =
    trim().removePrefix("#").let { cleaned ->
        cleaned.length == 6 && cleaned.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

private fun String.themeHexColorOrNull(): Color? {
    val cleaned = trim().removePrefix("#")
    if (cleaned.length != 6 || !cleaned.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
        return null
    }
    return runCatching { Color(("FF$cleaned").toLong(16)) }.getOrNull()
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
                    .background(palette.secondary),
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
