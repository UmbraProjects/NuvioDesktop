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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
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
import com.nuvio.app.features.player.HERO_TV_TRAILER_DELAY_VALUES
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.isDesktop
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_reset
import nuvio.composeapp.generated.resources.layout_hide_unreleased
import nuvio.composeapp.generated.resources.layout_hide_unreleased_sub
import nuvio.composeapp.generated.resources.settings_meta_hero_trailer_playback_area_fullscreen
import nuvio.composeapp.generated.resources.settings_meta_hero_trailer_playback_area_hero
import nuvio.composeapp.generated.resources.settings_homescreen_empty_message
import nuvio.composeapp.generated.resources.settings_homescreen_empty_title
import nuvio.composeapp.generated.resources.settings_homescreen_hide_catalog_underline
import nuvio.composeapp.generated.resources.settings_homescreen_hide_catalog_underline_description
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
import nuvio.composeapp.generated.resources.settings_playback_hero_tv_trailer
import nuvio.composeapp.generated.resources.settings_playback_hero_tv_trailer_delay
import nuvio.composeapp.generated.resources.settings_playback_hero_tv_trailer_delay_seconds
import nuvio.composeapp.generated.resources.settings_playback_hero_tv_trailer_description
import nuvio.composeapp.generated.resources.settings_playback_hero_tv_trailer_sound
import nuvio.composeapp.generated.resources.settings_playback_hero_tv_trailer_sound_description
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
    val heroEligibleItems = items.filter { !it.isCollection || it.hasHeroBackdrop }
    val selectedHeroSourceCount = heroEligibleItems.count { it.heroSourceEnabled }
    val enabledCatalogCount = items.count { it.enabled }
    if (isDesktop) {
        item {
            val playerSettings by PlayerSettingsRepository.uiState.collectAsStateWithLifecycle()
            val homeSettings by HomeCatalogSettingsRepository.uiState.collectAsStateWithLifecycle()
            val currentMode = homeDisplayModeOf(
                adaptiveHeroEnabled = adaptiveHeroEnabled,
                heroAmbientBackgroundEnabled = heroAmbientBackgroundEnabled,
                tvModeEnabled = tvModeEnabled,
            )
            // Where the trailer plays is independent of whether it autoplays: the area selector only
            // sets heroTvTrailerFullscreen, so manual (T) playback can be full screen without turning
            // autoplay on. Autoplay lives entirely in the wait selector below (0 == Manual).
            val selectedHeroTrailerArea = if (playerSettings.heroTvTrailerFullscreen) {
                HomeHeroTrailerPlaybackArea.Fullscreen
            } else {
                HomeHeroTrailerPlaybackArea.Hero
            }
            val selectedHeroTrailerWait = if (playerSettings.heroTvTrailerEnabled) {
                playerSettings.heroTvTrailerDelaySeconds
            } else {
                0
            }
            SettingsSection(
                title = "Display Mode",
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsChoiceRow(
                        title = "Display Mode",
                        description = currentMode.description,
                        options = HomeDisplayMode.entries.map { SettingsChoiceOption(it, it.label) },
                        selectedValue = currentMode,
                        enabled = heroEnabled,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.DisplayMode),
                        onSelected = { it.applyTo() },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = "Smooth scrolling",
                        description = "Ease mouse-wheel scrolling in Basic and Adaptive modes. " +
                            "TV Mode still jumps one catalog per scroll.",
                        checked = homeSettings.smoothScrollingEnabled,
                        enabled = currentMode != HomeDisplayMode.TvMode,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("home-smooth-scrolling")),
                        onCheckedChange = HomeCatalogSettingsRepository::setSmoothScrollingEnabled,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsChoiceRow(
                        title = stringResource(Res.string.settings_playback_hero_tv_trailer),
                        description = stringResource(Res.string.settings_playback_hero_tv_trailer_description),
                        options = listOf(
                            SettingsChoiceOption(HomeHeroTrailerPlaybackArea.Hero, stringResource(Res.string.settings_meta_hero_trailer_playback_area_hero)),
                            SettingsChoiceOption(HomeHeroTrailerPlaybackArea.Fullscreen, stringResource(Res.string.settings_meta_hero_trailer_playback_area_fullscreen)),
                        ),
                        selectedValue = selectedHeroTrailerArea,
                        enabled = heroEnabled,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.AutoPlayTrailer),
                        onSelected = { area ->
                            PlayerSettingsRepository.setHeroTvTrailerFullscreen(
                                area == HomeHeroTrailerPlaybackArea.Fullscreen,
                            )
                        },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    // "Wait before playing" now doubles as the autoplay switch: Manual (0) means the
                    // trailer only plays on the T shortcut; a positive value autoplays after that delay.
                    SettingsChoiceRow(
                        title = stringResource(Res.string.settings_playback_hero_tv_trailer_delay),
                        description = if (selectedHeroTrailerWait <= 0) {
                            "Manual"
                        } else {
                            stringResource(
                                Res.string.settings_playback_hero_tv_trailer_delay_seconds,
                                selectedHeroTrailerWait,
                            )
                        },
                        options = listOf(SettingsChoiceOption(0, "Manual")) +
                            HERO_TV_TRAILER_DELAY_VALUES.map { seconds ->
                                SettingsChoiceOption(
                                    seconds,
                                    stringResource(Res.string.settings_playback_hero_tv_trailer_delay_seconds, seconds),
                                )
                            },
                        selectedValue = selectedHeroTrailerWait,
                        enabled = heroEnabled,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.TrailerDelay),
                        onSelected = { seconds ->
                            if (seconds <= 0) {
                                PlayerSettingsRepository.setHeroTvTrailerEnabled(false)
                            } else {
                                PlayerSettingsRepository.setHeroTvTrailerEnabled(true)
                                PlayerSettingsRepository.setHeroTvTrailerDelaySeconds(seconds)
                            }
                        },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_hero_tv_trailer_sound),
                        description = stringResource(Res.string.settings_playback_hero_tv_trailer_sound_description),
                        checked = playerSettings.heroTvTrailerSoundEnabled,
                        enabled = heroEnabled,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.TrailerSound),
                        onCheckedChange = PlayerSettingsRepository::setHeroTvTrailerSoundEnabled,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = "Trailers in Search",
                        description = "Allow focused search results to play hero trailers.",
                        checked = playerSettings.heroTvTrailerSearchEnabled,
                        enabled = heroEnabled,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.TrailerSearch),
                        onCheckedChange = PlayerSettingsRepository::setHeroTvTrailerSearchEnabled,
                    )
                }
            }
        }
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
                SettingsChoiceRow(
                    title = "Hero badge count",
                    description = heroInfoLines.heroBadgeCountLabel(),
                    options = (0..6).map { count ->
                        SettingsChoiceOption(count, count.heroBadgeCountLabel())
                    },
                    selectedValue = heroInfoLines.coerceIn(0, 6),
                    isTablet = isTablet,
                    enabled = heroEnabled,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.HeroBadgeCount),
                    onSelected = HomeCatalogSettingsRepository::setHeroInfoLines,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsChoiceRow(
                    title = "Hero badge position",
                    description = heroBadgePlacement.settingsLabel(),
                    options = HeroBadgePlacement.entries.map { placement ->
                        SettingsChoiceOption(placement, placement.settingsLabel())
                    },
                    selectedValue = heroBadgePlacement,
                    isTablet = isTablet,
                    enabled = heroEnabled,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.HeroBadgePosition),
                    onSelected = HomeCatalogSettingsRepository::setHeroBadgePlacement,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsChoiceRow(
                    title = "Hero badge size",
                    description = "Modify badge sizes.",
                    options = HERO_BADGE_SCALE_STEPS.map { scale ->
                        SettingsChoiceOption(scale, scale.heroBadgeScaleLabel())
                    },
                    selectedValue = HERO_BADGE_SCALE_STEPS.minByOrNull {
                        kotlin.math.abs(it - heroBadgeScale)
                    } ?: 1f,
                    isTablet = isTablet,
                    enabled = heroEnabled,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.HeroBadgeSize),
                    onSelected = HomeCatalogSettingsRepository::setHeroBadgeScale,
                )
                  SettingsGroupDivider(isTablet = isTablet)
                  HeroInfoPriorityRow(
                      value = heroInfoPriority,
                      enabled = heroEnabled,
                      isTablet = isTablet,
                      modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.HeroBadgePriority),
                  )
                  SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = "Only show unavailable release status",
                    description = "Show release status only for cinema and production titles.",
                    checked = heroReleaseStatusUnavailableOnly,
                    enabled = heroEnabled,
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.HeroReleaseStatus),
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
                    AdaptiveHeroVerticalBiasRow(
                        bias = adaptiveHeroVerticalBias,
                        enabled = heroEnabled && adaptiveHeroEnabled && !tvModeEnabled,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.AdaptiveHeroPosition),
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    AdaptiveHeroHeightRow(
                        enabled = heroEnabled && adaptiveHeroEnabled && !tvModeEnabled,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.AdaptiveHeroHeight),
                    )
                }
            }
        }
    }
    item {
        // Catalogs are always eligible; collections qualify only when they or their
        // folders have curated hero art.
        if (heroEnabled && heroEligibleItems.isNotEmpty()) {
            var heroSourcesExpanded by remember { mutableStateOf(false) }
            SettingsSection(
                title = stringResource(Res.string.settings_homescreen_section_hero_sources),
                isTablet = isTablet,
            ) {
                HeroSourcesDropdown(
                    isTablet = isTablet,
                    items = heroEligibleItems,
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
                Text(
                    text = stringResource(
                        Res.string.settings_homescreen_summary,
                        enabledCatalogCount,
                        items.size,
                        selectedHeroSourceCount,
                    ),
                    modifier = Modifier.padding(bottom = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

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

/**
 * Single desktop control consolidating adaptive cropping, ambient background, and TV mode
 * into one "how the hero displays" choice instead of three independent switches.
 */
internal enum class HomeDisplayMode {
    Basic,
    Adaptive,
    AdaptiveAmbient,
    TvMode,
}

private enum class HomeHeroTrailerPlaybackArea {
    Hero,
    Fullscreen,
}

internal fun homeDisplayModeOf(
    adaptiveHeroEnabled: Boolean,
    heroAmbientBackgroundEnabled: Boolean,
    tvModeEnabled: Boolean,
): HomeDisplayMode = when {
    tvModeEnabled -> HomeDisplayMode.TvMode
    adaptiveHeroEnabled && heroAmbientBackgroundEnabled -> HomeDisplayMode.AdaptiveAmbient
    adaptiveHeroEnabled -> HomeDisplayMode.Adaptive
    else -> HomeDisplayMode.Basic
}

private fun HomeDisplayMode.applyTo() {
    when (this) {
        HomeDisplayMode.Basic -> {
            HomeCatalogSettingsRepository.setTvModeEnabled(false)
            HomeCatalogSettingsRepository.setAdaptiveHeroEnabled(false)
            HomeCatalogSettingsRepository.setHeroAmbientBackgroundEnabled(false)
        }
        HomeDisplayMode.Adaptive -> {
            HomeCatalogSettingsRepository.setTvModeEnabled(false)
            HomeCatalogSettingsRepository.setAdaptiveHeroEnabled(true)
            HomeCatalogSettingsRepository.setHeroAmbientBackgroundEnabled(false)
        }
        HomeDisplayMode.AdaptiveAmbient -> {
            HomeCatalogSettingsRepository.setTvModeEnabled(false)
            HomeCatalogSettingsRepository.setAdaptiveHeroEnabled(true)
            HomeCatalogSettingsRepository.setHeroAmbientBackgroundEnabled(true)
        }
        HomeDisplayMode.TvMode -> {
            HomeCatalogSettingsRepository.setTvModeEnabled(true)
        }
    }
}

private val HomeDisplayMode.label: String
    get() = when (this) {
        HomeDisplayMode.Basic -> "Basic"
        HomeDisplayMode.Adaptive -> "Adaptive"
        HomeDisplayMode.AdaptiveAmbient -> "Adaptive Ambient"
        HomeDisplayMode.TvMode -> "TV Mode"
    }

private val HomeDisplayMode.description: String
    get() = when (this) {
        HomeDisplayMode.Basic -> "Static hero backdrop with no extra effects."
        HomeDisplayMode.Adaptive -> "Crops the hero backdrop to keep the focal point in view as it changes."
        HomeDisplayMode.AdaptiveAmbient -> "Adaptive cropping plus a soft ambient glow behind the hero."
        HomeDisplayMode.TvMode -> "Optimized layout and larger badges for viewing from a couch."
    }

@Composable
private fun AdaptiveHeroVerticalBiasRow(
    bias: Float,
    enabled: Boolean,
    isTablet: Boolean,
    modifier: Modifier = Modifier,
) {
    val horizontalPadding = 16.dp
    var sliderValue by remember(bias) { mutableFloatStateOf(bias) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 8.dp)
            .alpha(if (enabled) 1f else 0.55f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "Backdrop vertical position",
                style = if (isTablet) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "Tune where adaptive hero backdrops crop vertically.",
                style = if (isTablet) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.width(if (isTablet) 210.dp else 260.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SettingsModernSlider(
                value = sliderValue,
                onValueChange = { if (enabled) sliderValue = it },
                onValueChangeFinished = {
                    if (enabled) HomeCatalogSettingsRepository.setAdaptiveHeroVerticalBias(sliderValue)
                },
                enabled = enabled,
                valueRange = -1f..1f,
                modifier = Modifier.weight(1f),
            )
            ValueBox(text = sliderValue.formatAdaptiveHeroVerticalBias(), modifier = Modifier.width(44.dp))
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
private fun AdaptiveHeroHeightRow(
    enabled: Boolean,
    isTablet: Boolean,
    modifier: Modifier = Modifier,
) {
    val settings by HomeCatalogSettingsRepository.uiState.collectAsStateWithLifecycle()
    var sliderValue by remember(settings.adaptiveHeroHeightMultiplier) {
        mutableFloatStateOf(settings.adaptiveHeroHeightMultiplier)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .alpha(if (enabled) 1f else 0.55f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "Hero height",
                style = if (isTablet) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "Set how much of the window the adaptive hero occupies.",
                style = if (isTablet) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.width(if (isTablet) 210.dp else 260.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SettingsModernSlider(
                value = sliderValue,
                onValueChange = { if (enabled) sliderValue = it },
                onValueChangeFinished = {
                    if (enabled) HomeCatalogSettingsRepository.setAdaptiveHeroHeightMultiplier(sliderValue)
                },
                enabled = enabled,
                valueRange = 0.75f..1.75f,
                modifier = Modifier.weight(1f),
            )
            ValueBox(text = sliderValue.formatAdaptiveHeroHeight(), modifier = Modifier.width(48.dp))
        }
    }
}

private fun Float.formatAdaptiveHeroHeight(): String =
    "${(this * 100).roundToInt()}%"

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

private val HERO_BADGE_SCALE_STEPS = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f)

private fun Float.heroBadgeScaleLabel(): String {
    val rounded = (this * 100f).toInt() / 100f
    return if (rounded % 1f == 0f) "${rounded.toInt()}×" else "$rounded×"
}

private data class HeroInfoPriorityField(
    val key: String,
    val label: String,
    val selected: Boolean,
)

private val HERO_INFO_PRIORITY_LABELS = listOf(
    "wins" to "Awards wins",
    "gg_wins" to "Golden Globe wins",
    "festival" to "Festival awards",
    "pic_noms" to "Best Picture nominations",
    "gg_noms" to "Golden Globe nominations",
    "emmy_noms" to "Emmy nominations",
    "studio" to "Studio",
    "director" to "Director",
    "trending" to "Trending",
    "cult" to "Cult favorite",
    "foreign" to "Foreign language",
    "new_release" to "New release",
    "metacritic" to "Metacritic",
    "true_story" to "True story",
    "short_film" to "Short film",
    "mini_series" to "Mini-series",
    "binge_ready" to "Binge ready",
    "release_status" to "Release status",
)

private fun heroInfoPriorityKeys(value: String): List<String> =
    value.split(',')
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()

private fun heroInfoPriorityFields(value: String): List<HeroInfoPriorityField> {
    val selectedKeys = heroInfoPriorityKeys(value)
    val labelsByKey = HERO_INFO_PRIORITY_LABELS.toMap()
    val knownKeys = HERO_INFO_PRIORITY_LABELS.map { it.first }
    val orderedKeys = selectedKeys + knownKeys.filterNot { it in selectedKeys }
    return orderedKeys.map { key ->
        HeroInfoPriorityField(
            key = key,
            label = labelsByKey[key] ?: key.replace('_', ' ').replaceFirstChar(Char::uppercase),
            selected = key in selectedKeys,
        )
    }
}

@Composable
private fun HeroInfoPriorityRow(
    value: String,
    enabled: Boolean,
    isTablet: Boolean,
    modifier: Modifier = Modifier,
) {
    var dialogVisible by remember { mutableStateOf(false) }
    val selectedFields = heroInfoPriorityFields(value).filter { it.selected }
    val summary = selectedFields
        .take(3)
        .joinToString(separator = ", ") { it.label }
        .let { preview ->
            when {
                selectedFields.isEmpty() -> "No hero info shown"
                selectedFields.size <= 3 -> preview
                else -> "$preview, +${selectedFields.size - 3}"
            }
        }

    SettingsNavigationRow(
        title = "Hero info priority",
        description = summary,
        isTablet = isTablet,
        enabled = enabled,
        modifier = modifier,
        onClick = { dialogVisible = true },
    )

    if (dialogVisible) {
        HeroInfoPriorityDialog(
            value = value,
            enabled = enabled,
            isTablet = isTablet,
            onDismiss = { dialogVisible = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeroInfoPriorityDialog(
    value: String,
    enabled: Boolean,
    isTablet: Boolean,
    onDismiss: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp),
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
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
                            text = "Hero info priority",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Choose which hero badges are preferred first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HeroInfoPriorityList(
                    value = value,
                    enabled = enabled,
                    isTablet = isTablet,
                    showHeader = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun HeroInfoPriorityList(
    value: String,
    enabled: Boolean,
    isTablet: Boolean,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
) {
    val fields = heroInfoPriorityFields(value)
    val selectedKeys = fields.filter { it.selected }.map { it.key }
    val hapticFeedback = LocalHapticFeedback.current
    val lazyListState = rememberLazyListState()
    val reorderableLazyListState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromField = fields.getOrNull(from.index) ?: return@rememberReorderableLazyListState
        val toField = fields.getOrNull(to.index) ?: return@rememberReorderableLazyListState
        if (!fromField.selected || !toField.selected) return@rememberReorderableLazyListState
        val reordered = selectedKeys.toMutableList()
        val fromSelectedIndex = reordered.indexOf(fromField.key)
        val toSelectedIndex = reordered.indexOf(toField.key)
        if (fromSelectedIndex < 0 || toSelectedIndex < 0 || fromSelectedIndex == toSelectedIndex) {
            return@rememberReorderableLazyListState
        }
        reordered.add(toSelectedIndex, reordered.removeAt(fromSelectedIndex))
        HomeCatalogSettingsRepository.setHeroInfoPriority(reordered.joinToString(","))
        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.55f),
    ) {
        if (showHeader) {
            Text(
                text = "Hero info priority",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = if (isTablet) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = if (isTablet) 520.dp else 360.dp),
            state = lazyListState,
        ) {
            itemsIndexed(fields, key = { _, field -> field.key }) { index, field ->
                ReorderableItem(
                    reorderableLazyListState,
                    key = field.key,
                    enabled = enabled && field.selected,
                ) { isDragging ->
                    val elevation by animateDpAsState(if (isDragging) 4.dp else 0.dp)
                    Surface(shadowElevation = elevation) {
                        Column {
                            if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = field.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        text = if (field.selected) "Shown in this priority order" else "Skipped",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                SettingsSquareSwitch(
                                    checked = field.selected,
                                    enabled = enabled,
                                    onCheckedChange = { checked ->
                                        val nextKeys = if (checked) {
                                            selectedKeys + field.key
                                        } else {
                                            selectedKeys.filterNot { it == field.key }
                                        }
                                        HomeCatalogSettingsRepository.setHeroInfoPriority(nextKeys.joinToString(","))
                                    },
                                )
                                IconButton(
                                    modifier = with(this@ReorderableItem) {
                                        Modifier.draggableHandle(
                                            onDragStarted = {
                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                            },
                                        )
                                    },
                                    enabled = enabled && field.selected,
                                    onClick = {},
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Menu,
                                        contentDescription = "Reorder",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                            alpha = if (field.selected) 1f else 0.45f,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
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

                Surface(
                    color = Color.Transparent,
                    shadowElevation = elevation,
                ) {
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
                            onSendToTop = { HomeCatalogSettingsRepository.moveToTop(item.key) },
                            dragHandleScope = this@ReorderableItem,
                            onPinnedDragAttempt = onPinnedDragAttempt,
                        )
                    }
                }
            }
        }
    }
}
