package com.nuvio.app.features.settings

import com.nuvio.app.core.build.AppFeaturePolicy
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.player.AudioLanguageOption
import com.nuvio.app.features.player.AvailableLanguageOptions
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.SubtitleLanguageOption
import com.nuvio.app.features.player.formatPlaybackSpeedLabel
import com.nuvio.app.features.player.languageLabelForCode
import com.nuvio.app.features.plugins.PluginsUiState
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamAutoPlaySource
import com.nuvio.app.isIos

internal fun LazyListScope.playbackSettingsContent(
    isTablet: Boolean,
    showLoadingOverlay: Boolean,
    holdToSpeedEnabled: Boolean,
    holdToSpeedValue: Float,
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
            holdToSpeedEnabled = holdToSpeedEnabled,
            holdToSpeedValue = holdToSpeedValue,
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

@Composable
private fun PlaybackSettingsSection(
    isTablet: Boolean,
    showLoadingOverlay: Boolean,
    holdToSpeedEnabled: Boolean,
    holdToSpeedValue: Float,
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
    var showPreferredAudioDialog by remember { mutableStateOf(false) }
    var showSecondaryAudioDialog by remember { mutableStateOf(false) }
    var showPreferredSubtitleDialog by remember { mutableStateOf(false) }
    var showSecondarySubtitleDialog by remember { mutableStateOf(false) }
    var showReuseCacheDurationDialog by remember { mutableStateOf(false) }
    var showDecoderPriorityDialog by remember { mutableStateOf(false) }
    var showHoldToSpeedValueDialog by remember { mutableStateOf(false) }
    var showLibassRenderTypeDialog by remember { mutableStateOf(false) }
    var showAutoPlayModeDialog by remember { mutableStateOf(false) }
    var showAutoPlaySourceDialog by remember { mutableStateOf(false) }
    var showAutoPlayAddonSelectionDialog by remember { mutableStateOf(false) }
    var showAutoPlayPluginSelectionDialog by remember { mutableStateOf(false) }
    var showAutoPlayRegexDialog by remember { mutableStateOf(false) }
    val pluginsEnabled = AppFeaturePolicy.pluginsEnabled
    val autoPlayPlayerSettings by PlayerSettingsRepository.uiState.collectAsStateWithLifecycle()
    val addonUiState by AddonRepository.uiState.collectAsStateWithLifecycle()
    val pluginUiState = if (pluginsEnabled) {
        val state by PluginRepository.uiState.collectAsStateWithLifecycle()
        state
    } else {
        PluginsUiState(pluginsEnabled = false)
    }
    val hapticFeedback = LocalHapticFeedback.current
    val sectionSpacing = if (isTablet) 18.dp else 12.dp

    Column(
        verticalArrangement = Arrangement.spacedBy(sectionSpacing),
    ) {
        SettingsSection(
            title = "PLAYER",
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = "Show Loading Overlay",
                    description = "Show the opening loading overlay while a stream starts playing.",
                    checked = showLoadingOverlay,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setShowLoadingOverlay,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = "Hold To Speed",
                    description = "Long-press anywhere on the player surface to temporarily boost playback speed.",
                    checked = holdToSpeedEnabled,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setHoldToSpeedEnabled,
                )
                if (holdToSpeedEnabled) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = "Hold Speed",
                        description = formatPlaybackSpeedLabel(holdToSpeedValue),
                        isTablet = isTablet,
                        onClick = { showHoldToSpeedValueDialog = true },
                    )
                }
            }
        }

        SettingsSection(
            title = "SUBTITLE AND AUDIO",
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = "Preferred Audio Language",
                    description = when (preferredAudioLanguage) {
                        AudioLanguageOption.DEFAULT -> "Default"
                        AudioLanguageOption.DEVICE -> "Device Language"
                        else -> languageLabelForCode(preferredAudioLanguage)
                    },
                    isTablet = isTablet,
                    onClick = { showPreferredAudioDialog = true },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = "Secondary Audio Language",
                    description = languageLabelForCode(secondaryPreferredAudioLanguage),
                    isTablet = isTablet,
                    onClick = { showSecondaryAudioDialog = true },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = "Preferred Subtitle Language",
                    description = when (preferredSubtitleLanguage) {
                        SubtitleLanguageOption.NONE -> "None"
                        SubtitleLanguageOption.DEVICE -> "Device Language"
                        SubtitleLanguageOption.FORCED -> "Forced"
                        else -> languageLabelForCode(preferredSubtitleLanguage)
                    },
                    isTablet = isTablet,
                    onClick = { showPreferredSubtitleDialog = true },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = "Secondary Subtitle Language",
                    description = languageLabelForCode(secondaryPreferredSubtitleLanguage),
                    isTablet = isTablet,
                    onClick = { showSecondarySubtitleDialog = true },
                )
            }
        }

        SettingsSection(
            title = "STREAM SELECTION",
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = "Reuse Last Link",
                    description = "Auto-play your last working stream for this same movie/episode when cache is still valid.",
                    checked = streamReuseLastLinkEnabled,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setStreamReuseLastLinkEnabled,
                )
                if (streamReuseLastLinkEnabled) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = "Last Link Cache Duration",
                        description = formatReuseCacheDuration(streamReuseLastLinkCacheHours),
                        isTablet = isTablet,
                        onClick = { showReuseCacheDurationDialog = true },
                    )
                }
            }
        }

        SettingsSection(
            title = "STREAM AUTO-PLAY",
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = "Stream Selection Mode",
                    description = when (autoPlayPlayerSettings.streamAutoPlayMode) {
                        StreamAutoPlayMode.MANUAL -> "Manual"
                        StreamAutoPlayMode.FIRST_STREAM -> "First Available Stream"
                        StreamAutoPlayMode.REGEX_MATCH -> "Regex Match"
                    },
                    isTablet = isTablet,
                    onClick = { showAutoPlayModeDialog = true },
                )
                if (autoPlayPlayerSettings.streamAutoPlayMode != StreamAutoPlayMode.MANUAL) {
                    if (autoPlayPlayerSettings.streamAutoPlayMode == StreamAutoPlayMode.REGEX_MATCH) {
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsNavigationRow(
                            title = "Regex Pattern",
                            description = autoPlayPlayerSettings.streamAutoPlayRegex.ifBlank { "Not set" },
                            isTablet = isTablet,
                            onClick = { showAutoPlayRegexDialog = true },
                        )
                    }
                    SettingsGroupDivider(isTablet = isTablet)
                    val timeoutSec = autoPlayPlayerSettings.streamAutoPlayTimeoutSeconds
                    val timeoutLabel = when (timeoutSec) {
                        0 -> "Instant"
                        11 -> "Unlimited"
                        else -> "${timeoutSec}s"
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = if (isTablet) 18.dp else 16.dp, vertical = 10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Stream Timeout",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "How long to wait for streams before auto-selecting.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = timeoutLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        var sliderValue by remember(timeoutSec) { mutableFloatStateOf(timeoutSec.toFloat()) }
                        var lastHapticStep by remember(timeoutSec) { mutableStateOf(timeoutSec) }
                        Slider(
                            value = sliderValue,
                            onValueChange = {
                                sliderValue = it
                                val steppedValue = it.toInt()
                                if (steppedValue != lastHapticStep) {
                                    lastHapticStep = steppedValue
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            },
                            onValueChangeFinished = {
                                PlayerSettingsRepository.setStreamAutoPlayTimeoutSeconds(sliderValue.toInt())
                            },
                            valueRange = 0f..11f,
                            steps = 10,
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = "Source Scope",
                        description = when (autoPlayPlayerSettings.streamAutoPlaySource) {
                            StreamAutoPlaySource.ALL_SOURCES -> if (pluginsEnabled) "All Sources" else "All Addons"
                            StreamAutoPlaySource.INSTALLED_ADDONS_ONLY -> "Installed Addons Only"
                            StreamAutoPlaySource.ENABLED_PLUGINS_ONLY -> "Enabled Plugins Only"
                        },
                        isTablet = isTablet,
                        onClick = { showAutoPlaySourceDialog = true },
                    )
                    if (autoPlayPlayerSettings.streamAutoPlaySource != StreamAutoPlaySource.ENABLED_PLUGINS_ONLY) {
                        SettingsGroupDivider(isTablet = isTablet)
                        val addonSubtitle = if (autoPlayPlayerSettings.streamAutoPlaySelectedAddons.isEmpty()) {
                            "All Addons"
                        } else {
                            "${autoPlayPlayerSettings.streamAutoPlaySelectedAddons.size} selected"
                        }
                        SettingsNavigationRow(
                            title = "Allowed Addons",
                            description = addonSubtitle,
                            isTablet = isTablet,
                            onClick = { showAutoPlayAddonSelectionDialog = true },
                        )
                    }
                    if (pluginsEnabled && autoPlayPlayerSettings.streamAutoPlaySource != StreamAutoPlaySource.INSTALLED_ADDONS_ONLY) {
                        SettingsGroupDivider(isTablet = isTablet)
                        val pluginSubtitle = if (autoPlayPlayerSettings.streamAutoPlaySelectedPlugins.isEmpty()) {
                            "All Plugins"
                        } else {
                            "${autoPlayPlayerSettings.streamAutoPlaySelectedPlugins.size} selected"
                        }
                        SettingsNavigationRow(
                            title = "Allowed Plugins",
                            description = pluginSubtitle,
                            isTablet = isTablet,
                            onClick = { showAutoPlayPluginSelectionDialog = true },
                        )
                    }
                }
            }
        }

        if (!isIos) {
            SettingsSection(
                title = "DECODER",
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsNavigationRow(
                        title = "Decoder Priority",
                        description = when (decoderPriority) {
                            0 -> "Device Only"
                            1 -> "Prefer Device"
                            2 -> "Prefer App (FFmpeg)"
                            else -> "Prefer Device"
                        },
                        isTablet = isTablet,
                        onClick = { showDecoderPriorityDialog = true },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = "Map DV7 to HEVC",
                        description = "Dolby Vision Profile 7 to HEVC fallback for unsupported devices.",
                        checked = mapDV7ToHevc,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setMapDV7ToHevc,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = "Tunneled Playback",
                        description = "Enable tunneled playback for lower latency audio/video sync.",
                        checked = tunnelingEnabled,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setTunnelingEnabled,
                    )
                }
            }
        }

        if (!isIos) {
            SettingsSection(
                title = "SUBTITLE RENDERING",
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsSwitchRow(
                        title = "Enable libass",
                        description = "Use libass for ASS/SSA subtitle rendering instead of the default renderer.",
                        checked = useLibass,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setUseLibass,
                    )
                    if (useLibass) {
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsNavigationRow(
                            title = "Render Type",
                            description = when (libassRenderType) {
                                "OVERLAY_OPEN_GL" -> "Overlay OpenGL"
                                "OVERLAY_CANVAS" -> "Overlay Canvas"
                                "EFFECTS_OPEN_GL" -> "Effects OpenGL"
                                "EFFECTS_CANVAS" -> "Effects Canvas"
                                "CUES" -> "Standard (Cues)"
                                else -> "Standard (Cues)"
                            },
                            isTablet = isTablet,
                            onClick = { showLibassRenderTypeDialog = true },
                        )
                    }
                }
            }
        }

        SettingsSection(
            title = "SKIP SEGMENTS",
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = "Skip Intro/Outro/Recap",
                    description = "Show skip button during detected intro, outro, and recap segments.",
                    checked = autoPlayPlayerSettings.skipIntroEnabled,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setSkipIntroEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = "Anime Skip",
                    description = "Also search AnimeSkip for skip timestamps (requires client ID).",
                    checked = autoPlayPlayerSettings.animeSkipEnabled,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setAnimeSkipEnabled,
                )
                if (autoPlayPlayerSettings.animeSkipEnabled) {
                    SettingsGroupDivider(isTablet = isTablet)
                    var showAnimeSkipClientIdDialog by remember { mutableStateOf(false) }
                    SettingsNavigationRow(
                        title = "AnimeSkip Client ID",
                        description = autoPlayPlayerSettings.animeSkipClientId.ifBlank { "Not set" },
                        isTablet = isTablet,
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
            }
        }

        SettingsSection(
            title = "NEXT EPISODE",
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = "Auto-Play Next Episode",
                    description = "Automatically find and play the next episode when the threshold is reached.",
                    checked = autoPlayPlayerSettings.streamAutoPlayNextEpisodeEnabled,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setStreamAutoPlayNextEpisodeEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = "Prefer Binge Group",
                    description = "When auto-playing, prefer a stream from the same binge group as the current one.",
                    checked = autoPlayPlayerSettings.streamAutoPlayPreferBingeGroup,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setStreamAutoPlayPreferBingeGroup,
                )
                SettingsGroupDivider(isTablet = isTablet)
                var showThresholdModeDialog by remember { mutableStateOf(false) }
                SettingsNavigationRow(
                    title = "Threshold Mode",
                    description = when (autoPlayPlayerSettings.nextEpisodeThresholdMode) {
                        com.nuvio.app.features.player.skip.NextEpisodeThresholdMode.PERCENTAGE -> "Percentage"
                        com.nuvio.app.features.player.skip.NextEpisodeThresholdMode.MINUTES_BEFORE_END -> "Minutes Before End"
                    },
                    isTablet = isTablet,
                    onClick = { showThresholdModeDialog = true },
                )
                if (showThresholdModeDialog) {
                    NextEpisodeThresholdModeDialog(
                        selected = autoPlayPlayerSettings.nextEpisodeThresholdMode,
                        onSelect = {
                            PlayerSettingsRepository.setNextEpisodeThresholdMode(it)
                            showThresholdModeDialog = false
                        },
                        onDismiss = { showThresholdModeDialog = false },
                    )
                }
                SettingsGroupDivider(isTablet = isTablet)
                when (autoPlayPlayerSettings.nextEpisodeThresholdMode) {
                    com.nuvio.app.features.player.skip.NextEpisodeThresholdMode.PERCENTAGE -> {
                        val thresholdPercent = autoPlayPlayerSettings.nextEpisodeThresholdPercent
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = if (isTablet) 18.dp else 16.dp, vertical = 10.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Threshold Percentage",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = "Show next episode card when playback reaches this percentage.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    text = "${thresholdPercent.toInt()}%",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            var sliderVal by remember(thresholdPercent) { mutableFloatStateOf(thresholdPercent) }
                            var lastHapticPercent by remember(thresholdPercent) { mutableStateOf(thresholdPercent.toInt()) }
                            Slider(
                                value = sliderVal,
                                onValueChange = {
                                    sliderVal = it
                                    val stepped = it.toInt()
                                    if (stepped != lastHapticPercent) {
                                        lastHapticPercent = stepped
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                },
                                onValueChangeFinished = {
                                    PlayerSettingsRepository.setNextEpisodeThresholdPercent(sliderVal)
                                },
                                valueRange = 50f..100f,
                                steps = 49,
                                colors = SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.primary,
                                    activeTrackColor = MaterialTheme.colorScheme.primary,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    com.nuvio.app.features.player.skip.NextEpisodeThresholdMode.MINUTES_BEFORE_END -> {
                        val thresholdMinutes = autoPlayPlayerSettings.nextEpisodeThresholdMinutesBeforeEnd
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = if (isTablet) 18.dp else 16.dp, vertical = 10.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Minutes Before End",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = "Show next episode card this many minutes before the end.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    text = "${thresholdMinutes.toInt()} min",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            var sliderVal by remember(thresholdMinutes) { mutableFloatStateOf(thresholdMinutes) }
                            var lastHapticMin by remember(thresholdMinutes) { mutableStateOf(thresholdMinutes.toInt()) }
                            Slider(
                                value = sliderVal,
                                onValueChange = {
                                    sliderVal = it
                                    val stepped = it.toInt()
                                    if (stepped != lastHapticMin) {
                                        lastHapticMin = stepped
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                },
                                onValueChangeFinished = {
                                    PlayerSettingsRepository.setNextEpisodeThresholdMinutesBeforeEnd(sliderVal)
                                },
                                valueRange = 1f..15f,
                                steps = 13,
                                colors = SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.primary,
                                    activeTrackColor = MaterialTheme.colorScheme.primary,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPreferredAudioDialog) {
        LanguageSelectionDialog(
            title = "Preferred Audio Language",
            options = listOf(
                LanguageSelectionOption(AudioLanguageOption.DEFAULT, "Default"),
                LanguageSelectionOption(AudioLanguageOption.DEVICE, "Device Language"),
            ) + AvailableLanguageOptions.map { option ->
                LanguageSelectionOption(option.code, option.label)
            },
            selectedValue = preferredAudioLanguage,
            onSelect = { value ->
                PlayerSettingsRepository.setPreferredAudioLanguage(value ?: AudioLanguageOption.DEVICE)
                showPreferredAudioDialog = false
            },
            onDismiss = { showPreferredAudioDialog = false },
        )
    }

    if (showSecondaryAudioDialog) {
        LanguageSelectionDialog(
            title = "Secondary Audio Language",
            options = listOf(
                LanguageSelectionOption(null, "None"),
            ) + AvailableLanguageOptions.map { option ->
                LanguageSelectionOption(option.code, option.label)
            },
            selectedValue = secondaryPreferredAudioLanguage,
            onSelect = { value ->
                PlayerSettingsRepository.setSecondaryPreferredAudioLanguage(value)
                showSecondaryAudioDialog = false
            },
            onDismiss = { showSecondaryAudioDialog = false },
        )
    }

    if (showPreferredSubtitleDialog) {
        LanguageSelectionDialog(
            title = "Preferred Subtitle Language",
            options = listOf(
                LanguageSelectionOption(SubtitleLanguageOption.NONE, "None"),
                LanguageSelectionOption(SubtitleLanguageOption.DEVICE, "Device Language"),
                LanguageSelectionOption(SubtitleLanguageOption.FORCED, "Forced"),
            ) + AvailableLanguageOptions.map { option ->
                LanguageSelectionOption(option.code, option.label)
            },
            selectedValue = preferredSubtitleLanguage,
            onSelect = { value ->
                PlayerSettingsRepository.setPreferredSubtitleLanguage(value ?: SubtitleLanguageOption.NONE)
                showPreferredSubtitleDialog = false
            },
            onDismiss = { showPreferredSubtitleDialog = false },
        )
    }

    if (showSecondarySubtitleDialog) {
        LanguageSelectionDialog(
            title = "Secondary Subtitle Language",
            options = listOf(
                LanguageSelectionOption(null, "None"),
                LanguageSelectionOption(SubtitleLanguageOption.FORCED, "Forced"),
            ) + AvailableLanguageOptions.map { option ->
                LanguageSelectionOption(option.code, option.label)
            },
            selectedValue = secondaryPreferredSubtitleLanguage,
            onSelect = { value ->
                PlayerSettingsRepository.setSecondaryPreferredSubtitleLanguage(value)
                showSecondarySubtitleDialog = false
            },
            onDismiss = { showSecondarySubtitleDialog = false },
        )
    }

    if (showReuseCacheDurationDialog) {
        ReuseCacheDurationDialog(
            selectedHours = streamReuseLastLinkCacheHours,
            onDurationSelected = { hours ->
                PlayerSettingsRepository.setStreamReuseLastLinkCacheHours(hours)
                showReuseCacheDurationDialog = false
            },
            onDismiss = { showReuseCacheDurationDialog = false },
        )
    }

    if (showDecoderPriorityDialog) {
        DecoderPriorityDialog(
            selectedPriority = decoderPriority,
            onPrioritySelected = { priority ->
                PlayerSettingsRepository.setDecoderPriority(priority)
                showDecoderPriorityDialog = false
            },
            onDismiss = { showDecoderPriorityDialog = false },
        )
    }

    if (showHoldToSpeedValueDialog) {
        HoldToSpeedValueDialog(
            selectedSpeed = holdToSpeedValue,
            onSpeedSelected = { speed ->
                PlayerSettingsRepository.setHoldToSpeedValue(speed)
                showHoldToSpeedValueDialog = false
            },
            onDismiss = { showHoldToSpeedValueDialog = false },
        )
    }

    if (showLibassRenderTypeDialog) {
        LibassRenderTypeDialog(
            selectedRenderType = libassRenderType,
            onRenderTypeSelected = { renderType ->
                PlayerSettingsRepository.setLibassRenderType(renderType)
                showLibassRenderTypeDialog = false
            },
            onDismiss = { showLibassRenderTypeDialog = false },
        )
    }

    if (showAutoPlayModeDialog) {
        StreamAutoPlayModeDialog(
            selectedMode = autoPlayPlayerSettings.streamAutoPlayMode,
            onModeSelected = {
                PlayerSettingsRepository.setStreamAutoPlayMode(it)
                showAutoPlayModeDialog = false
            },
            onDismiss = { showAutoPlayModeDialog = false },
        )
    }

    if (showAutoPlaySourceDialog) {
        StreamAutoPlaySourceDialog(
            pluginsEnabled = pluginsEnabled,
            selectedSource = autoPlayPlayerSettings.streamAutoPlaySource,
            onSourceSelected = {
                PlayerSettingsRepository.setStreamAutoPlaySource(it)
                showAutoPlaySourceDialog = false
            },
            onDismiss = { showAutoPlaySourceDialog = false },
        )
    }

    if (showAutoPlayAddonSelectionDialog) {
        val addonNames = addonUiState.addons
            .mapNotNull { it.manifest }
            .filter { manifest -> manifest.resources.any { resource -> resource.name == "stream" } }
            .map { it.name }
            .distinct()
            .sorted()
        StreamAutoPlayProviderSelectionDialog(
            title = "Allowed Addons",
            allLabel = "All Addons",
            items = addonNames,
            selectedItems = autoPlayPlayerSettings.streamAutoPlaySelectedAddons,
            onSelectionSaved = {
                PlayerSettingsRepository.setStreamAutoPlaySelectedAddons(it)
                showAutoPlayAddonSelectionDialog = false
            },
            onDismiss = { showAutoPlayAddonSelectionDialog = false },
        )
    }

    if (pluginsEnabled && showAutoPlayPluginSelectionDialog) {
        val pluginNames = pluginUiState.scrapers
            .filter { it.enabled }
            .map { it.name }
            .distinct()
            .sorted()
        StreamAutoPlayProviderSelectionDialog(
            title = "Allowed Plugins",
            allLabel = "All Plugins",
            items = pluginNames,
            selectedItems = autoPlayPlayerSettings.streamAutoPlaySelectedPlugins,
            onSelectionSaved = {
                PlayerSettingsRepository.setStreamAutoPlaySelectedPlugins(it)
                showAutoPlayPluginSelectionDialog = false
            },
            onDismiss = { showAutoPlayPluginSelectionDialog = false },
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

private fun formatReuseCacheDuration(hours: Int): String = when {
    hours < 24 -> "$hours hour${if (hours != 1) "s" else ""}"
    hours % 24 == 0 -> {
        val days = hours / 24
        "$days day${if (days != 1) "s" else ""}"
    }
    else -> "$hours hours"
}

private data class LanguageSelectionOption(
    val value: String?,
    val label: String,
)

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
        Surface(
            modifier = Modifier
                .fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
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
                        val isSelected = option.value == selectedValue
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
                    text = "Tap outside to close",
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
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Last Link Cache Duration",
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
                    text = "Tap outside to close",
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
        0 to "Device Only",
        1 to "Prefer Device",
        2 to "Prefer App (FFmpeg)",
    )

    BasicAlertDialog(
        onDismissRequest = onDismiss,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Decoder Priority",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.forEach { (priority, label) ->
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
                                    text = label,
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
                    text = "Tap outside to close",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun HoldToSpeedValueDialog(
    selectedSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

    BasicAlertDialog(
        onDismissRequest = onDismiss,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Hold Speed",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.forEach { speed ->
                        val isSelected = speed == selectedSpeed
                        val containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSpeedSelected(speed) },
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
                                    text = formatPlaybackSpeedLabel(speed),
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
                    text = "Tap outside to close",
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
        "OVERLAY_OPEN_GL" to "Overlay OpenGL",
        "OVERLAY_CANVAS" to "Overlay Canvas",
        "EFFECTS_OPEN_GL" to "Effects OpenGL",
        "EFFECTS_CANVAS" to "Effects Canvas",
        "CUES" to "Standard (Cues)",
    )

    BasicAlertDialog(
        onDismissRequest = onDismiss,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Render Type",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.forEach { (value, label) ->
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
                                    text = label,
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
                    text = "Tap outside to close",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
        Triple(StreamAutoPlayMode.MANUAL, "Manual", "Select streams manually each time."),
        Triple(StreamAutoPlayMode.FIRST_STREAM, "First Available Stream", "Automatically play the first stream found."),
        Triple(StreamAutoPlayMode.REGEX_MATCH, "Regex Match", "Auto-select a stream matching a regex pattern."),
    )

    BasicAlertDialog(
        onDismissRequest = onDismiss,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Stream Selection Mode",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.forEach { (mode, title, description) ->
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
                                        text = title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = description,
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
                    text = "Tap outside to close",
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
                if (pluginsEnabled) "All Sources" else "All Addons",
                if (pluginsEnabled) {
                    "Consider streams from both addons and plugins."
                } else {
                    "Consider streams from all installed addons."
                },
            ),
        )
        add(
            Triple(
                StreamAutoPlaySource.INSTALLED_ADDONS_ONLY,
                "Installed Addons Only",
                "Only consider streams from installed addons.",
            ),
        )
        if (pluginsEnabled) {
            add(
                Triple(
                    StreamAutoPlaySource.ENABLED_PLUGINS_ONLY,
                    "Enabled Plugins Only",
                    "Only consider streams from enabled plugins.",
                ),
            )
        }
    }

    BasicAlertDialog(
        onDismissRequest = onDismiss,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Source Scope",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.forEach { (source, title, description) ->
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
                                        text = title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = description,
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
                    text = "Tap outside to close",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
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
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
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
                        text = "No items available",
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
                    text = "Tap outside to save & close",
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

    val presets = remember {
        listOf(
            "Any 1080p+" to "(2160p|4k|1080p)",
            "4K / Remux" to "(2160p|4k|remux)",
            "1080p Standard" to "(1080p|full\\s*hd)",
            "720p / Smaller" to "(720p|webrip|web-dl)",
            "WEB Sources" to "(web[-\\s]?dl|webrip)",
            "BluRay Quality" to "(bluray|b[dr]rip|remux)",
            "HEVC / x265" to "(hevc|x265|h\\.265)",
            "AVC / x264" to "(x264|h\\.264|avc)",
            "HDR / Dolby Vision" to "(hdr|hdr10\\+?|dv|dolby\\s*vision)",
            "Dolby Atmos / DTS" to "(atmos|truehd|dts[-\\s]?hd|dtsx?)",
            "English" to "(\\beng\\b|english)",
            "No CAM/TS" to "^(?!.*\\b(cam|hdcam|ts|telesync)\\b).*$",
            "No REMUX/HDR" to "(?is)^(?!.*\\b(hdr|hdr10|dv|dolby|vision|hevc|remux|2160p)\\b).+$",
        )
    }

    BasicAlertDialog(
        onDismissRequest = onDismiss,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Regex Pattern",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )

                Text(
                    text = "Matches against stream name, label, description, addon, and URL.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(
                    text = "Presets",
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
                                    text = "4K|2160p|Remux",
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
                        Text("Cancel")
                    }
                    TextButton(onClick = {
                        regex = ""
                        regexError = null
                    }) {
                        Text("Clear")
                    }
                    TextButton(onClick = {
                        val value = regex.trim()
                        if (value.isNotEmpty()) {
                            val valid = runCatching { Regex(value, RegexOption.IGNORE_CASE) }.isSuccess
                            if (!valid) {
                                regexError = "Invalid regex pattern"
                                return@TextButton
                            }
                        }
                        onSave(value)
                    }) {
                        Text("Save")
                    }
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
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "AnimeSkip Client ID",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Enter your AnimeSkip API client ID. Get one at anime-skip.com.",
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
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onSave(value.trim()) }) { Text("Save") }
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
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Threshold Mode",
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
                    val label = when (mode) {
                        com.nuvio.app.features.player.skip.NextEpisodeThresholdMode.PERCENTAGE -> "Percentage"
                        com.nuvio.app.features.player.skip.NextEpisodeThresholdMode.MINUTES_BEFORE_END -> "Minutes Before End"
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
                                text = label,
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
                    text = "Tap outside to close",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
