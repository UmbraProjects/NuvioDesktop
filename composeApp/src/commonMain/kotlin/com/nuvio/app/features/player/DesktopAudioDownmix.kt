package com.nuvio.app.features.player

/*
 * Audio output channel count and the dialogue (centre channel) level used when mpv downmixes.
 *
 * A TV that advertises 5.1 over HDMI makes mpv send it 5.1 PCM, and many TVs then fold that into
 * their own speakers or a stereo soundbar badly: dialogue lives in the centre channel and swings
 * between too loud and too quiet. Downmixing to stereo in mpv (libswresample) avoids that, and the
 * centre mix level lifts dialogue over effects in the fold-down.
 *
 * Both are plain mpv options stored in the "Advanced (mpv)" override map, so the player's context
 * menu and the Playback settings page share one saved value and it is applied at file load.
 */

const val DESKTOP_AUDIO_CHANNELS_OVERRIDE_KEY = "audio-channels"
const val DESKTOP_AUDIO_SWRESAMPLE_OVERRIDE_KEY = "audio-swresample-o"

data class DesktopAudioChoice(val label: String, val value: String, val isDefault: Boolean = false)

/** mpv's own default is `auto-safe`: the layout the Windows audio device is set to. */
val desktopAudioChannelChoices = listOf(
    DesktopAudioChoice("Auto (device layout)", "auto-safe", isDefault = true),
    DesktopAudioChoice("Stereo (downmix)", "stereo"),
    DesktopAudioChoice("5.1", "5.1"),
    DesktopAudioChoice("7.1", "7.1"),
)

/**
 * libswresample's centre mix level, a linear gain. Its default is 0.707 (-3 dB); only used when a
 * surround track is mixed down to fewer channels.
 */
val desktopAudioDialogueChoices = listOf(
    DesktopAudioChoice("Normal", "", isDefault = true),
    DesktopAudioChoice("+3 dB", "center_mix_level=1.0"),
    DesktopAudioChoice("+6 dB", "center_mix_level=1.414"),
    DesktopAudioChoice("+9 dB", "center_mix_level=2.0"),
)

fun List<DesktopAudioChoice>.selectedFor(overrides: Map<String, String>, key: String): DesktopAudioChoice {
    val value = overrides[key]
    return firstOrNull { it.value == value } ?: first { it.isDefault }
}
