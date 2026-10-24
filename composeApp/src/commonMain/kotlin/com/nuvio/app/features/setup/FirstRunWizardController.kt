package com.nuvio.app.features.setup

import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.HomeDisplayMode
import com.nuvio.app.features.home.homeDisplayModeOf
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.settings.ApiKeysOnboardingController
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.isDesktop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Owns whether the setup wizard is on screen and what it has decided so far.
 *
 * Nothing here writes a setting until [finish]. [skip] and [close] differ only in whether the
 * completion marker moves: skipping is a decision ("I don't want this"), closing mid-flow on a
 * fresh install is not, so the wizard comes back next launch.
 */
internal object FirstRunWizardController {
    private val _uiState = MutableStateFlow(FirstRunWizardUiState())
    val uiState: StateFlow<FirstRunWizardUiState> = _uiState.asStateFlow()

    private var hasEvaluated = false

    fun evaluateOnLaunch() {
        if (hasEvaluated) return
        hasEvaluated = true
        if (!isDesktop) return
        // Before anything reads settings: a fresh installation gets this fork's own defaults
        // rather than upstream's, and the wizard then opens showing them.
        NewInstallDefaults.applyIfNeeded()
        // Reading eligibility persists it, which is the point: the fresh-install signal behind it
        // is only true in the process that created the app data directory.
        if (!FirstRunWizardStorage.isEligible()) return
        if (FirstRunWizardStorage.completedVersion() >= FIRST_RUN_WIZARD_VERSION) return

        _uiState.value = FirstRunWizardUiState(
            visible = true,
            step = FirstRunWizardStep.ordered.first(),
            // Seeded from the settings NewInstallDefaults just wrote, so the wizard opens on what
            // the app is actually configured to do — including when the user skips it.
            draft = currentSettingsDraft(),
            isRerun = false,
        )
    }

    /** Opened from Settings. Ignores the completion marker and starts from the user's own values. */
    fun openManually() {
        _uiState.value = FirstRunWizardUiState(
            visible = true,
            step = FirstRunWizardStep.ordered.first(),
            draft = currentSettingsDraft(),
            isRerun = true,
        )
    }

    fun next() {
        _uiState.update { state ->
            val nextIndex = (state.step.index + 1).coerceAtMost(FirstRunWizardStep.count - 1)
            state.copy(step = FirstRunWizardStep.ordered[nextIndex])
        }
    }

    fun back() {
        _uiState.update { state ->
            val previousIndex = (state.step.index - 1).coerceAtLeast(0)
            state.copy(step = FirstRunWizardStep.ordered[previousIndex])
        }
    }

    fun updateDraft(transform: (FirstRunWizardDraft) -> FirstRunWizardDraft) {
        _uiState.update { it.copy(draft = transform(it.draft)) }
    }

    /**
     * "Skip setup": the user has decided about the wizard, but not about API keys — they never saw
     * that step. The prompt is held until the next launch rather than dismissed for good, so
     * skipping setup does not silently opt out of ever being asked.
     */
    fun skip() {
        markComplete(dismissKeyPromptPermanently = false)
        _uiState.value = FirstRunWizardUiState()
        ApiKeysOnboardingController.dismissUntilRestart()
    }

    /**
     * "Finish later" / dismissal. A rerun is simply closed; an unfinished first run leaves the
     * completion marker alone so the wizard resumes on the next launch.
     */
    fun close() {
        val wasFirstRun = _uiState.value.visible && !_uiState.value.isRerun
        _uiState.value = FirstRunWizardUiState()
        // The key prompt is already "visible" underneath by the time the wizard shows, so simply
        // releasing the screen would pop it the instant the user chose to finish later. Hold it
        // until the next launch, when the wizard resumes and asks for the keys itself.
        if (wasFirstRun) ApiKeysOnboardingController.dismissUntilRestart()
    }

    fun finish() {
        val draft = _uiState.value.draft
        applyDraft(draft)
        markComplete(dismissKeyPromptPermanently = true)
        _uiState.value = FirstRunWizardUiState()
    }

    private fun markComplete(dismissKeyPromptPermanently: Boolean) {
        if (!isDesktop) return
        FirstRunWizardStorage.setCompletedVersion(FIRST_RUN_WIZARD_VERSION)
        // Single owner for "the user has been asked about API keys". Goes through the controller
        // rather than straight to its storage so the prompt's own in-memory state is cleared too —
        // otherwise it is still marked visible and appears the moment the wizard releases the
        // screen, which is the exact double-popup this was meant to prevent.
        if (dismissKeyPromptPermanently) ApiKeysOnboardingController.dismissPermanently()
    }

    private fun applyDraft(draft: FirstRunWizardDraft) {
        HomeCatalogSettingsRepository.setDisplayMode(draft.displayMode)
        HomeCatalogSettingsRepository.setSmoothScrollingEnabled(draft.smoothScrollingEnabled)
        when (draft.displayMode) {
            HomeDisplayMode.Adaptive, HomeDisplayMode.AdaptiveAmbient -> {
                HomeCatalogSettingsRepository.setAdaptiveHeroVerticalBias(draft.adaptiveHeroVerticalBias)
                HomeCatalogSettingsRepository.setAdaptiveHeroHeightMultiplier(draft.adaptiveHeroHeightMultiplier)
            }

            HomeDisplayMode.TvMode -> {
                HomeCatalogSettingsRepository.setTvFullBackdropEnabled(draft.tvFullBackdropEnabled)
                HomeCatalogSettingsRepository.setTvRowDotsEnabled(draft.tvRowDotsEnabled)
                HomeCatalogSettingsRepository.setTvRowDotsAnchor(draft.tvRowDotsAnchor)
            }

            HomeDisplayMode.Basic -> Unit
        }

        // 0 seconds is "manual" — the trailer plays only on the T shortcut — so autoplay is off
        // rather than set to a zero delay.
        PlayerSettingsRepository.setHeroTvTrailerEnabled(draft.trailerDelaySeconds > 0)
        if (draft.trailerDelaySeconds > 0) {
            PlayerSettingsRepository.setHeroTvTrailerDelaySeconds(draft.trailerDelaySeconds)
        }
        PlayerSettingsRepository.setHeroTvTrailerFullscreen(draft.trailerFullscreen)
        PlayerSettingsRepository.setHeroTvTrailerSoundEnabled(draft.trailerSoundEnabled)
        PlayerSettingsRepository.setHeroTvTrailerSearchEnabled(draft.trailerInSearchEnabled)

        applyApiKey(draft.tmdbApiKey, TmdbSettingsRepository::setApiKey, TmdbSettingsRepository::setEnabled)
        applyApiKey(draft.mdbListApiKey, MdbListSettingsRepository::setApiKey, MdbListSettingsRepository::setEnabled)
    }

    private fun applyApiKey(
        key: String,
        setApiKey: (String) -> Unit,
        setEnabled: (Boolean) -> Unit,
    ) {
        val normalized = key.trim()
        if (normalized.isBlank()) return
        setApiKey(normalized)
        setEnabled(true)
    }

    /**
     * Reruns start from what the user already has — every field, including the ones on steps they
     * skip past, or Finish would quietly reset settings they tuned outside the wizard.
     */
    private fun currentSettingsDraft(): FirstRunWizardDraft {
        val home = HomeCatalogSettingsRepository.uiState.value
        PlayerSettingsRepository.ensureLoaded()
        val player = PlayerSettingsRepository.uiState.value
        TmdbSettingsRepository.ensureLoaded()
        MdbListSettingsRepository.ensureLoaded()

        return FirstRunWizardDraft(
            displayMode = homeDisplayModeOf(home),
            adaptiveHeroVerticalBias = home.adaptiveHeroVerticalBias,
            adaptiveHeroHeightMultiplier = home.adaptiveHeroHeightMultiplier,
            smoothScrollingEnabled = home.smoothScrollingEnabled,
            tvFullBackdropEnabled = home.tvFullBackdropEnabled,
            tvRowDotsEnabled = home.tvRowDotsEnabled,
            tvRowDotsAnchor = home.tvRowDotsAnchor,
            tmdbApiKey = TmdbSettingsRepository.snapshot().apiKey,
            mdbListApiKey = MdbListSettingsRepository.snapshot().apiKey,
            trailerFullscreen = player.heroTvTrailerFullscreen,
            trailerDelaySeconds = if (player.heroTvTrailerEnabled) player.heroTvTrailerDelaySeconds else 0,
            trailerSoundEnabled = player.heroTvTrailerSoundEnabled,
            trailerInSearchEnabled = player.heroTvTrailerSearchEnabled,
        )
    }
}
