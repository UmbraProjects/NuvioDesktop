package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.home.HomeCatalogSettingsRepository

@Composable
internal fun rememberPosterCardStyleUiState(): PosterCardStyleUiState {
    PosterCardStyleRepository.ensureLoaded()
    val uiState by PosterCardStyleRepository.uiState.collectAsState()
    return uiState
}

/**
 * Poster styling shared by the Home, Search, Library, and Collections surfaces.
 * TV Mode always suppresses below-card labels without changing the saved desktop preference.
 */
@Composable
internal fun rememberHomePosterCardStyleUiState(): PosterCardStyleUiState {
    val base = rememberPosterCardStyleUiState()
    val homeSettings by remember {
        HomeCatalogSettingsRepository.snapshot()
        HomeCatalogSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    return effectiveHomePosterCardStyle(
        base = base,
        tvModeEnabled = homeSettings.tvModeEnabled,
    )
}

internal fun effectiveHomePosterCardStyle(
    base: PosterCardStyleUiState,
    tvModeEnabled: Boolean,
): PosterCardStyleUiState =
    if (tvModeEnabled) base.copy(hideLabelsEnabled = true) else base
