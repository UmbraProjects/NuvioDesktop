package com.nuvio.app.features.player

import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource

/**
 * A rebindable player keyboard action — the single source of truth for which player shortcuts
 * can be customized. The desktop key dispatcher and the settings reference page both resolve
 * against this enum.
 *
 * Intentionally NOT here (fixed, non-rebindable): directional navigation/seek/volume and panel
 * navigation (arrows/Enter/Esc). Every other player key is represented here. Defaults +
 * persistence live in `PlayerShortcutsRepository`
 * (desktop), whose defaults reproduce the historical hardcoded bindings exactly.
 */
enum class PlayerShortcutAction(
    val id: String,
    val displayNameRes: StringResource,
    val section: PlayerShortcutSection,
) {
    PlayPause("play_pause", Res.string.shortcut_player_play_pause, PlayerShortcutSection.Playback),
    AlternatePlayPause("alternate_play_pause", Res.string.shortcut_player_alternate_play_pause, PlayerShortcutSection.Playback),
    ToggleMute("toggle_mute", Res.string.shortcut_player_toggle_mute, PlayerShortcutSection.Playback),
    SeekBackward("seek_backward", Res.string.shortcut_player_seek_backward, PlayerShortcutSection.Playback),
    SeekForward("seek_forward", Res.string.shortcut_player_seek_forward, PlayerShortcutSection.Playback),
    SpeedUp("speed_up", Res.string.shortcut_player_speed_up, PlayerShortcutSection.Playback),
    SpeedDown("speed_down", Res.string.shortcut_player_speed_down, PlayerShortcutSection.Playback),
    ToggleSpeed("toggle_speed", Res.string.shortcut_player_toggle_speed, PlayerShortcutSection.Playback),
    ChannelUp("channel_up", Res.string.shortcut_player_channel_up, PlayerShortcutSection.Playback),
    ChannelDown("channel_down", Res.string.shortcut_player_channel_down, PlayerShortcutSection.Playback),
    NextSubtitle("next_subtitle", Res.string.shortcut_player_next_subtitle, PlayerShortcutSection.TracksAndPanels),
    NextAudio("next_audio", Res.string.shortcut_player_next_audio, PlayerShortcutSection.TracksAndPanels),
    OpenSources("open_sources", Res.string.shortcut_player_open_sources, PlayerShortcutSection.TracksAndPanels),
    OpenEpisodes("open_episodes", Res.string.shortcut_player_open_episodes, PlayerShortcutSection.TracksAndPanels),
    CycleZoom("cycle_zoom", Res.string.shortcut_player_cycle_zoom, PlayerShortcutSection.TracksAndPanels),
    SkipInterval("skip_interval", Res.string.shortcut_player_skip_interval, PlayerShortcutSection.TracksAndPanels),
    CycleSvp("cycle_svp", Res.string.shortcut_player_cycle_svp, PlayerShortcutSection.VideoEnhancement),
    CycleHdr("cycle_hdr", Res.string.shortcut_player_cycle_hdr, PlayerShortcutSection.VideoEnhancement),
    CycleColorProfile("cycle_color_profile", Res.string.shortcut_player_cycle_color_profile, PlayerShortcutSection.VideoEnhancement),
    CycleAnime("cycle_anime", Res.string.shortcut_player_cycle_anime, PlayerShortcutSection.VideoEnhancement),
    ToggleMpvDiagnostics("toggle_mpv_diagnostics", Res.string.shortcut_player_toggle_mpv_diagnostics, PlayerShortcutSection.VideoEnhancement),
    ;

    companion object {
        fun fromId(id: String): PlayerShortcutAction? = entries.firstOrNull { it.id == id }
    }
}

enum class PlayerShortcutSection {
    Playback,
    TracksAndPanels,
    VideoEnhancement,
}

enum class AppShortcutAction(val id: String, val displayNameRes: StringResource) {
    GoHome("go_home", Res.string.shortcut_app_go_home),
    OpenSearch("open_search", Res.string.shortcut_app_open_search),
    OpenLibrary("open_library", Res.string.shortcut_app_open_library),
    OpenDiscover("open_discover", Res.string.shortcut_app_open_discover),
    OpenCalendar("open_calendar", Res.string.shortcut_app_open_calendar),
    OpenLiveTv("open_live_tv", Res.string.shortcut_app_open_live_tv),
    OpenMusic("open_music", Res.string.shortcut_app_open_music),
    FocusMusicPlayer("focus_music_player", Res.string.shortcut_app_focus_music_player),
    ToggleTrailer("toggle_trailer", Res.string.shortcut_app_toggle_trailer),
    ToggleTrailerMute("toggle_trailer_mute", Res.string.shortcut_app_toggle_trailer_mute),
    TogglePeoplePanel("toggle_people_panel", Res.string.shortcut_app_toggle_people_panel),
    ToggleGameMode("toggle_game_mode", Res.string.shortcut_app_toggle_game_mode),
    ToggleFullscreen("toggle_fullscreen", Res.string.shortcut_app_toggle_fullscreen),
    SelectFocused("select_focused", Res.string.shortcut_app_select_focused),
    GoBack("go_back", Res.string.shortcut_app_go_back),
    DismissOverlay("dismiss_overlay", Res.string.shortcut_app_dismiss_overlay),
}
