package com.nuvio.app.features.setup

import com.nuvio.app.features.home.HomeCatalogSettingsUiState
import com.nuvio.app.features.home.HomeDisplayMode
import com.nuvio.app.features.home.HomeTvRowDotsAnchor

/** The shipped defaults, so the draft does not restate slider bounds the repository already owns. */
private val homeDefaults = HomeCatalogSettingsUiState()

internal enum class FirstRunWizardStep {
    Experience,
    Metadata,
    ;

    val index: Int get() = ordinal

    companion object {
        val ordered: List<FirstRunWizardStep> = entries
        val count: Int = entries.size
    }
}

/**
 * Everything the wizard has decided but not yet written.
 *
 * Staged rather than applied step-by-step so closing or cancelling the wizard leaves settings
 * exactly as they were.
 */
internal data class FirstRunWizardDraft(
    val displayMode: HomeDisplayMode = HomeDisplayMode.Adaptive,
    val adaptiveHeroVerticalBias: Float = homeDefaults.adaptiveHeroVerticalBias,
    val adaptiveHeroHeightMultiplier: Float = homeDefaults.adaptiveHeroHeightMultiplier,
    val smoothScrollingEnabled: Boolean = true,
    val tvFullBackdropEnabled: Boolean = true,
    val tvRowDotsEnabled: Boolean = true,
    val tvRowDotsAnchor: HomeTvRowDotsAnchor = HomeTvRowDotsAnchor.RowTitle,
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
    val step: FirstRunWizardStep = FirstRunWizardStep.Experience,
    val draft: FirstRunWizardDraft = FirstRunWizardDraft(),
    /** True when opened from Settings rather than automatically on a fresh install. */
    val isRerun: Boolean = false,
) {
    val isFirstStep: Boolean get() = step == FirstRunWizardStep.ordered.first()
    val isLastStep: Boolean get() = step == FirstRunWizardStep.ordered.last()
}
