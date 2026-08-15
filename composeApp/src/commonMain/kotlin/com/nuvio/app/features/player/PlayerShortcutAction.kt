package com.nuvio.app.features.player

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
    val displayName: String,
    val section: PlayerShortcutSection,
) {
    PlayPause("play_pause", "Play / pause", PlayerShortcutSection.Playback),
    AlternatePlayPause("alternate_play_pause", "Play / pause (alternate)", PlayerShortcutSection.Playback),
    ToggleMute("toggle_mute", "Toggle mute", PlayerShortcutSection.Playback),
    SeekBackward("seek_backward", "Seek backward", PlayerShortcutSection.Playback),
    SeekForward("seek_forward", "Seek forward", PlayerShortcutSection.Playback),
    SpeedUp("speed_up", "Increase playback speed", PlayerShortcutSection.Playback),
    SpeedDown("speed_down", "Decrease playback speed", PlayerShortcutSection.Playback),
    ToggleSpeed("toggle_speed", "Toggle playback speed", PlayerShortcutSection.Playback),
    NextSubtitle("next_subtitle", "Cycle subtitle track", PlayerShortcutSection.TracksAndPanels),
    NextAudio("next_audio", "Cycle audio track", PlayerShortcutSection.TracksAndPanels),
    OpenSources("open_sources", "Open sources panel", PlayerShortcutSection.TracksAndPanels),
    OpenEpisodes("open_episodes", "Open episodes panel", PlayerShortcutSection.TracksAndPanels),
    CycleZoom("cycle_zoom", "Cycle zoom / aspect ratio", PlayerShortcutSection.TracksAndPanels),
    SkipInterval("skip_interval", "Skip intro / outro (while prompt is shown)", PlayerShortcutSection.TracksAndPanels),
    CycleSvp("cycle_svp", "Cycle motion interpolation (SVP)", PlayerShortcutSection.VideoEnhancement),
    CycleHdr("cycle_hdr", "Cycle HDR mode", PlayerShortcutSection.VideoEnhancement),
    CycleColorProfile("cycle_color_profile", "Cycle color profile", PlayerShortcutSection.VideoEnhancement),
    CycleAnime("cycle_anime", "Cycle Anime4K enhancement (current session only)", PlayerShortcutSection.VideoEnhancement),
    ToggleMpvDiagnostics("toggle_mpv_diagnostics", "Toggle MPV diagnostics overlay", PlayerShortcutSection.VideoEnhancement),
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

enum class AppShortcutAction(val id: String, val displayName: String) {
    GoHome("go_home", "Go to Home"),
    OpenSearch("open_search", "Open Search"),
    OpenLibrary("open_library", "Open Library (toggle back to Home)"),
    OpenCalendar("open_calendar", "Open Calendar"),
    ToggleTrailer("toggle_trailer", "Play / dismiss focused trailer"),
    ToggleTrailerMute("toggle_trailer_mute", "Toggle trailer mute"),
    TogglePeoplePanel("toggle_people_panel", "Swap Starring / Production in hero"),
    ToggleFullscreen("toggle_fullscreen", "Toggle fullscreen / windowed"),
    SelectFocused("select_focused", "Select / open focused item"),
    GoBack("go_back", "Go back"),
    DismissOverlay("dismiss_overlay", "Close / dismiss overlay"),
}
