package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_track_number
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

data class AudioTrack(
    val index: Int,
    val id: String,
    val label: String,
    val language: String? = null,
    val isSelected: Boolean = false,
)

data class SubtitleTrack(
    val index: Int,
    val id: String,
    val label: String,
    val language: String? = null,
    val isSelected: Boolean = false,
    val isForced: Boolean = false,
)

data class AddonSubtitle(
    val id: String,
    val url: String,
    val language: String,
    val display: String,
    val addonName: String? = null,
    val isSelected: Boolean = false,
)

enum class SubtitleTab {
    BuiltIn,
    Addons,
    Style,
}

enum class AddonSubtitleStartupMode {
    FAST_STARTUP,
    PREFERRED_ONLY,
    ALL_SUBTITLES,
}

const val SUBTITLE_DELAY_MIN_MS = -60_000
const val SUBTITLE_DELAY_MAX_MS = 60_000
const val SUBTITLE_DELAY_STEP_MS = 100
const val SUBTITLE_AUTO_SYNC_REACTION_COMPENSATION_MS = 300L

data class SubtitleStyleState(
    val textColor: Color = Color.White,
    val backgroundColor: Color = Color.Transparent,
    val outlineColor: Color = Color.Black,
    val outlineEnabled: Boolean = true,
    val outlineWidth: Int = 2,
    // Drop shadow behind the subtitle text (mpv sub-shadow-offset). Independent of the outline.
    val shadowEnabled: Boolean = false,
    // Shadow colour + intensity. The alpha channel is the "intensity"; the default #66000000 is a
    // soft black (0x66 ≈ 40% opacity). Only takes visible effect on a transparent background — mpv
    // aliases sub-shadow-color to sub-back-color, so an opaque background wins (see applySubtitleStyle).
    val shadowColor: Color = Color(0x66000000),
    // Shadow offset in tenths of a scaled pixel (15 = 1.5 px). Divided by 10 for mpv sub-shadow-offset.
    val shadowOffset: Int = SUBTITLE_SHADOW_OFFSET_DEFAULT,
    // Gaussian edge blur (mpv sub-blur). 0 = crisp edges.
    val blur: Int = 0,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val fontSizeSp: Int = 18,
    val bottomOffset: Int = 20,
    // Subtitle font family. Empty = player default. Values are resolved by the platform's
    // font system (mpv/libass on desktop), so only widely-available families are offered.
    val fontFamily: String = "",
    val useForcedSubtitles: Boolean = false,
    val showOnlyPreferredLanguages: Boolean = false,
) {
    companion object {
        val DEFAULT = SubtitleStyleState()
    }
}

// Shadow offset is stored in tenths so it can round-trip through the integer settings store while
// still expressing sub-pixel offsets. The default mirrors the long-standing hardcoded 1.5 px offset.
const val SUBTITLE_SHADOW_OFFSET_DEFAULT = 15
const val SUBTITLE_SHADOW_OFFSET_MIN = 0
const val SUBTITLE_SHADOW_OFFSET_MAX = 60
const val SUBTITLE_SHADOW_OFFSET_STEP = 5
const val SUBTITLE_OUTLINE_WIDTH_MIN = 0
const val SUBTITLE_OUTLINE_WIDTH_MAX = 6
const val SUBTITLE_BLUR_MIN = 0
const val SUBTITLE_BLUR_MAX = 10

/** Formats a tenths-of-a-pixel shadow offset (15 -> "1.5") for display and for mpv. */
fun subtitleShadowOffsetLabel(offsetTenths: Int): String {
    val clamped = offsetTenths.coerceIn(SUBTITLE_SHADOW_OFFSET_MIN, SUBTITLE_SHADOW_OFFSET_MAX)
    return "${clamped / 10}.${clamped % 10}"
}

/**
 * Curated subtitle font families, kept to ones that ship by default on essentially every
 * desktop so they resolve reliably. The empty entry is the player's built-in default.
 */
val SubtitleFontFamilies: List<String> = listOf(
    "",
    "Arial",
    "Verdana",
    "Tahoma",
    "Trebuchet MS",
    "Georgia",
    "Times New Roman",
    "Courier New",
)

fun subtitleFontDisplayName(fontFamily: String): String = fontFamily.ifBlank { "Default" }

/** Cycles to the next/previous curated font family, wrapping around. */
fun cycleSubtitleFontFamily(current: String, delta: Int): String {
    val index = SubtitleFontFamilies.indexOf(current).let { if (it < 0) 0 else it }
    val size = SubtitleFontFamilies.size
    val next = ((index + delta) % size + size) % size
    return SubtitleFontFamilies[next]
}

/**
 * The selectable subtitle font families for this platform. The first entry is always ""
 * (the player default). On desktop this is the user's installed system fonts, so people can
 * use any font they install (e.g. Netflix Sans) without it being shipped with the app.
 */
expect fun availableSubtitleFontFamilies(): List<String>

data class SubtitleSyncCue(
    val startTimeMs: Long,
    val text: String,
)

data class SubtitleAutoSyncUiState(
    val capturedPositionMs: Long? = null,
    val cues: List<SubtitleSyncCue> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

val SubtitleColorSwatches = listOf(
    Color.White,
    Color(0xFFFFD700),
    Color(0xFF00E5FF),
    Color(0xFFFF5C5C),
    Color(0xFF00FF88),
    Color(0xFF9B59B6),
    Color(0xFFF97316),
    Color(0xFF22C55E),
    Color(0xFF3B82F6),
    Color.Black,
)

val SubtitleBackgroundColorSwatches = listOf(
    Color.Transparent,
    Color.Black.copy(alpha = 0.55f),
    Color(0xFF111827).copy(alpha = 0.72f),
    Color(0xFF7F1D1D).copy(alpha = 0.68f),
    Color(0xFF064E3B).copy(alpha = 0.68f),
    Color(0xFF1E3A8A).copy(alpha = 0.68f),
)

// Shadow colour swatches keep the RGB only; the drop-shadow intensity is controlled separately via
// the alpha stepper, so these are shown at full opacity and the current alpha is preserved on pick.
val SubtitleShadowColorSwatches = listOf(
    Color.Black,
    Color(0xFF1F2937),
    Color(0xFF4B5563),
    Color(0xFF3B82F6),
    Color(0xFF7C3AED),
    Color(0xFFDC2626),
    Color.White,
)

fun Color.toStorageHexString(): String {
    fun component(value: Float): String =
        (value * 255f).roundToInt().coerceIn(0, 255).toString(16).padStart(2, '0').uppercase()

    return buildString {
        append('#')
        append(component(alpha))
        append(component(red))
        append(component(green))
        append(component(blue))
    }
}

fun subtitleColorFromStorage(value: String?): Color? {
    val normalized = value
        ?.trim()
        ?.removePrefix("#")
        ?.takeIf { it.length == 6 || it.length == 8 }
        ?: return null

    val argb = if (normalized.length == 6) {
        "FF$normalized"
    } else {
        normalized
    }

    val parsed = argb.toLongOrNull(16) ?: return null
    return Color(
        red = ((parsed shr 16) and 0xFF).toFloat() / 255f,
        green = ((parsed shr 8) and 0xFF).toFloat() / 255f,
        blue = (parsed and 0xFF).toFloat() / 255f,
        alpha = ((parsed shr 24) and 0xFF).toFloat() / 255f,
    )
}

data class SubtitleAudioUiState(
    val audioTracks: List<AudioTrack> = emptyList(),
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    val addonSubtitles: List<AddonSubtitle> = emptyList(),
    val isLoadingAddonSubtitles: Boolean = false,
    val addonSubtitleError: String? = null,
    val selectedAudioIndex: Int = -1,
    val selectedSubtitleIndex: Int = -1,
    val selectedAddonSubtitleId: String? = null,
    val useCustomSubtitles: Boolean = false,
    val subtitleStyle: SubtitleStyleState = SubtitleStyleState.DEFAULT,
    val showAudioModal: Boolean = false,
    val showSubtitleModal: Boolean = false,
    val activeSubtitleTab: SubtitleTab = SubtitleTab.BuiltIn,
)

@Composable
fun localizedTrackDisplayName(label: String?, language: String?, index: Int): String {
    if (!label.isNullOrBlank()) return label
    if (!language.isNullOrBlank()) return languageLabelForCode(language)
    return stringResource(Res.string.compose_player_track_number, index + 1)
}
