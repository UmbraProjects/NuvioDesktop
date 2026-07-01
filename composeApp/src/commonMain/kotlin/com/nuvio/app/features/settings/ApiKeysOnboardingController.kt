package com.nuvio.app.features.settings

import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ApiKeysOnboardingUiState(
    val visible: Boolean = false,
    val tmdbApiKey: String = "",
    val mdbListApiKey: String = "",
)

internal object ApiKeysOnboardingController {
    private val _uiState = MutableStateFlow(ApiKeysOnboardingUiState())
    val uiState: StateFlow<ApiKeysOnboardingUiState> = _uiState.asStateFlow()

    private var hasEvaluated = false
    private var dismissedUntilRestart = false

    fun evaluateOnLaunch() {
        if (hasEvaluated) return
        hasEvaluated = true
        TmdbSettingsRepository.ensureLoaded()
        MdbListSettingsRepository.ensureLoaded()
        refresh()
    }

    fun onTmdbApiKeyCommitted(value: String) {
        TmdbSettingsRepository.setApiKey(value)
        if (value.isNotBlank()) {
            TmdbSettingsRepository.setEnabled(true)
        }
        refresh()
    }

    fun onMdbListApiKeyCommitted(value: String) {
        MdbListSettingsRepository.setApiKey(value)
        if (value.isNotBlank()) {
            MdbListSettingsRepository.setEnabled(true)
        }
        refresh()
    }

    fun dismissUntilRestart() {
        dismissedUntilRestart = true
        _uiState.update { it.copy(visible = false) }
    }

    fun dismissPermanently() {
        dismissedUntilRestart = true
        ApiKeysOnboardingStorage.setPermanentlyDismissed(true)
        _uiState.update { it.copy(visible = false) }
    }

    private fun refresh() {
        val tmdbApiKey = TmdbSettingsRepository.snapshot().apiKey
        val mdbListApiKey = MdbListSettingsRepository.snapshot().apiKey
        val missingAnyKey = tmdbApiKey.isBlank() || mdbListApiKey.isBlank()
        val shouldShow = missingAnyKey && !dismissedUntilRestart && !ApiKeysOnboardingStorage.isPermanentlyDismissed()
        _uiState.value = ApiKeysOnboardingUiState(
            visible = shouldShow,
            tmdbApiKey = tmdbApiKey,
            mdbListApiKey = mdbListApiKey,
        )
    }
}
