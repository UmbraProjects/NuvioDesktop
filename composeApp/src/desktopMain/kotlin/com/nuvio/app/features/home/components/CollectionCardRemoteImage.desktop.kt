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

    // Only worth a second request when there is a genuinely different still to show first.
    val placeholderUrl = staticImageUrl
        ?.takeIf { animateIfPossible && it.isNotBlank() && it != imageUrl }

    if (placeholderUrl == null) {
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
        return
    }

    // Keyed on the animation, so scrolling to a different folder starts from its own still rather
    // than briefly showing the previous card's animation.
    var animationReady by remember(imageUrl) { mutableStateOf(false) }
    val placeholderRequest = remember(context, placeholderUrl) {
        ImageRequest.Builder(context)
            .data(placeholderUrl)
            .memoryCacheKey("home-collection-static:$placeholderUrl")
            .diskCacheKey(placeholderUrl)
            .build()
    }

    Box(modifier = modifier) {
        if (!animationReady) {
            AsyncImage(
                model = placeholderRequest,
                contentDescription = contentDescription,
                modifier = Modifier.matchParentSize(),
                contentScale = contentScale,
            )
        }
        AsyncImage(
            model = request,
            // Null while the still is carrying the description, so the card is not announced twice.
            contentDescription = if (animationReady) contentDescription else null,
            modifier = Modifier.matchParentSize(),
            contentScale = contentScale,
            onSuccess = { animationReady = true },
        )
    }
}
