package com.nuvio.app.features.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.BalancedPosterCardWidthDp
import com.nuvio.app.core.ui.ComfortPosterCardWidthDp
import com.nuvio.app.core.ui.CompactPosterCardWidthDp
import com.nuvio.app.core.ui.DensePosterCardWidthDp
import com.nuvio.app.core.ui.ExtraLargePosterCardWidthDp
import com.nuvio.app.core.ui.LargePosterCardWidthDp
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.PosterHighlightMode
import com.nuvio.app.core.ui.StandardPosterCardWidthDp
import com.nuvio.app.core.ui.ThemeColors
import com.nuvio.app.core.ui.labelRes
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.home.HomeDisplayMode
import com.nuvio.app.features.home.HomeTvRowDotsAnchor
import com.nuvio.app.features.settings.ApiKeySetupContent
import com.nuvio.app.features.settings.ApiKeySetupStyle
import com.nuvio.app.features.settings.DesktopNavigationLayout
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
import kotlin.math.abs
import kotlin.math.roundToInt
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_appearance_desktop_navigation
import nuvio.composeapp.generated.resources.settings_appearance_desktop_top_bar_always_visible
import nuvio.composeapp.generated.resources.settings_appearance_desktop_top_bar_always_visible_description
import nuvio.composeapp.generated.resources.settings_continue_watching_section_card_style
import nuvio.composeapp.generated.resources.settings_continue_watching_separate_next_up_description
import nuvio.composeapp.generated.resources.settings_continue_watching_separate_next_up_title
import nuvio.composeapp.generated.resources.settings_continue_watching_separate_upcoming_description
import nuvio.composeapp.generated.resources.settings_continue_watching_separate_upcoming_title
import nuvio.composeapp.generated.resources.settings_continue_watching_show_description
import nuvio.composeapp.generated.resources.settings_continue_watching_show_title
import nuvio.composeapp.generated.resources.settings_continue_watching_style_card
import nuvio.composeapp.generated.resources.settings_continue_watching_style_card_description
import nuvio.composeapp.generated.resources.settings_continue_watching_style_poster
import nuvio.composeapp.generated.resources.settings_continue_watching_style_poster_description
import nuvio.composeapp.generated.resources.settings_continue_watching_style_wide
import nuvio.composeapp.generated.resources.settings_continue_watching_style_wide_description
import nuvio.composeapp.generated.resources.settings_home_adaptive_ambient_background
import nuvio.composeapp.generated.resources.settings_home_adaptive_ambient_background_description
import nuvio.composeapp.generated.resources.settings_home_backdrop_auto_framing
import nuvio.composeapp.generated.resources.settings_home_backdrop_auto_framing_description
import nuvio.composeapp.generated.resources.settings_home_backdrop_vertical_position
import nuvio.composeapp.generated.resources.settings_home_display_mode_adaptive
import nuvio.composeapp.generated.resources.settings_home_display_mode_adaptive_description
import nuvio.composeapp.generated.resources.settings_home_display_mode_basic
import nuvio.composeapp.generated.resources.settings_home_display_mode_basic_description
import nuvio.composeapp.generated.resources.settings_home_display_mode_tv
import nuvio.composeapp.generated.resources.settings_home_display_mode_tv_description
import nuvio.composeapp.generated.resources.settings_home_hero_height
import nuvio.composeapp.generated.resources.settings_home_row_jump_dot_position
import nuvio.composeapp.generated.resources.settings_home_row_jump_dot_position_backdrop
import nuvio.composeapp.generated.resources.settings_home_row_jump_dot_position_backdrop_description
import nuvio.composeapp.generated.resources.settings_home_row_jump_dot_position_row
import nuvio.composeapp.generated.resources.settings_home_row_jump_dot_position_row_description
import nuvio.composeapp.generated.resources.settings_home_row_jump_dots
import nuvio.composeapp.generated.resources.settings_home_row_jump_dots_description
import nuvio.composeapp.generated.resources.settings_home_smooth_scrolling
import nuvio.composeapp.generated.resources.settings_home_smooth_scrolling_description
import nuvio.composeapp.generated.resources.settings_home_tv_ambient_background
import nuvio.composeapp.generated.resources.settings_home_tv_ambient_background_description
import nuvio.composeapp.generated.resources.settings_home_tv_full_backdrop
import nuvio.composeapp.generated.resources.settings_home_tv_full_backdrop_description
import nuvio.composeapp.generated.resources.settings_meta_hero_trailer_manual
import nuvio.composeapp.generated.resources.settings_meta_hero_trailer_playback_area_fullscreen
import nuvio.composeapp.generated.resources.settings_meta_hero_trailer_playback_area_hero
import nuvio.composeapp.generated.resources.settings_playback_hero_tv_trailer_sound
import nuvio.composeapp.generated.resources.settings_playback_hero_tv_trailer_sound_description
import nuvio.composeapp.generated.resources.settings_poster_card_width
import nuvio.composeapp.generated.resources.settings_poster_hide_labels
import nuvio.composeapp.generated.resources.settings_poster_highlight
import nuvio.composeapp.generated.resources.settings_poster_highlight_accent
import nuvio.composeapp.generated.resources.settings_poster_highlight_description
import nuvio.composeapp.generated.resources.settings_poster_highlight_off
import nuvio.composeapp.generated.resources.settings_poster_highlight_shine
import nuvio.composeapp.generated.resources.settings_poster_highlight_sweep
import nuvio.composeapp.generated.resources.settings_poster_highlight_white
import nuvio.composeapp.generated.resources.settings_poster_landscape_mode
import nuvio.composeapp.generated.resources.settings_poster_width_balanced
import nuvio.composeapp.generated.resources.settings_poster_width_comfort
import nuvio.composeapp.generated.resources.settings_poster_width_compact
import nuvio.composeapp.generated.resources.settings_poster_width_dense
import nuvio.composeapp.generated.resources.settings_poster_width_extra_large
import nuvio.composeapp.generated.resources.settings_poster_width_large
import nuvio.composeapp.generated.resources.settings_poster_width_standard
import nuvio.composeapp.generated.resources.setup_wizard_cw_subtitle
import nuvio.composeapp.generated.resources.setup_wizard_cw_title
import nuvio.composeapp.generated.resources.setup_wizard_experience_subtitle
import nuvio.composeapp.generated.resources.setup_wizard_experience_title
import nuvio.composeapp.generated.resources.setup_wizard_experience_tv_badge
import nuvio.composeapp.generated.resources.setup_wizard_keys_subtitle
import nuvio.composeapp.generated.resources.setup_wizard_keys_title
import nuvio.composeapp.generated.resources.setup_wizard_layout_tuning
import nuvio.composeapp.generated.resources.setup_wizard_posters_subtitle
import nuvio.composeapp.generated.resources.setup_wizard_posters_title
import nuvio.composeapp.generated.resources.setup_wizard_renderer_note
import nuvio.composeapp.generated.resources.setup_wizard_shortcuts_after_finish
import nuvio.composeapp.generated.resources.setup_wizard_theme_accent
import nuvio.composeapp.generated.resources.setup_wizard_theme_subtitle
import nuvio.composeapp.generated.resources.setup_wizard_theme_title
import nuvio.composeapp.generated.resources.setup_wizard_trailers_area
import nuvio.composeapp.generated.resources.setup_wizard_trailers_start
import nuvio.composeapp.generated.resources.setup_wizard_trailers_start_auto
import nuvio.composeapp.generated.resources.setup_wizard_trailers_subtitle
import nuvio.composeapp.generated.resources.setup_wizard_trailers_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private const val AUTOPLAY_TRAILER_DELAY_SECONDS = 5

/** The hero-height range Settings offers, walked in 5% steps. */
private val HeroHeightRange = 0.75f..1.75f
private const val HeroHeightStep = 0.05f

/** Backdrop position, -1 (top) to 1 (bottom), walked in quarters. */
private const val VerticalBiasStep = 0.25f

private val PosterWidthPresets = listOf(
    CompactPosterCardWidthDp to Res.string.settings_poster_width_compact,
    DensePosterCardWidthDp to Res.string.settings_poster_width_dense,
    StandardPosterCardWidthDp to Res.string.settings_poster_width_standard,
    BalancedPosterCardWidthDp to Res.string.settings_poster_width_balanced,
    ComfortPosterCardWidthDp to Res.string.settings_poster_width_comfort,
    LargePosterCardWidthDp to Res.string.settings_poster_width_large,
    ExtraLargePosterCardWidthDp to Res.string.settings_poster_width_extra_large,
)

/** Custom needs the colour fields Settings has, which do not fit a panel this size. */
private val ThemeSwatches = listOf(AppTheme.WHITE) + AppTheme.entries.filterNot {
    it == AppTheme.WHITE || it == AppTheme.CUSTOM
}

private typealias DraftChange = ((FirstRunWizardDraft) -> FirstRunWizardDraft) -> Unit

/** Title and subtitle at the top of each step. */
@Composable
internal fun FirstRunWizardStep.header(): Pair<String, String> = when (this) {
    FirstRunWizardStep.Layout ->
        stringResource(Res.string.setup_wizard_experience_title) to
            stringResource(Res.string.setup_wizard_experience_subtitle)

    FirstRunWizardStep.Posters ->
        stringResource(Res.string.setup_wizard_posters_title) to
            stringResource(Res.string.setup_wizard_posters_subtitle)

    FirstRunWizardStep.ContinueWatching ->
        stringResource(Res.string.setup_wizard_cw_title) to stringResource(Res.string.setup_wizard_cw_subtitle)

    FirstRunWizardStep.Theme ->
        stringResource(Res.string.setup_wizard_theme_title) to stringResource(Res.string.setup_wizard_theme_subtitle)

    FirstRunWizardStep.Trailers ->
        stringResource(Res.string.setup_wizard_trailers_title) to
            stringResource(Res.string.setup_wizard_trailers_subtitle)

    FirstRunWizardStep.Metadata ->
        stringResource(Res.string.setup_wizard_keys_title) to stringResource(Res.string.setup_wizard_keys_subtitle)
}

/**
 * The body of [step]. [firstFocus] goes on its first control, which the panel focuses whenever a
 * step opens, so a keyboard or gamepad user always starts at the top of what is new.
 */
@Composable
internal fun WizardStepContent(
    step: FirstRunWizardStep,
    draft: FirstRunWizardDraft,
    onDraftChange: DraftChange,
    firstFocus: Modifier,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        when (step) {
            FirstRunWizardStep.Layout -> LayoutStep(draft, onDraftChange, firstFocus)
            FirstRunWizardStep.Posters -> PostersStep(draft, onDraftChange, firstFocus)
            FirstRunWizardStep.ContinueWatching -> ContinueWatchingStep(draft, onDraftChange, firstFocus)
            FirstRunWizardStep.Theme -> ThemeStep(draft, onDraftChange, firstFocus)
            FirstRunWizardStep.Trailers -> TrailersStep(draft, onDraftChange, firstFocus)
            // The panel focuses Finish here instead: landing in a key field would open the on-screen
            // keyboard for a gamepad user who only wanted to finish.
            FirstRunWizardStep.Metadata -> MetadataStep(draft, onDraftChange)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Layout — display mode and its tuning
// ---------------------------------------------------------------------------------------------

@Composable
private fun LayoutStep(draft: FirstRunWizardDraft, onDraftChange: DraftChange, firstFocus: Modifier) {
    HomeDisplayMode.entries.forEachIndexed { index, mode ->
        WizardOptionRow(
            label = mode.label(),
            description = mode.description(),
            badge = if (mode == HomeDisplayMode.TvMode) {
                stringResource(Res.string.setup_wizard_experience_tv_badge)
            } else {
                null
            },
            selected = draft.displayMode == mode,
            onClick = { onDraftChange { it.copy(displayMode = mode) } },
            modifier = if (index == 0) firstFocus else Modifier,
        )
    }

    WizardSectionLabel(stringResource(Res.string.setup_wizard_layout_tuning))
    when (draft.displayMode) {
        HomeDisplayMode.Adaptive -> {
            WizardToggleRow(
                title = stringResource(Res.string.settings_home_backdrop_auto_framing),
                description = stringResource(Res.string.settings_home_backdrop_auto_framing_description),
                checked = draft.adaptiveHeroAutoFraming,
                onCheckedChange = { value -> onDraftChange { it.copy(adaptiveHeroAutoFraming = value) } },
            )
            if (!draft.adaptiveHeroAutoFraming) {
                val bias = draft.adaptiveHeroVerticalBias
                WizardStepperRow(
                    title = stringResource(Res.string.settings_home_backdrop_vertical_position),
                    valueLabel = (bias * 100).roundToInt().toString(),
                    canDecrease = bias > -1f,
                    canIncrease = bias < 1f,
                    onDecrease = { onDraftChange { it.copy(adaptiveHeroVerticalBias = stepBias(bias, -1)) } },
                    onIncrease = { onDraftChange { it.copy(adaptiveHeroVerticalBias = stepBias(bias, 1)) } },
                )
            }
            val height = draft.adaptiveHeroHeightMultiplier
            WizardStepperRow(
                title = stringResource(Res.string.settings_home_hero_height),
                valueLabel = "${(height * 100).roundToInt()}%",
                canDecrease = height > HeroHeightRange.start + 0.001f,
                canIncrease = height < HeroHeightRange.endInclusive - 0.001f,
                onDecrease = { onDraftChange { it.copy(adaptiveHeroHeightMultiplier = stepHeight(height, -1)) } },
                onIncrease = { onDraftChange { it.copy(adaptiveHeroHeightMultiplier = stepHeight(height, 1)) } },
            )
            WizardToggleRow(
                title = stringResource(Res.string.settings_home_adaptive_ambient_background),
                description = stringResource(Res.string.settings_home_adaptive_ambient_background_description),
                checked = draft.adaptiveAmbientEnabled,
                onCheckedChange = { value -> onDraftChange { it.copy(adaptiveAmbientEnabled = value) } },
            )
            SmoothScrollingRow(draft, onDraftChange)
        }

        HomeDisplayMode.Basic -> SmoothScrollingRow(draft, onDraftChange)

        HomeDisplayMode.TvMode -> {
            WizardToggleRow(
                title = stringResource(Res.string.settings_home_tv_full_backdrop),
                description = stringResource(Res.string.settings_home_tv_full_backdrop_description),
                checked = draft.tvFullBackdropEnabled,
                onCheckedChange = { value -> onDraftChange { it.copy(tvFullBackdropEnabled = value) } },
            )
            WizardToggleRow(
                title = stringResource(Res.string.settings_home_tv_ambient_background),
                description = stringResource(Res.string.settings_home_tv_ambient_background_description),
                checked = draft.tvAmbientBackgroundEnabled,
                onCheckedChange = { value -> onDraftChange { it.copy(tvAmbientBackgroundEnabled = value) } },
            )
            WizardToggleRow(
                title = stringResource(Res.string.settings_home_row_jump_dots),
                description = stringResource(Res.string.settings_home_row_jump_dots_description),
                checked = draft.tvRowDotsEnabled,
                onCheckedChange = { value -> onDraftChange { it.copy(tvRowDotsEnabled = value) } },
            )
            if (draft.tvRowDotsEnabled) {
                WizardSectionLabel(stringResource(Res.string.settings_home_row_jump_dot_position))
                WizardOptionRow(
                    label = stringResource(Res.string.settings_home_row_jump_dot_position_row),
                    description = stringResource(Res.string.settings_home_row_jump_dot_position_row_description),
                    selected = draft.tvRowDotsAnchor == HomeTvRowDotsAnchor.RowTitle,
                    onClick = { onDraftChange { it.copy(tvRowDotsAnchor = HomeTvRowDotsAnchor.RowTitle) } },
                )
                WizardOptionRow(
                    label = stringResource(Res.string.settings_home_row_jump_dot_position_backdrop),
                    description = stringResource(Res.string.settings_home_row_jump_dot_position_backdrop_description),
                    selected = draft.tvRowDotsAnchor == HomeTvRowDotsAnchor.HeroBackdrop,
                    onClick = { onDraftChange { it.copy(tvRowDotsAnchor = HomeTvRowDotsAnchor.HeroBackdrop) } },
                )
            }
        }
    }
}

@Composable
private fun SmoothScrollingRow(draft: FirstRunWizardDraft, onDraftChange: DraftChange) {
    WizardToggleRow(
        title = stringResource(Res.string.settings_home_smooth_scrolling),
        description = stringResource(Res.string.settings_home_smooth_scrolling_description),
        checked = draft.smoothScrollingEnabled,
        onCheckedChange = { value -> onDraftChange { it.copy(smoothScrollingEnabled = value) } },
    )
}

internal fun stepBias(current: Float, direction: Int): Float =
    ((current / VerticalBiasStep).roundToInt() + direction).times(VerticalBiasStep).coerceIn(-1f, 1f)

internal fun stepHeight(current: Float, direction: Int): Float =
    ((current / HeroHeightStep).roundToInt() + direction).times(HeroHeightStep)
        .coerceIn(HeroHeightRange.start, HeroHeightRange.endInclusive)

// ---------------------------------------------------------------------------------------------
// Posters
// ---------------------------------------------------------------------------------------------

@Composable
private fun PostersStep(draft: FirstRunWizardDraft, onDraftChange: DraftChange, firstFocus: Modifier) {
    // TV Mode sizes its own cards and never shows their labels, so those two rows would change
    // nothing on Home and read as broken. The values are kept, for whichever mode comes next.
    val tvMode = draft.displayMode == HomeDisplayMode.TvMode
    // A width set in Settings may sit between presets; stepping starts from the nearest one.
    val widthIndex = nearestPosterPresetIndex(draft.posterWidthDp)
    if (!tvMode) WizardStepperRow(
        title = stringResource(Res.string.settings_poster_card_width),
        valueLabel = stringResource(PosterWidthPresets[widthIndex].second),
        canDecrease = widthIndex > 0,
        canIncrease = widthIndex < PosterWidthPresets.lastIndex,
        onDecrease = { onDraftChange { it.copy(posterWidthDp = PosterWidthPresets[widthIndex - 1].first) } },
        onIncrease = { onDraftChange { it.copy(posterWidthDp = PosterWidthPresets[widthIndex + 1].first) } },
        modifier = firstFocus,
    )
    val highlights = PosterHighlightMode.entries
    val highlightIndex = highlights.indexOf(draft.posterHighlightMode)
    WizardStepperRow(
        title = stringResource(Res.string.settings_poster_highlight),
        description = stringResource(Res.string.settings_poster_highlight_description),
        valueLabel = stringResource(draft.posterHighlightMode.labelRes),
        canDecrease = highlightIndex > 0,
        canIncrease = highlightIndex < highlights.lastIndex,
        onDecrease = { onDraftChange { it.copy(posterHighlightMode = highlights[highlightIndex - 1]) } },
        onIncrease = { onDraftChange { it.copy(posterHighlightMode = highlights[highlightIndex + 1]) } },
        modifier = if (tvMode) firstFocus else Modifier,
    )
    WizardToggleRow(
        title = stringResource(Res.string.settings_poster_landscape_mode),
        checked = draft.landscapePostersEnabled,
        onCheckedChange = { value -> onDraftChange { it.copy(landscapePostersEnabled = value) } },
    )
    if (!tvMode) WizardToggleRow(
        title = stringResource(Res.string.settings_poster_hide_labels),
        checked = draft.hidePosterLabelsEnabled,
        onCheckedChange = { value -> onDraftChange { it.copy(hidePosterLabelsEnabled = value) } },
    )
}

internal fun nearestPosterPresetIndex(widthDp: Int): Int =
    PosterWidthPresets.indices.minBy { abs(PosterWidthPresets[it].first - widthDp) }

private val PosterHighlightMode.labelRes: StringResource
    get() = when (this) {
        PosterHighlightMode.Off -> Res.string.settings_poster_highlight_off
        PosterHighlightMode.White -> Res.string.settings_poster_highlight_white
        PosterHighlightMode.Accent -> Res.string.settings_poster_highlight_accent
        PosterHighlightMode.Shine -> Res.string.settings_poster_highlight_shine
        PosterHighlightMode.Sweep -> Res.string.settings_poster_highlight_sweep
    }

// ---------------------------------------------------------------------------------------------
// Continue Watching
// ---------------------------------------------------------------------------------------------

@Composable
private fun ContinueWatchingStep(draft: FirstRunWizardDraft, onDraftChange: DraftChange, firstFocus: Modifier) {
    WizardToggleRow(
        title = stringResource(Res.string.settings_continue_watching_show_title),
        description = stringResource(Res.string.settings_continue_watching_show_description),
        checked = draft.continueWatchingVisible,
        onCheckedChange = { value -> onDraftChange { it.copy(continueWatchingVisible = value) } },
        modifier = firstFocus,
    )
    if (draft.continueWatchingVisible) {
        WizardSectionLabel(stringResource(Res.string.settings_continue_watching_section_card_style))
        ContinueWatchingSectionStyle.entries.forEach { style ->
            WizardOptionRow(
                label = stringResource(style.labelRes),
                description = stringResource(style.descriptionRes),
                selected = draft.continueWatchingStyle == style,
                onClick = { onDraftChange { it.copy(continueWatchingStyle = style) } },
            )
        }
        WizardDivider()
        WizardToggleRow(
            title = stringResource(Res.string.settings_continue_watching_separate_next_up_title),
            description = stringResource(Res.string.settings_continue_watching_separate_next_up_description),
            checked = draft.separateNextUpRow,
            onCheckedChange = { value -> onDraftChange { it.copy(separateNextUpRow = value) } },
        )
        WizardToggleRow(
            title = stringResource(Res.string.settings_continue_watching_separate_upcoming_title),
            description = stringResource(Res.string.settings_continue_watching_separate_upcoming_description),
            checked = draft.separateUpcomingRow,
            onCheckedChange = { value -> onDraftChange { it.copy(separateUpcomingRow = value) } },
        )
    }
}

private val ContinueWatchingSectionStyle.labelRes: StringResource
    get() = when (this) {
        ContinueWatchingSectionStyle.Card -> Res.string.settings_continue_watching_style_card
        ContinueWatchingSectionStyle.Wide -> Res.string.settings_continue_watching_style_wide
        ContinueWatchingSectionStyle.Poster -> Res.string.settings_continue_watching_style_poster
    }

private val ContinueWatchingSectionStyle.descriptionRes: StringResource
    get() = when (this) {
        ContinueWatchingSectionStyle.Card -> Res.string.settings_continue_watching_style_card_description
        ContinueWatchingSectionStyle.Wide -> Res.string.settings_continue_watching_style_wide_description
        ContinueWatchingSectionStyle.Poster -> Res.string.settings_continue_watching_style_poster_description
    }

// ---------------------------------------------------------------------------------------------
// Theme
// ---------------------------------------------------------------------------------------------

@Composable
private fun ThemeStep(draft: FirstRunWizardDraft, onDraftChange: DraftChange, firstFocus: Modifier) {
    val tokens = MaterialTheme.nuvio
    WizardSectionLabel(stringResource(Res.string.setup_wizard_theme_accent))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTokens.Space.s8, vertical = NuvioTokens.Space.s6),
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
    ) {
        ThemeSwatches.forEachIndexed { index, theme ->
            val label = stringResource(theme.labelRes)
            WizardSwatch(
                color = ThemeColors.getColorPalette(theme).secondary,
                contentDescription = label,
                selected = draft.theme == theme,
                onClick = { onDraftChange { it.copy(theme = theme) } },
                modifier = if (index == 0) firstFocus else Modifier,
            )
        }
    }
    Text(
        text = stringResource(draft.theme.labelRes),
        style = MaterialTheme.typography.bodyMedium,
        color = tokens.colors.textSecondary,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(horizontal = NuvioTokens.Space.s12),
    )

    WizardSectionLabel(stringResource(Res.string.settings_appearance_desktop_navigation))
    DesktopNavigationLayout.entries.forEach { layout ->
        WizardOptionRow(
            label = stringResource(layout.labelRes),
            selected = draft.navigationLayout == layout,
            onClick = { onDraftChange { it.copy(navigationLayout = layout) } },
        )
    }
    // Only the top bar fades out until the pointer reaches it; the sidebar has no such option.
    if (draft.navigationLayout == DesktopNavigationLayout.TopBar) {
        WizardDivider()
        WizardToggleRow(
            title = stringResource(Res.string.settings_appearance_desktop_top_bar_always_visible),
            description = stringResource(Res.string.settings_appearance_desktop_top_bar_always_visible_description),
            checked = draft.topBarAlwaysVisible,
            onCheckedChange = { value -> onDraftChange { it.copy(topBarAlwaysVisible = value) } },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Trailers
// ---------------------------------------------------------------------------------------------

@Composable
private fun TrailersStep(draft: FirstRunWizardDraft, onDraftChange: DraftChange, firstFocus: Modifier) {
    WizardSectionLabel(stringResource(Res.string.setup_wizard_trailers_area))
    WizardOptionRow(
        label = stringResource(Res.string.settings_meta_hero_trailer_playback_area_hero),
        selected = !draft.trailerFullscreen,
        onClick = { onDraftChange { it.copy(trailerFullscreen = false) } },
        modifier = firstFocus,
    )
    WizardOptionRow(
        label = stringResource(Res.string.settings_meta_hero_trailer_playback_area_fullscreen),
        selected = draft.trailerFullscreen,
        onClick = { onDraftChange { it.copy(trailerFullscreen = true) } },
    )
    WizardSectionLabel(stringResource(Res.string.setup_wizard_trailers_start))
    WizardOptionRow(
        label = stringResource(Res.string.settings_meta_hero_trailer_manual),
        selected = draft.trailerDelaySeconds == 0,
        onClick = { onDraftChange { it.copy(trailerDelaySeconds = 0) } },
    )
    WizardOptionRow(
        label = stringResource(Res.string.setup_wizard_trailers_start_auto),
        selected = draft.trailerDelaySeconds > 0,
        onClick = {
            onDraftChange {
                if (it.trailerDelaySeconds > 0) it else it.copy(trailerDelaySeconds = AUTOPLAY_TRAILER_DELAY_SECONDS)
            }
        },
    )
    WizardDivider()
    WizardToggleRow(
        title = stringResource(Res.string.settings_playback_hero_tv_trailer_sound),
        description = stringResource(Res.string.settings_playback_hero_tv_trailer_sound_description),
        checked = draft.trailerSoundEnabled,
        onCheckedChange = { value -> onDraftChange { it.copy(trailerSoundEnabled = value) } },
    )
}

// ---------------------------------------------------------------------------------------------
// Metadata keys, and what happens on Finish
// ---------------------------------------------------------------------------------------------

@Composable
private fun MetadataStep(draft: FirstRunWizardDraft, onDraftChange: DraftChange) {
    Column(
        modifier = Modifier.padding(horizontal = NuvioTokens.Space.s4),
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
    ) {
        ApiKeySetupContent(
            tmdbApiKey = draft.tmdbApiKey,
            mdbListApiKey = draft.mdbListApiKey,
            onTmdbApiKeyChange = { value -> onDraftChange { it.copy(tmdbApiKey = value) } },
            onMdbListApiKeyChange = { value -> onDraftChange { it.copy(mdbListApiKey = value) } },
            style = ApiKeySetupStyle.Staged,
        )
        WizardNote(text = stringResource(Res.string.setup_wizard_shortcuts_after_finish))
        WizardNote(text = stringResource(Res.string.setup_wizard_renderer_note))
    }
}

@Composable
internal fun HomeDisplayMode.label(): String = when (this) {
    HomeDisplayMode.Basic -> stringResource(Res.string.settings_home_display_mode_basic)
    HomeDisplayMode.Adaptive -> stringResource(Res.string.settings_home_display_mode_adaptive)
    HomeDisplayMode.TvMode -> stringResource(Res.string.settings_home_display_mode_tv)
}

@Composable
private fun HomeDisplayMode.description(): String = when (this) {
    HomeDisplayMode.Basic -> stringResource(Res.string.settings_home_display_mode_basic_description)
    HomeDisplayMode.Adaptive -> stringResource(Res.string.settings_home_display_mode_adaptive_description)
    HomeDisplayMode.TvMode -> stringResource(Res.string.settings_home_display_mode_tv_description)
}
