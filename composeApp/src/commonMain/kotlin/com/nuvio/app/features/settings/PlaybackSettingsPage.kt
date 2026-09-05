package com.nuvio.app.features.settings

import com.nuvio.app.core.build.AppFeaturePolicy
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioDialogSurface
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.player.AddonSubtitleStartupMode
import com.nuvio.app.features.player.AudioLanguageOption
import com.nuvio.app.features.player.AudioRejectKeyword
import com.nuvio.app.features.player.SubtitleRejectKeyword
import com.nuvio.app.features.player.AvailableLanguageOptions
import com.nuvio.app.features.player.DesktopAnimeMode
import com.nuvio.app.features.player.DesktopBufferPreset
import com.nuvio.app.features.player.DesktopCustomShaderCatalog
import com.nuvio.app.features.player.DesktopCustomShaderOption
import com.nuvio.app.features.player.DesktopRendererApi
import com.nuvio.app.features.player.DesktopPlayerNotificationPosition
import com.nuvio.app.features.player.DesktopSourceNotchPosition
import com.nuvio.app.features.player.DesktopColorProfile
import com.nuvio.app.features.player.DesktopHdrMode
import com.nuvio.app.features.player.DesktopLowVramMode
import com.nuvio.app.features.player.DesktopMpvConfigMode
import com.nuvio.app.features.player.ExternalPlayerApp
import com.nuvio.app.features.player.ExternalPlayerPlatform
import com.nuvio.app.features.player.HERO_TV_TRAILER_DELAY_VALUES
import com.nuvio.app.features.player.IosAudioOutputMode
import com.nuvio.app.features.player.IosHardwareDecoderMode
import com.nuvio.app.features.player.localizedLabel
import com.nuvio.app.features.player.IosTargetPrimaries
import com.nuvio.app.features.player.IosTargetTransfer
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.SEEK_STEP_SECONDS_RANGE
import com.nuvio.app.features.player.STREAM_AUTO_PLAY_TIMEOUT_VALUES
import com.nuvio.app.features.player.STREAM_FAILOVER_TIMEOUT_VALUES
import com.nuvio.app.features.player.SUBTITLE_ASS_SCALE_MAX
import com.nuvio.app.features.player.SUBTITLE_ASS_SCALE_MIN
import com.nuvio.app.features.player.SUBTITLE_ASS_SCALE_STEP
import com.nuvio.app.features.player.SubtitleAssStyleMode
import com.nuvio.app.features.player.SUBTITLE_BLUR_MAX
import com.nuvio.app.features.player.SUBTITLE_BLUR_MIN
import com.nuvio.app.features.player.SUBTITLE_OUTLINE_WIDTH_MAX
import com.nuvio.app.features.player.SUBTITLE_OUTLINE_WIDTH_MIN
import com.nuvio.app.features.player.SUBTITLE_SHADOW_OFFSET_MAX
import com.nuvio.app.features.player.SUBTITLE_SHADOW_OFFSET_MIN
import com.nuvio.app.features.player.SUBTITLE_SHADOW_OFFSET_STEP
import com.nuvio.app.features.player.SubtitleLanguageOption
import com.nuvio.app.features.player.formatPlaybackSpeedLabel
import com.nuvio.app.features.player.subtitleShadowOffsetLabel
import com.nuvio.app.features.player.languageLabelForCode
import com.nuvio.app.features.player.normalizeLanguageCode
import com.nuvio.app.features.player.subtitleColorFromStorage
import com.nuvio.app.features.player.toStorageHexString
import com.nuvio.app.features.p2p.P2pConsentDialog
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.plugins.PluginsUiState
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.streams.STREAM_PREFETCH_CACHE_MINUTE_VALUES
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamAutoPlaySource
import com.nuvio.app.features.streams.StreamPrefetchScope
import com.nuvio.app.isDesktop
import com.nuvio.app.isIos
import com.nuvio.app.isWindows
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import com.nuvio.app.core.ui.trackTextInputFocus
import com.nuvio.app.features.player.skip.SkipAutoAcceptMode
import com.nuvio.app.core.ui.accentBrush

internal fun LazyListScope.playbackSettingsContent(
    isTablet: Boolean,
    showLoadingOverlay: Boolean,
    defaultPlaybackSpeed: Float,
    preferredAudioLanguage: String,
    secondaryPreferredAudioLanguage: String?,
    preferredSubtitleLanguage: String,
    secondaryPreferredSubtitleLanguage: String?,
    streamReuseLastLinkEnabled: Boolean,
    streamReuseLastLinkCacheHours: Int,
    decoderPriority: Int,
    mapDV7ToHevc: Boolean,
    tunnelingEnabled: Boolean,
    useLibass: Boolean,
    libassRenderType: String,
) {
    item {
        PlaybackSettingsSection(
            isTablet = isTablet,
            showLoadingOverlay = showLoadingOverlay,
            defaultPlaybackSpeed = defaultPlaybackSpeed,
            preferredAudioLanguage = preferredAudioLanguage,
            secondaryPreferredAudioLanguage = secondaryPreferredAudioLanguage,
            preferredSubtitleLanguage = preferredSubtitleLanguage,
            secondaryPreferredSubtitleLanguage = secondaryPreferredSubtitleLanguage,
            streamReuseLastLinkEnabled = streamReuseLastLinkEnabled,
            streamReuseLastLinkCacheHours = streamReuseLastLinkCacheHours,
            decoderPriority = decoderPriority,
            mapDV7ToHevc = mapDV7ToHevc,
            tunnelingEnabled = tunnelingEnabled,
            useLibass = useLibass,
            libassRenderType = libassRenderType,
        )
    }
}

private fun formatStep(value: Float): String {
    return if (value % 1f == 0f) {
        value.toInt().toString()
    } else {
        value.toString()
    }
}

@Composable
private fun addonSubtitleStartupModeLabel(mode: AddonSubtitleStartupMode): String =
    when (mode) {
        AddonSubtitleStartupMode.FAST_STARTUP ->
            stringResource(Res.string.settings_playback_addon_subtitle_startup_fast)
        AddonSubtitleStartupMode.PREFERRED_ONLY ->
            stringResource(Res.string.settings_playback_addon_subtitle_startup_preferred)
        AddonSubtitleStartupMode.ALL_SUBTITLES ->
            stringResource(Res.string.settings_playback_addon_subtitle_startup_all)
    }

fun snapToStep(value: Float, step: Float): Float {
    return (value / step).roundToInt() * step
}

fun calculateSteps(
    min: Float,
    max: Float,
    stepSize: Float
): Int {
    val totalSteps = ((max - min) / stepSize).roundToInt()
    return (totalSteps - 1).coerceAtLeast(0)
}

/** How far the slider readout is pulled down toward its slider. */
private val ValueBoxSliderNudge = 4.dp

@Composable
fun ValueBox(
    text: String,
    modifier: Modifier = Modifier
) {
    Box(
        // Nudged toward the slider it labels. A plain offset rather than tighter column spacing:
        // most of the gap is the text's own line leading plus the slider's touch-target padding,
        // neither of which the arrangement can reach, and an offset moves the glyph without
        // changing the height the column reserves.
        modifier = modifier.offset(y = ValueBoxSliderNudge),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium.accentBrush(),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun SettingsSliderRow(
    title: String,
    value: Int,
    valueText: String,
    valueTextForValue: ((Int) -> String)? = null,
    valueRange: IntRange,
    step: Int,
    isTablet: Boolean,
    description: String? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onValueChange: (Int) -> Unit,
) {
    val horizontalPadding = 16.dp
    var sliderValue by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val displayedValue = sliderValue.roundToInt().coerceIn(valueRange.first, valueRange.last)
    val displayedValueText = valueTextForValue?.invoke(displayedValue)
        ?: if (displayedValue == value) {
            valueText
        } else {
            valueText.replaceFirst(value.toString(), displayedValue.toString())
        }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 8.dp)
            .alpha(if (enabled) 1f else 0.55f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsRowTextColumn(
            title = title,
            description = description,
            isTablet = isTablet,
            modifier = Modifier.weight(1f),
        )
        Column(
            modifier = Modifier.width(if (isTablet) 210.dp else 220.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ValueBox(text = displayedValueText)
            SettingsModernSlider(
                value = sliderValue.coerceIn(valueRange.first.toFloat(), valueRange.last.toFloat()),
                onValueChange = { if (enabled) sliderValue = snapToStep(it, step.toFloat()) },
                onValueChangeFinished = {
                    if (enabled) onValueChange(sliderValue.roundToInt().coerceIn(valueRange.first, valueRange.last))
                },
                enabled = enabled,
                valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
                steps = calculateSteps(valueRange.first.toFloat(), valueRange.last.toFloat(), step.toFloat()),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Title + optional description shared by slider rows. The value readout is stacked above the
 * slider itself, leaving this text in the same leading column as the other setting row types.
 */
@Composable
private fun SettingsRowTextColumn(
    title: String,
    description: String?,
    isTablet: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(end = if (isTablet) SettingsRowTextGap else 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = if (isTablet) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
        if (!description.isNullOrBlank()) {
            SettingsSubtext(
                text = description,
                isTablet = isTablet,
            )
        }
    }
}

/**
 * Range counterpart of [SettingsSliderRow]. The dragged thumb pushes the other one along rather
 * than crossing it, so the readout and the drawn band stay honest for the whole gesture — the
 * ordering is not deferred to whatever the caller does on release.
 */
@Composable
internal fun SettingsRangeSliderRow(
    title: String,
    lowValue: Int,
    highValue: Int,
    valueTextForRange: (low: Int, high: Int) -> String,
    valueRange: IntRange,
    step: Int,
    isTablet: Boolean,
    description: String? = null,
    enabled: Boolean = true,
    minGap: Int = 1,
    modifier: Modifier = Modifier,
    onValueChange: (low: Int, high: Int) -> Unit,
) {
    val horizontalPadding = 16.dp
    var lowSliderValue by remember(lowValue) { mutableFloatStateOf(lowValue.toFloat()) }
    var highSliderValue by remember(highValue) { mutableFloatStateOf(highValue.toFloat()) }
    val displayedLow = lowSliderValue.roundToInt().coerceIn(valueRange.first, valueRange.last)
    val displayedHigh = highSliderValue.roundToInt().coerceIn(valueRange.first, valueRange.last)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 8.dp)
            .alpha(if (enabled) 1f else 0.55f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsRowTextColumn(
            title = title,
            description = description,
            isTablet = isTablet,
            modifier = Modifier.weight(1f),
        )
        Column(
            modifier = Modifier.width(if (isTablet) 210.dp else 220.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ValueBox(
                text = valueTextForRange(displayedLow, displayedHigh),
            )
            SettingsModernRangeSlider(
                lowValue = lowSliderValue.coerceIn(valueRange.first.toFloat(), valueRange.last.toFloat()),
                highValue = highSliderValue.coerceIn(valueRange.first.toFloat(), valueRange.last.toFloat()),
                onValueChange = { low, high ->
                    if (!enabled) return@SettingsModernRangeSlider
                    val snappedLow = snapToStep(low, step.toFloat())
                    val snappedHigh = snapToStep(high, step.toFloat())
                    val gap = minGap.toFloat()
                    // Whichever end moved is authoritative; the other is pushed out of its way and
                    // clamped at the track edge, so a thumb dragged to the far end parks the pair
                    // there instead of the gesture silently doing nothing.
                    if (snappedLow != lowSliderValue) {
                        lowSliderValue = snappedLow.coerceAtMost(valueRange.last - gap)
                        highSliderValue = highSliderValue.coerceAtLeast(lowSliderValue + gap)
                    } else {
                        highSliderValue = snappedHigh.coerceAtLeast(valueRange.first + gap)
                        lowSliderValue = lowSliderValue.coerceAtMost(highSliderValue - gap)
                    }
                },
                onValueChangeFinished = {
                    if (enabled) {
                        onValueChange(
                            lowSliderValue.roundToInt().coerceIn(valueRange.first, valueRange.last),
                            highSliderValue.roundToInt().coerceIn(valueRange.first, valueRange.last),
                        )
                    }
                },
                enabled = enabled,
                valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
                steps = calculateSteps(valueRange.first.toFloat(), valueRange.last.toFloat(), step.toFloat()),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun subtitleColorLabel(color: Color): String {
    return if (color.alpha == 0f) {
        stringResource(Res.string.settings_playback_subtitle_color_transparent)
    } else {
        color.toStorageHexString()
    }
}

private data class SubtitleColorOption(
    val color: Color,
    val label: String,
)

private sealed interface DesktopAnimeEnhancementChoice {
    val label: String
    val description: String

    data class BuiltIn(val mode: DesktopAnimeMode) : DesktopAnimeEnhancementChoice {
        override val label: String = mode.label
        override val description: String = mode.description
    }

    data class Custom(val shader: DesktopCustomShaderOption) : DesktopAnimeEnhancementChoice {
        override val label: String = shader.fileName
        override val description: String = "Custom shader: ${shader.path}"
    }
}

private val desktopAnimeBuiltInModes: List<DesktopAnimeMode>
    get() = DesktopAnimeMode.entries.filter { it != DesktopAnimeMode.CustomShader }

private fun desktopAnimeEnhancementChoices(
    customShaders: List<DesktopCustomShaderOption>,
): List<DesktopAnimeEnhancementChoice> =
    desktopAnimeBuiltInModes.map { DesktopAnimeEnhancementChoice.BuiltIn(it) } +
        customShaders.map { DesktopAnimeEnhancementChoice.Custom(it) }

private fun selectedDesktopAnimeEnhancementChoice(
    mode: DesktopAnimeMode,
    selectedCustomShaderPath: String,
    choices: List<DesktopAnimeEnhancementChoice>,
): DesktopAnimeEnhancementChoice {
    if (mode == DesktopAnimeMode.CustomShader) {
        choices.filterIsInstance<DesktopAnimeEnhancementChoice.Custom>()
            .firstOrNull { it.shader.path == selectedCustomShaderPath }
            ?.let { return it }
    }
    return choices.filterIsInstance<DesktopAnimeEnhancementChoice.BuiltIn>()
        .firstOrNull { it.mode == mode }
        ?: DesktopAnimeEnhancementChoice.BuiltIn(DesktopAnimeMode.Off)
}

@Composable
private fun subtitleTextColorOptions(): List<SubtitleColorOption> =
    listOf(
        SubtitleColorOption(Color.White, "White"),
        SubtitleColorOption(Color(0xFFFFD700), "Gold"),
        SubtitleColorOption(Color(0xFF00E5FF), "Cyan"),
        SubtitleColorOption(Color(0xFFFF5C5C), "Coral"),
        SubtitleColorOption(Color(0xFF00FF88), "Mint"),
        SubtitleColorOption(Color(0xFF9B59B6), "Purple"),
        SubtitleColorOption(Color(0xFFF97316), "Orange"),
        SubtitleColorOption(Color(0xFF22C55E), "Green"),
        SubtitleColorOption(Color(0xFF3B82F6), "Blue"),
        SubtitleColorOption(Color.Black, "Black"),
    )

@Composable
private fun subtitleBackgroundColorOptions(): List<SubtitleColorOption> =
    listOf(
        SubtitleColorOption(Color.Transparent, stringResource(Res.string.settings_playback_subtitle_color_transparent)),
        SubtitleColorOption(Color.Black.copy(alpha = 0.55f), "Soft black"),
        SubtitleColorOption(Color(0xFF111827).copy(alpha = 0.72f), "Charcoal"),
        SubtitleColorOption(Color(0xFF7F1D1D).copy(alpha = 0.68f), "Burgundy"),
        SubtitleColorOption(Color(0xFF064E3B).copy(alpha = 0.68f), "Forest"),
        SubtitleColorOption(Color(0xFF1E3A8A).copy(alpha = 0.68f), "Navy"),
    )

@Composable
private fun SubtitleColorDropdownRow(
    title: String,
    options: List<SubtitleColorOption>,
    selectedColor: Color,
    enabled: Boolean,
    isTablet: Boolean,
    onColorSelected: (Color) -> Unit,
) {
    // No preset match means the colour came from the custom hex dialog: show it as-is instead of
    // falling back to the first preset, which used to make a custom colour read as "White".
    val selectedOption = options.firstOrNull { it.color.toStorageHexString() == selectedColor.toStorageHexString() }
        ?: SubtitleColorOption(selectedColor, subtitleColorLabel(selectedColor))
    var expanded by remember { mutableStateOf(false) }
    var showCustomColorDialog by remember { mutableStateOf(false) }
    val controlWidth = if (isTablet) 210.dp else 260.dp
    val triggerShape = RoundedCornerShape(
        topStart = 8.dp,
        topEnd = 8.dp,
        bottomStart = if (expanded) 0.dp else 8.dp,
        bottomEnd = if (expanded) 0.dp else 8.dp,
    )
    val menuShape = RoundedCornerShape(
        topStart = 0.dp,
        topEnd = 0.dp,
        bottomStart = 8.dp,
        bottomEnd = 8.dp,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (isTablet) 16.dp else 16.dp, vertical = if (isTablet) 8.8.dp else 12.dp)
            .alpha(if (enabled) 1f else 0.55f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = if (isTablet) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Box(modifier = Modifier.width(controlWidth)) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { expanded = true },
                shape = triggerShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SubtitleColorSwatch(color = selectedOption.color)
                    Text(
                        text = selectedOption.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.Rounded.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            DropdownMenu(
                expanded = expanded && enabled,
                onDismissRequest = { expanded = false },
                modifier = Modifier.width(controlWidth),
                shape = menuShape,
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
            ) {
                options.forEach { option ->
                    val selected = option.color.toStorageHexString() == selectedOption.color.toStorageHexString()
                    DropdownMenuItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (selected) 1f else 0.82f),
                        text = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SubtitleColorSwatch(color = option.color)
                                Text(option.label)
                            }
                        },
                        onClick = {
                            expanded = false
                            onColorSelected(option.color)
                        },
                    )
                }
                DropdownMenuItem(
                    modifier = Modifier.fillMaxWidth(),
                    text = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SubtitleColorSwatch(color = selectedColor)
                            Text(stringResource(Res.string.settings_playback_subtitle_color_custom))
                        }
                    },
                    onClick = {
                        expanded = false
                        showCustomColorDialog = true
                    },
                )
            }
        }
    }

    if (showCustomColorDialog) {
        SubtitleCustomColorDialog(
            title = title,
            initialColor = selectedColor,
            onConfirm = { color ->
                showCustomColorDialog = false
                onColorSelected(color)
            },
            onDismiss = { showCustomColorDialog = false },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SubtitleCustomColorDialog(
    title: String,
    initialColor: Color,
    onConfirm: (Color) -> Unit,
    onDismiss: () -> Unit,
) {
    var hexInput by remember(initialColor) { mutableStateOf(initialColor.toStorageHexString()) }
    var showError by remember { mutableStateOf(false) }
    val parsedColor = subtitleColorFromInput(hexInput, initialColor)

    BasicAlertDialog(onDismissRequest = onDismiss) {
        NuvioDialogSurface(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_playback_subtitle_color_custom_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(Res.string.settings_playback_subtitle_color_custom_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SubtitleColorSwatch(color = parsedColor ?: initialColor)
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = BorderStroke(
                            1.dp,
                            if (showError) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        ),
                    ) {
                        BasicTextField(
                            value = hexInput,
                            onValueChange = {
                                hexInput = it
                                showError = false
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .trackTextInputFocus()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontFamily = FontFamily.Monospace,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { innerTextField ->
                                if (hexInput.isEmpty()) {
                                    Text(
                                        text = stringResource(Res.string.settings_playback_subtitle_color_custom_placeholder),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                                innerTextField()
                            },
                        )
                    }
                }
                if (showError) {
                    Text(
                        text = stringResource(Res.string.settings_playback_subtitle_color_custom_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                    TextButton(onClick = {
                        val color = subtitleColorFromInput(hexInput, initialColor)
                        if (color == null) {
                            showError = true
                        } else {
                            onConfirm(color)
                        }
                    }) {
                        Text(stringResource(Res.string.action_save))
                    }
                }
            }
        }
    }
}

/**
 * Parses a typed hex colour, applying the same opacity rule as the in-player prompt: eight digits
 * set the alpha outright, six carry none and so keep the alpha of the colour being replaced. A
 * fully transparent [current] is the exception - honouring its alpha would apply an invisible
 * colour, which is never what typing a colour in means.
 */
private fun subtitleColorFromInput(input: String, current: Color): Color? {
    val parsed = subtitleColorFromStorage(input) ?: return null
    val digits = input.trim().removePrefix("#").length
    if (digits == 8) return parsed
    return parsed.copy(alpha = if (current.alpha > 0f) current.alpha else 1f)
}

@Composable
private fun SubtitleColorSwatch(color: Color) {
    Surface(
        modifier = Modifier.size(18.dp),
        shape = RoundedCornerShape(5.dp),
        color = if (color.alpha == 0f) MaterialTheme.colorScheme.surface else color,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
    ) {}
}

@Composable
private fun PlaybackSettingsSection(
    isTablet: Boolean,
    showLoadingOverlay: Boolean,
    defaultPlaybackSpeed: Float,
    preferredAudioLanguage: String,
    secondaryPreferredAudioLanguage: String?,
    preferredSubtitleLanguage: String,
    secondaryPreferredSubtitleLanguage: String?,
    streamReuseLastLinkEnabled: Boolean,
    streamReuseLastLinkCacheHours: Int,
    decoderPriority: Int,
    mapDV7ToHevc: Boolean,
    tunnelingEnabled: Boolean,
    useLibass: Boolean,
    libassRenderType: String,
) {
    var showExternalPlayerAppDialog by remember { mutableStateOf(false) }
    var showAutoPlayRegexDialog by remember { mutableStateOf(false) }
    var showP2pConsentDialog by remember { mutableStateOf(false) }
    val pluginsEnabled = AppFeaturePolicy.pluginsEnabled
    val autoPlayPlayerSettings by PlayerSettingsRepository.uiState.collectAsStateWithLifecycle()
    // ensureLoaded, in the same remember-then-collect shape as p2pSettings below: without it the
    // resolver-only prefetch row stays hidden for anyone who has debrid configured but has not
    // opened its settings page this session.
    val debridSettings by remember {
        DebridSettingsRepository.ensureLoaded()
        DebridSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val p2pSettings by remember {
        P2pSettingsRepository.ensureLoaded()
        P2pSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val availableExternalPlayers = ExternalPlayerPlatform.availablePlayers()
    val selectedExternalPlayer = availableExternalPlayers.firstOrNull {
        it.id == autoPlayPlayerSettings.externalPlayerId
    }
    val addonUiState by AddonRepository.uiState.collectAsStateWithLifecycle()
    val pluginUiState = if (pluginsEnabled) {
        val state by PluginRepository.uiState.collectAsStateWithLifecycle()
        state
    } else {
        PluginsUiState(pluginsEnabled = false)
    }
    val autoPlayAddonNames = addonUiState.addons
        .enabledAddons()
        .mapNotNull { it.manifest }
        .filter { manifest -> manifest.resources.any { resource -> resource.name == "stream" } }
        .map { it.name }
        .distinct()
        .sorted()
    val autoPlayPluginNames = if (pluginsEnabled) {
        pluginUiState.scrapers
            .filter { it.enabled }
            .map { it.name }
            .distinct()
            .sorted()
    } else {
        emptyList()
    }
    val hapticFeedback = LocalHapticFeedback.current
    val sectionSpacing = if (isTablet) 18.dp else 12.dp

    Column(
        verticalArrangement = Arrangement.spacedBy(sectionSpacing),
    ) {
        if (isWindows) {
            SettingsSection(
                title = stringResource(Res.string.settings_playback_nvidia_rtx_video_section),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_nvidia_rtx_super_resolution),
                        description = stringResource(Res.string.settings_playback_nvidia_rtx_super_resolution_desc),
                        checked = autoPlayPlayerSettings.nvidiaRtxSuperResolutionEnabled,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setNvidiaRtxSuperResolutionEnabled,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_nvidia_rtx_hdr),
                        description = stringResource(Res.string.settings_playback_nvidia_rtx_hdr_desc),
                        checked = autoPlayPlayerSettings.nvidiaRtxHdrEnabled,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setNvidiaRtxHdrEnabled,
                        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.RtxHdr),
                    )
                }
            }
        }
        SettingsSection(
            title = stringResource(Res.string.settings_playback_section_player),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                // Leads the section: it resizes the whole player HUD, and it is the first thing
                // a new user on a smaller display needs to find.
                if (isDesktop) {
                    val uiScalePercent = autoPlayPlayerSettings.desktopUiScalePercent
                    SettingsSliderRow(
                        title = stringResource(Res.string.settings_playback_ui_scale),
                        value = uiScalePercent,
                        valueText = "${if (uiScalePercent > 0) "+" else ""}$uiScalePercent%",
                        valueTextForValue = { "${if (it > 0) "+" else ""}$it%" },
                        valueRange = -50..50,
                        step = 5,
                        isTablet = isTablet,
                        onValueChange = PlayerSettingsRepository::setDesktopUiScalePercent,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                }
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_show_loading_overlay),
                    description = stringResource(Res.string.settings_playback_show_loading_overlay_description),
                    checked = showLoadingOverlay,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setShowLoadingOverlay,
                )
                SettingsGroupDivider(isTablet = isTablet)
                // Player preference picker: Internal / External
                SettingsChoiceRow(
                    title = stringResource(Res.string.settings_playback_player_preference),
                    description = stringResource(Res.string.settings_playback_player_preference),
                    options = listOf(
                        SettingsChoiceOption(false, stringResource(Res.string.settings_playback_player_preference_internal)),
                        SettingsChoiceOption(true, stringResource(Res.string.settings_playback_player_preference_external)),
                    ),
                    selectedValue = autoPlayPlayerSettings.externalPlayerEnabled,
                    isTablet = isTablet,
                    onSelected = PlayerSettingsRepository::setExternalPlayerEnabled,
                )
                if ((isIos || isDesktop) && autoPlayPlayerSettings.externalPlayerEnabled) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_playback_external_player_app),
                        description = selectedExternalPlayer?.name
                            ?: if (availableExternalPlayers.isEmpty()) {
                                stringResource(Res.string.settings_playback_external_player_none_available)
                            } else {
                                stringResource(Res.string.settings_playback_not_set)
                            },
                        isTablet = isTablet,
                        onClick = { showExternalPlayerAppDialog = true },
                    )
                }
                if (!isIos && autoPlayPlayerSettings.externalPlayerEnabled) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_external_player_forward_subtitles),
                        description = stringResource(Res.string.settings_playback_external_player_forward_subtitles_description),
                        checked = autoPlayPlayerSettings.externalPlayerForwardSubtitles,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setExternalPlayerForwardSubtitles,
                    )
                }
                SettingsGroupDivider(isTablet = isTablet)
                // Speed sliders run in twentieths so their positions land exactly on the 0.05 grid
                // the repository snaps to; 10..80 is 0.5x..4x.
                SettingsSliderRow(
                    title = stringResource(Res.string.settings_playback_default_speed),
                    value = (defaultPlaybackSpeed * 20f).roundToInt(),
                    valueText = formatPlaybackSpeedLabel(defaultPlaybackSpeed),
                    valueTextForValue = { formatPlaybackSpeedLabel(it / 20f) },
                    valueRange = 10..80,
                    step = 1,
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.DefaultSpeed),
                    onValueChange = { PlayerSettingsRepository.setDefaultPlaybackSpeed(it / 20f) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSliderRow(
                    title = stringResource(Res.string.settings_playback_seek_step),
                    description = stringResource(Res.string.settings_playback_seek_step_description),
                    value = autoPlayPlayerSettings.seekStepSeconds,
                    valueText = "${autoPlayPlayerSettings.seekStepSeconds}s",
                    valueTextForValue = { "${it}s" },
                    valueRange = SEEK_STEP_SECONDS_RANGE,
                    step = 1,
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("seek-step")),
                    onValueChange = PlayerSettingsRepository::setSeekStepSeconds,
                )
                if (isDesktop) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_fine_speed_increments),
                        description = stringResource(Res.string.settings_playback_fine_speed_increments_description),
                        checked = autoPlayPlayerSettings.desktopPlaybackSpeedFineIncrementsEnabled,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setDesktopPlaybackSpeedFineIncrementsEnabled,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsRangeSliderRow(
                        title = stringResource(Res.string.settings_playback_speed_toggle),
                        description = stringResource(Res.string.settings_playback_speed_toggle_description),
                        lowValue = (autoPlayPlayerSettings.playbackSpeedToggleLow * 20f).roundToInt(),
                        highValue = (autoPlayPlayerSettings.playbackSpeedToggleHigh * 20f).roundToInt(),
                        valueTextForRange = { low, high ->
                            "${formatPlaybackSpeedLabel(low / 20f)} ⇄ ${formatPlaybackSpeedLabel(high / 20f)}"
                        },
                        valueRange = 10..80,
                        step = 1,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.SpeedToggle),
                        onValueChange = { low, high ->
                            PlayerSettingsRepository.setPlaybackSpeedToggleRange(
                                low = low / 20f,
                                high = high / 20f,
                            )
                        },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_mouse_move_reveals_controls),
                        description = stringResource(Res.string.settings_playback_mouse_move_reveals_controls_description),
                        checked = autoPlayPlayerSettings.mouseMoveRevealsControlsEnabled,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.MouseMove),
                        onCheckedChange = PlayerSettingsRepository::setMouseMoveRevealsControlsEnabled,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    val sourceNotchLabels = mapOf(
                        DesktopSourceNotchPosition.Right to
                            stringResource(Res.string.settings_playback_source_notch_right),
                        DesktopSourceNotchPosition.Left to
                            stringResource(Res.string.settings_playback_source_notch_left),
                        DesktopSourceNotchPosition.Hidden to
                            stringResource(Res.string.settings_playback_source_notch_hidden),
                    )
                    SettingsChoiceRow(
                        title = stringResource(Res.string.settings_playback_source_notch),
                        description = sourceNotchLabels.getValue(
                            autoPlayPlayerSettings.desktopSourceNotchPosition,
                        ),
                        options = DesktopSourceNotchPosition.entries.map { position ->
                            SettingsChoiceOption(position, sourceNotchLabels.getValue(position))
                        },
                        selectedValue = autoPlayPlayerSettings.desktopSourceNotchPosition,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.SourceNotch),
                        onSelected = PlayerSettingsRepository::setDesktopSourceNotchPosition,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    val notificationPositionLabels = mapOf(
                        DesktopPlayerNotificationPosition.Center to
                            stringResource(Res.string.settings_playback_notification_position_center),
                        DesktopPlayerNotificationPosition.TopCenter to
                            stringResource(Res.string.settings_playback_notification_position_top_center),
                    )
                    SettingsChoiceRow(
                        title = stringResource(Res.string.settings_playback_notification_position),
                        description = notificationPositionLabels.getValue(
                            autoPlayPlayerSettings.desktopPlayerNotificationPosition,
                        ),
                        options = DesktopPlayerNotificationPosition.entries.map { position ->
                            SettingsChoiceOption(position, notificationPositionLabels.getValue(position))
                        },
                        selectedValue = autoPlayPlayerSettings.desktopPlayerNotificationPosition,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.NotificationPosition),
                        onSelected = PlayerSettingsRepository::setDesktopPlayerNotificationPosition,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_legacy_hud),
                        description = stringResource(Res.string.settings_playback_legacy_hud_description),
                        checked = autoPlayPlayerSettings.desktopLegacyHudEnabled,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setDesktopLegacyHudEnabled,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_always_show_clock),
                        description = stringResource(Res.string.settings_playback_always_show_clock_description),
                        checked = autoPlayPlayerSettings.desktopAlwaysShowClockEnabled,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setDesktopAlwaysShowClockEnabled,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_info_panel),
                        description = stringResource(Res.string.settings_playback_info_panel_description),
                        checked = autoPlayPlayerSettings.desktopPlaybackInfoPanelEnabled,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setDesktopPlaybackInfoPanelEnabled,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsChoiceRow(
                        title = stringResource(Res.string.settings_playback_desktop_hdr_mode),
                        description = autoPlayPlayerSettings.desktopHdrMode.description,
                        options = DesktopHdrMode.entries.map { SettingsChoiceOption(it, it.label) },
                        selectedValue = autoPlayPlayerSettings.desktopHdrMode,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.HdrMode),
                        onSelected = PlayerSettingsRepository::setDesktopHdrMode,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsChoiceRow(
                        title = stringResource(Res.string.settings_playback_desktop_color_profile),
                        description = autoPlayPlayerSettings.desktopColorProfile.description,
                        options = DesktopColorProfile.entries.map { SettingsChoiceOption(it, it.label) },
                        selectedValue = autoPlayPlayerSettings.desktopColorProfile,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.ColorProfile),
                        onSelected = PlayerSettingsRepository::setDesktopColorProfile,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsChoiceRow(
                        title = stringResource(Res.string.settings_playback_desktop_renderer),
                        description = autoPlayPlayerSettings.desktopRendererApi.description,
                        options = DesktopRendererApi.entries.map { SettingsChoiceOption(it, it.label) },
                        selectedValue = autoPlayPlayerSettings.desktopRendererApi,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.DesktopRenderer),
                        onSelected = PlayerSettingsRepository::setDesktopRendererApi,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsChoiceRow(
                        title = stringResource(Res.string.settings_playback_desktop_low_vram),
                        description = autoPlayPlayerSettings.desktopLowVramMode.description,
                        options = DesktopLowVramMode.entries.map { SettingsChoiceOption(it, it.label) },
                        selectedValue = autoPlayPlayerSettings.desktopLowVramMode,
                        isTablet = isTablet,
                        onSelected = PlayerSettingsRepository::setDesktopLowVramMode,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsChoiceRow(
                        title = stringResource(Res.string.settings_playback_desktop_buffer_preset),
                        description = autoPlayPlayerSettings.desktopBufferPreset.description,
                        options = DesktopBufferPreset.entries.map { SettingsChoiceOption(it, it.label) },
                        selectedValue = autoPlayPlayerSettings.desktopBufferPreset,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.BufferPreset),
                        onSelected = PlayerSettingsRepository::setDesktopBufferPreset,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_desktop_audio_passthrough),
                        description = stringResource(Res.string.settings_playback_desktop_audio_passthrough_desc),
                        checked = autoPlayPlayerSettings.desktopAudioPassthroughEnabled,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setDesktopAudioPassthroughEnabled,
                    )
                }
            }
        }

        if (isDesktop) {
            SettingsSection(
                title = stringResource(Res.string.settings_playback_section_anime),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    var showCustomShaderPathsDialog by remember { mutableStateOf(false) }
                    val shaderPathsNotSet = stringResource(Res.string.settings_playback_not_set)
                    val customShaderOptions = DesktopCustomShaderCatalog.availableShaders(
                        autoPlayPlayerSettings.desktopCustomShaderPaths,
                    )
                    val animeEnhancementChoices = desktopAnimeEnhancementChoices(customShaderOptions)
                    val selectedAnimeEnhancementChoice = selectedDesktopAnimeEnhancementChoice(
                        mode = autoPlayPlayerSettings.desktopAnimeMode,
                        selectedCustomShaderPath = autoPlayPlayerSettings.desktopCustomShaderSelectedPath,
                        choices = animeEnhancementChoices,
                    )
                    SettingsChoiceRow(
                        title = stringResource(Res.string.settings_playback_desktop_anime_mode),
                        description = selectedAnimeEnhancementChoice.description,
                        options = animeEnhancementChoices.map { choice ->
                            SettingsChoiceOption(choice, choice.label)
                        },
                        selectedValue = selectedAnimeEnhancementChoice,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.AnimeEnhancements),
                        onSelected = { choice ->
                            when (choice) {
                                is DesktopAnimeEnhancementChoice.BuiltIn ->
                                    PlayerSettingsRepository.setDesktopAnimeMode(choice.mode)
                                is DesktopAnimeEnhancementChoice.Custom -> {
                                    PlayerSettingsRepository.setDesktopCustomShaderSelectedPath(choice.shader.path)
                                    PlayerSettingsRepository.setDesktopAnimeMode(DesktopAnimeMode.CustomShader)
                                }
                            }
                        },
                    )
                    if (autoPlayPlayerSettings.desktopAnimeMode != DesktopAnimeMode.Off) {
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsSwitchRow(
                            title = stringResource(Res.string.settings_playback_desktop_anime_auto),
                            description = stringResource(Res.string.settings_playback_desktop_anime_auto_desc),
                            checked = autoPlayPlayerSettings.desktopAnimeModeAutoEnabled,
                            isTablet = isTablet,
                            modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.AnimeAutoApply),
                            onCheckedChange = PlayerSettingsRepository::setDesktopAnimeModeAutoEnabled,
                        )
                        if (autoPlayPlayerSettings.desktopAnimeModeAutoEnabled) {
                            SettingsGroupDivider(isTablet = isTablet)
                            SettingsSwitchRow(
                                title = stringResource(Res.string.settings_playback_desktop_anime_include_western),
                                description = stringResource(Res.string.settings_playback_desktop_anime_include_western_desc),
                                checked = autoPlayPlayerSettings.desktopAnimeTreatAnimationAsAnime,
                                isTablet = isTablet,
                                modifier = Modifier.settingsScrollAnchor(
                                    SettingsScrollAnchor.AnimeIncludeWesternAnimation,
                                ),
                                onCheckedChange = PlayerSettingsRepository::setDesktopAnimeTreatAnimationAsAnime,
                            )
                        }
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsSwitchRow(
                            title = stringResource(Res.string.settings_playback_desktop_anime_skip_uhd),
                            description = stringResource(Res.string.settings_playback_desktop_anime_skip_uhd_desc),
                            checked = autoPlayPlayerSettings.desktopAnimeSkipUltraHdEnabled,
                            isTablet = isTablet,
                            modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.AnimeSkipUltraHd),
                            onCheckedChange = PlayerSettingsRepository::setDesktopAnimeSkipUltraHdEnabled,
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsSwitchRow(
                            title = stringResource(Res.string.settings_playback_desktop_anime_svp),
                            description = stringResource(Res.string.settings_playback_desktop_anime_svp_desc),
                            checked = autoPlayPlayerSettings.desktopAnimeSvpEnabled,
                            isTablet = isTablet,
                            modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.AnimeSvp),
                            onCheckedChange = PlayerSettingsRepository::setDesktopAnimeSvpEnabled,
                        )
                        if (autoPlayPlayerSettings.desktopAnimeSvpEnabled) {
                            SettingsGroupDivider(isTablet = isTablet)
                            SettingsSwitchRow(
                                title = stringResource(Res.string.settings_playback_desktop_anime_svp_overlay),
                                description = stringResource(Res.string.settings_playback_desktop_anime_svp_overlay_desc),
                                checked = autoPlayPlayerSettings.desktopAnimeSvpDebugOverlayEnabled,
                                isTablet = isTablet,
                                modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.AnimeSvpOverlay),
                                onCheckedChange = PlayerSettingsRepository::setDesktopAnimeSvpDebugOverlayEnabled,
                            )
                        }
                    }
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_playback_desktop_custom_shader_paths),
                        description = autoPlayPlayerSettings.desktopCustomShaderPaths
                            .lineSequence()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && !it.startsWith("#") }
                            .joinToString("  |  ")
                            .ifBlank { shaderPathsNotSet },
                        isTablet = isTablet,
                        onClick = { showCustomShaderPathsDialog = true },
                    )
                    if (showCustomShaderPathsDialog) {
                        CustomShaderPathsDialog(
                            initialValue = autoPlayPlayerSettings.desktopCustomShaderPaths,
                            onSave = {
                                PlayerSettingsRepository.setDesktopCustomShaderPaths(it)
                                showCustomShaderPathsDialog = false
                            },
                            onDismiss = { showCustomShaderPathsDialog = false },
                        )
                    }
                }
            }

            SettingsSection(
                title = stringResource(Res.string.settings_playback_section_advanced),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    var showCustomMpvOptionsDialog by remember { mutableStateOf(false) }
                    val mpvOptionsNotSet = stringResource(Res.string.settings_playback_not_set)
                    SettingsChoiceRow(
                        title = stringResource(Res.string.settings_playback_mpv_configuration_mode),
                        description = autoPlayPlayerSettings.desktopMpvConfigMode.description,
                        options = DesktopMpvConfigMode.entries.map { mode ->
                            SettingsChoiceOption(mode, mode.label)
                        },
                        selectedValue = autoPlayPlayerSettings.desktopMpvConfigMode,
                        isTablet = isTablet,
                        onSelected = PlayerSettingsRepository::setDesktopMpvConfigMode,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_playback_desktop_custom_mpv_options),
                        description = autoPlayPlayerSettings.desktopCustomMpvOptions
                            .lineSequence()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && !it.startsWith("#") }
                            .joinToString("  •  ")
                            .ifBlank { mpvOptionsNotSet },
                        isTablet = isTablet,
                        onClick = { showCustomMpvOptionsDialog = true },
                    )
                    if (showCustomMpvOptionsDialog) {
                        CustomMpvOptionsDialog(
                            initialValue = autoPlayPlayerSettings.desktopCustomMpvOptions,
                            onSave = {
                                PlayerSettingsRepository.setDesktopCustomMpvOptions(it)
                                showCustomMpvOptionsDialog = false
                            },
                            onDismiss = { showCustomMpvOptionsDialog = false },
                        )
                    }
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_desktop_verbose_mpv_logging),
                        description = stringResource(Res.string.settings_playback_desktop_verbose_mpv_logging_desc),
                        checked = autoPlayPlayerSettings.desktopVerboseMpvLoggingEnabled,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setDesktopVerboseMpvLoggingEnabled,
                    )
                }
            }
        }

        SettingsSection(
            title = stringResource(Res.string.settings_playback_section_subtitle_audio),
            isTablet = isTablet,
        ) {
            // Subtitle/Audio settings enable/disable logic:
            // Internal: everything enabled
            // External + forwarding enabled: subtitle language pickers enabled, other subtitle options disabled
            // External + forwarding disabled: entire subtitle section disabled
            // External: audio language pickers always disabled (external player manages audio tracks)
            val isExternalPlayer = autoPlayPlayerSettings.externalPlayerEnabled
            val isForwardingSubtitles = autoPlayPlayerSettings.externalPlayerForwardSubtitles
            val audioLanguageEnabled = !isExternalPlayer
            val subtitleLanguageEnabled = !isExternalPlayer || isForwardingSubtitles
            val otherSubtitleOptionsEnabled = !isExternalPlayer
            val preferredAudioOptions: List<SettingsChoiceOption<String>> = listOf(
                SettingsChoiceOption(AudioLanguageOption.DEFAULT, stringResource(Res.string.settings_playback_option_default)),
                SettingsChoiceOption(AudioLanguageOption.DEVICE, stringResource(Res.string.settings_playback_option_device_language)),
                SettingsChoiceOption(AudioLanguageOption.ORIGINAL, stringResource(Res.string.settings_playback_option_original)),
            ) + AvailableLanguageOptions.map { option ->
                SettingsChoiceOption(normalizeLanguageCode(option.code) ?: option.code, stringResource(option.labelRes))
            }
            val secondaryAudioOptions: List<SettingsChoiceOption<String?>> = listOf(
                SettingsChoiceOption<String?>(null, stringResource(Res.string.settings_playback_option_none)),
                SettingsChoiceOption<String?>(AudioLanguageOption.ORIGINAL, stringResource(Res.string.settings_playback_option_original)),
            ) + AvailableLanguageOptions.map { option ->
                SettingsChoiceOption<String?>(normalizeLanguageCode(option.code), stringResource(option.labelRes))
            }
            val preferredSubtitleOptions: List<SettingsChoiceOption<String>> = listOf(
                SettingsChoiceOption(SubtitleLanguageOption.NONE, stringResource(Res.string.settings_playback_option_none)),
                SettingsChoiceOption(SubtitleLanguageOption.DEVICE, stringResource(Res.string.settings_playback_option_device_language)),
                SettingsChoiceOption(SubtitleLanguageOption.FORCED, stringResource(Res.string.settings_playback_option_forced)),
                SettingsChoiceOption(SubtitleLanguageOption.ORIGINAL, stringResource(Res.string.settings_playback_option_original)),
            ) + AvailableLanguageOptions.map { option ->
                SettingsChoiceOption(normalizeLanguageCode(option.code) ?: option.code, stringResource(option.labelRes))
            }
            val secondarySubtitleOptions: List<SettingsChoiceOption<String?>> = listOf(
                SettingsChoiceOption<String?>(null, stringResource(Res.string.settings_playback_option_none)),
                SettingsChoiceOption<String?>(SubtitleLanguageOption.FORCED, stringResource(Res.string.settings_playback_option_forced)),
                SettingsChoiceOption<String?>(SubtitleLanguageOption.ORIGINAL, stringResource(Res.string.settings_playback_option_original)),
            ) + AvailableLanguageOptions.map { option ->
                SettingsChoiceOption<String?>(normalizeLanguageCode(option.code), stringResource(option.labelRes))
            }

            SettingsGroup(isTablet = isTablet) {
                SettingsDropdownChoiceRow(
                    title = stringResource(Res.string.settings_playback_preferred_audio_language),
                    description = when (preferredAudioLanguage) {
                        AudioLanguageOption.DEFAULT -> stringResource(Res.string.settings_playback_option_default)
                        AudioLanguageOption.DEVICE -> stringResource(Res.string.settings_playback_option_device_language)
                        else -> languageLabelForCode(preferredAudioLanguage)
                    },
                    options = preferredAudioOptions,
                    selectedValue = preferredAudioLanguage,
                    enabled = audioLanguageEnabled,
                    isTablet = isTablet,
                    onSelected = PlayerSettingsRepository::setPreferredAudioLanguage,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsDropdownChoiceRow(
                    title = stringResource(Res.string.settings_playback_secondary_audio_language),
                    description = languageLabelForCode(secondaryPreferredAudioLanguage),
                    options = secondaryAudioOptions,
                    selectedValue = secondaryPreferredAudioLanguage,
                    enabled = audioLanguageEnabled,
                    isTablet = isTablet,
                    onSelected = PlayerSettingsRepository::setSecondaryPreferredAudioLanguage,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsDropdownChoiceRow(
                    title = stringResource(Res.string.settings_playback_preferred_subtitle_language),
                    description = when (preferredSubtitleLanguage) {
                        SubtitleLanguageOption.NONE -> stringResource(Res.string.settings_playback_option_none)
                        SubtitleLanguageOption.DEVICE -> stringResource(Res.string.settings_playback_option_device_language)
                        SubtitleLanguageOption.FORCED -> stringResource(Res.string.settings_playback_option_forced)
                        else -> languageLabelForCode(preferredSubtitleLanguage)
                    },
                    options = preferredSubtitleOptions,
                    selectedValue = preferredSubtitleLanguage,
                    enabled = subtitleLanguageEnabled,
                    isTablet = isTablet,
                    onSelected = PlayerSettingsRepository::setPreferredSubtitleLanguage,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsDropdownChoiceRow(
                    title = stringResource(Res.string.settings_playback_secondary_subtitle_language),
                    description = languageLabelForCode(secondaryPreferredSubtitleLanguage),
                    options = secondarySubtitleOptions,
                    selectedValue = secondaryPreferredSubtitleLanguage,
                    enabled = subtitleLanguageEnabled,
                    isTablet = isTablet,
                    onSelected = PlayerSettingsRepository::setSecondaryPreferredSubtitleLanguage,
                )
                if (isDesktop) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_dual_subtitles),
                        description = stringResource(Res.string.settings_playback_dual_subtitles_description),
                        checked = autoPlayPlayerSettings.dualSubtitlesEnabled,
                        enabled = otherSubtitleOptionsEnabled,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("dual-subtitles")),
                        onCheckedChange = PlayerSettingsRepository::setDualSubtitlesEnabled,
                    )
                }
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_subtitle_use_forced),
                    description = stringResource(Res.string.settings_playback_subtitle_use_forced_description),
                    checked = autoPlayPlayerSettings.subtitleStyle.useForcedSubtitles,
                    enabled = otherSubtitleOptionsEnabled,
                    isTablet = isTablet,
                    onCheckedChange = { enabled ->
                        PlayerSettingsRepository.setSubtitleStyle(
                            autoPlayPlayerSettings.subtitleStyle.copy(useForcedSubtitles = enabled),
                        )
                    },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_prefer_sdh_subtitles),
                    description = stringResource(Res.string.settings_playback_prefer_sdh_subtitles_description),
                    checked = autoPlayPlayerSettings.preferHearingImpairedSubtitles,
                    enabled = otherSubtitleOptionsEnabled,
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("prefer-sdh-subtitles")),
                    onCheckedChange = PlayerSettingsRepository::setPreferHearingImpairedSubtitles,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_subtitle_show_preferred_only),
                    description = stringResource(Res.string.settings_playback_subtitle_show_preferred_only_description),
                    checked = autoPlayPlayerSettings.subtitleStyle.showOnlyPreferredLanguages,
                    enabled = otherSubtitleOptionsEnabled,
                    isTablet = isTablet,
                    onCheckedChange = { enabled ->
                        PlayerSettingsRepository.setSubtitleStyle(
                            autoPlayPlayerSettings.subtitleStyle.copy(showOnlyPreferredLanguages = enabled),
                        )
                    },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsDropdownChoiceRow(
                    title = stringResource(Res.string.settings_playback_addon_subtitle_startup_mode),
                    description = addonSubtitleStartupModeLabel(autoPlayPlayerSettings.addonSubtitleStartupMode),
                    options = AddonSubtitleStartupMode.entries.map { mode ->
                        SettingsChoiceOption(mode, addonSubtitleStartupModeLabel(mode))
                    },
                    selectedValue = autoPlayPlayerSettings.addonSubtitleStartupMode,
                    enabled = otherSubtitleOptionsEnabled,
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(
                        SettingsScrollAnchor.searchKey("addon-subtitle-startup"),
                    ),
                    onSelected = PlayerSettingsRepository::setAddonSubtitleStartupMode,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsMultiSelectRow(
                    title = stringResource(Res.string.settings_playback_reject_subtitle_keywords),
                    description = stringResource(Res.string.settings_playback_reject_subtitle_keywords_description),
                    options = SubtitleRejectKeyword.entries.map { keyword ->
                        SettingsChoiceOption(keyword, subtitleRejectKeywordLabel(keyword))
                    },
                    selectedValues = autoPlayPlayerSettings.rejectedSubtitleKeywords,
                    emptyLabel = stringResource(Res.string.settings_playback_reject_keywords_none),
                    enabled = otherSubtitleOptionsEnabled,
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(
                        SettingsScrollAnchor.searchKey("reject-subtitle-keywords"),
                    ),
                    summaryForCount = { count ->
                        stringResource(Res.string.settings_playback_selected_count, count)
                    },
                    onToggle = { keyword ->
                        val selected = autoPlayPlayerSettings.rejectedSubtitleKeywords
                        PlayerSettingsRepository.setRejectedSubtitleKeywords(
                            if (keyword in selected) selected - keyword else selected + keyword,
                        )
                    },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsMultiSelectRow(
                    title = stringResource(Res.string.settings_playback_reject_audio_keywords),
                    description = stringResource(Res.string.settings_playback_reject_audio_keywords_description),
                    options = AudioRejectKeyword.entries.map { keyword ->
                        SettingsChoiceOption(keyword, audioRejectKeywordLabel(keyword))
                    },
                    selectedValues = autoPlayPlayerSettings.rejectedAudioKeywords,
                    emptyLabel = stringResource(Res.string.settings_playback_reject_keywords_none),
                    enabled = audioLanguageEnabled,
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(
                        SettingsScrollAnchor.searchKey("reject-audio-keywords"),
                    ),
                    summaryForCount = { count ->
                        stringResource(Res.string.settings_playback_selected_count, count)
                    },
                    onToggle = { keyword ->
                        val selected = autoPlayPlayerSettings.rejectedAudioKeywords
                        PlayerSettingsRepository.setRejectedAudioKeywords(
                            if (keyword in selected) selected - keyword else selected + keyword,
                        )
                    },
                )
            }
        }

        SettingsSection(
            title = stringResource(Res.string.settings_playback_section_subtitle_rendering),
            isTablet = isTablet,
        ) {
            val subtitleRenderingEnabled = !autoPlayPlayerSettings.externalPlayerEnabled
            SettingsGroup(isTablet = isTablet) {
                val subtitleStyle = autoPlayPlayerSettings.subtitleStyle
                SettingsSliderRow(
                    title = stringResource(Res.string.settings_playback_subtitle_size),
                    value = subtitleStyle.fontSizeSp,
                    valueText = stringResource(Res.string.compose_player_font_size_value, subtitleStyle.fontSizeSp),
                    valueRange = 6..40,
                    step = 2,
                    isTablet = isTablet,
                    enabled = subtitleRenderingEnabled,
                    onValueChange = { value ->
                        PlayerSettingsRepository.setSubtitleStyle(subtitleStyle.copy(fontSizeSp = value))
                    },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSliderRow(
                    title = stringResource(Res.string.settings_playback_subtitle_vertical_offset),
                    value = subtitleStyle.bottomOffset,
                    valueText = subtitleStyle.bottomOffset.toString(),
                    valueRange = 0..200,
                    step = 5,
                    isTablet = isTablet,
                    enabled = subtitleRenderingEnabled,
                    onValueChange = { value ->
                        PlayerSettingsRepository.setSubtitleStyle(subtitleStyle.copy(bottomOffset = value))
                    },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_subtitle_bold),
                    description = stringResource(Res.string.settings_playback_subtitle_bold_description),
                    checked = subtitleStyle.bold,
                    enabled = subtitleRenderingEnabled,
                    isTablet = isTablet,
                    onCheckedChange = { enabled ->
                        PlayerSettingsRepository.setSubtitleStyle(subtitleStyle.copy(bold = enabled))
                    },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_subtitle_italic),
                    description = stringResource(Res.string.settings_playback_subtitle_italic_description),
                    checked = subtitleStyle.italic,
                    enabled = subtitleRenderingEnabled,
                    isTablet = isTablet,
                    onCheckedChange = { enabled ->
                        PlayerSettingsRepository.setSubtitleStyle(subtitleStyle.copy(italic = enabled))
                    },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_subtitle_outline),
                    description = stringResource(Res.string.settings_playback_subtitle_outline_description),
                    checked = subtitleStyle.outlineEnabled,
                    enabled = subtitleRenderingEnabled,
                    isTablet = isTablet,
                    onCheckedChange = { enabled ->
                        PlayerSettingsRepository.setSubtitleStyle(subtitleStyle.copy(outlineEnabled = enabled))
                    },
                )
                if (subtitleStyle.outlineEnabled) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SubtitleColorDropdownRow(
                        title = stringResource(Res.string.settings_playback_subtitle_outline_color),
                        options = subtitleTextColorOptions(),
                        selectedColor = subtitleStyle.outlineColor,
                        enabled = subtitleRenderingEnabled,
                        isTablet = isTablet,
                        onColorSelected = { color ->
                            PlayerSettingsRepository.setSubtitleStyle(subtitleStyle.copy(outlineColor = color))
                        },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSliderRow(
                        title = stringResource(Res.string.settings_playback_subtitle_outline_width),
                        value = subtitleStyle.outlineWidth,
                        valueText = subtitleStyle.outlineWidth.toString(),
                        valueRange = SUBTITLE_OUTLINE_WIDTH_MIN..SUBTITLE_OUTLINE_WIDTH_MAX,
                        step = 1,
                        isTablet = isTablet,
                        enabled = subtitleRenderingEnabled,
                        onValueChange = { value ->
                            PlayerSettingsRepository.setSubtitleStyle(subtitleStyle.copy(outlineWidth = value))
                        },
                    )
                }
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_subtitle_shadow),
                    description = stringResource(Res.string.settings_playback_subtitle_shadow_description),
                    checked = subtitleStyle.shadowEnabled,
                    enabled = subtitleRenderingEnabled,
                    isTablet = isTablet,
                    onCheckedChange = { enabled ->
                        PlayerSettingsRepository.setSubtitleStyle(subtitleStyle.copy(shadowEnabled = enabled))
                    },
                )
                if (subtitleStyle.shadowEnabled) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSliderRow(
                        title = stringResource(Res.string.settings_playback_subtitle_shadow_offset),
                        value = subtitleStyle.shadowOffset,
                        valueText = subtitleShadowOffsetLabel(subtitleStyle.shadowOffset),
                        valueRange = SUBTITLE_SHADOW_OFFSET_MIN..SUBTITLE_SHADOW_OFFSET_MAX,
                        step = SUBTITLE_SHADOW_OFFSET_STEP,
                        isTablet = isTablet,
                        enabled = subtitleRenderingEnabled,
                        onValueChange = { value ->
                            PlayerSettingsRepository.setSubtitleStyle(subtitleStyle.copy(shadowOffset = value))
                        },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SubtitleColorDropdownRow(
                        title = stringResource(Res.string.settings_playback_subtitle_shadow_color),
                        options = subtitleTextColorOptions(),
                        selectedColor = subtitleStyle.shadowColor.copy(alpha = 1f),
                        enabled = subtitleRenderingEnabled,
                        isTablet = isTablet,
                        onColorSelected = { color ->
                            PlayerSettingsRepository.setSubtitleStyle(
                                subtitleStyle.copy(shadowColor = color.copy(alpha = subtitleStyle.shadowColor.alpha)),
                            )
                        },
                    )
                }
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSliderRow(
                    title = stringResource(Res.string.settings_playback_subtitle_blur),
                    value = subtitleStyle.blur,
                    valueText = subtitleStyle.blur.toString(),
                    valueRange = SUBTITLE_BLUR_MIN..SUBTITLE_BLUR_MAX,
                    step = 1,
                    isTablet = isTablet,
                    enabled = subtitleRenderingEnabled,
                    onValueChange = { value ->
                        PlayerSettingsRepository.setSubtitleStyle(subtitleStyle.copy(blur = value))
                    },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SubtitleColorDropdownRow(
                    title = stringResource(Res.string.settings_playback_subtitle_text_color),
                    options = subtitleTextColorOptions(),
                    selectedColor = subtitleStyle.textColor,
                    enabled = subtitleRenderingEnabled,
                    isTablet = isTablet,
                    onColorSelected = { color ->
                        PlayerSettingsRepository.setSubtitleStyle(subtitleStyle.copy(textColor = color))
                    },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SubtitleColorDropdownRow(
                    title = stringResource(Res.string.settings_playback_subtitle_background_color),
                    options = subtitleBackgroundColorOptions(),
                    selectedColor = subtitleStyle.backgroundColor,
                    enabled = subtitleRenderingEnabled,
                    isTablet = isTablet,
                    onColorSelected = { color ->
                        PlayerSettingsRepository.setSubtitleStyle(subtitleStyle.copy(backgroundColor = color))
                    },
                )
                // How much of everything above reaches an ASS/SSA track. Those scripts carry their
                // own fonts, colours and placement, so by default none of it does — and this is
                // the only way to resize or move one. Desktop-only: the ASS override level is an
                // mpv/libass property, and Android renders ASS through ExoPlayer instead.
                if (isDesktop) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsDropdownChoiceRow(
                        title = stringResource(Res.string.settings_subtitle_ass_mode_title),
                        description = stringResource(Res.string.settings_subtitle_ass_mode_subtitle),
                        options = SubtitleAssStyleMode.entries.map { mode ->
                            SettingsChoiceOption(mode.name, subtitleAssStyleModeLabel(mode))
                        },
                        selectedValue = subtitleStyle.assStyleMode.name,
                        enabled = subtitleRenderingEnabled,
                        isTablet = isTablet,
                        onSelected = { value ->
                            val mode = runCatching { SubtitleAssStyleMode.valueOf(value) }.getOrNull()
                                ?: SubtitleAssStyleMode.Original
                            PlayerSettingsRepository.setSubtitleStyle(
                                subtitleStyle.copy(assStyleMode = mode),
                            )
                        },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSliderRow(
                        title = stringResource(Res.string.settings_subtitle_ass_scale_title),
                        description = stringResource(Res.string.settings_subtitle_ass_scale_subtitle),
                        value = subtitleStyle.assScalePercent,
                        valueText = "${subtitleStyle.assScalePercent}%",
                        valueRange = SUBTITLE_ASS_SCALE_MIN..SUBTITLE_ASS_SCALE_MAX,
                        step = SUBTITLE_ASS_SCALE_STEP,
                        isTablet = isTablet,
                        enabled = subtitleRenderingEnabled &&
                            subtitleStyle.assStyleMode != SubtitleAssStyleMode.Original,
                        onValueChange = { value ->
                            PlayerSettingsRepository.setSubtitleStyle(
                                subtitleStyle.copy(assScalePercent = value),
                            )
                        },
                    )
                }
                // Android-only: this picks between ExoPlayer's WASM/JNI ASS rendering backends.
                // Desktop always renders ASS/SSA natively via mpv/libass regardless of this
                // setting (see sub-ass-override handling in player_bridge.cpp) - showing it here
                // was misleading since toggling it has no effect on this platform.
                if (!isIos && !isDesktop) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_enable_libass),
                        description = stringResource(Res.string.settings_playback_enable_libass_description),
                        checked = useLibass,
                        enabled = subtitleRenderingEnabled,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setUseLibass,
                    )
                    if (useLibass) {
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsDropdownChoiceRow(
                            title = stringResource(Res.string.settings_playback_render_type),
                            description = libassRenderTypeLabel(libassRenderType),
                            options = listOf(
                                "OVERLAY_OPEN_GL",
                                "OVERLAY_CANVAS",
                                "EFFECTS_OPEN_GL",
                                "EFFECTS_CANVAS",
                                "CUES",
                            ).map { renderType ->
                                SettingsChoiceOption(renderType, libassRenderTypeLabel(renderType))
                            },
                            selectedValue = libassRenderType,
                            enabled = subtitleRenderingEnabled,
                            isTablet = isTablet,
                            onSelected = PlayerSettingsRepository::setLibassRenderType,
                        )
                    }
                }
            }
        }

        if (P2pSettingsRepository.isVisible) {
            SettingsSection(
                title = stringResource(Res.string.settings_p2p_title),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_p2p_title),
                        description = stringResource(Res.string.settings_p2p_subtitle),
                        checked = p2pSettings.p2pEnabled,
                        isTablet = isTablet,
                        onCheckedChange = { enabled ->
                            if (enabled && !p2pSettings.p2pEnabled) {
                                showP2pConsentDialog = true
                            } else {
                                P2pSettingsRepository.setP2pEnabled(enabled)
                            }
                        },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_p2p_hide_stats_title),
                        description = stringResource(Res.string.settings_p2p_hide_stats_subtitle),
                        checked = p2pSettings.hideTorrentStats,
                        isTablet = isTablet,
                        onCheckedChange = P2pSettingsRepository::setHideTorrentStats,
                    )
                }
            }
        }

        SettingsSection(
            title = stringResource(Res.string.settings_playback_section_stream_selection),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsDropdownChoiceRow(
                    title = stringResource(Res.string.settings_playback_prefetch_streams),
                    description = stringResource(Res.string.settings_playback_prefetch_streams_description),
                    options = listOf(
                        SettingsChoiceOption(
                            StreamPrefetchScope.OFF,
                            stringResource(Res.string.settings_playback_prefetch_scope_off),
                        ),
                        SettingsChoiceOption(
                            StreamPrefetchScope.DETAILS,
                            stringResource(Res.string.settings_playback_prefetch_scope_details),
                        ),
                        SettingsChoiceOption(
                            StreamPrefetchScope.DETAILS_AND_CONTINUE_WATCHING,
                            stringResource(Res.string.settings_playback_prefetch_scope_details_and_continue_watching),
                        ),
                    ),
                    selectedValue = autoPlayPlayerSettings.streamPrefetchScope,
                    isTablet = isTablet,
                    onSelected = PlayerSettingsRepository::setStreamPrefetchScope,
                )
                if (autoPlayPlayerSettings.streamPrefetchScope.isEnabled) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsDropdownChoiceRow(
                        title = stringResource(Res.string.settings_playback_prefetch_cache_duration),
                        description = stringResource(
                            Res.string.settings_playback_prefetch_cache_duration_minutes,
                            autoPlayPlayerSettings.streamPrefetchCacheMinutes,
                        ),
                        options = STREAM_PREFETCH_CACHE_MINUTE_VALUES.map { minutes ->
                            SettingsChoiceOption(
                                minutes,
                                stringResource(
                                    Res.string.settings_playback_prefetch_cache_duration_minutes,
                                    minutes,
                                ),
                            )
                        },
                        selectedValue = autoPlayPlayerSettings.streamPrefetchCacheMinutes,
                        isTablet = isTablet,
                        onSelected = PlayerSettingsRepository::setStreamPrefetchCacheMinutes,
                    )
                    // Two conditions, both load-bearing. A resolver must be configured, and the
                    // user's own "Prepare links" switch (Debrid -> Link Preparation) must be on —
                    // this row extends that behaviour to background sweeps rather than replacing it,
                    // and `DirectDebridStreamPreparer` refuses to do anything while its limit is 0.
                    // Showing the row without both would offer a switch that reads On and silently
                    // does nothing, which is exactly how this went unnoticed across three test runs.
                    if (
                        debridSettings.canResolvePlayableLinks &&
                        debridSettings.instantPlaybackPreparationLimit > 0
                    ) {
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsSwitchRow(
                            title = stringResource(Res.string.settings_playback_prefetch_resolve_links),
                            description = stringResource(
                                Res.string.settings_playback_prefetch_resolve_links_description,
                            ),
                            checked = autoPlayPlayerSettings.streamPrefetchResolveLinks,
                            isTablet = isTablet,
                            onCheckedChange = PlayerSettingsRepository::setStreamPrefetchResolveLinks,
                        )
                    }
                }
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_reuse_last_link),
                    description = stringResource(Res.string.settings_playback_reuse_last_link_description),
                    checked = streamReuseLastLinkEnabled,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setStreamReuseLastLinkEnabled,
                )
                if (streamReuseLastLinkEnabled) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsDropdownChoiceRow(
                        title = stringResource(Res.string.settings_playback_last_link_cache_duration),
                        description = formatReuseCacheDuration(streamReuseLastLinkCacheHours),
                        options = listOf(1, 6, 12, 24, 48, 72, 168).map { hours ->
                            SettingsChoiceOption(hours, formatReuseCacheDuration(hours))
                        },
                        selectedValue = streamReuseLastLinkCacheHours,
                        isTablet = isTablet,
                        onSelected = PlayerSettingsRepository::setStreamReuseLastLinkCacheHours,
                    )
                }
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_stream_failover),
                    description = stringResource(Res.string.settings_playback_stream_failover_description),
                    checked = autoPlayPlayerSettings.streamFailoverEnabled,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setStreamFailoverEnabled,
                )
                if (autoPlayPlayerSettings.streamFailoverEnabled) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsChoiceRow(
                        title = stringResource(Res.string.settings_playback_stream_failover_timeout),
                        description = stringResource(
                            Res.string.settings_playback_stream_failover_timeout_description,
                        ),
                        options = STREAM_FAILOVER_TIMEOUT_VALUES.map { seconds ->
                            SettingsChoiceOption(
                                seconds,
                                stringResource(
                                    Res.string.settings_playback_stream_failover_timeout_seconds,
                                    seconds,
                                ),
                            )
                        },
                        selectedValue = autoPlayPlayerSettings.streamFailoverTimeoutSeconds,
                        isTablet = isTablet,
                        onSelected = PlayerSettingsRepository::setStreamFailoverTimeoutSeconds,
                    )
                }
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_pause_overlay_source),
                    description = stringResource(
                        Res.string.settings_playback_pause_overlay_source_description,
                    ),
                    checked = autoPlayPlayerSettings.desktopPauseOverlaySourceEnabled,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setDesktopPauseOverlaySourceEnabled,
                )
            }
        }

        SettingsSection(
            title = stringResource(Res.string.settings_playback_section_stream_auto_play),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsChoiceRow(
                    title = stringResource(Res.string.settings_playback_stream_selection_mode),
                    description = stringResource(autoPlayPlayerSettings.streamAutoPlayMode.labelRes),
                    options = StreamAutoPlayMode.entries.map { mode ->
                        SettingsChoiceOption(mode, stringResource(mode.labelRes))
                    },
                    selectedValue = autoPlayPlayerSettings.streamAutoPlayMode,
                    isTablet = isTablet,
                    onSelected = PlayerSettingsRepository::setStreamAutoPlayMode,
                )
                if (autoPlayPlayerSettings.streamAutoPlayMode == StreamAutoPlayMode.REGEX_MATCH) {
                    SettingsGroupDivider(isTablet = isTablet)
                    val notSetLabel = stringResource(Res.string.settings_playback_not_set)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_playback_regex_pattern),
                        description = autoPlayPlayerSettings.streamAutoPlayRegex.ifBlank { notSetLabel },
                        isTablet = isTablet,
                        onClick = { showAutoPlayRegexDialog = true },
                    )
                }
                SettingsGroupDivider(isTablet = isTablet)
                val timeoutSec = autoPlayPlayerSettings.streamAutoPlayTimeoutSeconds
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = if (isTablet) 18.dp else 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                    ) {
                        Text(
                            text = stringResource(Res.string.settings_playback_stream_timeout),
                            style = if (isTablet) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        SettingsSubtext(
                            text = stringResource(Res.string.settings_playback_stream_timeout_description),
                            isTablet = isTablet,
                        )
                    }
                    val timeoutIndex = STREAM_AUTO_PLAY_TIMEOUT_VALUES.indexOf(timeoutSec)
                        .coerceAtLeast(0)
                    val maxIndex = (STREAM_AUTO_PLAY_TIMEOUT_VALUES.size - 1).toFloat()
                    var sliderValue by remember(timeoutIndex) { mutableFloatStateOf(timeoutIndex.toFloat()) }
                    var lastHapticStep by remember(timeoutIndex) { mutableStateOf(timeoutIndex.toFloat()) }
                    val displayedTimeout = STREAM_AUTO_PLAY_TIMEOUT_VALUES[
                        sliderValue.roundToInt().coerceIn(0, STREAM_AUTO_PLAY_TIMEOUT_VALUES.lastIndex)
                    ]
                    val displayedTimeoutLabel = when (displayedTimeout) {
                        0 -> stringResource(Res.string.settings_playback_timeout_instant)
                        Int.MAX_VALUE -> stringResource(Res.string.settings_playback_timeout_unlimited)
                        else -> stringResource(Res.string.settings_playback_timeout_seconds, displayedTimeout)
                    }
                    Column(
                        modifier = Modifier.width(if (isTablet) 210.dp else 220.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        ValueBox(text = displayedTimeoutLabel)
                        SettingsModernSlider(
                            value = sliderValue,
                            onValueChange = {
                                val snapped = snapToStep(it, 1f)
                                sliderValue = snapped

                                if (snapped != lastHapticStep) {
                                    lastHapticStep = snapped
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            },
                            onValueChangeFinished = {
                                val index = sliderValue.toInt().coerceIn(0, STREAM_AUTO_PLAY_TIMEOUT_VALUES.size - 1)
                                PlayerSettingsRepository.setStreamAutoPlayTimeoutSeconds(STREAM_AUTO_PLAY_TIMEOUT_VALUES[index])
                            },
                            valueRange = 0f..maxIndex,
                            steps = calculateSteps(0f, maxIndex, 1f),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                SettingsGroupDivider(isTablet = isTablet)
                SettingsChoiceRow(
                    title = stringResource(Res.string.settings_playback_source_scope),
                    description = stringResource(autoPlayPlayerSettings.streamAutoPlaySource.labelRes(pluginsEnabled)),
                    options = StreamAutoPlaySource.entries
                        .filter { pluginsEnabled || it != StreamAutoPlaySource.ENABLED_PLUGINS_ONLY }
                        .map { source ->
                            SettingsChoiceOption(source, stringResource(source.labelRes(pluginsEnabled)))
                        },
                    selectedValue = autoPlayPlayerSettings.streamAutoPlaySource,
                    isTablet = isTablet,
                    onSelected = PlayerSettingsRepository::setStreamAutoPlaySource,
                )
                if (autoPlayPlayerSettings.streamAutoPlaySource != StreamAutoPlaySource.ENABLED_PLUGINS_ONLY) {
                    SettingsGroupDivider(isTablet = isTablet)
                    val selectedAddons = autoPlayPlayerSettings.streamAutoPlaySelectedAddons
                        .intersect(autoPlayAddonNames.toSet())
                    SettingsMultiSelectRow(
                        title = stringResource(Res.string.settings_playback_allowed_addons),
                        description = stringResource(Res.string.settings_playback_all_addons),
                        options = autoPlayAddonNames.map { name -> SettingsChoiceOption(name, name) },
                        selectedValues = selectedAddons,
                        emptyLabel = stringResource(Res.string.settings_playback_all_addons),
                        isTablet = isTablet,
                        summaryForCount = { count ->
                            stringResource(Res.string.settings_playback_selected_count, count)
                        },
                        onToggle = { name ->
                            PlayerSettingsRepository.setStreamAutoPlaySelectedAddons(
                                if (name in selectedAddons) selectedAddons - name else selectedAddons + name,
                            )
                        },
                    )
                }
                if (pluginsEnabled && autoPlayPlayerSettings.streamAutoPlaySource != StreamAutoPlaySource.INSTALLED_ADDONS_ONLY) {
                    SettingsGroupDivider(isTablet = isTablet)
                    val selectedPlugins = autoPlayPlayerSettings.streamAutoPlaySelectedPlugins
                        .intersect(autoPlayPluginNames.toSet())
                    SettingsMultiSelectRow(
                        title = stringResource(Res.string.settings_playback_allowed_plugins),
                        description = stringResource(Res.string.settings_playback_all_plugins),
                        options = autoPlayPluginNames.map { name -> SettingsChoiceOption(name, name) },
                        selectedValues = selectedPlugins,
                        emptyLabel = stringResource(Res.string.settings_playback_all_plugins),
                        isTablet = isTablet,
                        summaryForCount = { count ->
                            stringResource(Res.string.settings_playback_selected_count, count)
                        },
                        onToggle = { name ->
                            PlayerSettingsRepository.setStreamAutoPlaySelectedPlugins(
                                if (name in selectedPlugins) selectedPlugins - name else selectedPlugins + name,
                            )
                        },
                    )
                }
            }
        }

        if (isIos) {
            SettingsSection(
                title = stringResource(Res.string.settings_playback_ios_audio_output_section),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsChoiceRow(
                        title = stringResource(Res.string.settings_playback_ios_audio_output),
                        description = autoPlayPlayerSettings.iosAudioOutputMode.label,
                        options = IosAudioOutputMode.entries.map { SettingsChoiceOption(it, it.label) },
                        selectedValue = autoPlayPlayerSettings.iosAudioOutputMode,
                        isTablet = isTablet,
                        onSelected = PlayerSettingsRepository::setIosAudioOutputMode,
                    )
                }
            }

            SettingsSection(
                title = stringResource(Res.string.settings_playback_ios_video_output),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsChoiceRow(
                        title = stringResource(Res.string.settings_playback_ios_hardware_decoder),
                        description = autoPlayPlayerSettings.iosHardwareDecoderMode.localizedLabel(),
                        options = IosHardwareDecoderMode.entries.map { SettingsChoiceOption(it, it.localizedLabel()) },
                        selectedValue = autoPlayPlayerSettings.iosHardwareDecoderMode,
                        isTablet = isTablet,
                        onSelected = PlayerSettingsRepository::setIosHardwareDecoderMode,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_ios_extended_dynamic_range),
                        description = stringResource(Res.string.settings_playback_ios_extended_dynamic_range_desc),
                        checked = autoPlayPlayerSettings.iosExtendedDynamicRangeEnabled,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setIosExtendedDynamicRangeEnabled,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_ios_display_color_hint),
                        description = stringResource(Res.string.settings_playback_ios_display_color_hint_desc),
                        checked = autoPlayPlayerSettings.iosTargetColorspaceHintEnabled,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setIosTargetColorspaceHintEnabled,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsChoiceRow(
                        title = stringResource(Res.string.settings_playback_ios_target_primaries),
                        description = autoPlayPlayerSettings.iosTargetPrimaries.label,
                        options = IosTargetPrimaries.entries.map { SettingsChoiceOption(it, it.label) },
                        selectedValue = autoPlayPlayerSettings.iosTargetPrimaries,
                        isTablet = isTablet,
                        onSelected = PlayerSettingsRepository::setIosTargetPrimaries,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsChoiceRow(
                        title = stringResource(Res.string.settings_playback_ios_target_transfer),
                        description = autoPlayPlayerSettings.iosTargetTransfer.label,
                        options = IosTargetTransfer.entries.map { SettingsChoiceOption(it, it.label) },
                        selectedValue = autoPlayPlayerSettings.iosTargetTransfer,
                        isTablet = isTablet,
                        onSelected = PlayerSettingsRepository::setIosTargetTransfer,
                    )
                }
            }
        }

        SettingsSection(
            title = stringResource(Res.string.settings_playback_section_skip_segments),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_skip_intro_outro_recap),
                    description = stringResource(Res.string.settings_playback_skip_intro_outro_recap_description),
                    checked = autoPlayPlayerSettings.skipIntroEnabled,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setSkipIntroEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsDropdownChoiceRow(
                    title = stringResource(Res.string.settings_playback_skip_auto_accept),
                    description = stringResource(
                        autoPlayPlayerSettings.skipAutoAcceptMode.descriptionRes,
                    ),
                    options = SkipAutoAcceptMode.entries.map { mode ->
                        SettingsChoiceOption(mode, stringResource(mode.labelRes))
                    },
                    selectedValue = autoPlayPlayerSettings.skipAutoAcceptMode,
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(
                        SettingsScrollAnchor.searchKey("skip-auto-accept"),
                    ),
                    onSelected = PlayerSettingsRepository::setSkipAutoAcceptMode,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_anime_skip),
                    description = stringResource(Res.string.settings_playback_anime_skip_description),
                    checked = autoPlayPlayerSettings.animeSkipEnabled,
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(
                        SettingsScrollAnchor.searchKey("anime-skip"),
                    ),
                    onCheckedChange = PlayerSettingsRepository::setAnimeSkipEnabled,
                )
                if (autoPlayPlayerSettings.animeSkipEnabled) {
                    SettingsGroupDivider(isTablet = isTablet)
                    var showAnimeSkipClientIdDialog by remember { mutableStateOf(false) }
                    val notSetLabel = stringResource(Res.string.settings_playback_not_set)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_playback_anime_skip_client_id),
                        description = autoPlayPlayerSettings.animeSkipClientId.ifBlank { notSetLabel },
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(
                            SettingsScrollAnchor.searchKey("anime-skip-client"),
                        ),
                        onClick = { showAnimeSkipClientIdDialog = true },
                    )
                    if (showAnimeSkipClientIdDialog) {
                        AnimeSkipClientIdDialog(
                            initialValue = autoPlayPlayerSettings.animeSkipClientId,
                            onSave = {
                                PlayerSettingsRepository.setAnimeSkipClientId(it)
                                showAnimeSkipClientIdDialog = false
                            },
                            onDismiss = { showAnimeSkipClientIdDialog = false },
                        )
                    }
                }
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_intro_submit_enabled),
                    description = stringResource(Res.string.settings_playback_intro_submit_enabled_description),
                    checked = autoPlayPlayerSettings.introSubmitEnabled,
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(
                        SettingsScrollAnchor.searchKey("intro-submit"),
                    ),
                    onCheckedChange = PlayerSettingsRepository::setIntroSubmitEnabled,
                )
                if (autoPlayPlayerSettings.introSubmitEnabled) {
                    SettingsGroupDivider(isTablet = isTablet)
                    var showSkipDbApiKeyDialog by remember { mutableStateOf(false) }
                    val notSetLabel = stringResource(Res.string.settings_playback_not_set)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_playback_skipdb_api_key),
                        description = autoPlayPlayerSettings.skipDbApiKey.ifBlank { notSetLabel },
                        isTablet = isTablet,
                        onClick = { showSkipDbApiKeyDialog = true },
                    )
                    if (showSkipDbApiKeyDialog) {
                        SkipDbApiKeyDialog(
                            initialValue = autoPlayPlayerSettings.skipDbApiKey,
                            onSave = {
                                PlayerSettingsRepository.setSkipDbApiKey(it)
                                showSkipDbApiKeyDialog = false
                            },
                            onDismiss = { showSkipDbApiKeyDialog = false },
                        )
                    }

                    SettingsGroupDivider(isTablet = isTablet)
                    var showIntroDbApiKeyDialog by remember { mutableStateOf(false) }
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_playback_introdb_api_key),
                        description = autoPlayPlayerSettings.introDbApiKey.ifBlank { notSetLabel },
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(
                            SettingsScrollAnchor.searchKey("introdb-key"),
                        ),
                        onClick = { showIntroDbApiKeyDialog = true },
                    )
                    if (showIntroDbApiKeyDialog) {
                        IntroDbApiKeyDialog(
                            initialValue = autoPlayPlayerSettings.introDbApiKey,
                            onSave = {
                                PlayerSettingsRepository.setIntroDbApiKey(it)
                                showIntroDbApiKeyDialog = false
                            },
                            onDismiss = { showIntroDbApiKeyDialog = false },
                        )
                    }
                }
            }
        }

        SettingsSection(
            title = stringResource(Res.string.settings_playback_section_next_episode),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_auto_play_next_episode),
                    description = stringResource(Res.string.settings_playback_auto_play_next_episode_description),
                    checked = autoPlayPlayerSettings.streamAutoPlayNextEpisodeEnabled,
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.BingeMode),
                    onCheckedChange = PlayerSettingsRepository::setStreamAutoPlayNextEpisodeEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_prefer_binge_group),
                    description = stringResource(Res.string.settings_playback_prefer_binge_group_description),
                    checked = autoPlayPlayerSettings.streamAutoPlayPreferBingeGroup,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setStreamAutoPlayPreferBingeGroup,
                )
                if (autoPlayPlayerSettings.streamAutoPlayPreferBingeGroup) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_reuse_binge_group),
                        description = stringResource(Res.string.settings_playback_reuse_binge_group_description),
                        checked = autoPlayPlayerSettings.streamAutoPlayReuseBingeGroup,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setStreamAutoPlayReuseBingeGroup,
                    )
                }
                SettingsGroupDivider(isTablet = isTablet)
                SettingsDropdownChoiceRow(
                    title = stringResource(Res.string.settings_playback_threshold_mode),
                    description = stringResource(autoPlayPlayerSettings.nextEpisodeThresholdMode.labelRes),
                    options = com.nuvio.app.features.player.skip.NextEpisodeThresholdMode.entries.map { mode ->
                        SettingsChoiceOption(mode, stringResource(mode.labelRes))
                    },
                    selectedValue = autoPlayPlayerSettings.nextEpisodeThresholdMode,
                    isTablet = isTablet,
                    onSelected = PlayerSettingsRepository::setNextEpisodeThresholdMode,
                )
                SettingsGroupDivider(isTablet = isTablet)
                when (autoPlayPlayerSettings.nextEpisodeThresholdMode) {
                    com.nuvio.app.features.player.skip.NextEpisodeThresholdMode.PERCENTAGE -> {
                        val thresholdPercent = autoPlayPlayerSettings.nextEpisodeThresholdPercent
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = if (isTablet) 18.dp else 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f).padding(end = 12.dp),
                            ) {
                                Text(
                                    text = stringResource(Res.string.settings_playback_threshold_percentage),
                                    style = if (isTablet) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                SettingsSubtext(
                                    text = stringResource(Res.string.settings_playback_threshold_percentage_description),
                                    isTablet = isTablet,
                                )
                            }
                            var sliderValue by remember(thresholdPercent) { mutableFloatStateOf(thresholdPercent) }
                            var lastHapticPercent by remember(thresholdPercent) { mutableStateOf(thresholdPercent) }
                            Column(
                                modifier = Modifier.width(if (isTablet) 210.dp else 220.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                ValueBox(
                                    text = stringResource(
                                        Res.string.settings_playback_threshold_percentage_value,
                                        formatStep(sliderValue),
                                    ),
                                )
                                SettingsModernSlider(
                                    value = sliderValue,
                                    onValueChange = {
                                        val snapped = snapToStep(it, 0.5f)
                                        sliderValue = snapped

                                        if (snapped != lastHapticPercent) {
                                            lastHapticPercent = snapped
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    },
                                    onValueChangeFinished = {
                                        PlayerSettingsRepository.setNextEpisodeThresholdPercent(sliderValue)
                                    },
                                    valueRange = 97f..100f,
                                    steps = calculateSteps(97f, 100f, 0.5f),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                    com.nuvio.app.features.player.skip.NextEpisodeThresholdMode.MINUTES_BEFORE_END -> {
                        val thresholdMinutes = autoPlayPlayerSettings.nextEpisodeThresholdMinutesBeforeEnd
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = if (isTablet) 18.dp else 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f).padding(end = 12.dp),
                            ) {
                                Text(
                                    text = stringResource(Res.string.settings_playback_minutes_before_end),
                                    style = if (isTablet) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                SettingsSubtext(
                                    text = stringResource(Res.string.settings_playback_minutes_before_end_description),
                                    isTablet = isTablet,
                                )
                            }
                            var sliderValue by remember(thresholdMinutes) { mutableFloatStateOf(thresholdMinutes) }
                            var lastHapticMin by remember(thresholdMinutes) { mutableStateOf(thresholdMinutes) }
                            Column(
                                modifier = Modifier.width(if (isTablet) 210.dp else 220.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                ValueBox(
                                    text = stringResource(
                                        Res.string.settings_playback_minutes_value,
                                        formatStep(sliderValue),
                                    ),
                                )
                                SettingsModernSlider(
                                    value = sliderValue,
                                    onValueChange = {
                                        val snapped = snapToStep(it, 0.5f)
                                        sliderValue = snapped

                                        if (snapped != lastHapticMin) {
                                            lastHapticMin = snapped
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    },
                                    onValueChangeFinished = {
                                        PlayerSettingsRepository.setNextEpisodeThresholdMinutesBeforeEnd(sliderValue)
                                    },
                                    valueRange = 0f..3.5f,
                                    steps = calculateSteps(0f, 3.5f, 0.5f),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showExternalPlayerAppDialog) {
        ExternalPlayerSelectionDialog(
            players = availableExternalPlayers,
            selectedPlayerId = autoPlayPlayerSettings.externalPlayerId,
            onPlayerSelected = { player ->
                if (player.isAvailable || ExternalPlayerPlatform.configurePlayer(player.id)) {
                    PlayerSettingsRepository.setExternalPlayerId(player.id)
                    showExternalPlayerAppDialog = false
                }
            },
            onPlayerConfigured = { player ->
                if (ExternalPlayerPlatform.configurePlayer(player.id)) {
                    PlayerSettingsRepository.setExternalPlayerId(player.id)
                    showExternalPlayerAppDialog = false
                }
            },
            onDismiss = { showExternalPlayerAppDialog = false },
        )
    }

    if (showP2pConsentDialog) {
        P2pConsentDialog(
            onEnableP2p = {
                P2pSettingsRepository.setP2pEnabled(true)
                showP2pConsentDialog = false
            },
            onDismiss = { showP2pConsentDialog = false },
        )
    }

    if (showAutoPlayRegexDialog) {
        StreamAutoPlayRegexDialog(
            initialRegex = autoPlayPlayerSettings.streamAutoPlayRegex,
            onSave = {
                PlayerSettingsRepository.setStreamAutoPlayRegex(it)
                showAutoPlayRegexDialog = false
            },
            onDismiss = { showAutoPlayRegexDialog = false },
        )
    }
}

@Composable
private fun formatReuseCacheDuration(hours: Int): String = when {
    hours < 24 && hours == 1 -> stringResource(Res.string.settings_playback_duration_hour_one, hours)
    hours < 24 -> stringResource(Res.string.settings_playback_duration_hours, hours)
    hours % 24 == 0 -> {
        val days = hours / 24
        if (days == 1) stringResource(Res.string.settings_playback_duration_day_one, days)
        else stringResource(Res.string.settings_playback_duration_days, days)
    }
    else -> stringResource(Res.string.settings_playback_duration_hours, hours)
}

private data class LanguageSelectionOption(
    val value: String?,
    val label: String,
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ExternalPlayerSelectionDialog(
    players: List<ExternalPlayerApp>,
    selectedPlayerId: String?,
    onPlayerSelected: (ExternalPlayerApp) -> Unit,
    onPlayerConfigured: (ExternalPlayerApp) -> Unit,
    onDismiss: () -> Unit,
) {
    BasicAlertDialog(
        onDismissRequest = onDismiss,
    ) {
        NuvioDialogSurface(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_playback_external_player_app),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )

                if (players.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.settings_playback_external_player_none_available),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        players.forEach { player ->
                            val isSelected = player.id == selectedPlayerId
                            val containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            }

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPlayerSelected(player) },
                                shape = RoundedCornerShape(12.dp),
                                color = containerColor,
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(
                                            text = player.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        if (player.executablePath != null) {
                                            Text(
                                                text = player.executablePath,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontFamily = FontFamily.Monospace,
                                                ),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        } else if (!player.isAvailable) {
                                            Text(
                                                text = stringResource(Res.string.settings_playback_external_player_locate),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (player.isAvailable && player.canConfigure) {
                                            TextButton(
                                                onClick = { onPlayerConfigured(player) },
                                            ) {
                                                Text(stringResource(Res.string.settings_playback_external_player_change))
                                            }
                                        }
                                    }
                                    Box(
                                        modifier = Modifier.size(24.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(Res.string.settings_playback_dialog_close),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun LanguageSelectionDialog(
    title: String,
    options: List<LanguageSelectionOption>,
    selectedValue: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    BasicAlertDialog(
        onDismissRequest = onDismiss,
    ) {
        NuvioDialogSurface(modifier = Modifier
                .fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(options) { option ->
                        // Preferences are stored normalized ("pt-BR" persists as "pt-br"), so a raw
                        // string comparison leaves the regional options looking unselected.
                        val isSelected = option.value == selectedValue ||
                            (
                                option.value != null && selectedValue != null &&
                                    normalizeLanguageCode(option.value) == normalizeLanguageCode(selectedValue)
                                )
                        val containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(option.value) },
                            shape = RoundedCornerShape(12.dp),
                            color = containerColor,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = option.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                Box(
                                    modifier = Modifier.size(24.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(Res.string.settings_playback_dialog_close),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ReuseCacheDurationDialog(
    selectedHours: Int,
    onDurationSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(1, 6, 12, 24, 48, 72, 168)

    BasicAlertDialog(
        onDismissRequest = onDismiss,
    ) {
        NuvioDialogSurface(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_playback_last_link_cache_duration),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.forEach { hours ->
                        val isSelected = hours == selectedHours
                        val containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onDurationSelected(hours) },
                            shape = RoundedCornerShape(12.dp),
                            color = containerColor,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = formatReuseCacheDuration(hours),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                Box(
                                    modifier = Modifier.size(24.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(Res.string.settings_playback_dialog_close),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun DecoderPriorityDialog(
    selectedPriority: Int,
    onPrioritySelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(
        0 to Res.string.settings_playback_decoder_device_only,
        1 to Res.string.settings_playback_decoder_prefer_device,
        2 to Res.string.settings_playback_decoder_prefer_app,
    )

    BasicAlertDialog(
        onDismissRequest = onDismiss,
    ) {
        NuvioDialogSurface(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_playback_decoder_priority),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.forEach { (priority, labelRes) ->
                        val isSelected = priority == selectedPriority
                        val containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPrioritySelected(priority) },
                            shape = RoundedCornerShape(12.dp),
                            color = containerColor,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(labelRes),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                Box(
                                    modifier = Modifier.size(24.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(Res.string.settings_playback_dialog_close),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun <T> IosEnumSelectionDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    description: @Composable (T) -> String? = { null },
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    BasicAlertDialog(
        onDismissRequest = onDismiss,
    ) {
        NuvioDialogSurface(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.forEach { option ->
                        val isSelected = option == selected
                        val optionDescription = description(option)
                        val containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(option) },
                            shape = RoundedCornerShape(12.dp),
                            color = containerColor,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(
                                        text = label(option),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    if (optionDescription != null) {
                                        Text(
                                            text = optionDescription,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Box(
                                    modifier = Modifier.size(24.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(Res.string.settings_playback_dialog_close),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun LibassRenderTypeDialog(
    selectedRenderType: String,
    onRenderTypeSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(
        "OVERLAY_OPEN_GL" to Res.string.settings_playback_render_type_overlay_opengl,
        "OVERLAY_CANVAS" to Res.string.settings_playback_render_type_overlay_canvas,
        "EFFECTS_OPEN_GL" to Res.string.settings_playback_render_type_effects_opengl,
        "EFFECTS_CANVAS" to Res.string.settings_playback_render_type_effects_canvas,
        "CUES" to Res.string.settings_playback_render_type_cues,
    )

    BasicAlertDialog(
        onDismissRequest = onDismiss,
    ) {
        NuvioDialogSurface(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_playback_render_type),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.forEach { (value, labelRes) ->
                        val isSelected = value == selectedRenderType
                        val containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onRenderTypeSelected(value) },
                            shape = RoundedCornerShape(12.dp),
                            color = containerColor,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(labelRes),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                Box(
                                    modifier = Modifier.size(24.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(Res.string.settings_playback_dialog_close),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AddonSubtitleStartupModeDialog(
    selectedMode: AddonSubtitleStartupMode,
    onModeSelected: (AddonSubtitleStartupMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(
        Triple(
            AddonSubtitleStartupMode.FAST_STARTUP,
            Res.string.settings_playback_addon_subtitle_startup_fast,
            Res.string.settings_playback_addon_subtitle_startup_fast_description,
        ),
        Triple(
            AddonSubtitleStartupMode.PREFERRED_ONLY,
            Res.string.settings_playback_addon_subtitle_startup_preferred,
            Res.string.settings_playback_addon_subtitle_startup_preferred_description,
        ),
        Triple(
            AddonSubtitleStartupMode.ALL_SUBTITLES,
            Res.string.settings_playback_addon_subtitle_startup_all,
            Res.string.settings_playback_addon_subtitle_startup_all_description,
        ),
    )

    BasicAlertDialog(
        onDismissRequest = onDismiss,
    ) {
        NuvioDialogSurface(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_playback_addon_subtitle_startup_mode),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.forEach { (mode, titleRes, descriptionRes) ->
                        val isSelected = mode == selectedMode
                        val containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onModeSelected(mode) },
                            shape = RoundedCornerShape(12.dp),
                            color = containerColor,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(titleRes),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = stringResource(descriptionRes),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Box(
                                    modifier = Modifier.size(24.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun StreamAutoPlayModeDialog(
    selectedMode: StreamAutoPlayMode,
    onModeSelected: (StreamAutoPlayMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(
        Triple(
            StreamAutoPlayMode.MANUAL,
            Res.string.settings_playback_stream_selection_mode_manual,
            Res.string.settings_playback_stream_selection_mode_manual_description,
        ),
        Triple(
            StreamAutoPlayMode.FIRST_STREAM,
            Res.string.settings_playback_stream_selection_mode_first_stream,
            Res.string.settings_playback_stream_selection_mode_first_stream_description,
        ),
        Triple(
            StreamAutoPlayMode.SCORED,
            Res.string.settings_playback_stream_selection_mode_scored,
            Res.string.settings_playback_stream_selection_mode_scored_description,
        ),
        Triple(
            StreamAutoPlayMode.REGEX_MATCH,
            Res.string.settings_playback_stream_selection_mode_regex,
            Res.string.settings_playback_stream_selection_mode_regex_description,
        ),
    )

    BasicAlertDialog(
        onDismissRequest = onDismiss,
    ) {
        NuvioDialogSurface(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_playback_stream_selection_mode),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.forEach { (mode, titleRes, descriptionRes) ->
                        val isSelected = mode == selectedMode
                        val containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onModeSelected(mode) },
                            shape = RoundedCornerShape(12.dp),
                            color = containerColor,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(titleRes),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = stringResource(descriptionRes),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Box(
                                    modifier = Modifier.size(24.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(Res.string.settings_playback_dialog_close),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun StreamAutoPlaySourceDialog(
    pluginsEnabled: Boolean,
    selectedSource: StreamAutoPlaySource,
    onSourceSelected: (StreamAutoPlaySource) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = buildList {
        add(
            Triple(
                StreamAutoPlaySource.ALL_SOURCES,
                if (pluginsEnabled) {
                    Res.string.settings_playback_source_scope_all_sources
                } else {
                    Res.string.settings_playback_source_scope_all_addons
                },
                if (pluginsEnabled) {
                    Res.string.settings_playback_source_scope_all_sources_description
                } else {
                    Res.string.settings_playback_source_scope_all_addons_description
                },
            ),
        )
        add(
            Triple(
                StreamAutoPlaySource.INSTALLED_ADDONS_ONLY,
                Res.string.settings_playback_source_scope_installed_addons_only,
                Res.string.settings_playback_source_scope_installed_addons_only_description,
            ),
        )
        if (pluginsEnabled) {
            add(
                Triple(
                    StreamAutoPlaySource.ENABLED_PLUGINS_ONLY,
                    Res.string.settings_playback_source_scope_enabled_plugins_only,
                    Res.string.settings_playback_source_scope_enabled_plugins_only_description,
                ),
            )
        }
    }

    BasicAlertDialog(
        onDismissRequest = onDismiss,
    ) {
        NuvioDialogSurface(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_playback_source_scope),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.forEach { (source, titleRes, descriptionRes) ->
                        val isSelected = source == selectedSource
                        val containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSourceSelected(source) },
                            shape = RoundedCornerShape(12.dp),
                            color = containerColor,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(titleRes),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = stringResource(descriptionRes),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Box(
                                    modifier = Modifier.size(24.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(Res.string.settings_playback_dialog_close),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class TrackRejectKeywordOption<T>(val value: T, val label: String)

/**
 * Multi-select over a fixed set of track kinds. Ticking nothing means "reject nothing", which is
 * why there is no "All" row — the empty selection here is the permissive default, the opposite of
 * [StreamAutoPlayProviderSelectionDialog] where empty means "everything is allowed through".
 *
 * Saves on every tick rather than on dismiss so the summary line behind the dialog stays truthful;
 * the settings repository already ignores a set that has not changed.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun <T> TrackRejectKeywordDialog(
    title: String,
    description: String,
    options: List<TrackRejectKeywordOption<T>>,
    selected: Set<T>,
    onSelectionSaved: (Set<T>) -> Unit,
    onDismiss: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        NuvioDialogSurface(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                options.forEach { option ->
                    val isSelected = option.value in selected
                    val containerColor = if (isSelected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectionSaved(
                                    if (isSelected) selected - option.value else selected + option.value,
                                )
                            },
                        shape = RoundedCornerShape(12.dp),
                        color = containerColor,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(Res.string.settings_playback_dialog_close),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun rejectKeywordSummary(labels: List<String>): String = if (labels.isEmpty()) {
    stringResource(Res.string.settings_playback_reject_keywords_none)
} else {
    labels.joinToString(" • ")
}

@Composable
private fun subtitleRejectKeywordLabel(keyword: SubtitleRejectKeyword): String = when (keyword) {
    SubtitleRejectKeyword.SIGNS -> stringResource(Res.string.settings_playback_reject_keyword_signs)
    SubtitleRejectKeyword.SONGS -> stringResource(Res.string.settings_playback_reject_keyword_songs)
    SubtitleRejectKeyword.KARAOKE -> stringResource(Res.string.settings_playback_reject_keyword_karaoke)
    SubtitleRejectKeyword.FORCED -> stringResource(Res.string.settings_playback_reject_keyword_forced)
}

@Composable
private fun audioRejectKeywordLabel(keyword: AudioRejectKeyword): String = when (keyword) {
    AudioRejectKeyword.COMMENTARY -> stringResource(Res.string.settings_playback_reject_keyword_commentary)
    AudioRejectKeyword.DESCRIPTIVE_AUDIO ->
        stringResource(Res.string.settings_playback_reject_keyword_descriptive_audio)
    AudioRejectKeyword.VISUALLY_IMPAIRED ->
        stringResource(Res.string.settings_playback_reject_keyword_visually_impaired)
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun StreamAutoPlayProviderSelectionDialog(
    title: String,
    allLabel: String,
    items: List<String>,
    selectedItems: Set<String>,
    onSelectionSaved: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(selectedItems, items) {
        mutableStateOf(selectedItems.intersect(items.toSet()))
    }

    BasicAlertDialog(
        onDismissRequest = {
            onSelectionSaved(selected)
            onDismiss()
        },
    ) {
        NuvioDialogSurface(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )

                val allContainerColor = if (selected.isEmpty()) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selected = emptySet() },
                    shape = RoundedCornerShape(12.dp),
                    color = allContainerColor,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = allLabel,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected.isEmpty()) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                if (items.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.settings_playback_no_items_available),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 340.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            count = items.size,
                            key = { items[it] },
                        ) { index ->
                            val item = items[index]
                            val isSelected = item in selected
                            val containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            }

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selected = if (isSelected) selected - item else selected + item
                                    },
                                shape = RoundedCornerShape(12.dp),
                                color = containerColor,
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = item,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(Res.string.settings_playback_dialog_save_close),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun StreamAutoPlayRegexDialog(
    initialRegex: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var regex by remember(initialRegex) { mutableStateOf(initialRegex) }
    var regexError by remember { mutableStateOf<String?>(null) }

    val invalidRegexPattern = stringResource(Res.string.settings_playback_invalid_regex_pattern)
    val presets = listOf(
        stringResource(Res.string.settings_playback_regex_preset_any_1080p) to "(2160p|4k|1080p)",
        stringResource(Res.string.settings_playback_regex_preset_quality_4k_remux) to "(2160p|4k|remux)",
        stringResource(Res.string.settings_playback_regex_preset_quality_1080p_standard) to "(1080p|full\\s*hd)",
        stringResource(Res.string.settings_playback_regex_preset_quality_720p_smaller) to "(720p|webrip|web-dl)",
        stringResource(Res.string.settings_playback_regex_preset_web_sources) to "(web[-\\s]?dl|webrip)",
        stringResource(Res.string.settings_playback_regex_preset_bluray_quality) to "(bluray|b[dr]rip|remux)",
        stringResource(Res.string.settings_playback_regex_preset_hevc_x265) to "(hevc|x265|h\\.265)",
        stringResource(Res.string.settings_playback_regex_preset_avc_x264) to "(x264|h\\.264|avc)",
        stringResource(Res.string.settings_playback_regex_preset_hdr_dolby_vision) to "(hdr|hdr10\\+?|dv|dolby\\s*vision)",
        stringResource(Res.string.settings_playback_regex_preset_dolby_atmos_dts) to "(atmos|truehd|dts[-\\s]?hd|dtsx?)",
        stringResource(Res.string.settings_playback_regex_preset_english) to "(\\beng\\b|english)",
        stringResource(Res.string.settings_playback_regex_preset_no_cam_ts) to "^(?!.*\\b(cam|hdcam|ts|telesync)\\b).*$",
        stringResource(Res.string.settings_playback_regex_preset_no_remux_hdr) to "(?is)^(?!.*\\b(hdr|hdr10|dv|dolby|vision|hevc|remux|2160p)\\b).+$",
    )

    BasicAlertDialog(
        onDismissRequest = onDismiss,
    ) {
        NuvioDialogSurface(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_playback_regex_pattern),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )

                Text(
                    text = stringResource(Res.string.settings_playback_regex_matches_against),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(
                    text = stringResource(Res.string.settings_playback_presets),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(
                        count = presets.size,
                        key = { presets[it].first },
                    ) { index ->
                        val (label, pattern) = presets[index]
                        Surface(
                            modifier = Modifier.clickable {
                                regex = pattern
                                regexError = null
                            },
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        ) {
                            Text(
                                text = label,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    border = BorderStroke(
                        1.dp,
                        if (regexError != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    ),
                ) {
                    BasicTextField(
                        value = regex,
                        onValueChange = {
                            regex = it
                            regexError = null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .trackTextInputFocus()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                        ),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { innerTextField ->
                            if (regex.isBlank()) {
                                Text(
                                    text = stringResource(Res.string.settings_playback_regex_placeholder),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                )
                            }
                            innerTextField()
                        },
                    )
                }

                if (regexError != null) {
                    Text(
                        text = regexError ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                    TextButton(onClick = {
                        regex = ""
                        regexError = null
                    }) {
                        Text(stringResource(Res.string.action_clear))
                    }
                    TextButton(onClick = {
                        val value = regex.trim()
                        if (value.isNotEmpty()) {
                            val valid = runCatching { Regex(value, RegexOption.IGNORE_CASE) }.isSuccess
                            if (!valid) {
                                regexError = invalidRegexPattern
                                return@TextButton
                            }
                        }
                        onSave(value)
                    }) {
                        Text(stringResource(Res.string.action_save))
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun CustomMpvOptionsDialog(
    initialValue: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initialValue) }

    BasicAlertDialog(onDismissRequest = onDismiss) {
        NuvioDialogSurface(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_playback_desktop_custom_mpv_options),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(Res.string.settings_playback_desktop_custom_mpv_options_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                ) {
                    BasicTextField(
                        value = value,
                        onValueChange = { value = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .trackTextInputFocus()
                            .heightIn(min = 96.dp)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = FontFamily.Monospace,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = false,
                        decorationBox = { inner ->
                            if (value.isEmpty()) {
                                Text(
                                    text = stringResource(Res.string.settings_playback_desktop_custom_mpv_options_hint),
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                )
                            }
                            inner()
                        },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
                    TextButton(onClick = { onSave(value.trim()) }) { Text(stringResource(Res.string.action_save)) }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun CustomShaderPathsDialog(
    initialValue: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initialValue) }

    BasicAlertDialog(onDismissRequest = onDismiss) {
        NuvioDialogSurface(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_playback_desktop_custom_shader_paths),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(Res.string.settings_playback_desktop_custom_shader_paths_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                ) {
                    BasicTextField(
                        value = value,
                        onValueChange = { value = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .trackTextInputFocus()
                            .heightIn(min = 136.dp)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = FontFamily.Monospace,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = false,
                        decorationBox = { inner ->
                            if (value.isEmpty()) {
                                Text(
                                    text = stringResource(Res.string.settings_playback_desktop_custom_shader_paths_hint),
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                )
                            }
                            inner()
                        },
                    )
                }
                Text(
                    text = stringResource(Res.string.settings_playback_desktop_custom_shader_paths_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
                    TextButton(onClick = { value = "" }) { Text(stringResource(Res.string.action_clear)) }
                    TextButton(onClick = { onSave(value.trim()) }) { Text(stringResource(Res.string.action_save)) }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AnimeSkipClientIdDialog(
    initialValue: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initialValue) }

    BasicAlertDialog(onDismissRequest = onDismiss) {
        NuvioDialogSurface(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_playback_anime_skip_client_id),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(Res.string.settings_playback_anime_skip_client_id_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                ) {
                    BasicTextField(
                        value = value,
                        onValueChange = { value = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .trackTextInputFocus()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = true,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
                    TextButton(onClick = { onSave(value.trim()) }) { Text(stringResource(Res.string.action_save)) }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun IntroDbApiKeyDialog(
    initialValue: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var value by remember { mutableStateOf(initialValue) }
    var isVerifying by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val invalidKeyMessage = stringResource(Res.string.settings_playback_introdb_invalid_key)

    BasicAlertDialog(onDismissRequest = { if (!isVerifying) onDismiss() }) {
        NuvioDialogSurface(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_playback_introdb_api_key),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(Res.string.settings_playback_introdb_api_key_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingsSecretTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        errorMessage = null
                    },
                    label = stringResource(Res.string.settings_playback_introdb_api_key),
                    modifier = Modifier.fillMaxWidth(),
                    isError = errorMessage != null,
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss, enabled = !isVerifying) { 
                        Text(stringResource(Res.string.action_cancel)) 
                    }
                    TextButton(
                        onClick = { 
                            val trimmed = value.trim()
                            if (trimmed.isEmpty()) {
                                onSave(trimmed)
                                return@TextButton
                            }
                            
                            if (trimmed == initialValue) {
                                onDismiss()
                                return@TextButton
                            }

                            isVerifying = true
                            errorMessage = null
                            scope.launch {
                                val isValid = com.nuvio.app.features.player.skip.SkipIntroRepository.verifyIntroDbApiKey(trimmed)
                                isVerifying = false
                                if (isValid) {
                                    onSave(trimmed)
                                } else {
                                    errorMessage = invalidKeyMessage
                                }
                            }
                        },
                        enabled = !isVerifying
                    ) { 
                        if (isVerifying) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Text(stringResource(Res.string.action_save)) 
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SkipDbApiKeyDialog(
    initialValue: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var value by remember { mutableStateOf(initialValue) }
    var isCreatingKey by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val createFailedMessage = stringResource(Res.string.settings_playback_skipdb_create_key_failed)

    BasicAlertDialog(onDismissRequest = { if (!isCreatingKey) onDismiss() }) {
        NuvioDialogSurface(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_playback_skipdb_api_key),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(Res.string.settings_playback_skipdb_api_key_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingsSecretTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        errorMessage = null
                    },
                    label = stringResource(Res.string.settings_playback_skipdb_api_key),
                    modifier = Modifier.fillMaxWidth(),
                    isError = errorMessage != null,
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                // SkipDB hands out submission keys without an account, so contributing does not
                // require registering anywhere. Kept as an explicit action rather than something
                // that happens silently on first submit, since it creates a key on their service.
                TextButton(
                    onClick = {
                        isCreatingKey = true
                        errorMessage = null
                        scope.launch {
                            val created = com.nuvio.app.features.player.skip.SkipIntroRepository
                                .createSkipDbAnonymousKey()
                            isCreatingKey = false
                            if (created) {
                                onDismiss()
                            } else {
                                errorMessage = createFailedMessage
                            }
                        }
                    },
                    enabled = !isCreatingKey,
                ) {
                    if (isCreatingKey) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Text(stringResource(Res.string.settings_playback_skipdb_create_key))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss, enabled = !isCreatingKey) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                    TextButton(
                        onClick = { onSave(value.trim()) },
                        enabled = !isCreatingKey,
                    ) {
                        Text(stringResource(Res.string.action_save))
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun NextEpisodeThresholdModeDialog(
    selected: com.nuvio.app.features.player.skip.NextEpisodeThresholdMode,
    onSelect: (com.nuvio.app.features.player.skip.NextEpisodeThresholdMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = com.nuvio.app.features.player.skip.NextEpisodeThresholdMode.entries

    BasicAlertDialog(onDismissRequest = onDismiss) {
        NuvioDialogSurface(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_playback_threshold_mode),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )

                options.forEach { mode ->
                    val isSelected = mode == selected
                    val containerColor = if (isSelected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(mode) },
                        shape = RoundedCornerShape(12.dp),
                        color = containerColor,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(mode.labelRes),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Box(
                                modifier = Modifier.size(24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(Res.string.settings_playback_dialog_close),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun decoderPriorityRes(priority: Int): StringResource = when (priority) {
    0 -> Res.string.settings_playback_decoder_device_only
    1 -> Res.string.settings_playback_decoder_prefer_device
    2 -> Res.string.settings_playback_decoder_prefer_app
    else -> Res.string.settings_playback_decoder_prefer_device
}

@Composable
private fun decoderPriorityLabel(priority: Int): String = stringResource(decoderPriorityRes(priority))

private fun StreamAutoPlaySource.labelRes(pluginsEnabled: Boolean): StringResource = when (this) {
    StreamAutoPlaySource.ALL_SOURCES ->
        if (pluginsEnabled) Res.string.settings_playback_source_scope_all_sources
        else Res.string.settings_playback_source_scope_all_addons
    StreamAutoPlaySource.INSTALLED_ADDONS_ONLY -> Res.string.settings_playback_source_scope_installed_addons_only
    StreamAutoPlaySource.ENABLED_PLUGINS_ONLY -> Res.string.settings_playback_source_scope_enabled_plugins_only
}

private val StreamAutoPlayMode.labelRes: StringResource
    get() = when (this) {
        StreamAutoPlayMode.MANUAL -> Res.string.settings_playback_stream_selection_mode_manual
        StreamAutoPlayMode.FIRST_STREAM -> Res.string.settings_playback_stream_selection_mode_first_stream
        StreamAutoPlayMode.REGEX_MATCH -> Res.string.settings_playback_stream_selection_mode_regex
        StreamAutoPlayMode.SCORED -> Res.string.settings_playback_stream_selection_mode_scored
    }

private val SkipAutoAcceptMode.labelRes: StringResource
    get() = when (this) {
        SkipAutoAcceptMode.MANUAL -> Res.string.settings_playback_skip_auto_accept_manual
        SkipAutoAcceptMode.CHAPTERS -> Res.string.settings_playback_skip_auto_accept_chapters
        SkipAutoAcceptMode.ANY_SOURCE -> Res.string.settings_playback_skip_auto_accept_any
    }

private val SkipAutoAcceptMode.descriptionRes: StringResource
    get() = when (this) {
        SkipAutoAcceptMode.MANUAL ->
            Res.string.settings_playback_skip_auto_accept_manual_description
        SkipAutoAcceptMode.CHAPTERS ->
            Res.string.settings_playback_skip_auto_accept_chapters_description
        SkipAutoAcceptMode.ANY_SOURCE ->
            Res.string.settings_playback_skip_auto_accept_any_description
    }

private val com.nuvio.app.features.player.skip.NextEpisodeThresholdMode.labelRes: StringResource
    get() = when (this) {
        com.nuvio.app.features.player.skip.NextEpisodeThresholdMode.PERCENTAGE ->
            Res.string.settings_playback_threshold_mode_percentage
        com.nuvio.app.features.player.skip.NextEpisodeThresholdMode.MINUTES_BEFORE_END ->
            Res.string.settings_playback_threshold_mode_minutes_before_end
    }

private fun libassRenderTypeRes(renderType: String): StringResource = when (renderType) {
    "OVERLAY_OPEN_GL" -> Res.string.settings_playback_render_type_overlay_opengl
    "OVERLAY_CANVAS" -> Res.string.settings_playback_render_type_overlay_canvas
    "EFFECTS_OPEN_GL" -> Res.string.settings_playback_render_type_effects_opengl
    "EFFECTS_CANVAS" -> Res.string.settings_playback_render_type_effects_canvas
    "CUES" -> Res.string.settings_playback_render_type_cues
    else -> Res.string.settings_playback_render_type_cues
}

@Composable
private fun libassRenderTypeLabel(renderType: String): String = stringResource(libassRenderTypeRes(renderType))

@Composable
private fun subtitleAssStyleModeLabel(mode: SubtitleAssStyleMode): String = when (mode) {
    SubtitleAssStyleMode.Original -> stringResource(Res.string.player_subtitle_ass_mode_original)
    SubtitleAssStyleMode.Resize -> stringResource(Res.string.player_subtitle_ass_mode_resize)
    SubtitleAssStyleMode.Override -> stringResource(Res.string.player_subtitle_ass_mode_override)
}
