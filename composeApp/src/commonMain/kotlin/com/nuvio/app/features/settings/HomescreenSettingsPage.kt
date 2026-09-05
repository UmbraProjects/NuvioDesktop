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
import com.nuvio.app.core.ui.NuvioDialogSurface
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
import com.nuvio.app.features.home.HomeDisplayMode
import com.nuvio.app.features.home.HomeTvRowDotsAnchor
import com.nuvio.app.features.home.homeDisplayModeOf
import com.nuvio.app.features.home.hoverPreviewEnabledFor
import com.nuvio.app.features.home.components.HomeEmptyStateCard
import com.nuvio.app.features.player.HERO_TV_TRAILER_DELAY_VALUES
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.isDesktop
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.*
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
    catalogRowShuffleEnabled: Boolean = false,
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
                title = stringResource(Res.string.settings_home_display_mode),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsChoiceRow(
                        title = stringResource(Res.string.settings_home_display_mode),
                        description = currentMode.localizedDescription(),
                        options = HomeDisplayMode.entries.map { SettingsChoiceOption(it, it.localizedLabel()) },
                        selectedValue = currentMode,
                        enabled = heroEnabled,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.DisplayMode),
                        onSelected = HomeCatalogSettingsRepository::setDisplayMode,
                    )
                    // Keep mode-specific controls out of the page entirely. A disabled Adaptive
                    // position slider in Basic/TV mode looks like a setting that failed to load.
                    if (currentMode == HomeDisplayMode.Adaptive ||
                        currentMode == HomeDisplayMode.AdaptiveAmbient
                    ) {
                        SettingsGroupDivider(isTablet = isTablet)
                        AdaptiveHeroVerticalBiasRow(
                            bias = adaptiveHeroVerticalBias,
                            enabled = heroEnabled,
                            isTablet = isTablet,
                            modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.AdaptiveHeroPosition),
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        AdaptiveHeroHeightRow(
                            enabled = heroEnabled,
                            isTablet = isTablet,
                            modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.AdaptiveHeroHeight),
                        )
                    }
                    if (currentMode == HomeDisplayMode.TvMode) {
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsSwitchRow(
                            title = stringResource(Res.string.settings_home_tv_full_backdrop),
                            description = stringResource(
                                Res.string.settings_home_tv_full_backdrop_description,
                            ),
                            checked = homeSettings.tvFullBackdropEnabled,
                            isTablet = isTablet,
                            modifier = Modifier.settingsScrollAnchor(
                                SettingsScrollAnchor.searchKey("home-tv-full-backdrop"),
                            ),
                            onCheckedChange = HomeCatalogSettingsRepository::setTvFullBackdropEnabled,
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsSwitchRow(
                            title = stringResource(Res.string.settings_home_row_jump_dots),
                            description = stringResource(Res.string.settings_home_row_jump_dots_description),
                            checked = homeSettings.tvRowDotsEnabled,
                            isTablet = isTablet,
                            modifier = Modifier.settingsScrollAnchor(
                                SettingsScrollAnchor.searchKey("home-tv-row-dots"),
                            ),
                            onCheckedChange = HomeCatalogSettingsRepository::setTvRowDotsEnabled,
                        )
                        if (homeSettings.tvRowDotsEnabled) {
                            SettingsGroupDivider(isTablet = isTablet)
                            SettingsChoiceRow(
                                // Horizontal only — the dots stay on the row's title line either way.
                                title = stringResource(Res.string.settings_home_row_jump_dot_position),
                                description = homeSettings.tvRowDotsAnchor.localizedDescription(),
                                options = HomeTvRowDotsAnchor.entries.map {
                                    SettingsChoiceOption(it, it.localizedLabel())
                                },
                                selectedValue = homeSettings.tvRowDotsAnchor,
                                isTablet = isTablet,
                                modifier = Modifier.settingsScrollAnchor(
                                    SettingsScrollAnchor.searchKey("home-tv-row-dots-anchor"),
                                ),
                                onSelected = HomeCatalogSettingsRepository::setTvRowDotsAnchor,
                            )
                        }
                    } else {
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsSwitchRow(
                            title = stringResource(Res.string.settings_home_smooth_scrolling),
                            description = stringResource(Res.string.settings_home_smooth_scrolling_description),
                            checked = homeSettings.smoothScrollingEnabled,
                            isTablet = isTablet,
                            modifier = Modifier.settingsScrollAnchor(
                                SettingsScrollAnchor.searchKey("home-smooth-scrolling"),
                            ),
                            onCheckedChange = HomeCatalogSettingsRepository::setSmoothScrollingEnabled,
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        // One switch per mode rather than one switch overall: the preview is on by
                        // default in Basic and off in Adaptive, and reading/writing through the
                        // current mode is what keeps a choice made in one from following the user
                        // into the other. TV Mode never shows this row and has no stored value.
                        SettingsSwitchRow(
                            title = stringResource(Res.string.settings_home_hover_preview),
                            description = stringResource(Res.string.settings_home_hover_preview_description),
                            checked = homeSettings.hoverPreviewEnabledFor(currentMode),
                            isTablet = isTablet,
                            modifier = Modifier.settingsScrollAnchor(
                                SettingsScrollAnchor.searchKey("home-hover-preview"),
                            ),
                            onCheckedChange = { enabled ->
                                HomeCatalogSettingsRepository.setHoverPreviewEnabled(currentMode, enabled)
                            },
                        )
                    }
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_home_see_more_arrows),
                        description = stringResource(Res.string.settings_home_see_more_arrows_description),
                        checked = homeSettings.catalogSeeMoreEnabled,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(
                            SettingsScrollAnchor.searchKey("home-catalog-see-more"),
                        ),
                        onCheckedChange = HomeCatalogSettingsRepository::setCatalogSeeMoreEnabled,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_home_number_catalog_rows),
                        description = stringResource(Res.string.settings_home_number_catalog_rows_description),
                        checked = homeSettings.catalogRowNumbersEnabled,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(
                            SettingsScrollAnchor.searchKey("home-catalog-row-numbers"),
                        ),
                        onCheckedChange = HomeCatalogSettingsRepository::setCatalogRowNumbersEnabled,
                    )
                    // Basic's hero sits in the rows list, so it has no area to play a trailer
                    // in that would survive a scroll — there it is always full screen and
                    // there is nothing to choose. Hidden rather than disabled, same as the
                    // Adaptive-only rows above: a greyed row reads as one that failed to load.
                    if (currentMode != HomeDisplayMode.Basic) {
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
                    }
                    SettingsGroupDivider(isTablet = isTablet)
                    // "Wait before playing" now doubles as the autoplay switch: Manual (0) means the
                    // trailer only plays on the T shortcut; a positive value autoplays after that delay.
                    SettingsChoiceRow(
                        title = stringResource(Res.string.settings_playback_hero_tv_trailer_delay),
                        description = if (selectedHeroTrailerWait <= 0) {
                            stringResource(Res.string.settings_meta_hero_trailer_manual)
                        } else {
                            stringResource(
                                Res.string.settings_playback_hero_tv_trailer_delay_seconds,
                                selectedHeroTrailerWait,
                            )
                        },
                        options = listOf(SettingsChoiceOption(0, stringResource(Res.string.settings_meta_hero_trailer_manual))) +
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
                        title = stringResource(Res.string.settings_home_search_trailers),
                        description = stringResource(Res.string.settings_home_search_trailers_description),
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
                    title = stringResource(Res.string.settings_home_hero_badge_count),
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
                    title = stringResource(Res.string.settings_home_hero_badge_position),
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
                    title = stringResource(Res.string.settings_home_hero_badge_size),
                    description = stringResource(Res.string.settings_home_hero_badge_size_description),
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
                    title = stringResource(Res.string.settings_home_release_status_unavailable_only),
                    description = stringResource(Res.string.settings_home_release_status_unavailable_only_description),
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

                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_homescreen_row_shuffle),
                    description = stringResource(Res.string.settings_homescreen_row_shuffle_description),
                    checked = catalogRowShuffleEnabled,
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("home-row-shuffle")),
                    onCheckedChange = HomeCatalogSettingsRepository::setCatalogRowShuffleEnabled,
                )
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

private enum class HomeHeroTrailerPlaybackArea {
    Hero,
    Fullscreen,
}

@Composable
private fun HomeDisplayMode.localizedLabel(): String =
    when (this) {
        HomeDisplayMode.Basic -> stringResource(Res.string.settings_home_display_mode_basic)
        HomeDisplayMode.Adaptive -> stringResource(Res.string.settings_home_display_mode_adaptive)
        HomeDisplayMode.AdaptiveAmbient -> stringResource(Res.string.settings_home_display_mode_adaptive_ambient)
        HomeDisplayMode.TvMode -> stringResource(Res.string.settings_home_display_mode_tv)
    }

@Composable
private fun HomeDisplayMode.localizedDescription(): String =
    when (this) {
        HomeDisplayMode.Basic -> stringResource(Res.string.settings_home_display_mode_basic_description)
        HomeDisplayMode.Adaptive -> stringResource(Res.string.settings_home_display_mode_adaptive_description)
        HomeDisplayMode.AdaptiveAmbient -> stringResource(Res.string.settings_home_display_mode_adaptive_ambient_description)
        HomeDisplayMode.TvMode -> stringResource(Res.string.settings_home_display_mode_tv_description)
    }

@Composable
private fun HomeTvRowDotsAnchor.localizedLabel(): String =
    when (this) {
        HomeTvRowDotsAnchor.RowTitle -> stringResource(Res.string.settings_home_row_jump_dot_position_row)
        HomeTvRowDotsAnchor.HeroBackdrop -> stringResource(Res.string.settings_home_row_jump_dot_position_backdrop)
    }

@Composable
private fun HomeTvRowDotsAnchor.localizedDescription(): String =
    when (this) {
        HomeTvRowDotsAnchor.RowTitle -> stringResource(Res.string.settings_home_row_jump_dot_position_row_description)
        HomeTvRowDotsAnchor.HeroBackdrop ->
            stringResource(Res.string.settings_home_row_jump_dot_position_backdrop_description)
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
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(Res.string.settings_home_backdrop_vertical_position),
                style = if (isTablet) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            SettingsSubtext(
                text = stringResource(Res.string.settings_home_backdrop_vertical_position_description),
                isTablet = isTablet,
            )
        }
        Column(
            modifier = Modifier.width(if (isTablet) 210.dp else 220.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ValueBox(text = sliderValue.formatAdaptiveHeroVerticalBias())
            SettingsModernSlider(
                value = sliderValue,
                onValueChange = { if (enabled) sliderValue = it },
                onValueChangeFinished = {
                    if (enabled) HomeCatalogSettingsRepository.setAdaptiveHeroVerticalBias(sliderValue)
                },
                enabled = enabled,
                valueRange = -1f..1f,
                modifier = Modifier.fillMaxWidth(),
            )
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
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(Res.string.settings_home_hero_height),
                style = if (isTablet) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            SettingsSubtext(
                text = stringResource(Res.string.settings_home_hero_height_description),
                isTablet = isTablet,
            )
        }
        Column(
            modifier = Modifier.width(if (isTablet) 210.dp else 220.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ValueBox(text = sliderValue.formatAdaptiveHeroHeight())
            SettingsModernSlider(
                value = sliderValue,
                onValueChange = { if (enabled) sliderValue = it },
                onValueChangeFinished = {
                    if (enabled) HomeCatalogSettingsRepository.setAdaptiveHeroHeightMultiplier(sliderValue)
                },
                enabled = enabled,
                valueRange = 0.75f..1.75f,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun Float.formatAdaptiveHeroHeight(): String =
    "${(this * 100).roundToInt()}%"

@Composable
private fun Int.heroBadgeCountLabel(): String =
    when (this) {
        0 -> stringResource(Res.string.settings_home_hero_badge_count_hidden)
        1 -> stringResource(Res.string.settings_home_hero_badge_count_one)
        else -> stringResource(Res.string.settings_home_hero_badge_count_many, this)
    }

@Composable
private fun HeroBadgePlacement.settingsLabel(): String =
    when (this) {
        HeroBadgePlacement.BottomBackdrop -> stringResource(Res.string.settings_home_hero_badge_position_bottom)
        HeroBadgePlacement.TopRightHorizontal -> stringResource(Res.string.settings_home_hero_badge_position_top_horizontal)
        HeroBadgePlacement.TopRightVertical -> stringResource(Res.string.settings_home_hero_badge_position_top_vertical)
    }

private val HERO_BADGE_SCALE_STEPS = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f)

private fun Float.heroBadgeScaleLabel(): String {
    val rounded = (this * 100f).toInt() / 100f
    return if (rounded % 1f == 0f) "${rounded.toInt()}×" else "$rounded×"
}

private data class HeroInfoPriorityField(
    val key: String,
    val label: String,
    val description: String,
    val selected: Boolean,
)

private val HERO_INFO_PRIORITY_KEYS = listOf(
    "wins",
    "gg_wins",
    "festival",
    "pic_noms",
    "gg_noms",
    "emmy_noms",
    "studio",
    "director",
    "trending",
    "cult",
    "foreign",
    "new_release",
    "metacritic",
    "true_story",
    "stinger",
    "short_film",
    "mini_series",
    "binge_ready",
    "release_status",
)

private fun heroInfoPriorityKeys(value: String): List<String> =
    value.split(',')
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()

@Composable
private fun heroInfoPriorityFields(value: String): List<HeroInfoPriorityField> {
    val selectedKeys = heroInfoPriorityKeys(value)
    val labelsByKey = mapOf(
        "wins" to stringResource(Res.string.settings_home_hero_info_awards_wins),
        "gg_wins" to stringResource(Res.string.settings_home_hero_info_golden_globe_wins),
        "festival" to stringResource(Res.string.settings_home_hero_info_festival_awards),
        "pic_noms" to stringResource(Res.string.settings_home_hero_info_best_picture_nominations),
        "gg_noms" to stringResource(Res.string.settings_home_hero_info_golden_globe_nominations),
        "emmy_noms" to stringResource(Res.string.settings_home_hero_info_emmy_nominations),
        "studio" to stringResource(Res.string.settings_home_hero_info_studio),
        "director" to stringResource(Res.string.settings_home_hero_info_director),
        "trending" to stringResource(Res.string.settings_home_hero_info_trending),
        "cult" to stringResource(Res.string.settings_home_hero_info_cult_favorite),
        "foreign" to stringResource(Res.string.settings_home_hero_info_foreign_language),
        "new_release" to stringResource(Res.string.settings_home_hero_info_new_release),
        "metacritic" to stringResource(Res.string.settings_home_hero_info_metacritic),
        "true_story" to stringResource(Res.string.settings_home_hero_info_true_story),
        "stinger" to stringResource(Res.string.settings_home_hero_info_stinger),
        "short_film" to stringResource(Res.string.settings_home_hero_info_short_film),
        "mini_series" to stringResource(Res.string.settings_home_hero_info_mini_series),
        "binge_ready" to stringResource(Res.string.settings_home_hero_info_binge_ready),
        "release_status" to stringResource(Res.string.settings_home_hero_info_release_status),
    )
    val descriptionsByKey = mapOf(
        "wins" to stringResource(Res.string.settings_home_hero_info_awards_wins_description),
        "gg_wins" to stringResource(Res.string.settings_home_hero_info_golden_globe_wins_description),
        "festival" to stringResource(Res.string.settings_home_hero_info_festival_awards_description),
        "pic_noms" to stringResource(Res.string.settings_home_hero_info_best_picture_nominations_description),
        "gg_noms" to stringResource(Res.string.settings_home_hero_info_golden_globe_nominations_description),
        "emmy_noms" to stringResource(Res.string.settings_home_hero_info_emmy_nominations_description),
        "studio" to stringResource(Res.string.settings_home_hero_info_studio_description),
        "director" to stringResource(Res.string.settings_home_hero_info_director_description),
        "trending" to stringResource(Res.string.settings_home_hero_info_trending_description),
        "cult" to stringResource(Res.string.settings_home_hero_info_cult_favorite_description),
        "foreign" to stringResource(Res.string.settings_home_hero_info_foreign_language_description),
        "new_release" to stringResource(Res.string.settings_home_hero_info_new_release_description),
        "metacritic" to stringResource(Res.string.settings_home_hero_info_metacritic_description),
        "true_story" to stringResource(Res.string.settings_home_hero_info_true_story_description),
        "stinger" to stringResource(Res.string.settings_home_hero_info_stinger_description),
        "short_film" to stringResource(Res.string.settings_home_hero_info_short_film_description),
        "mini_series" to stringResource(Res.string.settings_home_hero_info_mini_series_description),
        "binge_ready" to stringResource(Res.string.settings_home_hero_info_binge_ready_description),
        "release_status" to stringResource(Res.string.settings_home_hero_info_release_status_description),
    )
    val knownKeys = HERO_INFO_PRIORITY_KEYS
    val orderedKeys = selectedKeys + knownKeys.filterNot { it in selectedKeys }
    return orderedKeys.map { key ->
        HeroInfoPriorityField(
            key = key,
            label = labelsByKey[key] ?: key.replace('_', ' ').replaceFirstChar(Char::uppercase),
            description = descriptionsByKey[key]
                ?: stringResource(Res.string.settings_home_hero_info_custom_description),
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
                selectedFields.isEmpty() -> stringResource(Res.string.settings_home_hero_info_none)
                selectedFields.size <= 3 -> preview
                else -> "$preview, +${selectedFields.size - 3}"
            }
        }

    SettingsNavigationRow(
        title = stringResource(Res.string.settings_home_hero_info_priority),
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
        NuvioDialogSurface(modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)) {
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
                            text = stringResource(Res.string.settings_home_hero_info_priority),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(Res.string.settings_home_hero_info_priority_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(Res.string.action_close),
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
                text = stringResource(Res.string.settings_home_hero_info_priority),
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
                    Surface(
                        modifier = with(this@ReorderableItem) {
                            Modifier.draggableHandle(
                                onDragStarted = {
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                            )
                        },
                        shadowElevation = elevation,
                    ) {
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
                                        text = field.description,
                                        modifier = Modifier.padding(end = if (isTablet) 24.dp else 18.dp),
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
                    modifier = with(this@ReorderableItem) {
                        Modifier.draggableHandle(
                            onDragStarted = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragStopped = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            },
                        )
                    },
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
                            onMarkerColorChange = {
                                HomeCatalogSettingsRepository.setMarkerColor(item.key, it)
                            },
                            onEnabledChange = { HomeCatalogSettingsRepository.setEnabled(item.key, it) },
                            onSendToTop = { HomeCatalogSettingsRepository.moveToTop(item.key) },
                            onPinnedDragAttempt = onPinnedDragAttempt,
                        )
                    }
                }
            }
        }
    }
}
