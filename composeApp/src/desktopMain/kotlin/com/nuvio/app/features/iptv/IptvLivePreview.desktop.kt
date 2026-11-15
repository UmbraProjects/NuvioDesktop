package com.nuvio.app.features.iptv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import com.nuvio.app.core.ui.LocalNuvioBaseDensity
import com.nuvio.app.features.player.PlayerControlsState
import com.nuvio.app.features.player.PlayerHeroTrailerState
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.NativePlayerController
import com.nuvio.app.features.player.desktop.NativePlayerHost
import kotlinx.coroutines.delay

private val previewLog = Logger.withTag("IptvLivePreview")

/**
 * fork:live-tv-front-door - a small mpv of its own (as the hero trailers have), muted and chromeless, with the IPTV
 * options the player uses. The window stays parked at one pixel until the picture is moving, so a channel that never
 * opens leaves the card's art in place.
 */
@Composable
internal actual fun IptvLivePreview(
    url: String?,
    headers: Map<String, String>,
    modifier: Modifier,
    onShowing: (Boolean) -> Unit,
) {
    val supported = DesktopHostOs.current == DesktopHostOs.WINDOWS || DesktopHostOs.current == DesktopHostOs.MACOS
    val latestOnShowing = rememberUpdatedState(onShowing)
    if (!supported || url == null) {
        LaunchedEffect(Unit) { latestOnShowing.value(false) }
        return
    }
    val host = remember {
        NativePlayerHost().apply {
            isFocusable = false
            focusTraversalKeysEnabled = false
        }
    }
    val controller = remember(host) { NativePlayerController(host) }
    var painted by remember { mutableStateOf(false) }
    var showing by remember(url) { mutableStateOf(false) }
    var failed by remember(url) { mutableStateOf(false) }

    DisposableEffect(host) {
        host.onFirstPaint = { painted = true }
        onDispose { host.onFirstPaint = null }
    }
    DisposableEffect(controller) {
        onDispose {
            latestOnShowing.value(false)
            controller.dispose()
        }
    }

    LaunchedEffect(controller, url) {
        showing = false
        latestOnShowing.value(false)
        controller.updateControls(
            PlayerControlsState(
                isLoading = true,
                heroTrailer = PlayerHeroTrailerState(enabled = true, backgroundColor = "#000000", muted = true, volume = 0),
            ),
        )
        previewLog.i { "preview ${url.take(80)}" }
        val settings = IptvRepository.settings.value
        controller.attach(
            sourceUrl = url,
            sourceAudioUrl = null,
            sourceHeaders = headers,
            playWhenReady = true,
            initialPositionMs = 0L,
            nvidiaRtxSuperResolutionEnabled = false,
            nvidiaRtxHdrEnabled = false,
            animeSvpEnabled = false,
            onError = { message ->
                previewLog.w { "preview failed: $message" }
                failed = true
            },
            controlsPageUrlSuffix = "?heroTrailer=1&chromeless=1",
            initMpvOptions = buildList {
                add("mute=yes")
                add("volume=0")
                add("ytdl=no")
                add("network-timeout=${if (url.isHlsPlaylistUrl()) IPTV_HLS_NETWORK_TIMEOUT_SECONDS else IPTV_NETWORK_TIMEOUT_SECONDS}")
                add(iptvDemuxerOption(url))
                // A preview needs no rewind: keep the cache small and in memory.
                add("cache-secs=10")
                if (settings.proxy.enabled) add("http-proxy=${IptvProxyRelay.mpvProxyUrl()}")
            },
        )
        controller.setMpvProperty("mute", "yes")
        // Fill the card, cropping rather than letterboxing.
        controller.setMpvProperty("panscan", "1.0")
    }

    LaunchedEffect(controller, url) {
        var lastPosition = -1L
        while (true) {
            val snapshot = controller.snapshotOffUiThread()
            val moving = snapshot.isPlaying && snapshot.positionMs > 300L && snapshot.positionMs != lastPosition
            lastPosition = snapshot.positionMs
            if (!showing && painted && moving && !failed) {
                showing = true
                latestOnShowing.value(true)
            }
            if (showing && failed) {
                showing = false
                latestOnShowing.value(false)
            }
            delay(200L)
        }
    }

    // The picture wears the card's rounded corners (the window is re-cut when the card resizes).
    val cornerPx = (10 * LocalDensity.current.density).toInt()
    DisposableEffect(host, controller, cornerPx) {
        val listener = object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) = controller.setCornerRadius(cornerPx)
        }
        host.addComponentListener(listener)
        onDispose { host.removeComponentListener(listener) }
    }
    LaunchedEffect(controller, cornerPx, showing) { controller.setCornerRadius(cornerPx) }

    Box(modifier = modifier) {
        CompositionLocalProvider(LocalDensity provides LocalNuvioBaseDensity.current) {
            SwingPanel(
                factory = { host },
                modifier = if (showing) Modifier.fillMaxSize() else Modifier.align(Alignment.BottomEnd).requiredSize(1.dp),
                background = Color.Black,
            )
        }
    }
}
