package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter

/**
 * A painter for a bundled raster asset that is drawn smaller than its source.
 *
 * Compose's ordinary bitmap draw hands the whole reduction to one GPU sample with no mip pyramid,
 * which aliases past ~2x no matter which [androidx.compose.ui.graphics.FilterQuality] is asked
 * for. The bundled rating logos are 48px sources drawn at 16.dp, so at the density 1.0 a 1080p
 * window runs at they were being reduced 3x that way — enough source data for a clean result, but
 * sampled badly. This routes them through the same box-pre-reduction pipeline [NuvioAsyncImage]
 * uses for posters.
 *
 * Only worth reaching for on assets that are genuinely minified; an upscaled or 1:1 asset gains
 * nothing and the painter falls back to a direct draw anyway.
 */
@Composable
internal expect fun rememberNuvioDownscaledPainter(bitmap: ImageBitmap): Painter
