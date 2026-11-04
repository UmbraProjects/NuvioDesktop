package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
                modifier = Modifier.settingsSearchAnchors("voice-enable", "voice-push-to-talk", "voice-end-pause"),
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
                SettingsActionRow(
                    title = "Commands",
                    description = "“Search YouTube / movies / shows / music for …”, “Search for …”, " +
                        "“Open settings / library / home / YouTube / music / calendar / live TV”, " +
                        "“Play”, “Pause”, “Next”, “Go back”. " +
                        "During a video only play, pause, next and go back act.",
                    isTablet = isTablet,
                ) {}
                SettingsGroupDivider(isTablet = isTablet)
                SettingsDropdownChoiceRow(
                    title = "End of command",
                    description = "How long a pause means you have finished speaking.",
                    options = VoiceActivityDetector.END_SILENCE_OPTIONS_MS.map { ms ->
                        SettingsChoiceOption(
                            ms,
                            when (ms) {
                                VoiceActivityDetector.END_SILENCE_OPTIONS_MS.first() -> "Short pause (${ms / 100 / 10.0} s)"
                                VoiceActivityDetector.END_SILENCE_OPTIONS_MS.last() -> "Long pause (${ms / 100 / 10.0} s)"
                                else -> "Normal (${ms / 100 / 10.0} s)"
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
            else -> SettingsGlassButton(
                text = if (test is VoiceTestState.Idle) "Test" else "Test again",
                enabled = ready,
                accent = true,
                onClick = VoiceControlRepository::startTest,
            )
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
