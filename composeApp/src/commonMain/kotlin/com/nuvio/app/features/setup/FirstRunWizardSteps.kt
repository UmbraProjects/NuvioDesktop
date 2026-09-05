package com.nuvio.app.features.setup

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.home.HomeDisplayMode
import com.nuvio.app.features.home.HomeTvRowDotsAnchor
import com.nuvio.app.features.settings.ApiKeySetupContent
import com.nuvio.app.features.settings.ApiKeySetupStyle
import com.nuvio.app.features.settings.SettingsChoiceOption
import com.nuvio.app.features.settings.SettingsChoiceRow
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsModernSlider
import com.nuvio.app.features.settings.SettingsSwitchRow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_home_backdrop_vertical_position
import nuvio.composeapp.generated.resources.settings_home_backdrop_vertical_position_description
import nuvio.composeapp.generated.resources.settings_home_display_mode_adaptive
import nuvio.composeapp.generated.resources.settings_home_display_mode_adaptive_ambient
import nuvio.composeapp.generated.resources.settings_home_display_mode_adaptive_ambient_description
import nuvio.composeapp.generated.resources.settings_home_display_mode_adaptive_description
import nuvio.composeapp.generated.resources.settings_home_display_mode_basic
import nuvio.composeapp.generated.resources.settings_home_display_mode_basic_description
import nuvio.composeapp.generated.resources.settings_home_display_mode_tv
import nuvio.composeapp.generated.resources.settings_home_display_mode_tv_description
import nuvio.composeapp.generated.resources.settings_home_hero_height
import nuvio.composeapp.generated.resources.settings_home_hero_height_description
import nuvio.composeapp.generated.resources.settings_home_row_jump_dot_position
import nuvio.composeapp.generated.resources.settings_home_row_jump_dot_position_backdrop
import nuvio.composeapp.generated.resources.settings_home_row_jump_dot_position_backdrop_description
import nuvio.composeapp.generated.resources.settings_home_row_jump_dot_position_row
import nuvio.composeapp.generated.resources.settings_home_row_jump_dot_position_row_description
import nuvio.composeapp.generated.resources.settings_home_row_jump_dots
import nuvio.composeapp.generated.resources.settings_home_row_jump_dots_description
import nuvio.composeapp.generated.resources.settings_home_smooth_scrolling
import nuvio.composeapp.generated.resources.settings_home_smooth_scrolling_description
import nuvio.composeapp.generated.resources.settings_home_tv_full_backdrop
import nuvio.composeapp.generated.resources.settings_home_tv_full_backdrop_description
import nuvio.composeapp.generated.resources.settings_meta_hero_trailer_manual
import nuvio.composeapp.generated.resources.settings_meta_hero_trailer_playback_area_fullscreen
import nuvio.composeapp.generated.resources.settings_meta_hero_trailer_playback_area_hero
import nuvio.composeapp.generated.resources.settings_playback_hero_tv_trailer
import nuvio.composeapp.generated.resources.settings_playback_hero_tv_trailer_description
import nuvio.composeapp.generated.resources.settings_playback_hero_tv_trailer_sound
import nuvio.composeapp.generated.resources.settings_playback_hero_tv_trailer_sound_description
import nuvio.composeapp.generated.resources.setup_mode_adaptive
import nuvio.composeapp.generated.resources.setup_mode_adaptive_ambient
import nuvio.composeapp.generated.resources.setup_mode_basic
import nuvio.composeapp.generated.resources.setup_mode_tv
import nuvio.composeapp.generated.resources.setup_wizard_experience_subtitle
import nuvio.composeapp.generated.resources.setup_wizard_experience_title
import nuvio.composeapp.generated.resources.setup_wizard_experience_tv_badge
import nuvio.composeapp.generated.resources.setup_wizard_keys_subtitle
import nuvio.composeapp.generated.resources.setup_wizard_keys_title
import nuvio.composeapp.generated.resources.setup_wizard_mode_preview_close
import nuvio.composeapp.generated.resources.setup_wizard_mode_preview_hint
import nuvio.composeapp.generated.resources.setup_wizard_shortcuts_after_finish
import nuvio.composeapp.generated.resources.setup_wizard_summary_integrations
import nuvio.composeapp.generated.resources.setup_wizard_summary_mode
import nuvio.composeapp.generated.resources.setup_wizard_summary_none
import nuvio.composeapp.generated.resources.setup_wizard_summary_title
import nuvio.composeapp.generated.resources.setup_wizard_trailers_area
import nuvio.composeapp.generated.resources.setup_wizard_trailers_start
import nuvio.composeapp.generated.resources.setup_wizard_trailers_start_auto
import nuvio.composeapp.generated.resources.setup_wizard_trailers_title
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import com.nuvio.app.core.ui.accentBrush

private const val AUTOPLAY_TRAILER_DELAY_SECONDS = 5

@Composable
internal fun WizardStepHeader(title: String, subtitle: String) {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textSecondary,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Step 1 — display mode, its tuning, and trailers
// ---------------------------------------------------------------------------------------------

@Composable
internal fun WizardExperienceStep(
    draft: FirstRunWizardDraft,
    onDraftChange: ((FirstRunWizardDraft) -> FirstRunWizardDraft) -> Unit,
    compact: Boolean,
    onExpandPreview: (HomeDisplayMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s20)) {
        WizardStepHeader(
            title = stringResource(Res.string.setup_wizard_experience_title),
            subtitle = stringResource(Res.string.setup_wizard_experience_subtitle),
        )

        val modes = HomeDisplayMode.entries
        val rows = if (compact) modes.chunked(2) else listOf(modes)
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12)) {
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
                ) {
                    row.forEach { mode ->
                        DisplayModeCard(
                            mode = mode,
                            selected = draft.displayMode == mode,
                            onSelect = { onDraftChange { it.copy(displayMode = mode) } },
                            onExpandPreview = { onExpandPreview(mode) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        ModeTuning(draft = draft, onDraftChange = onDraftChange)
        // Basic has no hero for a trailer to play in, so offering the settings there would be
        // advertising something that cannot happen.
        if (draft.displayMode != HomeDisplayMode.Basic) {
            TrailerSettings(draft = draft, onDraftChange = onDraftChange)
        }
    }
}

/**
 * Bundled captures of each mode.
 *
 * Deliberately real screenshots rather than a drawn schematic: the shape of each mode has not
 * meaningfully changed in many releases, so a slightly dated capture still communicates far more
 * than a diagram. They are thumbnails in the cards and open full size on click.
 */
internal fun HomeDisplayMode.screenshot(): DrawableResource = when (this) {
    HomeDisplayMode.Basic -> Res.drawable.setup_mode_basic
    HomeDisplayMode.Adaptive -> Res.drawable.setup_mode_adaptive
    HomeDisplayMode.AdaptiveAmbient -> Res.drawable.setup_mode_adaptive_ambient
    HomeDisplayMode.TvMode -> Res.drawable.setup_mode_tv
}

@Composable
private fun DisplayModeCard(
    mode: HomeDisplayMode,
    selected: Boolean,
    onSelect: () -> Unit,
    onExpandPreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val borderColor by animateColorAsState(
        if (selected) tokens.colors.accent else tokens.colors.borderSubtle,
        label = "wizard_mode_card_border",
    )
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(NuvioTokens.Radius.lg))
            .background(tokens.colors.surface)
            .border(
                BorderStroke(if (selected) 2.dp else tokens.borders.hairline, borderColor),
                RoundedCornerShape(NuvioTokens.Radius.lg),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onSelect,
            )
            .padding(NuvioTokens.Space.s10),
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
    ) {
        // No clip and no rounding here: the captures include the app window's own rounded border,
        // so clipping them to a different radius sliced the corners off that border. Fit rather
        // than Crop for the same reason — the captures are not exactly 16:9, and cropping ate the
        // edge. The box keeps a fixed aspect so all four cards stay the same height.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onExpandPreview,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(mode.screenshot()),
                contentDescription = mode.label(),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            text = stringResource(Res.string.setup_wizard_mode_preview_hint),
            style = MaterialTheme.typography.labelSmall,
            color = tokens.colors.textMuted,
        )
        Text(
            text = mode.label(),
            style = MaterialTheme.typography.titleSmall,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        // Fixed at two lines, and the badge slot is reserved on every card, so the four cards are
        // the same height whatever their copy does.
        Text(
            text = mode.description(),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textSecondary,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Box(modifier = Modifier.height(NuvioTokens.Space.s16)) {
            if (mode == HomeDisplayMode.TvMode) {
                Text(
                    text = stringResource(Res.string.setup_wizard_experience_tv_badge),
                    style = MaterialTheme.typography.labelSmall.accentBrush(),
                    color = tokens.colors.accent,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun ModePreviewLightbox(mode: HomeDisplayMode, onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.88f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(NuvioTokens.Space.s32),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        ) {
            Image(
                painter = painterResource(mode.screenshot()),
                contentDescription = mode.label(),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .widthIn(max = 1600.dp),
            )
            Text(
                text = mode.label(),
                style = MaterialTheme.typography.titleMedium,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(Res.string.setup_wizard_mode_preview_close),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
        }
    }
}

@Composable
private fun ModeTuning(
    draft: FirstRunWizardDraft,
    onDraftChange: ((FirstRunWizardDraft) -> FirstRunWizardDraft) -> Unit,
) {
    // The real settings rows, so a first-run user meets the same labels, subtext and controls they
    // will see again in Settings — and gets an explanation of what "full backdrop" actually means.
    SettingsGroup(isTablet = true) {
        when (draft.displayMode) {
            HomeDisplayMode.Adaptive, HomeDisplayMode.AdaptiveAmbient -> {
                WizardSliderRow(
                    title = stringResource(Res.string.settings_home_backdrop_vertical_position),
                    description = stringResource(Res.string.settings_home_backdrop_vertical_position_description),
                    value = draft.adaptiveHeroVerticalBias,
                    valueRange = -1f..1f,
                    onValueChange = { value -> onDraftChange { it.copy(adaptiveHeroVerticalBias = value) } },
                )
                SettingsGroupDivider(isTablet = true)
                WizardSliderRow(
                    title = stringResource(Res.string.settings_home_hero_height),
                    description = stringResource(Res.string.settings_home_hero_height_description),
                    value = draft.adaptiveHeroHeightMultiplier,
                    valueRange = 0.75f..1.75f,
                    onValueChange = { value -> onDraftChange { it.copy(adaptiveHeroHeightMultiplier = value) } },
                )
                SettingsGroupDivider(isTablet = true)
                SmoothScrollingRow(draft, onDraftChange)
            }

            HomeDisplayMode.Basic -> SmoothScrollingRow(draft, onDraftChange)

            HomeDisplayMode.TvMode -> {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_home_tv_full_backdrop),
                    description = stringResource(Res.string.settings_home_tv_full_backdrop_description),
                    checked = draft.tvFullBackdropEnabled,
                    isTablet = true,
                    onCheckedChange = { value -> onDraftChange { it.copy(tvFullBackdropEnabled = value) } },
                )
                SettingsGroupDivider(isTablet = true)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_home_row_jump_dots),
                    description = stringResource(Res.string.settings_home_row_jump_dots_description),
                    checked = draft.tvRowDotsEnabled,
                    isTablet = true,
                    onCheckedChange = { value -> onDraftChange { it.copy(tvRowDotsEnabled = value) } },
                )
                if (draft.tvRowDotsEnabled) {
                    SettingsGroupDivider(isTablet = true)
                    SettingsChoiceRow(
                        title = stringResource(Res.string.settings_home_row_jump_dot_position),
                        description = draft.tvRowDotsAnchor.dotPositionDescription(),
                        options = listOf(
                            SettingsChoiceOption(
                                HomeTvRowDotsAnchor.RowTitle,
                                stringResource(Res.string.settings_home_row_jump_dot_position_row),
                            ),
                            SettingsChoiceOption(
                                HomeTvRowDotsAnchor.HeroBackdrop,
                                stringResource(Res.string.settings_home_row_jump_dot_position_backdrop),
                            ),
                        ),
                        selectedValue = draft.tvRowDotsAnchor,
                        isTablet = true,
                        onSelected = { value -> onDraftChange { it.copy(tvRowDotsAnchor = value) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun SmoothScrollingRow(
    draft: FirstRunWizardDraft,
    onDraftChange: ((FirstRunWizardDraft) -> FirstRunWizardDraft) -> Unit,
) {
    SettingsSwitchRow(
        title = stringResource(Res.string.settings_home_smooth_scrolling),
        description = stringResource(Res.string.settings_home_smooth_scrolling_description),
        checked = draft.smoothScrollingEnabled,
        isTablet = true,
        onCheckedChange = { value -> onDraftChange { it.copy(smoothScrollingEnabled = value) } },
    )
}

@Composable
private fun TrailerSettings(
    draft: FirstRunWizardDraft,
    onDraftChange: ((FirstRunWizardDraft) -> FirstRunWizardDraft) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12)) {
        Text(
            text = stringResource(Res.string.setup_wizard_trailers_title),
            style = MaterialTheme.typography.titleMedium,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        SettingsGroup(isTablet = true) {
            SettingsChoiceRow(
                title = stringResource(Res.string.setup_wizard_trailers_area),
                description = stringResource(Res.string.settings_playback_hero_tv_trailer_description),
                options = listOf(
                    SettingsChoiceOption(
                        false,
                        stringResource(Res.string.settings_meta_hero_trailer_playback_area_hero),
                    ),
                    SettingsChoiceOption(
                        true,
                        stringResource(Res.string.settings_meta_hero_trailer_playback_area_fullscreen),
                    ),
                ),
                selectedValue = draft.trailerFullscreen,
                isTablet = true,
                onSelected = { value -> onDraftChange { it.copy(trailerFullscreen = value) } },
            )
            SettingsGroupDivider(isTablet = true)
            SettingsChoiceRow(
                title = stringResource(Res.string.setup_wizard_trailers_start),
                description = stringResource(Res.string.settings_playback_hero_tv_trailer),
                options = listOf(
                    SettingsChoiceOption(false, stringResource(Res.string.settings_meta_hero_trailer_manual)),
                    SettingsChoiceOption(true, stringResource(Res.string.setup_wizard_trailers_start_auto)),
                ),
                selectedValue = draft.trailerDelaySeconds > 0,
                isTablet = true,
                onSelected = { autoplay ->
                    onDraftChange {
                        it.copy(trailerDelaySeconds = if (autoplay) AUTOPLAY_TRAILER_DELAY_SECONDS else 0)
                    }
                },
            )
            SettingsGroupDivider(isTablet = true)
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_playback_hero_tv_trailer_sound),
                description = stringResource(Res.string.settings_playback_hero_tv_trailer_sound_description),
                checked = draft.trailerSoundEnabled,
                isTablet = true,
                onCheckedChange = { value -> onDraftChange { it.copy(trailerSoundEnabled = value) } },
            )
        }
    }
}

@Composable
private fun WizardSliderRow(
    title: String,
    description: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Matches the desktop settings row metrics so this sits flush with the switch and
            // choice rows above it in the same group.
            .padding(horizontal = 16.dp, vertical = 8.8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s16),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textSecondary,
            )
        }
        SettingsModernSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.widthIn(max = 210.dp),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Step 2 — metadata keys, summary, and what happens on Finish
// ---------------------------------------------------------------------------------------------

@Composable
internal fun WizardMetadataStep(
    draft: FirstRunWizardDraft,
    onDraftChange: ((FirstRunWizardDraft) -> FirstRunWizardDraft) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s20)) {
        WizardStepHeader(
            title = stringResource(Res.string.setup_wizard_keys_title),
            subtitle = stringResource(Res.string.setup_wizard_keys_subtitle),
        )
        ApiKeySetupContent(
            tmdbApiKey = draft.tmdbApiKey,
            mdbListApiKey = draft.mdbListApiKey,
            onTmdbApiKeyChange = { value -> onDraftChange { it.copy(tmdbApiKey = value) } },
            onMdbListApiKeyChange = { value -> onDraftChange { it.copy(mdbListApiKey = value) } },
            style = ApiKeySetupStyle.Staged,
        )
        WizardSummary(draft = draft)
        WizardNote(text = stringResource(Res.string.setup_wizard_shortcuts_after_finish))
    }
}

@Composable
private fun WizardSummary(draft: FirstRunWizardDraft) {
    val tokens = MaterialTheme.nuvio
    val none = stringResource(Res.string.setup_wizard_summary_none)
    val integrations = buildList {
        if (draft.tmdbApiKey.isNotBlank()) add("TMDB")
        if (draft.mdbListApiKey.isNotBlank()) add("MDBList")
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
    ) {
        Text(
            text = stringResource(Res.string.setup_wizard_summary_title),
            style = MaterialTheme.typography.titleMedium,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        SummaryLine(stringResource(Res.string.setup_wizard_summary_mode), draft.displayMode.label())
        SummaryLine(
            stringResource(Res.string.setup_wizard_summary_integrations),
            integrations.joinToString().ifBlank { none },
        )
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    val tokens = MaterialTheme.nuvio
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textMuted,
            modifier = Modifier.widthIn(min = 140.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textPrimary,
        )
    }
}

@Composable
internal fun HomeDisplayMode.label(): String = when (this) {
    HomeDisplayMode.Basic -> stringResource(Res.string.settings_home_display_mode_basic)
    HomeDisplayMode.Adaptive -> stringResource(Res.string.settings_home_display_mode_adaptive)
    HomeDisplayMode.AdaptiveAmbient -> stringResource(Res.string.settings_home_display_mode_adaptive_ambient)
    HomeDisplayMode.TvMode -> stringResource(Res.string.settings_home_display_mode_tv)
}

@Composable
private fun HomeDisplayMode.description(): String = when (this) {
    HomeDisplayMode.Basic -> stringResource(Res.string.settings_home_display_mode_basic_description)
    HomeDisplayMode.Adaptive -> stringResource(Res.string.settings_home_display_mode_adaptive_description)
    HomeDisplayMode.AdaptiveAmbient ->
        stringResource(Res.string.settings_home_display_mode_adaptive_ambient_description)

    HomeDisplayMode.TvMode -> stringResource(Res.string.settings_home_display_mode_tv_description)
}

@Composable
private fun HomeTvRowDotsAnchor.dotPositionDescription(): String = when (this) {
    HomeTvRowDotsAnchor.RowTitle ->
        stringResource(Res.string.settings_home_row_jump_dot_position_row_description)

    HomeTvRowDotsAnchor.HeroBackdrop ->
        stringResource(Res.string.settings_home_row_jump_dot_position_backdrop_description)
}
