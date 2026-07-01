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

@Composable
fun NuvioDesktopViewportDensityScaler(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!isDesktop) {
        BoxWithConstraints(modifier = modifier) {
            content()
        }
        return
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val baseDensity = LocalDensity.current
        val viewportScale = remember(maxWidth, maxHeight) {
            minOf(
                maxWidth.value / NUVIO_DESKTOP_REFERENCE_WIDTH_DP,
                maxHeight.value / NUVIO_DESKTOP_REFERENCE_HEIGHT_DP,
            ).coerceIn(1f, NUVIO_DESKTOP_MAX_VIEWPORT_SCALE)
        }
        val scaledDensity = remember(baseDensity, viewportScale) {
            Density(
                density = baseDensity.density * viewportScale,
                fontScale = baseDensity.fontScale,
            )
        }

        CompositionLocalProvider(
            LocalDensity provides scaledDensity,
            LocalNuvioBaseDensity provides baseDensity,
        ) {
            content()
        }
    }
}

private const val NUVIO_DESKTOP_REFERENCE_WIDTH_DP = 1920f
private const val NUVIO_DESKTOP_REFERENCE_HEIGHT_DP = 1080f
private const val NUVIO_DESKTOP_MAX_VIEWPORT_SCALE = 2f
