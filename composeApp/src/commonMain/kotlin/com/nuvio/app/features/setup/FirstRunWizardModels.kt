package com.nuvio.app.features.setup

import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.DefaultPosterCardWidthDp
import com.nuvio.app.core.ui.PosterHighlightMode
import com.nuvio.app.features.home.HomeCatalogSettingsUiState
import com.nuvio.app.features.home.HomeDisplayMode
import com.nuvio.app.features.home.HomeTvRowDotsAnchor
import com.nuvio.app.features.settings.DesktopNavigationLayout
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle

/** The shipped defaults, so the draft does not restate slider bounds the repository already owns. */
private val homeDefaults = HomeCatalogSettingsUiState()

internal enum class FirstRunWizardStep {
    Layout,
    Posters,
    ContinueWatching,
    Theme,
    Trailers,
    Metadata,
    ;

    /** Basic has no hero for a trailer to play in, so its step would offer something that cannot happen. */
    fun appliesTo(draft: FirstRunWizardDraft): Boolean = when (this) {
        Trailers -> draft.displayMode != HomeDisplayMode.Basic
        else -> true
    }

    companion object {
        fun visibleSteps(draft: FirstRunWizardDraft): List<FirstRunWizardStep> =
            entries.filter { it.appliesTo(draft) }
    }
}

/**
 * Everything the wizard shows, in one place.
 *
 * Every field except the API keys is written to its repository the moment it changes, so Home
 * behind the panel shows the choice straight away. The keys wait for Finish: they change what
 * metadata is fetched, not how Home looks, and a half-typed key is not worth validating.
 */
internal data class FirstRunWizardDraft(
    val displayMode: HomeDisplayMode = HomeDisplayMode.Adaptive,
    val adaptiveHeroAutoFraming: Boolean = homeDefaults.adaptiveHeroAutoFraming,
    val adaptiveHeroVerticalBias: Float = homeDefaults.adaptiveHeroVerticalBias,
    val adaptiveHeroHeightMultiplier: Float = homeDefaults.adaptiveHeroHeightMultiplier,
    val adaptiveAmbientEnabled: Boolean = homeDefaults.heroAmbientBackgroundEnabled,
    val smoothScrollingEnabled: Boolean = true,
    val tvFullBackdropEnabled: Boolean = true,
    val tvAmbientBackgroundEnabled: Boolean = homeDefaults.tvAmbientBackgroundEnabled,
    val tvRowDotsEnabled: Boolean = true,
    val tvRowDotsAnchor: HomeTvRowDotsAnchor = HomeTvRowDotsAnchor.RowTitle,
    val posterWidthDp: Int = DefaultPosterCardWidthDp,
    val landscapePostersEnabled: Boolean = false,
    val posterHighlightMode: PosterHighlightMode = PosterHighlightMode.Shine,
    val hidePosterLabelsEnabled: Boolean = false,
    val continueWatchingVisible: Boolean = true,
    val continueWatchingStyle: ContinueWatchingSectionStyle = ContinueWatchingSectionStyle.Card,
    val separateNextUpRow: Boolean = true,
    val separateUpcomingRow: Boolean = true,
    val theme: AppTheme = AppTheme.OCEAN,
    val navigationLayout: DesktopNavigationLayout = DesktopNavigationLayout.Default,
    val topBarAlwaysVisible: Boolean = false,
    val tmdbApiKey: String = "",
    val mdbListApiKey: String = "",
    val trailerFullscreen: Boolean = false,
    /** 0 == manual (the T shortcut only); anything higher autoplays after that many seconds. */
    val trailerDelaySeconds: Int = 0,
    val trailerSoundEnabled: Boolean = false,
    val trailerInSearchEnabled: Boolean = true,
)

internal data class FirstRunWizardUiState(
    val visible: Boolean = false,
    val step: FirstRunWizardStep = FirstRunWizardStep.entries.first(),
    val draft: FirstRunWizardDraft = FirstRunWizardDraft(),
    /** True when opened from Settings rather than automatically on a fresh install. */
    val isRerun: Boolean = false,
) {
    val steps: List<FirstRunWizardStep> get() = FirstRunWizardStep.visibleSteps(draft)
    val stepIndex: Int get() = steps.indexOf(step).coerceAtLeast(0)
    val isFirstStep: Boolean get() = step == steps.first()
    val isLastStep: Boolean get() = step == steps.last()
}
