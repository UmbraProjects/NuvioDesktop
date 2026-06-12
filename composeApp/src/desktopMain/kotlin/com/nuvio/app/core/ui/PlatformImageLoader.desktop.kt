package com.nuvio.app.core.ui

import coil3.ImageLoader

internal actual fun ImageLoader.Builder.configurePlatformImageLoader(): ImageLoader.Builder =
    components {
        add(AnimatedSkiaImageDecoder.Factory())
    }
