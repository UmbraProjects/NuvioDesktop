package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.ThemeColors

@OptIn(ExperimentalLayoutApi::class)
internal fun LazyListScope.appearanceSettingsContent(
    isTablet: Boolean,
    selectedTheme: AppTheme,
    onThemeSelected: (AppTheme) -> Unit,
    amoledEnabled: Boolean,
    onAmoledToggle: (Boolean) -> Unit,
    onContinueWatchingClick: () -> Unit,
    onPosterCustomizationClick: () -> Unit,
) {
    item {
        SettingsSection(
            title = "THEME",
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                val themes = listOf(AppTheme.WHITE) + AppTheme.entries.filterNot { it == AppTheme.WHITE }
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = if (isTablet) 20.dp else 16.dp,
                            vertical = if (isTablet) 18.dp else 14.dp,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(if (isTablet) 16.dp else 12.dp),
                    verticalArrangement = Arrangement.spacedBy(if (isTablet) 16.dp else 12.dp),
                ) {
                    themes.forEach { theme ->
                        ThemeChip(
                            theme = theme,
                            isSelected = theme == selectedTheme,
                            onClick = { onThemeSelected(theme) },
                        )
                    }
                }
            }
        }
    }

    item {
        SettingsSection(
            title = "DISPLAY",
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = "AMOLED Black",
                    description = "Use pure black backgrounds for OLED screens.",
                    checked = amoledEnabled,
                    isTablet = isTablet,
                    onCheckedChange = onAmoledToggle,
                )
            }
        }
    }

    item {
        SettingsSection(
            title = "HOME",
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = "Continue Watching",
                    description = "Show, hide, and style the Continue Watching shelf.",
                    icon = Icons.Rounded.Style,
                    isTablet = isTablet,
                    onClick = onContinueWatchingClick,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = "Poster Customization",
                    description = "Adjust shared poster card width and corner radius presets.",
                    icon = Icons.Rounded.Tune,
                    isTablet = isTablet,
                    onClick = onPosterCustomizationClick,
                )
            }
        }
    }
}

@Composable
private fun ThemeChip(
    theme: AppTheme,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val palette = ThemeColors.getColorPalette(theme)

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 1.5.dp,
                        color = palette.focusRing,
                        shape = RoundedCornerShape(12.dp),
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
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
                    contentDescription = "Selected",
                    tint = palette.onSecondary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = theme.displayName,
            style = MaterialTheme.typography.labelMedium,
            color = if (isSelected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            textAlign = TextAlign.Center,
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
