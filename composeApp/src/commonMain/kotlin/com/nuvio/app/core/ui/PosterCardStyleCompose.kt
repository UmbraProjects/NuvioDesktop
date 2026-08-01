package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.home.HomeCatalogSettingsRepository

@Composable
internal fun rememberPosterCardStyleUiState(): PosterCardStyleUiState {
    PosterCardStyleRepository.ensureLoaded()
    val uiState by PosterCardStyleRepository.uiState.collectAsState()
    return uiState
}

/**
 * Variant for Home/Search/Library/Collections' TV Mode shelf and continue-watching rendering.
 * Landscape-poster mode and hidden labels assume a flexible flat grid, not TV Mode's uniform
 * shelf, and produce broken layouts when combined — force them off/on while TV Mode is active
 * instead of respecting the (non-TV-Mode) saved preference. This only adjusts the value
 * returned here, it never writes back to [PosterCardStyleRepository], so the user's actual
 * preference is untouched and reapplies as soon as TV Mode is turned back off.
 */
@Composable
internal fun rememberHomePosterCardStyleUiState(): PosterCardStyleUiState {
    val base = rememberPosterCardStyleUiState()
    val tvModeEnabled by HomeCatalogSettingsRepository.uiState.collectAsStateWithLifecycle()
    return if (tvModeEnabled.tvModeEnabled) {
        base.copy(catalogLandscapeModeEnabled = false)
    } else {
        base
    }
}
