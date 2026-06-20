package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_settings_page_homescreen
import nuvio.composeapp.generated.resources.compose_settings_page_playback
import nuvio.composeapp.generated.resources.compose_settings_page_poster_customization
import nuvio.composeapp.generated.resources.settings_fork_binge_mode_description
import nuvio.composeapp.generated.resources.settings_fork_color_profile_description
import nuvio.composeapp.generated.resources.settings_fork_default_speed_description
import nuvio.composeapp.generated.resources.settings_fork_enhancements_intro
import nuvio.composeapp.generated.resources.settings_fork_extra_large_posters
import nuvio.composeapp.generated.resources.settings_fork_extra_large_posters_description
import nuvio.composeapp.generated.resources.settings_fork_hdr_mode_description
import nuvio.composeapp.generated.resources.settings_homescreen_hero_ambient_background
import nuvio.composeapp.generated.resources.settings_homescreen_hero_ambient_background_description
import nuvio.composeapp.generated.resources.settings_homescreen_immersive_catalog_mode
import nuvio.composeapp.generated.resources.settings_homescreen_immersive_catalog_mode_description
import nuvio.composeapp.generated.resources.settings_homescreen_tv_mode
import nuvio.composeapp.generated.resources.settings_homescreen_tv_mode_description
import nuvio.composeapp.generated.resources.settings_playback_auto_play_next_episode
import nuvio.composeapp.generated.resources.settings_playback_default_speed
import nuvio.composeapp.generated.resources.settings_playback_desktop_color_profile
import nuvio.composeapp.generated.resources.settings_playback_desktop_hdr_mode
import nuvio.composeapp.generated.resources.settings_playback_hero_tv_trailer
import nuvio.composeapp.generated.resources.settings_playback_hero_tv_trailer_description
import nuvio.composeapp.generated.resources.settings_playback_hero_tv_trailer_fullscreen
import nuvio.composeapp.generated.resources.settings_playback_hero_tv_trailer_fullscreen_description
import nuvio.composeapp.generated.resources.settings_playback_hero_tv_trailer_sound
import nuvio.composeapp.generated.resources.settings_playback_hero_tv_trailer_sound_description
import nuvio.composeapp.generated.resources.settings_playback_mouse_move_reveals_controls
import nuvio.composeapp.generated.resources.settings_playback_mouse_move_reveals_controls_description
import org.jetbrains.compose.resources.stringResource

/**
 * Overview of the features this desktop fork adds on top of Nuvio. Each entry deep-links to
 * the page where it's actually configured, so the settings stay in their functional homes
 * while this page makes the fork additions discoverable in one place.
 */
internal fun LazyListScope.forkEnhancementsContent(
    isTablet: Boolean,
    onOpenHomescreen: (anchor: String) -> Unit,
    onOpenPlayback: (anchor: String) -> Unit,
    onOpenPosterCustomization: () -> Unit,
) {
    item {
        Text(
            text = stringResource(Res.string.settings_fork_enhancements_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
        )
    }
    item {
        SettingsSection(
            title = stringResource(Res.string.compose_settings_page_homescreen),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_homescreen_tv_mode),
                    description = stringResource(Res.string.settings_homescreen_tv_mode_description),
                    isTablet = isTablet,
                    onClick = { onOpenHomescreen(SettingsScrollAnchor.AdaptiveHero) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_homescreen_immersive_catalog_mode),
                    description = stringResource(Res.string.settings_homescreen_immersive_catalog_mode_description),
                    isTablet = isTablet,
                    onClick = { onOpenHomescreen(SettingsScrollAnchor.TvMode) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_homescreen_hero_ambient_background),
                    description = stringResource(Res.string.settings_homescreen_hero_ambient_background_description),
                    isTablet = isTablet,
                    onClick = { onOpenHomescreen(SettingsScrollAnchor.HeroAmbient) },
                )
            }
        }
    }
    item {
        SettingsSection(
            title = stringResource(Res.string.compose_settings_page_playback),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_playback_hero_tv_trailer),
                    description = stringResource(Res.string.settings_playback_hero_tv_trailer_description),
                    isTablet = isTablet,
                    onClick = { onOpenPlayback(SettingsScrollAnchor.AutoPlayTrailer) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_playback_hero_tv_trailer_sound),
                    description = stringResource(Res.string.settings_playback_hero_tv_trailer_sound_description),
                    isTablet = isTablet,
                    onClick = { onOpenPlayback(SettingsScrollAnchor.TrailerSound) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_playback_hero_tv_trailer_fullscreen),
                    description = stringResource(Res.string.settings_playback_hero_tv_trailer_fullscreen_description),
                    isTablet = isTablet,
                    onClick = { onOpenPlayback(SettingsScrollAnchor.TrailerFullscreen) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_playback_desktop_hdr_mode),
                    description = stringResource(Res.string.settings_fork_hdr_mode_description),
                    isTablet = isTablet,
                    onClick = { onOpenPlayback(SettingsScrollAnchor.HdrMode) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_playback_desktop_color_profile),
                    description = stringResource(Res.string.settings_fork_color_profile_description),
                    isTablet = isTablet,
                    onClick = { onOpenPlayback(SettingsScrollAnchor.ColorProfile) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_playback_mouse_move_reveals_controls),
                    description = stringResource(Res.string.settings_playback_mouse_move_reveals_controls_description),
                    isTablet = isTablet,
                    onClick = { onOpenPlayback(SettingsScrollAnchor.MouseMove) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_playback_default_speed),
                    description = stringResource(Res.string.settings_fork_default_speed_description),
                    isTablet = isTablet,
                    onClick = { onOpenPlayback(SettingsScrollAnchor.DefaultSpeed) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_playback_auto_play_next_episode),
                    description = stringResource(Res.string.settings_fork_binge_mode_description),
                    isTablet = isTablet,
                    onClick = { onOpenPlayback(SettingsScrollAnchor.BingeMode) },
                )
            }
        }
    }
    item {
        SettingsSection(
            title = stringResource(Res.string.compose_settings_page_poster_customization),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_fork_extra_large_posters),
                    description = stringResource(Res.string.settings_fork_extra_large_posters_description),
                    isTablet = isTablet,
                    onClick = onOpenPosterCustomization,
                )
            }
        }
    }
}
