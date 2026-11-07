package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.AppShortcutAction
import com.nuvio.app.features.player.appShortcutKeyLabels
import com.nuvio.app.features.player.ensureAppShortcutBindingsLoaded
import com.nuvio.app.features.voice.VOICE_TEST_MAX_MS
import com.nuvio.app.features.voice.VoiceActivityDetector
import com.nuvio.app.features.voice.VoiceControlRepository
import com.nuvio.app.features.voice.VoiceControlUiState
import com.nuvio.app.features.voice.VoiceModel
import com.nuvio.app.features.voice.VoiceModelState
import com.nuvio.app.features.voice.VoiceModels
import com.nuvio.app.features.voice.VoiceTestState
import com.nuvio.app.features.voice.describeVoiceIntent

/*
 * fork:voice-control - Settings > Voice control: download the speech model, pick the microphone,
 * and test what the model hears. English-only for now, like the fork's other pages.
 */

internal const val VOICE_SECTION_GENERAL = "Voice commands"
internal const val VOICE_SECTION_MODEL = "Speech model"
internal const val VOICE_SECTION_MICROPHONE = "Microphone"

@Composable
private fun rememberVoiceControlState(): VoiceControlUiState {
    remember { VoiceControlRepository.ensureLoaded() }
    val state by VoiceControlRepository.uiState.collectAsState()
    return state
}

internal fun LazyListScope.voiceControlSettingsContent(isTablet: Boolean) {
    item {
        val state = rememberVoiceControlState()
        val keyLabels by remember {
            ensureAppShortcutBindingsLoaded()
            appShortcutKeyLabels()
        }.collectAsState()
        val voiceKey = keyLabels[AppShortcutAction.VoiceCommand] ?: "N"
        SettingsSection(title = VOICE_SECTION_GENERAL, isTablet = isTablet) {
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsSearchAnchors("voice-enable", "voice-push-to-talk", "voice-wake-word", "voice-end-pause"),
            ) {
                SettingsSwitchRow(
                    title = "Voice commands",
                    description = if (state.selectedModelInstalled) {
                        "Say what you want, such as “search YouTube for cat videos”, “search movies for " +
                            "Midsommar” or “open settings”. Keeps the speech model loaded (about 150 MB of memory)."
                    } else {
                        "Download a speech model below first."
                    },
                    checked = state.settings.enabled,
                    isTablet = isTablet,
                    onCheckedChange = VoiceControlRepository::setEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsActionRow(
                    title = "Push to talk",
                    description = "Press $voiceKey (change it under Keyboard shortcuts), the mic in the sidebar, the " +
                        "mic button on a DualSense, or hold View on an Xbox controller, then speak. It stops when " +
                        "you pause; press again to finish sooner, or Escape to cancel. The mic in the search and " +
                        "YouTube boxes searches there: just say what to look for.",
                    isTablet = isTablet,
                ) {
                    SettingsGlassButton(
                        text = "Try it",
                        enabled = state.ready,
                        accent = true,
                        onClick = VoiceControlRepository::toggleListening,
                    )
                }
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = "Wake word",
                    description = "Say “Nuvio” and a command, such as “Nuvio, pause” or “Nuvio, search movies for " +
                        "Midsommar”, or “Nuvio” alone and then the command. Keeps the microphone open while Nuvio " +
                        "runs (Windows shows the mic as in use) and reads everything it hears on this PC, nothing " +
                        "sent anywhere, so it uses some CPU while people talk or a film plays loudly." +
                        if (state.wakeListening) " Listening now." else "",
                    checked = state.settings.wakeWord,
                    enabled = state.ready,
                    isTablet = isTablet,
                    onCheckedChange = VoiceControlRepository::setWakeWord,
                )
                SettingsGroupDivider(isTablet = isTablet)
                var commandsOpen by remember { mutableStateOf(false) }
                val ownCount = state.phrases.custom.values.sumOf { it.size }
                SettingsActionRow(
                    title = "Commands",
                    description = "Search YouTube / movies / shows / music for …, open a tab, play, pause, next, " +
                        "skip forward or back, go to 20 minutes, skip the intro, volume, subtitles, playback speed, " +
                        "go back. On a title, “play” presses Play and “source 3” picks from the list; “down”, " +
                        "“go right 3” and “select” move around. Give any of them a phrase of your own, or replace " +
                        "its built-in ones." +
                        if (ownCount > 0) " $ownCount of your own so far." else "",
                    isTablet = isTablet,
                ) {
                    SettingsGlassButton(text = "Edit commands", onClick = { commandsOpen = true })
                }
                if (commandsOpen) VoiceCommandsDialog(state.phrases, onDismiss = { commandsOpen = false })
                SettingsGroupDivider(isTablet = isTablet)
                SettingsDropdownChoiceRow(
                    title = "End of command",
                    description = "How long a pause means you have finished speaking.",
                    options = VoiceActivityDetector.END_SILENCE_OPTIONS_MS.map { ms ->
                        SettingsChoiceOption(
                            ms,
                            when (ms) {
                                300 -> "Very short (0.3 s)"
                                500 -> "Short (0.5 s)"
                                VoiceActivityDetector.DEFAULT_END_SILENCE_MS -> "Normal (${ms / 100 / 10.0} s)"
                                else -> "Long (${ms / 100 / 10.0} s)"
                            },
                        )
                    },
                    selectedValue = state.settings.endSilenceMs,
                    enabled = state.settings.enabled,
                    isTablet = isTablet,
                    onSelected = VoiceControlRepository::setEndSilenceMs,
                )
            }
        }
    }

    item {
        val state = rememberVoiceControlState()
        SettingsSection(title = VOICE_SECTION_MODEL, isTablet = isTablet) {
            SettingsSectionNote(
                text = "Voice commands are recognised on this PC, without sending audio anywhere. Download a " +
                    "speech model once; it is kept outside the install folder, so updating or reinstalling " +
                    "Nuvio does not remove it.",
                isTablet = isTablet,
            )
            state.engineProblem?.let { problem ->
                SettingsSectionNote(text = problem, isTablet = isTablet, modifier = Modifier.padding(top = 4.dp))
            }
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsSearchAnchors("voice-model", "voice-models-folder"),
            ) {
                val anyDownloading = state.models.values.any {
                    it is VoiceModelState.Downloading || it is VoiceModelState.Verifying
                }
                VoiceModels.all.forEachIndexed { index, model ->
                    if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                    VoiceModelRow(model, state, anyDownloading, isTablet)
                }
                SettingsGroupDivider(isTablet = isTablet)
                SettingsActionRow(
                    title = "Models folder",
                    description = state.modelsFolder,
                    isTablet = isTablet,
                ) {
                    SettingsGlassButton(text = "Open folder", onClick = VoiceControlRepository::openModelsFolder)
                }
            }
        }
    }

    item {
        val state = rememberVoiceControlState()
        SettingsSection(title = VOICE_SECTION_MICROPHONE, isTablet = isTablet) {
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsSearchAnchors("voice-microphone", "voice-test"),
            ) {
                val selected = state.settings.microphone
                // A saved device that is unplugged right now stays listed, so the choice is not lost.
                val names = (state.microphones + listOfNotNull(selected)).distinct()
                SettingsDropdownChoiceRow(
                    title = "Microphone",
                    description = "Which recording device voice commands listen on.",
                    options = listOf(SettingsChoiceOption<String?>(null, "Windows default")) +
                        names.map { SettingsChoiceOption<String?>(it, it) },
                    selectedValue = selected,
                    isTablet = isTablet,
                    onSelected = VoiceControlRepository::setMicrophone,
                )
                SettingsGroupDivider(isTablet = isTablet)
                VoiceTestRow(state, isTablet)
            }
        }
    }
}

@Composable
private fun VoiceModelRow(model: VoiceModel, state: VoiceControlUiState, anyDownloading: Boolean, isTablet: Boolean) {
    val modelState = state.stateOf(model)
    val inUse = state.settings.modelId == model.id
    val status = when (modelState) {
        VoiceModelState.NotInstalled -> null
        is VoiceModelState.Downloading ->
            "Downloading ${megabytes(modelState.bytes)} of ${megabytes(modelState.totalBytes)} MB"
        VoiceModelState.Verifying -> "Checking the download…"
        VoiceModelState.Installed -> if (inUse) "Downloaded · in use" else "Downloaded"
        is VoiceModelState.Failed -> modelState.message
    }
    SettingsActionRow(
        title = "${model.label} · ${megabytes(model.sizeBytes)} MB",
        description = listOfNotNull(model.description, status).joinToString("\n"),
        descriptionColor = if (modelState is VoiceModelState.Failed) MaterialTheme.colorScheme.error else null,
        isTablet = isTablet,
    ) {
        when (modelState) {
            VoiceModelState.NotInstalled, is VoiceModelState.Failed -> SettingsGlassButton(
                text = if (modelState is VoiceModelState.Failed) "Retry" else "Download",
                enabled = !anyDownloading,
                accent = model == VoiceModels.default,
                onClick = { VoiceControlRepository.download(model.id) },
            )
            is VoiceModelState.Downloading -> SettingsGlassButton(
                text = "Cancel",
                onClick = { VoiceControlRepository.cancelDownload(model.id) },
            )
            VoiceModelState.Verifying -> SettingsGlassButton(text = "Checking", enabled = false, loading = true, onClick = {})
            VoiceModelState.Installed -> {
                if (!inUse) {
                    SettingsGlassButton(text = "Use", accent = true, onClick = { VoiceControlRepository.setModel(model.id) })
                }
                SettingsGlassButton(text = "Delete", onClick = { VoiceControlRepository.delete(model.id) })
            }
        }
    }
    if (modelState is VoiceModelState.Downloading) {
        LinearProgressIndicator(
            progress = modelState.fraction,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
    }
}

@Composable
private fun VoiceTestRow(state: VoiceControlUiState, isTablet: Boolean) {
    val test = state.test
    val ready = state.engineProblem == null && state.selectedModelInstalled
    val description = when (test) {
        VoiceTestState.Idle -> if (ready) {
            "Say a command, such as \"search YouTube for cat videos\" or \"open settings\", and see what " +
                "the ${state.selectedModel.label} model heard."
        } else {
            "Download a speech model first."
        }
        is VoiceTestState.Recording ->
            "Listening… ${((VOICE_TEST_MAX_MS - test.elapsedMs).coerceAtLeast(0) + 999) / 1000} s left"
        VoiceTestState.Transcribing -> "Working out what was said…"
        is VoiceTestState.Heard -> buildString {
            append(if (test.text.isBlank()) "Heard nothing." else "Heard: \"${test.text}\"")
            append("\nUnderstood as: ").append(describeVoiceIntent(test.intent))
            append("\n${seconds(test.audioMs)} s of audio, recognised in ${seconds(test.decodeMs)} s")
        }
        is VoiceTestState.Failed -> test.message
    }
    SettingsActionRow(
        title = "Test",
        description = description,
        descriptionColor = if (test is VoiceTestState.Failed) MaterialTheme.colorScheme.error else null,
        isTablet = isTablet,
    ) {
        when (test) {
            is VoiceTestState.Recording -> SettingsGlassButton(text = "Stop", accent = true, onClick = VoiceControlRepository::stopTest)
            VoiceTestState.Transcribing -> SettingsGlassButton(text = "Test", enabled = false, loading = true, onClick = {})
            else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // What the model was given, to hear why it misheard: kept in memory for this result only.
                if (test is VoiceTestState.Heard) {
                    SettingsGlassButton(
                        text = if (state.testPlaying) "Stop" else "Play recording",
                        onClick = VoiceControlRepository::playTestRecording,
                    )
                    SettingsGlassButton(text = "Save as WAV", onClick = VoiceControlRepository::showTestRecording)
                }
                SettingsGlassButton(
                    text = if (test is VoiceTestState.Idle) "Test" else "Test again",
                    enabled = ready,
                    accent = true,
                    onClick = VoiceControlRepository::startTest,
                )
            }
        }
    }
    if (test is VoiceTestState.Recording) {
        LinearProgressIndicator(
            progress = test.level,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
    }
}

private fun megabytes(bytes: Long): Long = (bytes + 500_000) / 1_000_000

private fun seconds(ms: Long): String = "${ms / 1000}.${(ms % 1000) / 100}"
