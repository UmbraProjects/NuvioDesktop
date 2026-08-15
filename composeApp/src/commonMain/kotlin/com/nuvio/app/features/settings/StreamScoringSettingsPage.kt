package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.trackTextInputFocus
import com.nuvio.app.features.streams.AudioDeviceSupport
import com.nuvio.app.features.streams.DebridCachedBoost
import com.nuvio.app.features.streams.HdrPreference
import com.nuvio.app.features.streams.LanguageImportance
import com.nuvio.app.features.streams.LowQualityGroupPenalty
import com.nuvio.app.features.streams.ScoreAnswerOption
import com.nuvio.app.features.streams.SizeQualityPreference
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamScore
import com.nuvio.app.features.streams.StreamSizeBand
import com.nuvio.app.features.streams.StreamsRepository
import com.nuvio.app.features.streams.StreamScoreContext
import com.nuvio.app.features.streams.StreamScoreContexts
import com.nuvio.app.features.streams.StreamScoreProfile
import com.nuvio.app.features.streams.StreamScoreRepository
import com.nuvio.app.features.streams.StreamScoreTrait
import com.nuvio.app.features.streams.StreamScoreTraitGroup
import com.nuvio.app.features.streams.StreamScorer
import com.nuvio.app.features.streams.ThreeDEquipment
import com.nuvio.app.features.streams.UnknownGroupTrust
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_stream_scoring_apply_auto_download
import nuvio.composeapp.generated.resources.settings_stream_scoring_apply_failover
import nuvio.composeapp.generated.resources.settings_stream_scoring_apply_first_stream
import nuvio.composeapp.generated.resources.settings_stream_scoring_enabled_desc
import nuvio.composeapp.generated.resources.settings_stream_scoring_enabled_title
import nuvio.composeapp.generated.resources.settings_stream_scoring_group_audio
import nuvio.composeapp.generated.resources.settings_stream_scoring_group_availability
import nuvio.composeapp.generated.resources.settings_stream_scoring_group_channels
import nuvio.composeapp.generated.resources.settings_stream_scoring_group_codec
import nuvio.composeapp.generated.resources.settings_stream_scoring_group_hdr
import nuvio.composeapp.generated.resources.settings_stream_scoring_group_language
import nuvio.composeapp.generated.resources.settings_stream_scoring_group_edition
import nuvio.composeapp.generated.resources.settings_stream_scoring_group_quality
import nuvio.composeapp.generated.resources.settings_stream_scoring_group_release
import nuvio.composeapp.generated.resources.settings_stream_scoring_group_release_flags
import nuvio.composeapp.generated.resources.settings_stream_scoring_group_size
import nuvio.composeapp.generated.resources.settings_stream_scoring_minimum_desc
import nuvio.composeapp.generated.resources.settings_stream_scoring_minimum_off
import nuvio.composeapp.generated.resources.settings_stream_scoring_minimum_title
import nuvio.composeapp.generated.resources.settings_stream_scoring_override_binge_group_desc
import nuvio.composeapp.generated.resources.settings_stream_scoring_override_binge_group_title
import nuvio.composeapp.generated.resources.settings_stream_scoring_question_audio_caption
import nuvio.composeapp.generated.resources.settings_stream_scoring_question_audio_title
import nuvio.composeapp.generated.resources.settings_stream_scoring_question_hdr_caption
import nuvio.composeapp.generated.resources.settings_stream_scoring_question_hdr_title
import nuvio.composeapp.generated.resources.settings_stream_scoring_question_three_d_caption
import nuvio.composeapp.generated.resources.settings_stream_scoring_question_three_d_title
import nuvio.composeapp.generated.resources.settings_stream_scoring_question_size_quality_caption
import nuvio.composeapp.generated.resources.settings_stream_scoring_question_size_quality_title
import nuvio.composeapp.generated.resources.settings_stream_scoring_question_language_caption
import nuvio.composeapp.generated.resources.settings_stream_scoring_question_language_title
import nuvio.composeapp.generated.resources.settings_stream_scoring_question_cached_caption
import nuvio.composeapp.generated.resources.settings_stream_scoring_question_cached_title
import nuvio.composeapp.generated.resources.settings_stream_scoring_question_unknown_group_caption
import nuvio.composeapp.generated.resources.settings_stream_scoring_question_unknown_group_title
import nuvio.composeapp.generated.resources.settings_stream_scoring_question_low_quality_group_caption
import nuvio.composeapp.generated.resources.settings_stream_scoring_question_low_quality_group_title
import nuvio.composeapp.generated.resources.settings_stream_scoring_reset_action
import nuvio.composeapp.generated.resources.settings_stream_scoring_reset_desc
import nuvio.composeapp.generated.resources.settings_stream_scoring_reset_title
import nuvio.composeapp.generated.resources.settings_stream_scoring_preview_empty
import nuvio.composeapp.generated.resources.settings_stream_scoring_preview_title
import nuvio.composeapp.generated.resources.settings_stream_scoring_section_apply
import nuvio.composeapp.generated.resources.settings_stream_scoring_section_setup
import nuvio.composeapp.generated.resources.settings_stream_scoring_show_on_streams_desc
import nuvio.composeapp.generated.resources.settings_stream_scoring_show_on_streams_title
import nuvio.composeapp.generated.resources.settings_stream_scoring_size_band_desc
import nuvio.composeapp.generated.resources.settings_stream_scoring_size_band_title
import nuvio.composeapp.generated.resources.settings_stream_scoring_size_episode_max
import nuvio.composeapp.generated.resources.settings_stream_scoring_size_episode_min
import nuvio.composeapp.generated.resources.settings_stream_scoring_size_movie_max
import nuvio.composeapp.generated.resources.settings_stream_scoring_size_movie_min
import nuvio.composeapp.generated.resources.settings_stream_scoring_sort_list_desc
import nuvio.composeapp.generated.resources.settings_stream_scoring_sort_list_title
import nuvio.composeapp.generated.resources.settings_stream_scoring_merge_sources_title
import nuvio.composeapp.generated.resources.settings_stream_scoring_merge_sources_desc
import nuvio.composeapp.generated.resources.settings_stream_scoring_htpc_tab_title
import nuvio.composeapp.generated.resources.settings_stream_scoring_htpc_tab_desc
import nuvio.composeapp.generated.resources.settings_stream_scoring_test_episode
import nuvio.composeapp.generated.resources.settings_stream_scoring_test_hint
import nuvio.composeapp.generated.resources.settings_stream_scoring_test_label
import nuvio.composeapp.generated.resources.settings_stream_scoring_test_movie
import nuvio.composeapp.generated.resources.settings_stream_scoring_test_no_matches
import nuvio.composeapp.generated.resources.settings_stream_scoring_test_rejected
import nuvio.composeapp.generated.resources.settings_stream_scoring_test_size
import nuvio.composeapp.generated.resources.settings_stream_scoring_test_size_note
import nuvio.composeapp.generated.resources.settings_stream_scoring_test_total
import org.jetbrains.compose.resources.stringResource

/**
 * The Stream scoring settings page.
 *
 * Ordered so the cheap decisions come first: turn it on, answer a few setup questions, and leave.
 * The full trait list sits below for anyone who wants to hand-tune, and the live preview above it
 * means an edit can be judged without leaving the page.
 */
internal fun LazyListScope.streamScoringSection(isTablet: Boolean) {
    item { StreamScoringHeaderGroup(isTablet) }
    item { StreamScoringSetupGroup(isTablet) }
    item { StreamScoringPreviewGroup(isTablet) }
    StreamScoreTraitGroup.entries.forEach { group ->
        item(key = "stream-scoring-${group.name}") { StreamScoringTraitGroup(group, isTablet) }
    }
    item { StreamScoringSizeBandGroup(isTablet) }
}

@Composable
private fun StreamScoringHeaderGroup(isTablet: Boolean) {
    val profile by remember {
        StreamScoreRepository.ensureLoaded()
        StreamScoreRepository.uiState
    }.collectAsStateWithLifecycle()

    SettingsSection(title = stringResource(Res.string.settings_stream_scoring_enabled_title), isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_stream_scoring_enabled_title),
                description = stringResource(Res.string.settings_stream_scoring_enabled_desc),
                checked = profile.enabled,
                isTablet = isTablet,
                onCheckedChange = { enabled -> StreamScoreRepository.update { it.copy(enabled = enabled) } },
            )
            SettingsGroupDivider(isTablet = isTablet)
            MinimumScoreRow(profile, isTablet)
        }
    }

    SettingsSection(title = stringResource(Res.string.settings_stream_scoring_section_apply), isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_stream_scoring_apply_first_stream),
                checked = profile.useForFirstStream,
                enabled = profile.enabled,
                isTablet = isTablet,
                onCheckedChange = { on -> StreamScoreRepository.update { it.copy(useForFirstStream = on) } },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_stream_scoring_override_binge_group_title),
                description = stringResource(Res.string.settings_stream_scoring_override_binge_group_desc),
                checked = profile.overrideBingeGroup,
                enabled = profile.enabled,
                isTablet = isTablet,
                onCheckedChange = { on -> StreamScoreRepository.update { it.copy(overrideBingeGroup = on) } },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_stream_scoring_apply_auto_download),
                checked = profile.useForAutoDownload,
                enabled = profile.enabled,
                isTablet = isTablet,
                onCheckedChange = { on -> StreamScoreRepository.update { it.copy(useForAutoDownload = on) } },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_stream_scoring_apply_failover),
                checked = profile.useForFailover,
                enabled = profile.enabled,
                isTablet = isTablet,
                onCheckedChange = { on -> StreamScoreRepository.update { it.copy(useForFailover = on) } },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_stream_scoring_sort_list_title),
                description = stringResource(Res.string.settings_stream_scoring_sort_list_desc),
                checked = profile.sortStreamList,
                enabled = profile.enabled,
                isTablet = isTablet,
                onCheckedChange = { on -> StreamScoreRepository.update { it.copy(sortStreamList = on) } },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_stream_scoring_merge_sources_title),
                description = stringResource(Res.string.settings_stream_scoring_merge_sources_desc),
                checked = profile.mergeSources,
                enabled = profile.enabled && profile.sortStreamList,
                isTablet = isTablet,
                onCheckedChange = { on -> StreamScoreRepository.update { it.copy(mergeSources = on) } },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_stream_scoring_htpc_tab_title),
                description = stringResource(Res.string.settings_stream_scoring_htpc_tab_desc),
                checked = profile.htpcSourceTab,
                // Deliberately not gated on sortStreamList: the whole point is an extra ranked list
                // for people who leave every addon's own ordering alone.
                enabled = profile.enabled,
                isTablet = isTablet,
                onCheckedChange = { on -> StreamScoreRepository.update { it.copy(htpcSourceTab = on) } },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_stream_scoring_show_on_streams_title),
                description = stringResource(Res.string.settings_stream_scoring_show_on_streams_desc),
                checked = profile.showScoreOnStreams,
                enabled = profile.enabled,
                isTablet = isTablet,
                onCheckedChange = { on -> StreamScoreRepository.update { it.copy(showScoreOnStreams = on) } },
            )
        }
    }
}

/**
 * The setup questionnaire — a handful of dropdowns that each set the defaults for one slice of the
 * scoring model. Answering one only rewrites that question's traits, so the questions compose freely
 * and never clobber each other (or a manual edit made in the trait list below).
 */
@Composable
private fun StreamScoringSetupGroup(isTablet: Boolean) {
    val profile by StreamScoreRepository.uiState.collectAsStateWithLifecycle()
    val answers = profile.answers
    val enabled = profile.enabled

    SettingsSection(title = stringResource(Res.string.settings_stream_scoring_section_setup), isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            QuestionRow(
                title = stringResource(Res.string.settings_stream_scoring_question_audio_title),
                caption = stringResource(Res.string.settings_stream_scoring_question_audio_caption),
                options = AudioDeviceSupport.entries,
                selected = answers.audioDevice,
                enabled = enabled,
                isTablet = isTablet,
            ) { choice -> StreamScoreRepository.update { it.withAudioDevice(choice) } }
            SettingsGroupDivider(isTablet = isTablet)
            QuestionRow(
                title = stringResource(Res.string.settings_stream_scoring_question_hdr_title),
                caption = stringResource(Res.string.settings_stream_scoring_question_hdr_caption),
                options = HdrPreference.entries,
                selected = answers.hdr,
                enabled = enabled,
                isTablet = isTablet,
            ) { choice -> StreamScoreRepository.update { it.withHdrPreference(choice) } }
            SettingsGroupDivider(isTablet = isTablet)
            QuestionRow(
                title = stringResource(Res.string.settings_stream_scoring_question_three_d_title),
                caption = stringResource(Res.string.settings_stream_scoring_question_three_d_caption),
                options = ThreeDEquipment.entries,
                selected = answers.threeD,
                enabled = enabled,
                isTablet = isTablet,
            ) { choice -> StreamScoreRepository.update { it.withThreeDEquipment(choice) } }
            SettingsGroupDivider(isTablet = isTablet)
            QuestionRow(
                title = stringResource(Res.string.settings_stream_scoring_question_size_quality_title),
                caption = stringResource(Res.string.settings_stream_scoring_question_size_quality_caption),
                options = SizeQualityPreference.entries,
                selected = answers.sizeQuality,
                enabled = enabled,
                isTablet = isTablet,
            ) { choice -> StreamScoreRepository.update { it.withSizeQuality(choice) } }
            SettingsGroupDivider(isTablet = isTablet)
            QuestionRow(
                title = stringResource(Res.string.settings_stream_scoring_question_language_title),
                caption = stringResource(Res.string.settings_stream_scoring_question_language_caption),
                options = LanguageImportance.entries,
                selected = answers.language,
                enabled = enabled,
                isTablet = isTablet,
            ) { choice -> StreamScoreRepository.update { it.withLanguageImportance(choice) } }
            SettingsGroupDivider(isTablet = isTablet)
            QuestionRow(
                title = stringResource(Res.string.settings_stream_scoring_question_cached_title),
                caption = stringResource(Res.string.settings_stream_scoring_question_cached_caption),
                options = DebridCachedBoost.entries,
                selected = answers.debridCached,
                enabled = enabled,
                isTablet = isTablet,
            ) { choice -> StreamScoreRepository.update { it.withDebridCachedBoost(choice) } }
            SettingsGroupDivider(isTablet = isTablet)
            QuestionRow(
                title = stringResource(Res.string.settings_stream_scoring_question_unknown_group_title),
                caption = stringResource(Res.string.settings_stream_scoring_question_unknown_group_caption),
                options = UnknownGroupTrust.entries,
                selected = answers.unknownGroup,
                enabled = enabled,
                isTablet = isTablet,
            ) { choice -> StreamScoreRepository.update { it.withUnknownGroupTrust(choice) } }
            SettingsGroupDivider(isTablet = isTablet)
            QuestionRow(
                title = stringResource(Res.string.settings_stream_scoring_question_low_quality_group_title),
                caption = stringResource(Res.string.settings_stream_scoring_question_low_quality_group_caption),
                options = LowQualityGroupPenalty.entries,
                selected = answers.lowQualityGroup,
                enabled = enabled,
                isTablet = isTablet,
            ) { choice -> StreamScoreRepository.update { it.withLowQualityPenalty(choice) } }
            SettingsGroupDivider(isTablet = isTablet)
            ResetToPresetRow(enabled = enabled, isTablet = isTablet)
        }
    }
}

/**
 * Rebuilds every score from the answers above, discarding hand-tuned values. Sits at the bottom of
 * setup because that is where you land after answering — and it is the way back if the trait list
 * below has been edited into a state you no longer want.
 */
@Composable
private fun ResetToPresetRow(enabled: Boolean, isTablet: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) {
                StreamScoreRepository.update { it.resetPointsToAnswers() }
            }
            .padding(horizontal = 16.dp, vertical = if (isTablet) 16.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f).padding(end = SettingsRowTextGap),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(Res.string.settings_stream_scoring_reset_title),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(Res.string.settings_stream_scoring_reset_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(Res.string.settings_stream_scoring_reset_action),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun <T : ScoreAnswerOption> QuestionRow(
    title: String,
    caption: String,
    options: List<T>,
    selected: T,
    enabled: Boolean,
    isTablet: Boolean,
    onSelect: (T) -> Unit,
) {
    SettingsDropdownChoiceRow(
        title = title,
        description = caption,
        options = options.map { SettingsChoiceOption(it, stringResource(it.labelRes)) },
        selectedValue = selected,
        enabled = enabled,
        isTablet = isTablet,
        onSelected = onSelect,
    )
}

@Composable
private fun MinimumScoreRow(profile: StreamScoreProfile, isTablet: Boolean) {
    val minimum = profile.minimumScore
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = stringResource(Res.string.settings_stream_scoring_minimum_title),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(Res.string.settings_stream_scoring_minimum_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PointsStepper(
                value = minimum ?: 0,
                enabled = profile.enabled && minimum != null,
                onChange = { next -> StreamScoreRepository.update { it.copy(minimumScore = next) } },
            )
            FilterChip(
                selected = minimum == null,
                enabled = profile.enabled,
                onClick = {
                    StreamScoreRepository.update {
                        it.copy(minimumScore = if (minimum == null) 0 else null)
                    }
                },
                label = { Text(stringResource(Res.string.settings_stream_scoring_minimum_off)) },
            )
        }
    }
}

@Composable
private fun StreamScoringTraitGroup(group: StreamScoreTraitGroup, isTablet: Boolean) {
    val profile by StreamScoreRepository.uiState.collectAsStateWithLifecycle()
    val traits = remember(group) { StreamScoreTrait.inGroup(group) }
    if (traits.isEmpty()) return

    SettingsSection(title = group.label(), isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            traits.forEachIndexed { index, trait ->
                if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                TraitRow(trait = trait, profile = profile)
            }
        }
    }
}

@Composable
private fun TraitRow(trait: StreamScoreTrait, profile: StreamScoreProfile) {
    val points = profile.pointsFor(trait)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = stringResource(trait.labelRes),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            trait.captionRes?.let {
                Text(
                    text = stringResource(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        PointsStepper(
            value = points,
            enabled = profile.enabled,
            onChange = { next -> StreamScoreRepository.setPoints(trait, next) },
        )
    }
}

/**
 * `−  [ +40 ]  +` in steps of 5. The value is tinted by sign so a column of rows reads as
 * "what I want" versus "what I don't" without parsing every number.
 */
@Composable
private fun PointsStepper(
    value: Int,
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    NumberStepper(
        value = value.toDouble(),
        step = STEP.toDouble(),
        min = StreamScoreProfile.MIN_POINTS.toDouble(),
        max = StreamScoreProfile.MAX_POINTS.toDouble(),
        enabled = enabled,
        // Signed on display so the sign is obvious, but unsigned while editing — nobody wants to
        // type a leading "+".
        format = { if (it > 0) "+${it.toInt()}" else it.toInt().toString() },
        editFormat = { it.toInt().toString() },
        valueColor = when {
            !enabled -> tokens.colors.textDisabled
            value > 0 -> tokens.colors.accent
            value < 0 -> MaterialTheme.colorScheme.error
            else -> tokens.colors.textMuted
        },
        onChange = { onChange(it.toInt()) },
    )
}

/**
 * The shared `−  [ value ]  +` control.
 *
 * Clicking the number turns it into an inline field so a value far outside step range can simply be
 * typed — stepping to 500 in fives is not a real option. Enter or clicking away commits, Escape
 * reverts. The field is deliberately styled identically to the static text, so the control looks the
 * same whether or not it is being edited.
 */
@Composable
private fun NumberStepper(
    value: Double,
    step: Double,
    min: Double,
    max: Double,
    enabled: Boolean,
    format: (Double) -> String,
    valueColor: Color,
    onChange: (Double) -> Unit,
    editFormat: (Double) -> String = format,
    fieldWidth: Dp = 58.dp,
) {
    var editing by remember { mutableStateOf(false) }
    // TextFieldValue rather than String so the whole number can start out selected — typing then
    // replaces it, which is what you want when swapping 40 for 500. A plain String field would put
    // the caret at one end and force the user to clear it first.
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    // A newly composed text field reports "not focused" once, before requestFocus() has had a
    // chance to run. Without this latch that first report is read as "focus lost" and immediately
    // ends the edit — the field appears for a single frame and closes again.
    var hasGainedFocus by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    fun commit() {
        draft.text.trim().replace(",", ".").toDoubleOrNull()
            ?.coerceIn(min, max)
            ?.let(onChange)
        editing = false
    }

    fun startEditing() {
        val text = editFormat(value)
        draft = TextFieldValue(text = text, selection = TextRange(0, text.length))
        hasGainedFocus = false
        editing = true
    }

    LaunchedEffect(editing) {
        if (editing) runCatching { focusRequester.requestFocus() }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepperButton(Icons.Rounded.Remove, enabled && !editing) {
            onChange((value - step).coerceIn(min, max))
        }
        Box(
            modifier = Modifier.width(fieldWidth),
            contentAlignment = Alignment.Center,
        ) {
            val textStyle = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                color = valueColor,
            )
            if (editing) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    enabled = enabled,
                    singleLine = true,
                    textStyle = textStyle,
                    cursorBrush = SolidColor(valueColor),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commit(); focusManager.clearFocus() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        // Clicking away is a commit, not a cancel — losing an edit because the
                        // pointer moved would be worse than accepting what was typed. Only counts
                        // once focus has actually arrived; see hasGainedFocus.
                        .onFocusChanged { state ->
                            if (state.isFocused) {
                                hasGainedFocus = true
                            } else if (hasGainedFocus && editing) {
                                commit()
                            }
                        }
                        .onPreviewKeyEvent { event ->
                            when {
                                event.type != KeyEventType.KeyDown -> false
                                event.key == Key.Escape -> {
                                    editing = false
                                    focusManager.clearFocus()
                                    true
                                }
                                event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                                    commit()
                                    focusManager.clearFocus()
                                    true
                                }
                                else -> false
                            }
                        }
                        // Keeps app-wide keyboard shortcuts from firing on the digits being typed.
                        .trackTextInputFocus(),
                )
            } else {
                Text(
                    text = format(value),
                    style = textStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled) { startEditing() },
                )
            }
        }
        StepperButton(Icons.Rounded.Add, enabled && !editing) {
            onChange((value + step).coerceIn(min, max))
        }
    }
}

@Composable
private fun StepperButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(tokens.colors.surfaceCard)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) tokens.colors.textPrimary else tokens.colors.textDisabled,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * Scores real streams against the current profile, plus a scratch field for testing an arbitrary
 * release name.
 *
 * The point is that an edit can be judged immediately rather than by playing something and hoping.
 * The test bench matters most for the size rules: a bitrate floor is hard to trust without being
 * able to poke at it, so the size and movie/episode inputs are there to make it falsifiable.
 */
@Composable
private fun StreamScoringPreviewGroup(isTablet: Boolean) {
    val profile by StreamScoreRepository.uiState.collectAsStateWithLifecycle()
    val streamsState by StreamsRepository.uiState.collectAsStateWithLifecycle()
    // Prefer whatever the user last actually looked at; fall back to representative examples so the
    // page explains itself on a first visit instead of showing an empty box.
    val sample = remember(streamsState.groups) {
        streamsState.groups.flatMap { it.streams }.ifEmpty { PREVIEW_EXAMPLES }
    }
    // Must match the content type the sample actually came from. Scoring real episode streams as if
    // they were films measures them against the movie size band, which marks every normal episode
    // "far from preferred size" — the preview would then contradict the picker it is previewing.
    // The fallback examples are films, so they score as films.
    val sampleIsEpisode by StreamsRepository.isEpisodeRequest.collectAsStateWithLifecycle()
    val previewIsEpisode = sampleIsEpisode && streamsState.groups.isNotEmpty()
    val scored = remember(profile, sample, previewIsEpisode) {
        StreamScorer.rankWithScores(
            sample,
            profile,
            StreamScoreContexts.forPlayback(isEpisode = previewIsEpisode),
        ).take(PREVIEW_LIMIT)
    }

    var testName by rememberSaveable { mutableStateOf("") }
    var testSizeGb by rememberSaveable { mutableStateOf(0.0) }
    var testIsEpisode by rememberSaveable { mutableStateOf(false) }
    val testResult = remember(profile, testName, testSizeGb, testIsEpisode) {
        testName.trim().takeIf { it.isNotBlank() }?.let { name ->
            val sizeBytes = testSizeGb
                .takeIf { it > 0.0 }
                ?.let { (it * StreamSizeBand.BYTES_PER_GB).toLong() }
            val probe = StreamItem(
                name = name,
                addonName = "Test",
                addonId = "test",
                url = "http://example.invalid/test",
                behaviorHints = StreamBehaviorHints(videoSize = sizeBytes, filename = name),
            )
            probe to StreamScorer.score(
                probe,
                profile,
                StreamScoreContexts.forPlayback(isEpisode = testIsEpisode),
            )
        }
    }

    SettingsSection(title = stringResource(Res.string.settings_stream_scoring_preview_title), isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            ScoreTestBench(
                name = testName,
                sizeGb = testSizeGb,
                isEpisode = testIsEpisode,
                enabled = profile.enabled,
                result = testResult?.second,
                onNameChange = { testName = it },
                onSizeChange = { testSizeGb = it },
                onIsEpisodeChange = { testIsEpisode = it },
            )
            SettingsGroupDivider(isTablet = isTablet)
            if (scored.isEmpty()) {
                Text(
                    text = stringResource(Res.string.settings_stream_scoring_preview_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                )
            } else {
                scored.forEachIndexed { index, (stream, score) ->
                    if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = stream.streamLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                // stringResource is @Composable, so it cannot run inside
                                // joinToString's non-inline lambda — resolve via map (inline) first.
                                text = score.components
                                    .sortedByDescending { kotlin.math.abs(it.points) }
                                    .map { component ->
                                        "${stringResource(component.trait.labelRes)} ${component.points.signed()}"
                                    }
                                    .joinToString("  ")
                                    .ifBlank { "—" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = score.total.signed(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (score.total >= 0) {
                                MaterialTheme.nuvio.colors.accent
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Scratch pad: paste a release name (and optionally its size) and see exactly what it scores.
 *
 * Size is a separate input because a filename carries no byte count, and without one the two size
 * traits — the implausibility floor and the preferred band — can never fire, which would make them
 * look broken. The movie/episode switch is needed for the same reason: both size rules use different
 * thresholds per content type.
 */
@Composable
private fun ScoreTestBench(
    name: String,
    sizeGb: Double,
    isEpisode: Boolean,
    enabled: Boolean,
    result: StreamScore?,
    onNameChange: (String) -> Unit,
    onSizeChange: (Double) -> Unit,
    onIsEpisodeChange: (Boolean) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            enabled = enabled,
            singleLine = true,
            label = { Text(stringResource(Res.string.settings_stream_scoring_test_label)) },
            placeholder = { Text(stringResource(Res.string.settings_stream_scoring_test_hint)) },
            modifier = Modifier.fillMaxWidth().trackTextInputFocus(),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.settings_stream_scoring_test_size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.nuvio.colors.textSecondary,
            )
            GigabyteStepper(
                value = sizeGb,
                enabled = enabled,
                onChange = onSizeChange,
            )
            FilterChip(
                selected = !isEpisode,
                enabled = enabled,
                onClick = { onIsEpisodeChange(false) },
                label = { Text(stringResource(Res.string.settings_stream_scoring_test_movie)) },
            )
            FilterChip(
                selected = isEpisode,
                enabled = enabled,
                onClick = { onIsEpisodeChange(true) },
                label = { Text(stringResource(Res.string.settings_stream_scoring_test_episode)) },
            )
        }

        if (result != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(tokens.colors.surfaceCard)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (result.rejected) {
                            stringResource(Res.string.settings_stream_scoring_test_rejected)
                        } else {
                            stringResource(Res.string.settings_stream_scoring_test_total)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (result.rejected) {
                            MaterialTheme.colorScheme.error
                        } else {
                            tokens.colors.textSecondary
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = result.total.signed(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            result.rejected -> MaterialTheme.colorScheme.error
                            result.total >= 0 -> tokens.colors.accent
                            else -> MaterialTheme.colorScheme.error
                        },
                    )
                }
                if (result.components.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.settings_stream_scoring_test_no_matches),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                    )
                }
                // Every component, not a truncated list: the whole point of the bench is seeing
                // exactly what did and did not fire.
                result.components
                    .sortedByDescending { kotlin.math.abs(it.points) }
                    .forEach { component ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(component.trait.labelRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = component.points.signed(),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = if (component.points >= 0) {
                                    tokens.colors.textSecondary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                    }
                if (sizeGb <= 0.0) {
                    Text(
                        text = stringResource(Res.string.settings_stream_scoring_test_size_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamScoringSizeBandGroup(isTablet: Boolean) {
    val profile by StreamScoreRepository.uiState.collectAsStateWithLifecycle()
    val band = profile.sizeBand

    SettingsSection(title = stringResource(Res.string.settings_stream_scoring_size_band_title), isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_stream_scoring_size_band_title),
                description = stringResource(Res.string.settings_stream_scoring_size_band_desc),
                checked = band.enabled,
                enabled = profile.enabled,
                isTablet = isTablet,
                onCheckedChange = { on ->
                    StreamScoreRepository.update { it.copy(sizeBand = it.sizeBand.copy(enabled = on)) }
                },
            )
            if (band.enabled) {
                SettingsGroupDivider(isTablet = isTablet)
                GigabyteRow(
                    title = stringResource(Res.string.settings_stream_scoring_size_movie_min),
                    value = band.movieMinGb,
                    enabled = profile.enabled,
                ) { next -> StreamScoreRepository.update { it.copy(sizeBand = it.sizeBand.copy(movieMinGb = next)) } }
                SettingsGroupDivider(isTablet = isTablet)
                GigabyteRow(
                    title = stringResource(Res.string.settings_stream_scoring_size_movie_max),
                    value = band.movieMaxGb,
                    enabled = profile.enabled,
                ) { next -> StreamScoreRepository.update { it.copy(sizeBand = it.sizeBand.copy(movieMaxGb = next)) } }
                SettingsGroupDivider(isTablet = isTablet)
                GigabyteRow(
                    title = stringResource(Res.string.settings_stream_scoring_size_episode_min),
                    value = band.episodeMinGb,
                    enabled = profile.enabled,
                ) { next -> StreamScoreRepository.update { it.copy(sizeBand = it.sizeBand.copy(episodeMinGb = next)) } }
                SettingsGroupDivider(isTablet = isTablet)
                GigabyteRow(
                    title = stringResource(Res.string.settings_stream_scoring_size_episode_max),
                    value = band.episodeMaxGb,
                    enabled = profile.enabled,
                ) { next -> StreamScoreRepository.update { it.copy(sizeBand = it.sizeBand.copy(episodeMaxGb = next)) } }
            }
        }
    }
}

@Composable
private fun GigabyteRow(
    title: String,
    value: Double,
    enabled: Boolean,
    onChange: (Double) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        GigabyteStepper(value = value, enabled = enabled, onChange = onChange)
    }
}

/**
 * The same stepper the points rows use, in gigabytes. Steps by 1 GB, which suits the common case;
 * anything finer (or a 200 GB ceiling) is a click on the number away.
 */
@Composable
private fun GigabyteStepper(
    value: Double,
    enabled: Boolean,
    onChange: (Double) -> Unit,
) {
    NumberStepper(
        value = value,
        step = 1.0,
        min = 0.0,
        max = MAX_SIZE_GB,
        enabled = enabled,
        format = { "${formatGb(it)} GB" },
        editFormat = ::formatGb,
        valueColor = if (enabled) MaterialTheme.nuvio.colors.textPrimary else MaterialTheme.nuvio.colors.textDisabled,
        fieldWidth = 78.dp,
        onChange = onChange,
    )
}

private fun formatGb(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

private fun Int.signed(): String = if (this > 0) "+$this" else toString()

@Composable
private fun StreamScoreTraitGroup.label(): String = when (this) {
    StreamScoreTraitGroup.QUALITY -> stringResource(Res.string.settings_stream_scoring_group_quality)
    StreamScoreTraitGroup.HDR -> stringResource(Res.string.settings_stream_scoring_group_hdr)
    StreamScoreTraitGroup.AUDIO -> stringResource(Res.string.settings_stream_scoring_group_audio)
    StreamScoreTraitGroup.CHANNELS -> stringResource(Res.string.settings_stream_scoring_group_channels)
    StreamScoreTraitGroup.CODEC -> stringResource(Res.string.settings_stream_scoring_group_codec)
    StreamScoreTraitGroup.RELEASE -> stringResource(Res.string.settings_stream_scoring_group_release)
    StreamScoreTraitGroup.RELEASE_FLAGS -> stringResource(Res.string.settings_stream_scoring_group_release_flags)
    StreamScoreTraitGroup.EDITION -> stringResource(Res.string.settings_stream_scoring_group_edition)
    StreamScoreTraitGroup.LANGUAGE -> stringResource(Res.string.settings_stream_scoring_group_language)
    StreamScoreTraitGroup.AVAILABILITY -> stringResource(Res.string.settings_stream_scoring_group_availability)
    StreamScoreTraitGroup.SIZE -> stringResource(Res.string.settings_stream_scoring_group_size)
}

private const val STEP = 5
private const val MAX_SIZE_GB = 500.0
private const val PREVIEW_LIMIT = 5

/**
 * Stand-in releases for the preview before the user has opened any title this session. Written as
 * realistic release names so the trait detector classifies them exactly as it would a real result.
 */
private val PREVIEW_EXAMPLES: List<StreamItem> = listOf(
    "Example.Film.2024.2160p.UHD.BluRay.REMUX.DV.HDR10.TrueHD.Atmos.7.1-FraMeSToR",
    "Example.Film.2024.2160p.WEB-DL.DDP5.1.Atmos.HDR.HEVC-NTb",
    "Example.Film.2024.1080p.BluRay.x264.DTS-HD.MA.5.1-CMRG",
    "Example.Film.2024.1080p.WEBRip.x265.AAC-YTS.MX",
    "Example.Film.2024.720p.HDTV.x264-JUNK",
).map { name ->
    StreamItem(name = name, addonName = "Example", addonId = "example", url = "http://example.invalid/$name")
}
