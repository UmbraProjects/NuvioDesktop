package com.nuvio.app.features.settings

// fork:search-rows
// Settings rows that the main index in SettingsSearch.kt never listed, so typing their name
// found nothing. Kept in its own file so upstream merges of SettingsSearch.kt stay clean.
// Each row lands on its section (the row's own anchor is used when the page has one), and
// SettingsSearchCoverageTest keeps this list honest.

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.nuvio.app.isDesktop
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource

internal data class ForkSearchRow(
    val page: SettingsPage,
    val key: String,
    val title: StringResource,
    val description: StringResource?,
    val pageLabel: StringResource,
    val section: StringResource,
    val desktopOnly: Boolean = false,
    /** Playback rows carry no category, like the rest of the playback entries. */
    val noCategory: Boolean = false,
)

internal val forkSearchIcon: ImageVector get() = Icons.Rounded.Settings

internal val forkSearchRows: List<ForkSearchRow> = listOf(
    ForkSearchRow(SettingsPage.Appearance, "x-appearance-close-to-tray", Res.string.settings_appearance_close_to_tray, Res.string.settings_appearance_close_to_tray_description, Res.string.compose_settings_page_appearance, Res.string.settings_appearance_section_display, desktopOnly = true),
    ForkSearchRow(SettingsPage.ContinueWatching, "x-cw-seed-nuvio-title", Res.string.settings_cw_seed_nuvio_title, Res.string.settings_cw_seed_nuvio_description, Res.string.compose_settings_page_continue_watching, Res.string.compose_settings_page_continue_watching),
    ForkSearchRow(SettingsPage.PosterService, "x-poster-service-placeholders-title", Res.string.settings_poster_service_placeholders_title, Res.string.settings_poster_service_placeholders_subtitle, Res.string.compose_settings_page_poster_service, Res.string.settings_poster_service_templates_section),
    ForkSearchRow(SettingsPage.Debrid, "x-debrid-prepare-stream-count", Res.string.settings_debrid_prepare_stream_count, null, Res.string.compose_settings_page_debrid, Res.string.settings_debrid_section_instant_playback),
    ForkSearchRow(SettingsPage.Discover, "x-discover-ai-provider", Res.string.settings_discover_ai_provider, null, Res.string.compose_settings_page_discover, Res.string.settings_discover_ai_section),
    ForkSearchRow(SettingsPage.Discover, "x-discover-ai-base-url", Res.string.settings_discover_ai_base_url, Res.string.settings_discover_ai_base_url_description, Res.string.compose_settings_page_discover, Res.string.settings_discover_ai_section),
    ForkSearchRow(SettingsPage.Discover, "x-discover-ai-model", Res.string.settings_discover_ai_model, Res.string.settings_discover_ai_model_description, Res.string.compose_settings_page_discover, Res.string.settings_discover_ai_section),
    ForkSearchRow(SettingsPage.Player, "x-playback-hud-button-order", Res.string.settings_playback_hud_button_order, Res.string.settings_playback_hud_button_order_description, Res.string.compose_settings_page_player, Res.string.settings_playback_section_player, desktopOnly = true, noCategory = true),
    ForkSearchRow(SettingsPage.Iptv, "x-proxy-host", Res.string.iptv_proxy_host, Res.string.iptv_proxy_host_description, Res.string.iptv_settings_page, Res.string.iptv_settings_section_connection),
    ForkSearchRow(SettingsPage.Iptv, "x-proxy-port", Res.string.iptv_proxy_port, null, Res.string.iptv_settings_page, Res.string.iptv_settings_section_connection),
    ForkSearchRow(SettingsPage.Iptv, "x-proxy-username", Res.string.iptv_proxy_username, Res.string.iptv_proxy_username_description, Res.string.iptv_settings_page, Res.string.iptv_settings_section_connection),
    ForkSearchRow(SettingsPage.Iptv, "x-proxy-password", Res.string.iptv_proxy_password, null, Res.string.iptv_settings_page, Res.string.iptv_settings_section_connection),
    ForkSearchRow(SettingsPage.KeyboardShortcuts, "x-shortcuts-move-focus", Res.string.settings_shortcuts_move_focus, null, Res.string.compose_settings_page_keyboard_shortcuts, Res.string.settings_shortcuts_navigation, desktopOnly = true),
    ForkSearchRow(SettingsPage.KeyboardShortcuts, "x-shortcuts-hold-to-select", Res.string.settings_shortcuts_hold_to_select, Res.string.settings_shortcuts_hold_to_select_description, Res.string.compose_settings_page_keyboard_shortcuts, Res.string.settings_shortcuts_navigation, desktopOnly = true),
    ForkSearchRow(SettingsPage.KeyboardShortcuts, "x-shortcuts-osk-show-for-mouse", Res.string.settings_shortcuts_osk_show_for_mouse, Res.string.settings_shortcuts_osk_show_for_mouse_description, Res.string.compose_settings_page_keyboard_shortcuts, Res.string.settings_shortcuts_on_screen_keyboard, desktopOnly = true),
    ForkSearchRow(SettingsPage.Lights, "x-lights-dim-level", Res.string.settings_lights_dim_level, Res.string.settings_lights_dim_level_description, Res.string.compose_settings_page_lights, Res.string.settings_lights_section_title, desktopOnly = true),
    ForkSearchRow(SettingsPage.Lights, "x-lights-schedule-from", Res.string.settings_lights_schedule_from, Res.string.settings_lights_schedule_from_description, Res.string.compose_settings_page_lights, Res.string.settings_lights_section_title, desktopOnly = true),
    ForkSearchRow(SettingsPage.Lights, "x-lights-schedule-until", Res.string.settings_lights_schedule_until, Res.string.settings_lights_schedule_until_description, Res.string.compose_settings_page_lights, Res.string.settings_lights_section_title, desktopOnly = true),
    ForkSearchRow(SettingsPage.Lights, "x-lights-govee-load-devices", Res.string.settings_lights_govee_load_devices, null, Res.string.compose_settings_page_lights, Res.string.settings_lights_section_webhooks, desktopOnly = true),
    ForkSearchRow(SettingsPage.MdbListRatings, "x-mdb-enable-tracking", Res.string.settings_mdb_enable_tracking, Res.string.settings_mdb_enable_tracking_description, Res.string.compose_settings_page_mdblist_ratings, Res.string.settings_mdb_section_title),
    ForkSearchRow(SettingsPage.Music, "x-music-facts-switch", Res.string.settings_music_facts_switch, Res.string.settings_music_facts_switch_description, Res.string.compose_settings_page_music, Res.string.settings_music_facts_section, desktopOnly = true),
    ForkSearchRow(SettingsPage.Music, "x-music-facts-cap", Res.string.settings_music_facts_cap, Res.string.settings_music_facts_cap_description, Res.string.compose_settings_page_music, Res.string.settings_music_facts_section, desktopOnly = true),
    ForkSearchRow(SettingsPage.Music, "x-music-facts-retry", Res.string.settings_music_facts_retry, Res.string.settings_music_facts_retry_description, Res.string.compose_settings_page_music, Res.string.settings_music_facts_section, desktopOnly = true),
    ForkSearchRow(SettingsPage.Music, "x-music-facts-check", Res.string.settings_music_facts_check, Res.string.settings_music_facts_check_description, Res.string.compose_settings_page_music, Res.string.settings_music_facts_section, desktopOnly = true),
    ForkSearchRow(SettingsPage.Music, "x-music-share-export", Res.string.settings_music_share_export, Res.string.settings_music_share_export_description, Res.string.compose_settings_page_music, Res.string.settings_music_share_section, desktopOnly = true),
    ForkSearchRow(SettingsPage.Music, "x-music-share-import", Res.string.settings_music_share_import, Res.string.settings_music_share_import_description, Res.string.compose_settings_page_music, Res.string.settings_music_share_section, desktopOnly = true),
    ForkSearchRow(SettingsPage.Music, "x-music-nebula-link", Res.string.settings_music_nebula_link, Res.string.settings_music_nebula_link_description, Res.string.compose_settings_page_music, Res.string.settings_music_section_sources, desktopOnly = true),
    ForkSearchRow(SettingsPage.Music, "x-music-nebula-link-2", Res.string.settings_music_nebula_link_2, null, Res.string.compose_settings_page_music, Res.string.settings_music_section_sources, desktopOnly = true),
    ForkSearchRow(SettingsPage.Music, "x-music-nebula-link-3", Res.string.settings_music_nebula_link_3, null, Res.string.compose_settings_page_music, Res.string.settings_music_section_sources, desktopOnly = true),
    ForkSearchRow(SettingsPage.Music, "x-music-prefer-addon", Res.string.settings_music_prefer_addon, Res.string.settings_music_prefer_addon_description, Res.string.compose_settings_page_music, Res.string.settings_music_section_sources, desktopOnly = true),
    ForkSearchRow(SettingsPage.Playback, "x-playback-nvidia-rtx-super-resolution", Res.string.settings_playback_nvidia_rtx_super_resolution, null, Res.string.compose_settings_page_video_audio, Res.string.settings_playback_nvidia_rtx_video_section, desktopOnly = true, noCategory = true),
    ForkSearchRow(SettingsPage.Player, "x-playback-ui-scale", Res.string.settings_playback_ui_scale, null, Res.string.compose_settings_page_player, Res.string.settings_playback_section_player, desktopOnly = true, noCategory = true),
    ForkSearchRow(SettingsPage.Player, "x-playback-control-icon-scale", Res.string.settings_playback_control_icon_scale, Res.string.settings_playback_control_icon_scale_description, Res.string.compose_settings_page_player, Res.string.settings_playback_section_player, desktopOnly = true, noCategory = true),
    ForkSearchRow(SettingsPage.Player, "x-playback-external-player-forward-subtitles", Res.string.settings_playback_external_player_forward_subtitles, Res.string.settings_playback_external_player_forward_subtitles_description, Res.string.compose_settings_page_player, Res.string.settings_playback_section_player, desktopOnly = true, noCategory = true),
    ForkSearchRow(SettingsPage.Player, "x-playback-fine-speed-increments", Res.string.settings_playback_fine_speed_increments, Res.string.settings_playback_fine_speed_increments_description, Res.string.compose_settings_page_player, Res.string.settings_playback_section_player, desktopOnly = true, noCategory = true),
    ForkSearchRow(SettingsPage.Player, "x-playback-hud-layout", Res.string.settings_playback_hud_layout, null, Res.string.compose_settings_page_player, Res.string.settings_playback_section_player, desktopOnly = true, noCategory = true),
    ForkSearchRow(SettingsPage.Player, "x-playback-hud-layout-pills", Res.string.settings_playback_hud_layout_pills, Res.string.settings_playback_hud_layout_pills_description, Res.string.compose_settings_page_player, Res.string.settings_playback_section_player, desktopOnly = true, noCategory = true),
    ForkSearchRow(SettingsPage.Player, "x-playback-hud-dock-from-bar", Res.string.settings_playback_hud_dock_from_bar, Res.string.settings_playback_hud_dock_from_bar_description, Res.string.compose_settings_page_player, Res.string.settings_playback_section_player, desktopOnly = true, noCategory = true),
    ForkSearchRow(SettingsPage.Player, "x-playback-seek-handle", Res.string.settings_playback_seek_handle, Res.string.settings_playback_seek_handle_description, Res.string.compose_settings_page_player, Res.string.settings_playback_section_player, desktopOnly = true, noCategory = true),
    ForkSearchRow(SettingsPage.Player, "x-playback-hud-vignette", Res.string.settings_playback_hud_vignette, Res.string.settings_playback_hud_vignette_description, Res.string.compose_settings_page_player, Res.string.settings_playback_section_player, desktopOnly = true, noCategory = true),
    ForkSearchRow(SettingsPage.Player, "x-playback-always-show-clock", Res.string.settings_playback_always_show_clock, Res.string.settings_playback_always_show_clock_description, Res.string.compose_settings_page_player, Res.string.settings_playback_section_player, desktopOnly = true, noCategory = true),
    ForkSearchRow(SettingsPage.Player, "x-playback-info-panel", Res.string.settings_playback_info_panel, Res.string.settings_playback_info_panel_description, Res.string.compose_settings_page_player, Res.string.settings_playback_section_player, desktopOnly = true, noCategory = true),
    ForkSearchRow(SettingsPage.Playback, "x-playback-desktop-inverse-tone-mapping-peak", Res.string.settings_playback_desktop_inverse_tone_mapping_peak, null, Res.string.compose_settings_page_video_audio, Res.string.settings_playback_section_picture_audio, desktopOnly = true, noCategory = true),
    ForkSearchRow(SettingsPage.Playback, "x-playback-desktop-color-reset", Res.string.settings_playback_desktop_color_reset, Res.string.settings_playback_desktop_color_reset_description, Res.string.compose_settings_page_video_audio, Res.string.settings_playback_section_picture_audio, desktopOnly = true, noCategory = true),
    ForkSearchRow(SettingsPage.Playback, "x-playback-desktop-low-vram", Res.string.settings_playback_desktop_low_vram, null, Res.string.compose_settings_page_video_audio, Res.string.settings_playback_section_picture_audio, desktopOnly = true, noCategory = true),
    ForkSearchRow(SettingsPage.Playback, "x-playback-desktop-audio-passthrough", Res.string.settings_playback_desktop_audio_passthrough, null, Res.string.compose_settings_page_video_audio, Res.string.settings_playback_section_picture_audio, desktopOnly = true, noCategory = true),
    ForkSearchRow(SettingsPage.Playback, "x-playback-desktop-custom-shader-paths", Res.string.settings_playback_desktop_custom_shader_paths, null, Res.string.compose_settings_page_video_audio, Res.string.settings_playback_section_anime, desktopOnly = true, noCategory = true),
    ForkSearchRow(SettingsPage.Playback, "x-playback-mpv-configuration-mode", Res.string.settings_playback_mpv_configuration_mode, null, Res.string.compose_settings_page_video_audio, Res.string.settings_playback_section_advanced, desktopOnly = true, noCategory = true),
    ForkSearchRow(SettingsPage.Playback, "x-playback-desktop-custom-mpv-options", Res.string.settings_playback_desktop_custom_mpv_options, null, Res.string.compose_settings_page_video_audio, Res.string.settings_playback_section_advanced, desktopOnly = true, noCategory = true),
    ForkSearchRow(SettingsPage.Playback, "x-playback-desktop-verbose-mpv-logging", Res.string.settings_playback_desktop_verbose_mpv_logging, null, Res.string.compose_settings_page_video_audio, Res.string.settings_playback_section_advanced, desktopOnly = true, noCategory = true),
    ForkSearchRow(SettingsPage.Player, "x-playback-subtitle-show-preferred-only", Res.string.settings_playback_subtitle_show_preferred_only, Res.string.settings_playback_subtitle_show_preferred_only_description, Res.string.compose_settings_page_player, Res.string.settings_playback_section_subtitle_audio, noCategory = true),
    ForkSearchRow(SettingsPage.Player, "x-playback-subtitle-size", Res.string.settings_playback_subtitle_size, null, Res.string.compose_settings_page_player, Res.string.settings_playback_section_subtitle_rendering, noCategory = true),
    ForkSearchRow(SettingsPage.Player, "x-playback-subtitle-vertical-offset", Res.string.settings_playback_subtitle_vertical_offset, null, Res.string.compose_settings_page_player, Res.string.settings_playback_section_subtitle_rendering, noCategory = true),
    ForkSearchRow(SettingsPage.Player, "x-playback-subtitle-bold", Res.string.settings_playback_subtitle_bold, Res.string.settings_playback_subtitle_bold_description, Res.string.compose_settings_page_player, Res.string.settings_playback_section_subtitle_rendering, noCategory = true),
    ForkSearchRow(SettingsPage.Player, "x-playback-subtitle-italic", Res.string.settings_playback_subtitle_italic, Res.string.settings_playback_subtitle_italic_description, Res.string.compose_settings_page_player, Res.string.settings_playback_section_subtitle_rendering, noCategory = true),
    ForkSearchRow(SettingsPage.Player, "x-playback-subtitle-outline", Res.string.settings_playback_subtitle_outline, Res.string.settings_playback_subtitle_outline_description, Res.string.compose_settings_page_player, Res.string.settings_playback_section_subtitle_rendering, noCategory = true),
    ForkSearchRow(SettingsPage.Player, "x-playback-subtitle-outline-width", Res.string.settings_playback_subtitle_outline_width, null, Res.string.compose_settings_page_player, Res.string.settings_playback_section_subtitle_rendering, noCategory = true),
    ForkSearchRow(SettingsPage.Player, "x-playback-subtitle-shadow", Res.string.settings_playback_subtitle_shadow, Res.string.settings_playback_subtitle_shadow_description, Res.string.compose_settings_page_player, Res.string.settings_playback_section_subtitle_rendering, noCategory = true),
    ForkSearchRow(SettingsPage.Player, "x-playback-subtitle-shadow-offset", Res.string.settings_playback_subtitle_shadow_offset, null, Res.string.compose_settings_page_player, Res.string.settings_playback_section_subtitle_rendering, noCategory = true),
    ForkSearchRow(SettingsPage.Player, "x-playback-subtitle-blur", Res.string.settings_playback_subtitle_blur, null, Res.string.compose_settings_page_player, Res.string.settings_playback_section_subtitle_rendering, noCategory = true),
    ForkSearchRow(SettingsPage.Streams, "x-p2p-hide-stats-title", Res.string.settings_p2p_hide_stats_title, Res.string.settings_p2p_hide_stats_subtitle, Res.string.compose_settings_page_streams, Res.string.settings_p2p_title, noCategory = true),
    ForkSearchRow(SettingsPage.Streams, "x-playback-stream-failover", Res.string.settings_playback_stream_failover, Res.string.settings_playback_stream_failover_description, Res.string.compose_settings_page_streams, Res.string.settings_playback_section_stream_selection, noCategory = true),
    ForkSearchRow(SettingsPage.Streams, "x-playback-stream-failover-timeout", Res.string.settings_playback_stream_failover_timeout, Res.string.settings_playback_stream_failover_timeout_description, Res.string.compose_settings_page_streams, Res.string.settings_playback_section_stream_selection, noCategory = true),
    ForkSearchRow(SettingsPage.Streams, "x-playback-desktop-rate-limit-first-wait", Res.string.settings_playback_desktop_rate_limit_first_wait, null, Res.string.compose_settings_page_streams, Res.string.settings_playback_section_stream_selection, desktopOnly = true, noCategory = true),
    ForkSearchRow(SettingsPage.Streams, "x-playback-desktop-rate-limit-second-wait", Res.string.settings_playback_desktop_rate_limit_second_wait, null, Res.string.compose_settings_page_streams, Res.string.settings_playback_section_stream_selection, desktopOnly = true, noCategory = true),
    ForkSearchRow(SettingsPage.Player, "x-playback-skipdb-api-key", Res.string.settings_playback_skipdb_api_key, Res.string.settings_playback_skipdb_api_key_description, Res.string.compose_settings_page_player, Res.string.settings_playback_section_skip_segments, noCategory = true),
    ForkSearchRow(SettingsPage.Player, "x-playback-reuse-binge-group", Res.string.settings_playback_reuse_binge_group, Res.string.settings_playback_reuse_binge_group_description, Res.string.compose_settings_page_player, Res.string.settings_playback_section_next_episode, noCategory = true),
    ForkSearchRow(SettingsPage.Player, "x-playback-hidden-hud-buttons", Res.string.settings_playback_hidden_hud_buttons, Res.string.settings_playback_hidden_hud_buttons_description, Res.string.compose_settings_page_player, Res.string.settings_playback_section_player, desktopOnly = true, noCategory = true),
    ForkSearchRow(SettingsPage.Preshow, "x-theme-song-trivia", Res.string.settings_theme_song_trivia, Res.string.settings_theme_song_trivia_description, Res.string.compose_settings_page_preshow, Res.string.settings_theme_song_section, desktopOnly = true),
    ForkSearchRow(SettingsPage.Preshow, "x-theme-song-details", Res.string.settings_theme_song_details, Res.string.settings_theme_song_details_description, Res.string.compose_settings_page_preshow, Res.string.settings_theme_song_section, desktopOnly = true),
    ForkSearchRow(SettingsPage.Preshow, "x-theme-song-volume", Res.string.settings_theme_song_volume, null, Res.string.compose_settings_page_preshow, Res.string.settings_theme_song_section, desktopOnly = true),
    // fork:live-scores - the pop-ups' switch at the end of the Live TV page.
    ForkSearchRow(SettingsPage.Iptv, "x-live-scores-enable", Res.string.live_scores_enable, Res.string.live_scores_enable_description, Res.string.iptv_settings_page, Res.string.live_scores_section, desktopOnly = true),
    // fork:tonight - the Tonight picker's switches sit at the top of the Random Play page.
    ForkSearchRow(SettingsPage.RandomPlay, "x-tonight-enable", Res.string.tonight_enable, Res.string.tonight_enable_description, Res.string.random_play_title, Res.string.tonight_section, desktopOnly = true),
    ForkSearchRow(SettingsPage.RandomPlay, "x-tonight-bedtime", Res.string.tonight_bedtime, Res.string.tonight_bedtime_description, Res.string.random_play_title, Res.string.tonight_section, desktopOnly = true),
    ForkSearchRow(SettingsPage.RandomPlay, "x-tonight-map-ai", Res.string.tonight_map_ai, Res.string.tonight_map_ai_description, Res.string.random_play_title, Res.string.tonight_section, desktopOnly = true),
    ForkSearchRow(SettingsPage.RandomPlay, "x-tonight-pick-from-top", Res.string.tonight_pick_from_top, Res.string.tonight_pick_from_top_description, Res.string.random_play_title, Res.string.tonight_section, desktopOnly = true),
    ForkSearchRow(SettingsPage.RandomPlay, "x-tonight-source-top-picks", Res.string.tonight_source_top_picks, Res.string.tonight_source_description, Res.string.random_play_title, Res.string.tonight_section, desktopOnly = true),
    ForkSearchRow(SettingsPage.RandomPlay, "x-tonight-source-because", Res.string.tonight_source_because, Res.string.tonight_source_description, Res.string.random_play_title, Res.string.tonight_section, desktopOnly = true),
    ForkSearchRow(SettingsPage.RandomPlay, "x-tonight-source-favourites", Res.string.tonight_source_favourites, Res.string.tonight_source_description, Res.string.random_play_title, Res.string.tonight_section, desktopOnly = true),
    ForkSearchRow(SettingsPage.SimklAuthentication, "x-simkl-track-rewatches", Res.string.settings_simkl_track_rewatches, null, Res.string.compose_settings_page_simkl, Res.string.settings_simkl_section_rewatches),
    ForkSearchRow(SettingsPage.StreamScoring, "x-stream-scoring-reset-title", Res.string.settings_stream_scoring_reset_title, null, Res.string.compose_settings_page_stream_scoring, Res.string.settings_stream_scoring_section_setup),
    ForkSearchRow(SettingsPage.StreamScoring, "x-stream-scoring-minimum-title", Res.string.settings_stream_scoring_minimum_title, null, Res.string.compose_settings_page_stream_scoring, Res.string.settings_stream_scoring_section_setup),
    ForkSearchRow(SettingsPage.Streams, "x-stream-player-badges-title", Res.string.settings_stream_player_badges_title, null, Res.string.compose_settings_page_streams, Res.string.settings_stream_badges_section),
    ForkSearchRow(SettingsPage.TmdbEnrichment, "x-tmdb-hero-artwork-title", Res.string.settings_tmdb_hero_artwork_title, Res.string.settings_tmdb_hero_artwork_description, Res.string.compose_settings_page_tmdb_enrichment, Res.string.settings_tmdb_artwork_catalogs_section),
    ForkSearchRow(SettingsPage.TmdbEnrichment, "x-tvdb-api-key", Res.string.settings_tvdb_api_key, Res.string.settings_tvdb_api_key_description, Res.string.compose_settings_page_tmdb_enrichment, Res.string.settings_tmdb_artwork_catalogs_section),
)

internal fun forkSearchRowsForPlatform(): List<ForkSearchRow> =
    forkSearchRows.filter { !it.desktopOnly || isDesktop }
