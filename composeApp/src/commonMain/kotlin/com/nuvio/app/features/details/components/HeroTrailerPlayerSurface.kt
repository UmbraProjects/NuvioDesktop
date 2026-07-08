package com.nuvio.app.features.details.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun HeroTrailerPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    playWhenReady: Boolean,
    muted: Boolean,
    volume: Int,
    keyboardNavigationEnabled: Boolean,
    modifier: Modifier,
    onReady: () -> Unit,
    onEnded: () -> Unit,
    onError: () -> Unit,
    onMuteToggle: () -> Unit = {},
    onVolumeChange: (Int) -> Unit = {},
    // The native trailer surface never keeps OS focus; if a chrome click transiently focuses its
    // WebView2, this is invoked so the details screen can reclaim keyboard focus.
    onReclaimFocus: () -> Unit = {},
)
