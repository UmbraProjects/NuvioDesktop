package com.nuvio.app.features.setup

import com.nuvio.app.core.ui.PosterCardStyleRepository
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.homeDisplayModeOf
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.settings.ApiKeysOnboardingController
import com.nuvio.app.features.settings.ThemeSettingsRepository
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.isDesktop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Owns whether the setup panel is on Home and what it has chosen so far.
 *
 * Choices are written as they are made, so Home behind the panel is the preview. What the settings
 * were when the panel opened is kept, and every exit except [finish] puts it back: closing the panel
 * must not leave half a setup applied. [skip] and [close] differ only in whether the completion
 * marker moves: skipping is a decision ("I don't want this"), closing mid-flow on a fresh install
 * is not, so the panel comes back next launch.
 */
internal object FirstRunWizardController {
    private val _uiState = MutableStateFlow(FirstRunWizardUiState())
    val uiState: StateFlow<FirstRunWizardUiState> = _uiState.asStateFlow()

    /** The settings as they were when the panel opened; what every exit but Finish restores. */
    private var snapshot = FirstRunWizardDraft()

    private var hasEvaluated = false

    fun evaluateOnLaunch() {
        if (hasEvaluated) return
        hasEvaluated = true
        if (!isDesktop) return
        // Before anything reads settings: a fresh installation gets this fork's own defaults
        // rather than upstream's, and the panel then opens showing them.
        NewInstallDefaults.applyIfNeeded()
        // Reading eligibility persists it, which is the point: the fresh-install signal behind it
        // is only true in the process that created the app data directory.
        if (!FirstRunWizardStorage.isEligible()) return
        if (FirstRunWizardStorage.completedVersion() >= FIRST_RUN_WIZARD_VERSION) return
        open(isRerun = false)
    }

    /** Opened from Settings. Ignores the completion marker and starts from the user's own values. */
    fun openManually() {
        open(isRerun = true)
    }

    private fun open(isRerun: Boolean) {
        // Seeded from live settings — on a fresh install, the ones NewInstallDefaults just wrote —
        // so the panel opens on what the app is actually configured to do.
        val current = currentSettingsDraft()
        snapshot = current
        _uiState.value = FirstRunWizardUiState(
            visible = true,
            step = FirstRunWizardStep.entries.first(),
            draft = current,
            isRerun = isRerun,
        )
    }

    fun next() {
        _uiState.update { state ->
            val steps = state.steps
            state.copy(step = steps[(state.stepIndex + 1).coerceAtMost(steps.lastIndex)])
        }
    }

    fun back() {
        _uiState.update { state ->
            state.copy(step = state.steps[(state.stepIndex - 1).coerceAtLeast(0)])
        }
    }

    fun updateDraft(transform: (FirstRunWizardDraft) -> FirstRunWizardDraft) {
        val before = _uiState.value
        if (!before.visible) return
        val draft = transform(before.draft)
        _uiState.value = before.copy(draft = draft)
        applyLiveChanges(from = before.draft, to = draft)
    }

    /**
     * "Skip setup": the user has decided about the wizard, but not about API keys — they never saw
     * that step. The prompt is held until the next launch rather than dismissed for good, so
     * skipping setup does not silently opt out of ever being asked.
     */
    fun skip() {
        revert()
        markComplete(dismissKeyPromptPermanently = false)
        _uiState.value = FirstRunWizardUiState()
        ApiKeysOnboardingController.dismissUntilRestart()
    }

    /**
     * "Finish later", ✕ and Escape. A rerun is simply closed; an unfinished first run leaves the
     * completion marker alone so the panel resumes on the next launch.
     */
    fun close() {
        val state = _uiState.value
        if (!state.visible) return
        revert()
        _uiState.value = FirstRunWizardUiState()
        // The key prompt is already "visible" underneath by the time the panel shows, so simply
        // releasing the screen would pop it the instant the user chose to finish later. Hold it
        // until the next launch, when the panel resumes and asks for the keys itself.
        if (!state.isRerun) ApiKeysOnboardingController.dismissUntilRestart()
    }

    fun finish() {
        val draft = _uiState.value.draft
        applyApiKey(draft.tmdbApiKey, TmdbSettingsRepository::setApiKey, TmdbSettingsRepository::setEnabled)
        applyApiKey(draft.mdbListApiKey, MdbListSettingsRepository::setApiKey, MdbListSettingsRepository::setEnabled)
        markComplete(dismissKeyPromptPermanently = true)
        _uiState.value = FirstRunWizardUiState()
    }

    private fun revert() {
        applyLiveChanges(from = _uiState.value.draft, to = snapshot)
    }

    private fun markComplete(dismissKeyPromptPermanently: Boolean) {
        if (!isDesktop) return
        FirstRunWizardStorage.setCompletedVersion(FIRST_RUN_WIZARD_VERSION)
        // Single owner for "the user has been asked about API keys". Goes through the controller
        // rather than straight to its storage so the prompt's own in-memory state is cleared too —
        // otherwise it is still marked visible and appears the moment the panel releases the
        // screen, which is the exact double-popup this was meant to prevent.
        if (dismissKeyPromptPermanently) ApiKeysOnboardingController.dismissPermanently()
    }

    /**
     * Writes only what differs between [from] and [to]. Each setter persists and republishes on its
     * own, and a display-mode write rebuilds Home, so rewriting every field on every click would
     * turn one toggle into a dozen saves and a Home rebuild.
     */
    private fun applyLiveChanges(from: FirstRunWizardDraft, to: FirstRunWizardDraft) {
        fun <T> changed(field: (FirstRunWizardDraft) -> T, write: (T) -> Unit) {
            val value = field(to)
            if (field(from) != value) write(value)
        }

        val home = HomeCatalogSettingsRepository
        changed(FirstRunWizardDraft::displayMode, home::setDisplayMode)
        changed(FirstRunWizardDraft::adaptiveHeroAutoFraming, home::setAdaptiveHeroAutoFraming)
        changed(FirstRunWizardDraft::adaptiveHeroVerticalBias, home::setAdaptiveHeroVerticalBias)
        changed(FirstRunWizardDraft::adaptiveHeroHeightMultiplier, home::setAdaptiveHeroHeightMultiplier)
        changed(FirstRunWizardDraft::adaptiveAmbientEnabled, home::setHeroAmbientBackgroundEnabled)
        changed(FirstRunWizardDraft::smoothScrollingEnabled, home::setSmoothScrollingEnabled)
        changed(FirstRunWizardDraft::tvFullBackdropEnabled, home::setTvFullBackdropEnabled)
        changed(FirstRunWizardDraft::tvAmbientBackgroundEnabled, home::setTvAmbientBackgroundEnabled)
        changed(FirstRunWizardDraft::tvRowDotsEnabled, home::setTvRowDotsEnabled)
        changed(FirstRunWizardDraft::tvRowDotsAnchor, home::setTvRowDotsAnchor)

        val posters = PosterCardStyleRepository
        changed(FirstRunWizardDraft::posterWidthDp, posters::setWidthDp)
        changed(FirstRunWizardDraft::landscapePostersEnabled, posters::setCatalogLandscapeModeEnabled)
        changed(FirstRunWizardDraft::posterHighlightMode, posters::setPosterHighlightMode)
        changed(FirstRunWizardDraft::hidePosterLabelsEnabled, posters::setHideLabelsEnabled)

        changed(FirstRunWizardDraft::continueWatchingVisible, ContinueWatchingPreferencesRepository::setVisible)
        changed(FirstRunWizardDraft::continueWatchingStyle, ContinueWatchingPreferencesRepository::setStyle)
        changed(FirstRunWizardDraft::separateNextUpRow, ContinueWatchingPreferencesRepository::setSeparateNextUpRow)
        changed(FirstRunWizardDraft::separateUpcomingRow, ContinueWatchingPreferencesRepository::setSeparateUpcomingRow)

        changed(FirstRunWizardDraft::theme, ThemeSettingsRepository::setTheme)
        changed(FirstRunWizardDraft::navigationLayout, ThemeSettingsRepository::setDesktopNavigationLayout)
        changed(FirstRunWizardDraft::topBarAlwaysVisible, ThemeSettingsRepository::setDesktopTopBarAlwaysVisible)

        val player = PlayerSettingsRepository
        // 0 seconds is "manual" — the trailer plays only on the T shortcut — so autoplay is off
        // rather than set to a zero delay.
        changed(FirstRunWizardDraft::trailerDelaySeconds) { seconds ->
            player.setHeroTvTrailerEnabled(seconds > 0)
            if (seconds > 0) player.setHeroTvTrailerDelaySeconds(seconds)
        }
        changed(FirstRunWizardDraft::trailerFullscreen, player::setHeroTvTrailerFullscreen)
        changed(FirstRunWizardDraft::trailerSoundEnabled, player::setHeroTvTrailerSoundEnabled)
        changed(FirstRunWizardDraft::trailerInSearchEnabled, player::setHeroTvTrailerSearchEnabled)
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

    private fun currentSettingsDraft(): FirstRunWizardDraft {
        val home = HomeCatalogSettingsRepository.uiState.value
        PlayerSettingsRepository.ensureLoaded()
        val player = PlayerSettingsRepository.uiState.value
        PosterCardStyleRepository.ensureLoaded()
        val posters = PosterCardStyleRepository.uiState.value
        ContinueWatchingPreferencesRepository.ensureLoaded()
        val continueWatching = ContinueWatchingPreferencesRepository.uiState.value
        ThemeSettingsRepository.ensureLoaded()
        TmdbSettingsRepository.ensureLoaded()
        MdbListSettingsRepository.ensureLoaded()

        return FirstRunWizardDraft(
            displayMode = homeDisplayModeOf(home),
            adaptiveHeroAutoFraming = home.adaptiveHeroAutoFraming,
            adaptiveHeroVerticalBias = home.adaptiveHeroVerticalBias,
            adaptiveHeroHeightMultiplier = home.adaptiveHeroHeightMultiplier,
            adaptiveAmbientEnabled = home.heroAmbientBackgroundEnabled,
            smoothScrollingEnabled = home.smoothScrollingEnabled,
            tvFullBackdropEnabled = home.tvFullBackdropEnabled,
            tvAmbientBackgroundEnabled = home.tvAmbientBackgroundEnabled,
            tvRowDotsEnabled = home.tvRowDotsEnabled,
            tvRowDotsAnchor = home.tvRowDotsAnchor,
            posterWidthDp = posters.widthDp,
            landscapePostersEnabled = posters.catalogLandscapeModeEnabled,
            posterHighlightMode = posters.posterHighlightMode,
            hidePosterLabelsEnabled = posters.hideLabelsEnabled,
            continueWatchingVisible = continueWatching.isVisible,
            continueWatchingStyle = continueWatching.style,
            separateNextUpRow = continueWatching.separateNextUpRow,
            separateUpcomingRow = continueWatching.separateUpcomingRow,
            theme = ThemeSettingsRepository.selectedTheme.value,
            navigationLayout = ThemeSettingsRepository.desktopNavigationLayout.value,
            topBarAlwaysVisible = ThemeSettingsRepository.desktopTopBarAlwaysVisible.value,
            tmdbApiKey = TmdbSettingsRepository.snapshot().apiKey,
            mdbListApiKey = MdbListSettingsRepository.snapshot().apiKey,
            trailerFullscreen = player.heroTvTrailerFullscreen,
            trailerDelaySeconds = if (player.heroTvTrailerEnabled) player.heroTvTrailerDelaySeconds else 0,
            trailerSoundEnabled = player.heroTvTrailerSoundEnabled,
            trailerInSearchEnabled = player.heroTvTrailerSearchEnabled,
        )
    }
}
