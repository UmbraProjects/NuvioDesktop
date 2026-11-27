package com.nuvio.app.features.home.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.nuvio.app.core.ui.NuvioAsyncImage as AsyncImage
import com.nuvio.app.core.ui.disableAnimation
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest

/**
 * Animated collection art, with the still shown first.
 *
 * The bytes are disk-cached, but decoding them is the expensive half: a 73-frame GIF measures at
 * ~700ms (`readPixels` is 60-80% of that, and it is inherent to the codec). Decoded frames live
 * only in heap, so every cold start paid that again and the card sat grey throughout.
 *
 * Upstream avoids the problem on desktop by rendering [staticImageUrl] *instead of* the animation.
 * This renders it *underneath*: the still is an ordinary image that decodes in milliseconds, so the
 * artwork appears at once and the animation replaces it the moment its frames are ready.
 *
 * [animateIfPossible] is a real switch, not a hint. It used to control only whether the still was
 * drawn underneath, so a surface that asked for no animation still downloaded and fully decoded the
 * GIF and then played it. Now it also decides the URL (see the caller) and, when the folder has no
 * separate still to fall back to, tells the decoder to hand back the first frame instead.
 */
@Composable
internal actual fun CollectionCardRemoteImage(
    imageUrl: String,
    contentDescription: String,
    modifier: Modifier,
    contentScale: ContentScale,
    animateIfPossible: Boolean,
    staticImageUrl: String?,
    playAnimation: Boolean,
) {
    val context = LocalPlatformContext.current
    val request = remember(context, imageUrl, animateIfPossible) {
        ImageRequest.Builder(context)
            .data(imageUrl)
            // Distinct memory-cache keys for the two decodes of one URL. Home animates a folder and
            // Search does not, so without this the animated entry cached by one surface would be
            // handed to the other and play there regardless.
            .memoryCacheKey(
                if (animateIfPossible) "home-collection:$imageUrl" else "home-collection-still:$imageUrl",
            )
            .diskCacheKey(imageUrl)
            // The bytes are the same either way, so the disk cache is shared; only the decode differs.
            .apply { if (!animateIfPossible) disableAnimation() }
            .build()
    }

    // Frame one of the animation itself, decoded as an ordinary still.
    //
    // The cover was the wrong picture to hold underneath: it is a *different image*, so the card
    // showed the cover and then cut to the animation's opening frame — a visible jump on every
    // card, every time. The same URL with animation disabled decodes exactly the frame the
    // animation is about to open on, so there is nothing to see when it takes over.
    //
    // It is also an ordinary bitmap, so unlike the animation it is allowed into Coil's memory cache
    // (see DesktopArtworkMemoryCache) and comes back instantly on scroll-back. It shares its cache
    // key with the non-animating surfaces above, so Search and Library have usually paid for it
    // already.
    val firstFrameRequest = remember(context, imageUrl, animateIfPossible) {
        if (!animateIfPossible) {
            null
        } else {
            ImageRequest.Builder(context)
                .data(imageUrl)
                .memoryCacheKey("home-collection-still:$imageUrl")
                .diskCacheKey(imageUrl)
                .disableAnimation()
                .build()
        }
    }

    if (firstFrameRequest == null) {
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
        return
    }

    // Separate cover art, when the folder has one.
    //
    // Unfocused cards show the cover (e.g. cover.png) rather than the focus artwork (e.g. hover.png
    // or a focus GIF). When focus arrives, the cover stays composed underneath while the focus art
    // decodes, and is revealed immediately again when focus leaves — no grey flash either way.
    val coverRequest = remember(context, staticImageUrl, imageUrl) {
        staticImageUrl
            ?.takeIf { it.isNotBlank() && it != imageUrl }
            ?.let { cover ->
                ImageRequest.Builder(context)
                    .data(cover)
                    .memoryCacheKey("home-collection-static:$cover")
                    .diskCacheKey(cover)
                    .build()
            }
    }

    val layers = collectionCardImageLayers(
        hasDistinctCover = coverRequest != null,
        playAnimation = playAnimation,
    )

    // Both re-armed on Loading rather than latched on Success. Latching left a hole: an animated
    // image is deliberately excluded from Coil's memory cache to keep a single owner for its
    // frames, so its request restarts whenever the card is recomposed from scratch or its size
    // bucket drifts — and with the layer beneath already torn down, the card went
    // picture -> GREY -> animation. Each layer now stays up until the one above it is really
    // drawing.
    var animationReady by remember(imageUrl, layers.composeAnimation) { mutableStateOf(false) }
    var firstFrameReady by remember(imageUrl, layers.composeFirstFrame) { mutableStateOf(false) }

    val animationCarriesDescription = layers.composeAnimation && animationReady
    val firstFrameCarriesDescription =
        layers.composeFirstFrame && !animationCarriesDescription && (!layers.composeCover || firstFrameReady)
    val coverCarriesDescription =
        layers.composeCover && !animationCarriesDescription && !firstFrameCarriesDescription

    Box(modifier = modifier) {
        if (layers.composeCover && coverRequest != null) {
            AsyncImage(
                model = coverRequest,
                contentDescription = if (coverCarriesDescription) contentDescription else null,
                modifier = Modifier.matchParentSize(),
                contentScale = contentScale,
            )
        }
        // Only composed while the focus artwork should be shown (or when there is no separate cover
        // to fall back to). While active, leaving it composed underneath the animation keeps frame
        // one on screen if Coil restarts the uncached animated decode.
        if (layers.composeFirstFrame) {
            AsyncImage(
                model = firstFrameRequest,
                contentDescription = if (firstFrameCarriesDescription) contentDescription else null,
                modifier = Modifier.matchParentSize(),
                contentScale = contentScale,
                onLoading = { firstFrameReady = false },
                onSuccess = { firstFrameReady = true },
                onError = { firstFrameReady = false },
            )
        }
        // Focus-only playback: an unfocused card never composes this layer, so it never decodes
        // the animation either. Focus adds the layer on top of the still/first frame.
        if (layers.composeAnimation) {
            AsyncImage(
                model = request,
                // Null while a layer below is carrying the description, so the card is not
                // announced twice.
                contentDescription = if (animationCarriesDescription) contentDescription else null,
                modifier = Modifier.matchParentSize(),
                contentScale = contentScale,
                onLoading = { animationReady = false },
                onSuccess = { animationReady = true },
                onError = { animationReady = false },
            )
        }
    }
}

internal data class CollectionCardImageLayers(
    val composeCover: Boolean,
    val composeFirstFrame: Boolean,
    val composeAnimation: Boolean,
)

internal fun collectionCardImageLayers(
    hasDistinctCover: Boolean,
    playAnimation: Boolean,
): CollectionCardImageLayers = CollectionCardImageLayers(
    composeCover = hasDistinctCover,
    composeFirstFrame = playAnimation || !hasDistinctCover,
    composeAnimation = playAnimation,
)
