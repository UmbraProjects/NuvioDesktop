package com.nuvio.app.features.home

import androidx.compose.ui.graphics.Color
import coil3.Image

internal expect fun extractHeroAccentColor(image: Image): Color?

/**
 * fork:music-search - the image's main colours, most present first, each a different hue (up to [count]); a greyscale
 * image gives its average grey. For a backdrop made of a cover's colours instead of the cover itself.
 */
internal expect fun extractHeroPaletteColors(image: Image, count: Int = 3): List<Color>
