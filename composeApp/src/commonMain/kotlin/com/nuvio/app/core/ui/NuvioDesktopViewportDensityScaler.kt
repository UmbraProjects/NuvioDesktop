package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.nuvio.app.desktopDisplayMetrics
import com.nuvio.app.isDesktop

/**
 * The window's real, unscaled density — i.e. what [LocalDensity] would be without
 * [NuvioDesktopViewportDensityScaler]'s artificial inflation. Compose Desktop's `SwingPanel`
 * bridges native AWT peers (the mpv video surface, WebView-based trailer surface) using
 * whatever [LocalDensity] is ambient at the call site to position/size that peer — but those
 * peers are real OS windows sized in actual screen pixels, not scaled UI density. If they
 * inherit the inflated density meant for Compose-rendered UI, they end up sized as a fraction
 * of the real window instead of filling it. Native-bridging call sites should provide this
 * density locally instead of the ambient (possibly inflated) [LocalDensity].
 */
val LocalNuvioBaseDensity = compositionLocalOf { Density(1f) }

/** The adaptive viewport density before the user's optional app UI scale is applied. */
val LocalNuvioViewportDensity = compositionLocalOf { Density(1f) }

val LocalNuvioDesktopCompactWindow = compositionLocalOf { false }

@Composable
fun NuvioDesktopViewportDensityScaler(
    modifier: Modifier = Modifier,
    uiScalePercent: Int = 0,
    content: @Composable () -> Unit,
) {
    if (!isDesktop) {
        BoxWithConstraints(modifier = modifier) {
            content()
        }
        return
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val ambientDensity = LocalDensity.current
        // This is snapshot-backed on desktop. Moving the host window to another monitor causes a
        // recomposition even if the Compose window itself kept exactly the same dimensions.
        val displayMetrics = desktopDisplayMetrics()
        val baseDensity = remember(ambientDensity, displayMetrics) {
            Density(
                density = displayMetrics?.density ?: ambientDensity.density,
                fontScale = ambientDensity.fontScale,
            )
        }
        // BoxWithConstraints was measured using the density supplied by Compose Desktop. Convert
        // its dimensions to the newly detected monitor density as well, since AWT and Compose can
        // observe a per-monitor DPI transition one frame apart on Windows.
        val viewportWidthDp = maxWidth.value * ambientDensity.density / baseDensity.density
        val viewportHeightDp = maxHeight.value * ambientDensity.density / baseDensity.density
        val useCompactWindowLayout = remember(viewportWidthDp, viewportHeightDp, baseDensity, displayMetrics) {
            val displaySize = displayMetrics?.sizePx ?: return@remember false
            val displayWidthDp = displaySize.width / baseDensity.density
            val displayHeightDp = displaySize.height / baseDensity.density
            viewportWidthDp < displayWidthDp * NUVIO_DESKTOP_COMPACT_WINDOW_FRACTION ||
                viewportHeightDp < displayHeightDp * NUVIO_DESKTOP_COMPACT_WINDOW_FRACTION
        }
        val viewportScale = remember(viewportWidthDp, viewportHeightDp, useCompactWindowLayout, baseDensity, displayMetrics) {
            if (useCompactWindowLayout) {
                1f
            } else {
                // Never let a windowed viewport render below the scale it would get at fullscreen
                // (capped at the 1.0 reference). Without this floor, a window between the compact
                // threshold and full display size renders a proportionally shrunken miniature of
                // the fullscreen layout — on a high-DPI display that is up to a 2x size drop the
                // moment a dragged window crosses the compact boundary, and it only recovers at
                // exactly fullscreen. Flooring keeps intermediate window sizes at normal UI size
                // (they reveal less content instead) and makes the compact hand-off continuous.
                val fullscreenScale = displayMetrics?.sizePx?.let { displaySize ->
                    minOf(
                        displaySize.width / baseDensity.density / NUVIO_DESKTOP_REFERENCE_WIDTH_DP,
                        displaySize.height / baseDensity.density / NUVIO_DESKTOP_REFERENCE_HEIGHT_DP,
                    )
                }
                val minScale = fullscreenScale
                    ?.coerceIn(NUVIO_DESKTOP_MIN_VIEWPORT_SCALE, 1f)
                    ?: NUVIO_DESKTOP_MIN_VIEWPORT_SCALE
                minOf(
                    viewportWidthDp / NUVIO_DESKTOP_REFERENCE_WIDTH_DP,
                    viewportHeightDp / NUVIO_DESKTOP_REFERENCE_HEIGHT_DP,
                ).coerceIn(minScale, NUVIO_DESKTOP_MAX_VIEWPORT_SCALE)
            }
        }
        val userScale = remember(uiScalePercent) {
            1f + uiScalePercent.coerceIn(-25, 25) / 100f
        }
        val viewportDensity = remember(baseDensity, viewportScale) {
            Density(
                density = baseDensity.density * viewportScale,
                fontScale = baseDensity.fontScale,
            )
        }
        val scaledDensity = remember(viewportDensity, userScale) {
            Density(
                density = viewportDensity.density * userScale,
                fontScale = viewportDensity.fontScale,
            )
        }

        CompositionLocalProvider(
            LocalDensity provides scaledDensity,
            LocalNuvioBaseDensity provides baseDensity,
            LocalNuvioViewportDensity provides viewportDensity,
            LocalNuvioDesktopCompactWindow provides useCompactWindowLayout,
        ) {
            content()
        }
    }
}

private const val NUVIO_DESKTOP_REFERENCE_WIDTH_DP = 1920f
private const val NUVIO_DESKTOP_REFERENCE_HEIGHT_DP = 1080f
private const val NUVIO_DESKTOP_MIN_VIEWPORT_SCALE = 0.5f
private const val NUVIO_DESKTOP_MAX_VIEWPORT_SCALE = 2f
private const val NUVIO_DESKTOP_COMPACT_WINDOW_FRACTION = 0.5f
