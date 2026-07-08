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
import nuvio.composeapp.generated.resources.compose_settings_page_integrations
import nuvio.composeapp.generated.resources.compose_settings_page_playback
import nuvio.composeapp.generated.resources.compose_settings_page_poster_customization
import nuvio.composeapp.generated.resources.compose_settings_page_simkl
import nuvio.composeapp.generated.resources.compose_settings_page_tmdb_enrichment
import nuvio.composeapp.generated.resources.settings_fork_hero_images_description
import nuvio.composeapp.generated.resources.settings_fork_anime_enhancements_description
import nuvio.composeapp.generated.resources.settings_fork_binge_mode_description
import nuvio.composeapp.generated.resources.settings_fork_buffer_preset_description
import nuvio.composeapp.generated.resources.settings_fork_color_profile_description
import nuvio.composeapp.generated.resources.settings_fork_default_speed_description
import nuvio.composeapp.generated.resources.settings_fork_enhancements_intro
import nuvio.composeapp.generated.resources.settings_fork_extra_large_posters
import nuvio.composeapp.generated.resources.settings_fork_extra_large_posters_description
import nuvio.composeapp.generated.resources.settings_fork_hdr_mode_description
import nuvio.composeapp.generated.resources.settings_fork_other_features
import nuvio.composeapp.generated.resources.settings_fork_other_features_note
import nuvio.composeapp.generated.resources.settings_fork_rtx_hdr_description
import nuvio.composeapp.generated.resources.settings_fork_simkl_description
import nuvio.composeapp.generated.resources.settings_fork_tvdb_description
import nuvio.composeapp.generated.resources.settings_playback_auto_play_next_episode
import nuvio.composeapp.generated.resources.settings_playback_default_speed
import nuvio.composeapp.generated.resources.settings_playback_desktop_anime_auto
import nuvio.composeapp.generated.resources.settings_playback_desktop_anime_auto_desc
import nuvio.composeapp.generated.resources.settings_playback_desktop_anime_mode
import nuvio.composeapp.generated.resources.settings_playback_desktop_anime_svp
import nuvio.composeapp.generated.resources.settings_playback_desktop_anime_svp_desc
import nuvio.composeapp.generated.resources.settings_playback_desktop_buffer_preset
import nuvio.composeapp.generated.resources.settings_playback_desktop_color_profile
import nuvio.composeapp.generated.resources.settings_playback_desktop_hdr_mode
import nuvio.composeapp.generated.resources.settings_playback_desktop_renderer
import nuvio.composeapp.generated.resources.settings_playback_desktop_renderer_dialog
import nuvio.composeapp.generated.resources.settings_playback_hero_tv_trailer
import nuvio.composeapp.generated.resources.settings_playback_hero_tv_trailer_delay
import nuvio.composeapp.generated.resources.settings_playback_hero_tv_trailer_description
import nuvio.composeapp.generated.resources.settings_playback_hero_tv_trailer_sound
import nuvio.composeapp.generated.resources.settings_playback_hero_tv_trailer_sound_description
import nuvio.composeapp.generated.resources.settings_playback_mouse_move_reveals_controls
import nuvio.composeapp.generated.resources.settings_playback_mouse_move_reveals_controls_description
import nuvio.composeapp.generated.resources.settings_playback_nvidia_rtx_hdr
import nuvio.composeapp.generated.resources.settings_licenses_attributions_tvdb_title
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
    onOpenTmdb: (anchor: String) -> Unit,
    onOpenPosterCustomization: (anchor: String) -> Unit,
    onOpenSimkl: () -> Unit,
    onOpenIntegrations: (anchor: String) -> Unit,
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
                    title = "Display Mode",
                    description = "Basic, Adaptive, Adaptive Ambient, or TV Mode.",
                    isTablet = isTablet,
                    onClick = { onOpenHomescreen(SettingsScrollAnchor.DisplayMode) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_playback_hero_tv_trailer),
                    description = stringResource(Res.string.settings_playback_hero_tv_trailer_description),
                    isTablet = isTablet,
                    onClick = { onOpenHomescreen(SettingsScrollAnchor.AutoPlayTrailer) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_playback_hero_tv_trailer_delay),
                    description = "Delay before focused hero trailers start playing.",
                    isTablet = isTablet,
                    onClick = { onOpenHomescreen(SettingsScrollAnchor.TrailerDelay) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_playback_hero_tv_trailer_sound),
                    description = stringResource(Res.string.settings_playback_hero_tv_trailer_sound_description),
                    isTablet = isTablet,
                    onClick = { onOpenHomescreen(SettingsScrollAnchor.TrailerSound) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = "Backdrop vertical position",
                    description = "Manually tune how adaptive hero backdrops crop vertically.",
                    isTablet = isTablet,
                    onClick = { onOpenHomescreen(SettingsScrollAnchor.AdaptiveHeroPosition) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = "Hero badge settings",
                    description = "Control badge count, placement, size, priority, and release-status filtering.",
                    isTablet = isTablet,
                    onClick = { onOpenHomescreen(SettingsScrollAnchor.HeroBadgeCount) },
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
                    title = stringResource(Res.string.settings_playback_desktop_renderer),
                    description = stringResource(Res.string.settings_playback_desktop_renderer_dialog),
                    isTablet = isTablet,
                    onClick = { onOpenPlayback(SettingsScrollAnchor.DesktopRenderer) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_playback_desktop_buffer_preset),
                    description = stringResource(Res.string.settings_fork_buffer_preset_description),
                    isTablet = isTablet,
                    onClick = { onOpenPlayback(SettingsScrollAnchor.BufferPreset) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_playback_desktop_anime_mode),
                    description = stringResource(Res.string.settings_fork_anime_enhancements_description),
                    isTablet = isTablet,
                    onClick = { onOpenPlayback(SettingsScrollAnchor.AnimeEnhancements) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_playback_desktop_anime_auto),
                    description = stringResource(Res.string.settings_playback_desktop_anime_auto_desc),
                    isTablet = isTablet,
                    onClick = { onOpenPlayback(SettingsScrollAnchor.AnimeAutoApply) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_playback_desktop_anime_svp),
                    description = stringResource(Res.string.settings_playback_desktop_anime_svp_desc),
                    isTablet = isTablet,
                    onClick = { onOpenPlayback(SettingsScrollAnchor.AnimeSvp) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_playback_nvidia_rtx_hdr),
                    description = stringResource(Res.string.settings_fork_rtx_hdr_description),
                    isTablet = isTablet,
                    onClick = { onOpenPlayback(SettingsScrollAnchor.RtxHdr) },
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
            title = stringResource(Res.string.compose_settings_page_tmdb_enrichment),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = "Hero backdrop & logo",
                    description = stringResource(Res.string.settings_fork_hero_images_description),
                    isTablet = isTablet,
                    onClick = { onOpenTmdb(SettingsScrollAnchor.TmdbHeroImages) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_licenses_attributions_tvdb_title),
                    description = stringResource(Res.string.settings_fork_tvdb_description),
                    isTablet = isTablet,
                    onClick = { onOpenTmdb(SettingsScrollAnchor.TvdbApiKey) },
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
                    onClick = { onOpenPosterCustomization(SettingsScrollAnchor.ExtraLargePosters) },
                )
            }
        }
    }
    item {
        SettingsSection(
            title = stringResource(Res.string.compose_settings_page_integrations),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = "Discord Rich Presence",
                    description = "Show what you are watching on your Discord profile.",
                    isTablet = isTablet,
                    onClick = { onOpenIntegrations(SettingsScrollAnchor.DiscordPresence) },
                )
            }
        }
    }
    item {
        SettingsSection(
            title = stringResource(Res.string.compose_settings_page_simkl),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_simkl),
                    description = stringResource(Res.string.settings_fork_simkl_description),
                    isTablet = isTablet,
                    onClick = onOpenSimkl,
                )
            }
        }
    }
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_fork_other_features),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                Text(
                    text = stringResource(Res.string.settings_fork_other_features_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 14.dp),
                )
            }
        }
    }
}
