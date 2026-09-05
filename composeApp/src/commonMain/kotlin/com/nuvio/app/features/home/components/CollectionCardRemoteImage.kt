package com.nuvio.app.features.home.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

/**
 * @param staticImageUrl a non-animated cover for the same folder, when one exists. Desktop shows it
 *   immediately and swaps to the animation once its frames are decoded, because decoding a 73-frame
 *   GIF takes ~700ms and the card is otherwise blank for that whole time on a cold start.
 */
@Composable
internal expect fun CollectionCardRemoteImage(
    imageUrl: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    animateIfPossible: Boolean = false,
    staticImageUrl: String? = null,
)
