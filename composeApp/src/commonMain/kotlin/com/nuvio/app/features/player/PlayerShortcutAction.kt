package com.nuvio.app.features.player

/**
 * A rebindable player keyboard action — the single source of truth for which player shortcuts
 * can be customized. The desktop key dispatcher and the settings reference page both resolve
 * against this enum.
 *
 * Intentionally NOT here (fixed, non-rebindable): the arrow keys (seek/volume), `K` (play/pause
 * alternate), `Tab` (skip intro/outro), and panel navigation (arrows/Enter/Esc). Those stay
 * hardcoded in the dispatcher. Defaults + persistence live in `PlayerShortcutsRepository`
 * (desktop), whose defaults reproduce the historical hardcoded bindings exactly.
 */
enum class PlayerShortcutAction(
    val id: String,
    val displayName: String,
    val section: PlayerShortcutSection,
) {
    PlayPause("play_pause", "Play / pause", PlayerShortcutSection.Playback),
    SeekBackward("seek_backward", "Seek backward", PlayerShortcutSection.Playback),
    SeekForward("seek_forward", "Seek forward", PlayerShortcutSection.Playback),
    SpeedUp("speed_up", "Increase playback speed", PlayerShortcutSection.Playback),
    SpeedDown("speed_down", "Decrease playback speed", PlayerShortcutSection.Playback),
    NextSubtitle("next_subtitle", "Cycle subtitle track", PlayerShortcutSection.TracksAndPanels),
    NextAudio("next_audio", "Cycle audio track", PlayerShortcutSection.TracksAndPanels),
    OpenSources("open_sources", "Open sources panel", PlayerShortcutSection.TracksAndPanels),
    OpenEpisodes("open_episodes", "Open episodes panel", PlayerShortcutSection.TracksAndPanels),
    CycleZoom("cycle_zoom", "Cycle zoom / aspect ratio", PlayerShortcutSection.TracksAndPanels),
    CycleSvp("cycle_svp", "Cycle motion interpolation (SVP)", PlayerShortcutSection.VideoEnhancement),
    CycleHdr("cycle_hdr", "Cycle HDR mode", PlayerShortcutSection.VideoEnhancement),
    CycleColorProfile("cycle_color_profile", "Cycle color profile", PlayerShortcutSection.VideoEnhancement),
    CycleAnime("cycle_anime", "Cycle Anime4K enhancement", PlayerShortcutSection.VideoEnhancement),
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
