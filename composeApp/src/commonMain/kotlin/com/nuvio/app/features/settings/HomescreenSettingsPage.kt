package com.nuvio.app.features.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioActionLabel
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.home.HeroBadgePlacement
import com.nuvio.app.features.home.HomeCatalogSettingsItem
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.components.HomeEmptyStateCard
import com.nuvio.app.isDesktop
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_reset
import nuvio.composeapp.generated.resources.layout_hide_unreleased
import nuvio.composeapp.generated.resources.layout_hide_unreleased_sub
import nuvio.composeapp.generated.resources.settings_homescreen_empty_message
import nuvio.composeapp.generated.resources.settings_homescreen_empty_title
import nuvio.composeapp.generated.resources.settings_homescreen_hide_catalog_underline
import nuvio.composeapp.generated.resources.settings_homescreen_hide_catalog_underline_description
import nuvio.composeapp.generated.resources.settings_homescreen_hero_ambient_background
import nuvio.composeapp.generated.resources.settings_homescreen_hero_ambient_background_description
import nuvio.composeapp.generated.resources.settings_homescreen_adaptive_hero
import nuvio.composeapp.generated.resources.settings_homescreen_adaptive_hero_description
import nuvio.composeapp.generated.resources.settings_homescreen_keep_home_focused
import nuvio.composeapp.generated.resources.settings_homescreen_limit_reached
import nuvio.composeapp.generated.resources.settings_homescreen_no_sources_selected
import nuvio.composeapp.generated.resources.settings_homescreen_pin_to_move_toast
import nuvio.composeapp.generated.resources.settings_homescreen_section_catalogs
import nuvio.composeapp.generated.resources.settings_homescreen_section_catalogs_collections
import nuvio.composeapp.generated.resources.settings_homescreen_section_collections
import nuvio.composeapp.generated.resources.settings_homescreen_section_hero
import nuvio.composeapp.generated.resources.settings_homescreen_section_hero_sources
import nuvio.composeapp.generated.resources.settings_homescreen_selected_count
import nuvio.composeapp.generated.resources.settings_homescreen_show_hero
import nuvio.composeapp.generated.resources.settings_homescreen_show_hero_description
import nuvio.composeapp.generated.resources.settings_homescreen_summary
import nuvio.composeapp.generated.resources.settings_homescreen_summary_hint
import nuvio.composeapp.generated.resources.settings_homescreen_tv_mode
import nuvio.composeapp.generated.resources.settings_homescreen_tv_mode_description
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

internal fun LazyListScope.homescreenSettingsContent(
    isTablet: Boolean,
    heroEnabled: Boolean,
    heroInfoLines: Int,
    heroInfoPriority: String,
    heroBadgePlacement: HeroBadgePlacement,
    heroBadgeScale: Float,
    heroReleaseStatusUnavailableOnly: Boolean,
    hideUnreleasedContent: Boolean,
    hideCatalogUnderline: Boolean,
    adaptiveHeroEnabled: Boolean = false,
    adaptiveHeroVerticalBias: Float = -0.58f,
    heroAmbientBackgroundEnabled: Boolean = false,
    tvModeEnabled: Boolean = false,
    items: List<HomeCatalogSettingsItem>,
) {
    val selectedHeroSourceCount = items.count { it.heroSourceEnabled }
    val enabledCatalogCount = items.count { it.enabled }
    item {
        HomescreenSummaryCard(
            isTablet = isTablet,
            enabledCatalogCount = enabledCatalogCount,
            totalCatalogCount = items.size,
            selectedHeroSourceCount = selectedHeroSourceCount,
        )
    }
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_homescreen_section_hero),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_homescreen_show_hero),
                    description = stringResource(Res.string.settings_homescreen_show_hero_description),
                    checked = heroEnabled,
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("home-hero")),
                    onCheckedChange = HomeCatalogSettingsRepository::setHeroEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                HeroBadgeCountDropdownRow(
                    heroInfoLines = heroInfoLines,
                    heroEnabled = heroEnabled,
                    isTablet = isTablet,
                )
                  SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = "Hero badge position",
                    description = heroBadgePlacement.settingsLabel(),
                    isTablet = isTablet,
                    enabled = heroEnabled,
                    onClick = {
                        HomeCatalogSettingsRepository.setHeroBadgePlacement(heroBadgePlacement.nextPlacement())
                    },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = "Hero badge size",
                    description = "${heroBadgeScale.heroBadgeScaleLabel()}  (larger for TV viewing)",
                    isTablet = isTablet,
                    enabled = heroEnabled,
                    onClick = {
                        HomeCatalogSettingsRepository.setHeroBadgeScale(heroBadgeScale.nextHeroBadgeScale())
                    },
                )
                  SettingsGroupDivider(isTablet = isTablet)
                  androidx.compose.foundation.layout.Row(
                      modifier = androidx.compose.ui.Modifier.padding(horizontal = 24.dp, vertical = 16.dp).fillMaxWidth(),
                      verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                  ) {
                      androidx.compose.material3.OutlinedTextField(
                          value = heroInfoPriority,
                          onValueChange = { HomeCatalogSettingsRepository.setHeroInfoPriority(it) },
                          label = { androidx.compose.material3.Text("Hero Info Priority (comma separated)") },
                          modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                          enabled = heroEnabled,
                          singleLine = true
                      )
                  }
                  SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = "Only show unavailable release status",
                    description = "Show release status only for cinema and production titles.",
                    checked = heroReleaseStatusUnavailableOnly,
                    enabled = heroEnabled,
                    isTablet = isTablet,
                    onCheckedChange = HomeCatalogSettingsRepository::setHeroReleaseStatusUnavailableOnly,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.layout_hide_unreleased),
                    description = stringResource(Res.string.layout_hide_unreleased_sub),
                    checked = hideUnreleasedContent,
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("home-hide-unreleased")),
                    onCheckedChange = HomeCatalogSettingsRepository::setHideUnreleasedContent,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_homescreen_hide_catalog_underline),
                    description = stringResource(Res.string.settings_homescreen_hide_catalog_underline_description),
                    checked = hideCatalogUnderline,
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("home-hide-catalog-underline")),
                    onCheckedChange = HomeCatalogSettingsRepository::setHideCatalogUnderline,
                )
                if (isDesktop) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_homescreen_adaptive_hero),
                        description = stringResource(Res.string.settings_homescreen_adaptive_hero_description),
                        checked = adaptiveHeroEnabled,
                        enabled = heroEnabled && !tvModeEnabled,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.AdaptiveHero),
                        onCheckedChange = HomeCatalogSettingsRepository::setAdaptiveHeroEnabled,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    AdaptiveHeroVerticalBiasRow(
                        bias = adaptiveHeroVerticalBias,
                        enabled = heroEnabled && adaptiveHeroEnabled && !tvModeEnabled,
                        isTablet = isTablet,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_homescreen_hero_ambient_background),
                        description = stringResource(Res.string.settings_homescreen_hero_ambient_background_description),
                        checked = heroAmbientBackgroundEnabled,
                        enabled = heroEnabled && !tvModeEnabled,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.HeroAmbient),
                        onCheckedChange = HomeCatalogSettingsRepository::setHeroAmbientBackgroundEnabled,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_homescreen_tv_mode),
                        description = stringResource(Res.string.settings_homescreen_tv_mode_description),
                        checked = tvModeEnabled,
                        enabled = heroEnabled,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.TvMode),
                        onCheckedChange = HomeCatalogSettingsRepository::setTvModeEnabled,
                    )
                }
            }
        }
    }
    item {
        val catalogOnlyItems = items.filter { !it.isCollection }
        if (heroEnabled && catalogOnlyItems.isNotEmpty()) {
            var heroSourcesExpanded by remember { mutableStateOf(false) }
            SettingsSection(
                title = stringResource(Res.string.settings_homescreen_section_hero_sources),
                isTablet = isTablet,
            ) {
                HeroSourcesDropdown(
                    isTablet = isTablet,
                    items = catalogOnlyItems,
                    selectedHeroSourceCount = selectedHeroSourceCount,
                    expanded = heroSourcesExpanded,
                    onExpandedChange = { heroSourcesExpanded = it },
                )
            }
        }
    }
    item {
        if (items.isEmpty()) {
            HomeEmptyStateCard(
                modifier = Modifier.fillMaxWidth(),
                title = stringResource(Res.string.settings_homescreen_empty_title),
                message = stringResource(Res.string.settings_homescreen_empty_message),
            )
        } else {
            val catalogCount = items.count { !it.isCollection }
            val collectionCount = items.count { it.isCollection }
            val sectionTitle = when {
                collectionCount > 0 && catalogCount > 0 -> stringResource(Res.string.settings_homescreen_section_catalogs_collections)
                collectionCount > 0 -> stringResource(Res.string.settings_homescreen_section_collections)
                else -> stringResource(Res.string.settings_homescreen_section_catalogs)
            }
            SettingsSection(
                title = sectionTitle,
                isTablet = isTablet,
                actions = {
                    NuvioActionLabel(
                        text = stringResource(Res.string.action_reset),
                        onClick = HomeCatalogSettingsRepository::resetToDefaults,
                    )
                },
            ) {
                val hapticFeedback = LocalHapticFeedback.current
                val pinToMoveToast = stringResource(Res.string.settings_homescreen_pin_to_move_toast)

                HomescreenCatalogList(
                    isTablet = isTablet,
                    items = items,
                    onPinnedDragAttempt = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        NuvioToastController.show(pinToMoveToast)
                    },
                )
            }
        }
    }
}

@Composable
private fun AdaptiveHeroVerticalBiasRow(
    bias: Float,
    enabled: Boolean,
    isTablet: Boolean,
) {
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    var sliderValue by remember(bias) { mutableFloatStateOf(bias) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 10.dp)
            .alpha(if (enabled) 1f else 0.55f),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Backdrop vertical position",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            ValueBox(text = sliderValue.formatAdaptiveHeroVerticalBias(), modifier = Modifier.wrapContentWidth())
        }
        Text(
            text = "Manual control over where adaptive hero backdrops crop vertically. Drag toward Top to " +
                "show more of the top of the image, toward Bottom to show more of the bottom.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = sliderValue,
            onValueChange = { if (enabled) sliderValue = it },
            onValueChangeFinished = {
                if (enabled) HomeCatalogSettingsRepository.setAdaptiveHeroVerticalBias(sliderValue)
            },
            enabled = enabled,
            valueRange = -1f..1f,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = "Top", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = "Bottom", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun Float.formatAdaptiveHeroVerticalBias(): String {
    val hundredths = (this * 100).roundToInt()
    val sign = if (hundredths < 0) "-" else ""
    val wholeAndFraction = abs(hundredths)
    return "$sign${wholeAndFraction / 100}.${(wholeAndFraction % 100).toString().padStart(2, '0')}"
}

@Composable
private fun HeroBadgeCountDropdownRow(
    heroInfoLines: Int,
    heroEnabled: Boolean,
    isTablet: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SettingsNavigationRow(
            title = "Hero badge count",
            description = heroInfoLines.heroBadgeCountLabel(),
            isTablet = isTablet,
            enabled = heroEnabled,
            onClick = { expanded = true },
        )
        DropdownMenu(
            expanded = expanded && heroEnabled,
            onDismissRequest = { expanded = false },
        ) {
            (0..6).forEach { count ->
                DropdownMenuItem(
                    text = { Text(count.heroBadgeCountLabel()) },
                    onClick = {
                        HomeCatalogSettingsRepository.setHeroInfoLines(count)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun Int.heroBadgeCountLabel(): String =
    when (this) {
        0 -> "Hidden"
        1 -> "1 badge"
        else -> "$this badges"
    }

private fun HeroBadgePlacement.settingsLabel(): String =
    when (this) {
        HeroBadgePlacement.BottomBackdrop -> "Bottom of backdrop"
        HeroBadgePlacement.TopRightHorizontal -> "Top right horizontal"
        HeroBadgePlacement.TopRightVertical -> "Top right vertical"
    }

private fun HeroBadgePlacement.nextPlacement(): HeroBadgePlacement =
    when (this) {
        HeroBadgePlacement.BottomBackdrop -> HeroBadgePlacement.TopRightHorizontal
        HeroBadgePlacement.TopRightHorizontal -> HeroBadgePlacement.TopRightVertical
        HeroBadgePlacement.TopRightVertical -> HeroBadgePlacement.BottomBackdrop
    }

private val HERO_BADGE_SCALE_STEPS = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f)

private fun Float.heroBadgeScaleLabel(): String {
    val rounded = (this * 100f).toInt() / 100f
    return if (rounded % 1f == 0f) "${rounded.toInt()}×" else "$rounded×"
}

private fun Float.nextHeroBadgeScale(): Float {
    val index = HERO_BADGE_SCALE_STEPS.indexOfFirst { kotlin.math.abs(it - this) < 0.01f }
    return HERO_BADGE_SCALE_STEPS[(index + 1).mod(HERO_BADGE_SCALE_STEPS.size).coerceAtLeast(0)]
}

@Composable
private fun HeroSourcesDropdown(
    isTablet: Boolean,
    items: List<HomeCatalogSettingsItem>,
    selectedHeroSourceCount: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    val noSourcesSelected = stringResource(Res.string.settings_homescreen_no_sources_selected)
    SettingsGroup(isTablet = isTablet) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .clickable { onExpandedChange(!expanded) },
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(
                        Res.string.settings_homescreen_selected_count,
                        selectedHeroSourceCount,
                        HomeCatalogSettingsRepository.HERO_SOURCE_SELECTION_LIMIT,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = items.filter { it.heroSourceEnabled }
                        .joinToString(separator = ", ") { it.displayTitle }
                        .ifBlank { noSourcesSelected },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column {
                SettingsGroupDivider(isTablet = isTablet)
                items.forEachIndexed { index, item ->
                    if (index > 0) {
                        SettingsGroupDivider(isTablet = isTablet)
                    }
                    SettingsSwitchRow(
                        title = item.displayTitle,
                        description = if (!item.heroSourceEnabled &&
                            selectedHeroSourceCount >= HomeCatalogSettingsRepository.HERO_SOURCE_SELECTION_LIMIT
                        ) {
                            stringResource(
                                Res.string.settings_homescreen_limit_reached,
                                item.addonName,
                                HomeCatalogSettingsRepository.HERO_SOURCE_SELECTION_LIMIT,
                            )
                        } else {
                            item.addonName
                        },
                        checked = item.heroSourceEnabled,
                        enabled = item.heroSourceEnabled ||
                            selectedHeroSourceCount < HomeCatalogSettingsRepository.HERO_SOURCE_SELECTION_LIMIT,
                        isTablet = isTablet,
                        onCheckedChange = { HomeCatalogSettingsRepository.setHeroSourceEnabled(item.key, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomescreenSummaryCard(
    isTablet: Boolean,
    enabledCatalogCount: Int,
    totalCatalogCount: Int,
    selectedHeroSourceCount: Int,
) {
    SettingsGroup(isTablet = isTablet) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(Res.string.settings_homescreen_keep_home_focused),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(
                    Res.string.settings_homescreen_summary,
                    enabledCatalogCount,
                    totalCatalogCount,
                    selectedHeroSourceCount,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(Res.string.settings_homescreen_summary_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HomescreenCatalogList(
    isTablet: Boolean,
    items: List<HomeCatalogSettingsItem>,
    onPinnedDragAttempt: () -> Unit,
) {
    var expandedKey by remember { mutableStateOf<String?>(null) }
    val hapticFeedback = LocalHapticFeedback.current
    val lazyListState = rememberLazyListState()
    val reorderableLazyListState = rememberReorderableLazyListState(
        lazyListState = lazyListState,
    ) { from, to ->
        val fromItem = items.getOrNull(from.index)
        val toItem = items.getOrNull(to.index)
        if (fromItem?.isPinnedToTop == true || toItem?.isPinnedToTop == true) {
            return@rememberReorderableLazyListState
        }
        HomeCatalogSettingsRepository.moveByIndex(from.index, to.index)
        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    SettingsGroup(isTablet = isTablet) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = if (isTablet) 900.dp else 680.dp),
            state = lazyListState,
        ) {
            itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                ReorderableItem(
                    reorderableLazyListState,
                    key = item.key,
                    enabled = !item.isPinnedToTop,
                ) { isDragging ->
                    val elevation by animateDpAsState(if (isDragging) 4.dp else 0.dp)

                    Surface(shadowElevation = elevation) {
                        Column {
                            if (index > 0) {
                                SettingsGroupDivider(isTablet = isTablet)
                            }
                            HomescreenCatalogRow(
                                item = item,
                                isTablet = isTablet,
                                expanded = expandedKey == item.key,
                                onExpandedChange = { shouldExpand ->
                                    expandedKey = if (shouldExpand) item.key else null
                                },
                                onTitleChange = { HomeCatalogSettingsRepository.setCustomTitle(item.key, it) },
                                onEnabledChange = { HomeCatalogSettingsRepository.setEnabled(item.key, it) },
                                dragHandleScope = this@ReorderableItem,
                                onPinnedDragAttempt = onPinnedDragAttempt,
                            )
                        }
                    }
                }
            }
        }
    }
}
