package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest

internal enum class NuvioDesktopImageScaling {
    Auto,
    Disabled,
}

@Composable
internal expect fun NuvioAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    placeholder: Painter? = null,
    error: Painter? = null,
    fallback: Painter? = error,
    onLoading: ((AsyncImagePainter.State.Loading) -> Unit)? = null,
    onSuccess: ((AsyncImagePainter.State.Success) -> Unit)? = null,
    onError: ((AsyncImagePainter.State.Error) -> Unit)? = null,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = DefaultAlpha,
    colorFilter: ColorFilter? = null,
    filterQuality: FilterQuality? = null,
    clipToBounds: Boolean = true,
    desktopImageScaling: NuvioDesktopImageScaling = NuvioDesktopImageScaling.Auto,
)

/**
 * Applies the same request size [NuvioAsyncImage] will use when it displays this artwork.
 *
 * Prefetches exist to have the bitmap ready before the card asks for it, which only works if they
 * warm the entry the card actually asks for. A builder left unsized falls back to
 * `SizeResolver.ORIGINAL` at `Precision.EXACT`, so the prefetch decoded the source at full
 * resolution — and because Coil validates a cached entry against the requested size rather than
 * keying on it, that oversized bitmap is what the display request then found and kept. Sixteen
 * prefetched posters could occupy a large share of the whole memory cache before the user had
 * scrolled anywhere.
 */
internal expect fun ImageRequest.Builder.nuvioArtworkRequestSize(): ImageRequest.Builder
