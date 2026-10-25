package com.nuvio.app.core.ui

import coil3.ImageLoader

internal expect fun ImageLoader.Builder.configurePlatformImageLoader(): ImageLoader.Builder

/** Decoders that must be offered an SVG before Coil's own `SvgDecoder` sees it. */
internal expect fun coil3.ComponentRegistry.Builder.addPlatformSvgDecoders()