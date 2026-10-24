package com.nuvio.app.core.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.unit.Constraints
import coil3.Extras
import coil3.compose.ConstraintsSizeResolver
import coil3.intercept.Interceptor
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.ImageResult
import coil3.size.Dimension
import coil3.size.Size
import coil3.size.SizeResolver
import kotlin.math.ceil

private val DesktopArtworkSizeKey = Extras.Key(default = false)

// Compose constraints are already physical pixels (including LocalDensity). Do not apply the
// monitor's scale again.
//
// Exact, deliberately. This used to add 1.04x focus headroom and round up to 8 px, so every card
// decoded a few percent larger than its slot and the draw then resampled it a second time on the way
// down — through FilterQuality.High, which is a Mitchell cubic and blurs even at 1:1. Simulated on
// real posters in a 4K TV Mode slot that cost 40% of the achievable sharpness at rest; an exact decode
// lands 1:1 and the draw is a straight blit. The focused card's 1.04x enlarge now magnifies instead,
// and still measured sharper than before (68% vs 57%), because it too used to take two soft passes.
internal fun desktopArtworkDimension(pixels: Int): Int = pixels.coerceAtLeast(1)

// Request-invalidation granularity, separate from the decode size: a slot that drifts inside the
// same 8 px bucket keeps its request instead of restarting the load for every pixel of a resize.
private const val ArtworkSizeBucketPx = 8

private fun Dimension.bucket(): Int? = (this as? Dimension.Pixels)?.px?.let { ceil(it / ArtworkSizeBucketPx.toDouble()).toInt() }

internal fun Size.sameArtworkBucketAs(other: Size?): Boolean =
    other != null && width.bucket() == other.width.bucket() && height.bucket() == other.height.bucket()

// A slot that bounds neither axis leaves Coil with Size.ORIGINAL, and the source then decodes at
// full resolution - silent except in RAM, and the reason hero backdrops used to cost 31.6 MB each.
// Every request used to carry this pin unconditionally; it stays as the backstop for that one case.
// A single unbounded axis needs no backstop and must not get one: Coil sizes from the bounded axis,
// which is exactly what a wrap-height logo wants.
private const val UnboundedArtworkFallbackPx = 1536

internal fun desktopArtworkSize(size: Size): Size {
    val width = (size.width as? Dimension.Pixels)?.let { Dimension.Pixels(desktopArtworkDimension(it.px)) }
    val height = (size.height as? Dimension.Pixels)?.let { Dimension.Pixels(desktopArtworkDimension(it.px)) }
    if (width == null && height == null) {
        return Size(
            Dimension.Pixels(UnboundedArtworkFallbackPx),
            Dimension.Pixels(UnboundedArtworkFallbackPx),
        )
    }
    return Size(width ?: Dimension.Undefined, height ?: Dimension.Undefined)
}

/** Waits for layout, and invalidates the request only when its pixel-size bucket changes. */
internal class DesktopArtworkSizeResolver : SizeResolver, LayoutModifier {
    private val constraintsResolver = ConstraintsSizeResolver()
    var requestSize: Size? by mutableStateOf(null)
        private set

    override suspend fun size(): Size = desktopArtworkSize(constraintsResolver.size())

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        constraintsResolver.setConstraints(constraints)
        if (constraints.maxWidth > 0 && constraints.maxHeight > 0) {
            requestSize = desktopArtworkSize(Size(
                if (constraints.hasBoundedWidth) Dimension.Pixels(constraints.maxWidth) else Dimension.Undefined,
                if (constraints.hasBoundedHeight) Dimension.Pixels(constraints.maxHeight) else Dimension.Undefined,
            ))
        }
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}

/**
 * Whether this decode was sized from its destination. The hero paths deliberately are not, so any
 * measurement that lumps the two together is measuring two different changes at once.
 */
internal fun Options.isDesktopArtworkSized(): Boolean = extras[DesktopArtworkSizeKey] == true

/**
 * Whether this decode was given a destination size at all, for telemetry.
 *
 * Deliberately NOT [isDesktopArtworkSized]. That reads the cache-key tag, which the hero declines
 * (`tagForCacheKey = false`, because a hero and a card must not pin the same bitmap under one key) —
 * so hero decodes were reported as `unsized` while being perfectly well sized from layout. On a
 * browsing log that reads as "35 unsized decodes costing 252 MB", which looks exactly like a sizing
 * bug and is not one. The honest question is whether a size reached the decoder, and `Size.ORIGINAL`
 * is precisely Coil's "no size given".
 */
internal fun Options.hasDesktopArtworkDestinationSize(): Boolean = size != Size.ORIGINAL

internal fun ImageRequest.Builder.desktopArtworkCacheSize(): ImageRequest.Builder = apply {
    extras[DesktopArtworkSizeKey] = true
}

/** A hero and a small card may share compressed bytes, but must not pin the same large bitmap. */
internal class DesktopArtworkSizeInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        if (chain.request.extras[DesktopArtworkSizeKey] != true) return chain.proceed()
        return chain.withRequest(chain.request.newBuilder()
            .memoryCacheKeyExtra("nuvio:artwork-size", "${chain.size}:${chain.request.scale}")
            .build()).proceed()
    }
}
