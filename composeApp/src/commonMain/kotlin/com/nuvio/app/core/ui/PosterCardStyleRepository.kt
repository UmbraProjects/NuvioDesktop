package com.nuvio.app.core.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val CompactPosterCardWidthDp = 104
internal const val DensePosterCardWidthDp = 112
internal const val StandardPosterCardWidthDp = 120
internal const val BalancedPosterCardWidthDp = 126
// The desktop fork spreads the upper presets out: upstream's 134dp/140dp Comfort/Large pair is
// visually near-identical at desktop scale, so 140dp becomes Comfort and Large fills the gap to
// the fork's 210dp Extra Large size.
internal const val ComfortPosterCardWidthDp = 140
internal const val LargePosterCardWidthDp = 175
internal const val DefaultPosterCardWidthDp = BalancedPosterCardWidthDp
internal const val DefaultPosterCardHeightDp = 189
internal const val DefaultPosterCardCornerRadiusDp = 12

/** The desktop fork's largest poster preset. */
const val ExtraLargePosterCardWidthDp = 210

@Serializable
private data class StoredPosterCardStylePreferences(
    val widthDp: Int = DefaultPosterCardWidthDp,
    val heightDp: Int = DefaultPosterCardHeightDp,
    val cornerRadiusDp: Int = DefaultPosterCardCornerRadiusDp,
    val catalogLandscapeModeEnabled: Boolean = false,
    val hideLabelsEnabled: Boolean = false,
    val depthEnabled: Boolean = false,
    val depthEdgeStrength: Int = 42,
    val depthSheenStrength: Int = 10,
    val depthEdgeCoverage: Int = 64,
    val depthPosters: Boolean = true,
    val depthContinueWatching: Boolean = true,
    val depthEpisodes: Boolean = true,
    val depthCast: Boolean = true,
    val depthTrailers: Boolean = true,
    val zoomActionPreviewEnabled: Boolean = true,
)

data class PosterCardStyleUiState(
    val widthDp: Int = DefaultPosterCardWidthDp,
    val heightDp: Int = DefaultPosterCardHeightDp,
    val cornerRadiusDp: Int = DefaultPosterCardCornerRadiusDp,
    val catalogLandscapeModeEnabled: Boolean = false,
    val hideLabelsEnabled: Boolean = false,
    val depthEnabled: Boolean = false,
    val depthEdgeStrength: Int = 42,
    val depthSheenStrength: Int = 10,
    val depthEdgeCoverage: Int = 64,
    val depthPosters: Boolean = true,
    val depthContinueWatching: Boolean = true,
    val depthEpisodes: Boolean = true,
    val depthCast: Boolean = true,
    val depthTrailers: Boolean = true,
    val zoomActionPreviewEnabled: Boolean = true,
)

object PosterCardStyleRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _uiState = MutableStateFlow(PosterCardStyleUiState())
    val uiState: StateFlow<PosterCardStyleUiState> = _uiState.asStateFlow()

    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun clearLocalState() {
        hasLoaded = false
        _uiState.value = PosterCardStyleUiState()
    }

    fun setWidthDp(widthDp: Int) {
        ensureLoaded()
        val nextWidth = widthDp
        val nextHeight = (nextWidth * 3) / 2
        if (_uiState.value.widthDp == nextWidth && _uiState.value.heightDp == nextHeight) return
        _uiState.value = _uiState.value.copy(
            widthDp = nextWidth,
            heightDp = nextHeight,
        )
        persist()
    }

    fun setCornerRadiusDp(cornerRadiusDp: Int) {
        ensureLoaded()
        if (_uiState.value.cornerRadiusDp == cornerRadiusDp) return
        _uiState.value = _uiState.value.copy(cornerRadiusDp = cornerRadiusDp)
        persist()
    }

    fun setCatalogLandscapeModeEnabled(enabled: Boolean) {
        ensureLoaded()
        if (_uiState.value.catalogLandscapeModeEnabled == enabled) return
        _uiState.value = _uiState.value.copy(catalogLandscapeModeEnabled = enabled)
        persist()
    }

    fun setHideLabelsEnabled(enabled: Boolean) {
        ensureLoaded()
        if (_uiState.value.hideLabelsEnabled == enabled) return
        _uiState.value = _uiState.value.copy(hideLabelsEnabled = enabled)
        persist()
    }

    fun setDepthEnabled(enabled: Boolean) {
        ensureLoaded()
        if (_uiState.value.depthEnabled == enabled) return
        _uiState.value = _uiState.value.copy(depthEnabled = enabled)
        persist()
    }

    fun setDepthEdgeStrength(value: Int) {
        ensureLoaded()
        _uiState.value = _uiState.value.copy(depthEdgeStrength = value.coerceIn(0, 100))
        persist()
    }

    fun setDepthSheenStrength(value: Int) {
        ensureLoaded()
        _uiState.value = _uiState.value.copy(depthSheenStrength = value.coerceIn(0, 100))
        persist()
    }

    fun setDepthEdgeCoverage(value: Int) {
        ensureLoaded()
        _uiState.value = _uiState.value.copy(depthEdgeCoverage = value.coerceIn(0, 100))
        persist()
    }

    fun setDepthSurfaceEnabled(surface: NuvioCardDepthSurface, enabled: Boolean) {
        ensureLoaded()
        _uiState.value = when (surface) {
            NuvioCardDepthSurface.Posters -> _uiState.value.copy(depthPosters = enabled)
            NuvioCardDepthSurface.ContinueWatching -> _uiState.value.copy(depthContinueWatching = enabled)
            NuvioCardDepthSurface.Episodes -> _uiState.value.copy(depthEpisodes = enabled)
            NuvioCardDepthSurface.Cast -> _uiState.value.copy(depthCast = enabled)
            NuvioCardDepthSurface.Trailers -> _uiState.value.copy(depthTrailers = enabled)
        }
        persist()
    }

    fun setZoomActionPreviewEnabled(enabled: Boolean) {
        ensureLoaded()
        if (_uiState.value.zoomActionPreviewEnabled == enabled) return
        _uiState.value = _uiState.value.copy(zoomActionPreviewEnabled = enabled)
        persist()
    }

    fun resetToDefaults() {
        ensureLoaded()
        if (_uiState.value == PosterCardStyleUiState()) return
        _uiState.value = PosterCardStyleUiState()
        persist()
    }

    private fun loadFromDisk() {
        hasLoaded = true

        val payload = PosterCardStyleStorage.loadPayload().orEmpty().trim()
        if (payload.isEmpty()) {
            _uiState.value = PosterCardStyleUiState()
            return
        }

        val stored = runCatching {
            json.decodeFromString<StoredPosterCardStylePreferences>(payload)
        }.getOrNull()

        _uiState.value = if (stored != null) {
            val storedWidthDp = stored.widthDp.takeIf { it > 0 } ?: DefaultPosterCardWidthDp
            // Migrate the old upstream Comfort preset into the refactored desktop Comfort tier;
            // otherwise existing users would be left with an unexplained custom "134dp" entry.
            val widthDp = if (storedWidthDp == 134) ComfortPosterCardWidthDp else storedWidthDp
            val heightDp = if (storedWidthDp == 134) {
                (widthDp * 3) / 2
            } else {
                stored.heightDp.takeIf { it > 0 } ?: ((widthDp * 3) / 2)
            }
            val cornerRadiusDp = stored.cornerRadiusDp.coerceAtLeast(0)
            PosterCardStyleUiState(
                widthDp = widthDp,
                heightDp = heightDp,
                cornerRadiusDp = cornerRadiusDp,
                catalogLandscapeModeEnabled = stored.catalogLandscapeModeEnabled,
                hideLabelsEnabled = stored.hideLabelsEnabled,
                depthEnabled = stored.depthEnabled,
                depthEdgeStrength = stored.depthEdgeStrength.coerceIn(0, 100),
                depthSheenStrength = stored.depthSheenStrength.coerceIn(0, 100),
                depthEdgeCoverage = stored.depthEdgeCoverage.coerceIn(0, 100),
                depthPosters = stored.depthPosters,
                depthContinueWatching = stored.depthContinueWatching,
                depthEpisodes = stored.depthEpisodes,
                depthCast = stored.depthCast,
                depthTrailers = stored.depthTrailers,
                zoomActionPreviewEnabled = stored.zoomActionPreviewEnabled,
            )
        } else {
            PosterCardStyleUiState()
        }
    }

    private fun persist() {
        PosterCardStyleStorage.savePayload(
            json.encodeToString(
                StoredPosterCardStylePreferences(
                    widthDp = _uiState.value.widthDp,
                    heightDp = _uiState.value.heightDp,
                    cornerRadiusDp = _uiState.value.cornerRadiusDp,
                    catalogLandscapeModeEnabled = _uiState.value.catalogLandscapeModeEnabled,
                    hideLabelsEnabled = _uiState.value.hideLabelsEnabled,
                    depthEnabled = _uiState.value.depthEnabled,
                    depthEdgeStrength = _uiState.value.depthEdgeStrength,
                    depthSheenStrength = _uiState.value.depthSheenStrength,
                    depthEdgeCoverage = _uiState.value.depthEdgeCoverage,
                    depthPosters = _uiState.value.depthPosters,
                    depthContinueWatching = _uiState.value.depthContinueWatching,
                    depthEpisodes = _uiState.value.depthEpisodes,
                    depthCast = _uiState.value.depthCast,
                    depthTrailers = _uiState.value.depthTrailers,
                    zoomActionPreviewEnabled = _uiState.value.zoomActionPreviewEnabled,
                ),
            ),
        )
    }
}
