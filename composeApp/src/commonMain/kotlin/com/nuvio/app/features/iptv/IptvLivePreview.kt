package com.nuvio.app.features.iptv

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * fork:live-tv-front-door - the pick's channel playing, muted, in the pick card. [onShowing] turns true once a picture is
 * up (until then the card's own art shows) and false again if the channel fails; [url] null stops it.
 */
@Composable
internal expect fun IptvLivePreview(
    url: String?,
    headers: Map<String, String>,
    modifier: Modifier,
    onShowing: (Boolean) -> Unit,
)

/**
 * fork:live-tv-front-door - true while a menu or dialog covers the stage. The preview is a native window, drawn over
 * everything in its box, so it steps aside rather than sit on top of the menu.
 */
internal object IptvPreviewCover {
    private val _covered = MutableStateFlow(false)
    val covered: StateFlow<Boolean> = _covered

    fun set(covered: Boolean) {
        _covered.value = covered
    }
}
