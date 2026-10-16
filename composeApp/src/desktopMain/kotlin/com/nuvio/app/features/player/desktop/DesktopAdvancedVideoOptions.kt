package com.nuvio.app.features.player.desktop

/*
 * "Advanced (mpv)" menu rows whose mpv properties the runtime video profile rewrites on every
 * file load and settings change (applyDesktopVideoProfile / applyDesktopAnimeProfile). Writing
 * them straight to mpv would be undone a frame later, so the menu only saves the choice and the
 * profile reads it from the override map. Keys starting with "@nuvio-" are not mpv properties.
 */

/** Sharpen. Kept as mpv's name so saved choices carry over; drives the CAS shader instead. */
internal const val DESKTOP_SHARPEN_OVERRIDE_KEY = "sharpen"
internal const val DESKTOP_FILM_GRAIN_OVERRIDE_KEY = "@nuvio-film-grain"
internal const val DESKTOP_DISPLAY_PANEL_OVERRIDE_KEY = "@nuvio-display-panel"
internal const val DESKTOP_INTERPOLATION_OVERRIDE_KEY = "@nuvio-interpolation"

/** Never sent to mpv as-is, neither at startup nor from the menu. */
internal val DESKTOP_PROFILE_OWNED_OVERRIDE_KEYS = setOf(
    DESKTOP_SHARPEN_OVERRIDE_KEY,
    DESKTOP_FILM_GRAIN_OVERRIDE_KEY,
    DESKTOP_DISPLAY_PANEL_OVERRIDE_KEY,
    DESKTOP_INTERPOLATION_OVERRIDE_KEY,
)

internal const val DESKTOP_FILM_GRAIN_NONE = "none"
internal const val DESKTOP_FILM_GRAIN_LIGHT = "light"
internal const val DESKTOP_FILM_GRAIN_HEAVY = "heavy"

/**
 * `deband-grain` for the Film grain choice, or [profileDefault] (0 live action, 20 anime) when the
 * choice is Default. mpv adds this noise inside the deband pass, so it needs Deband on; the menu
 * keeps the two rows consistent. Grain an AV1 encode carries is separate and always applied: mpv
 * has no setting that drops it.
 */
internal fun desktopDebandGrain(choice: String?, profileDefault: String): String = when (choice) {
    DESKTOP_FILM_GRAIN_NONE -> "0"
    DESKTOP_FILM_GRAIN_LIGHT -> "16"
    DESKTOP_FILM_GRAIN_HEAVY -> "32"
    else -> profileDefault
}

internal const val DESKTOP_DISPLAY_PANEL_OLED = "oled"

/**
 * `target-contrast` for the display type. An OLED's black is true black, so tone mapping to SDR
 * should not lift shadows toward an LCD's black level; otherwise the HDR mode's own value stands.
 */
internal fun desktopTargetContrast(panel: String?, hdrModeDefault: String): String =
    if (panel == DESKTOP_DISPLAY_PANEL_OLED) "inf" else hdrModeDefault

/**
 * mpv's built-in motion interpolation: it times each frame to the display refresh and blends
 * neighbouring frames at the seams, removing judder (24 fps on 60 Hz) without inventing new
 * in-between frames the way SVP does. Off whenever SVP runs (the two must never stack) and under
 * audio passthrough, because display-resample works by resampling the audio, which a bitstream
 * cannot be.
 *
 * Left at Off, nothing is written, so a `video-sync`/`interpolation` line in the custom options
 * box still stands — except while SVP runs, which always wins. The menu resets mpv itself when
 * the row is switched back to Off.
 */
internal fun desktopInterpolationProperties(
    choice: String?,
    svpActive: Boolean,
    audioPassthrough: Boolean,
): List<Pair<String, String>> = when {
    svpActive || (choice == "yes" && audioPassthrough) -> DESKTOP_INTERPOLATION_OFF
    choice == "yes" -> listOf(
        "video-sync" to "display-resample",
        "interpolation" to "yes",
        "tscale" to "oversample",
    )
    else -> emptyList()
}

internal val DESKTOP_INTERPOLATION_OFF = listOf("interpolation" to "no", "video-sync" to "audio")
