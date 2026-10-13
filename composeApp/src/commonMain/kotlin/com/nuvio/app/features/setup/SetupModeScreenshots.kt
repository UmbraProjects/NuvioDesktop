package com.nuvio.app.features.setup

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import com.nuvio.app.features.home.HomeDisplayMode

/**
 * Bundled capture of [mode], or an empty painter if it can't be read.
 *
 * Deliberately real screenshots rather than a drawn schematic: the shape of each mode has not
 * meaningfully changed in many releases, so a slightly dated capture still communicates far more
 * than a diagram. They are thumbnails in the cards and open full size on click.
 *
 * Loaded from loose files rather than compose resources so the ~2MB of screenshots stay out of the
 * app jar, which every update re-downloads in full.
 */
@Composable
internal expect fun setupModeScreenshotPainter(mode: HomeDisplayMode): Painter
