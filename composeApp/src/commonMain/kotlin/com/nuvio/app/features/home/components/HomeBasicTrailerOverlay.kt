package com.nuvio.app.features.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import co.touchlab.kermit.Logger
import com.nuvio.app.core.format.formatReleaseDateForDisplay
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.features.details.HeroTrailerAudioState
import com.nuvio.app.features.details.formatRuntimeForDisplay
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.trailer.HeroTrailerMetadataService
import com.nuvio.app.features.trailer.TrailerPlaybackSource
import kotlinx.coroutines.delay

private val log = Logger.withTag("HomeBasicTrailer")

/**
 * Basic mode's hero trailer, hosted by the home screen rather than by the hero.
 *
 * The hero cannot host it. In Basic it is an item inside the rows list, so Compose recycles it the
 * moment it scrolls out of view — see [HomeHeroTrailerSurface]'s `onSurfaceDisposed`, which exists
 * because of exactly that. A trailer parented there dies when the user scrolls, and can only be
 * *started* while the hero happens to be on screen, which is the opposite of what this is for: the
 * point is to scroll down to your rows, point at something, and play its trailer.
 *
 * So this sits outside the list, always composed while Basic is the display mode, and plays
 * whatever a [HomeHeroTrailerRequest] names. It is full screen because there is nowhere else for it
 * to go — the hero it would otherwise occupy is somewhere up the list.
 *
 * [heroItem] is what the hero is currently showing, used for a request that names nothing (T with
 * the pointer off the rows) and as the subject of auto-play. Auto-play deliberately stays about the
 * hero and only while [heroOnScreen]: a full-screen video that starts on its own while the user is
 * reading a row three screens down is an ambush, not a feature.
 */
@Composable
internal fun HomeBasicTrailerOverlay(
    enabled: Boolean,
    heroItem: MetaPreview?,
    heroOnScreen: Boolean,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playerSettings by PlayerSettingsRepository.uiState.collectAsState()
    val homeActive by HomeHeroTrailerGate.homeActive.collectAsState()
    val request by HomeHeroTrailerManualTrigger.requests.collectAsState(initial = null)
    val muted by HeroTrailerAudioState.muted.collectAsState()
    val volume by HeroTrailerAudioState.volume.collectAsState()

    var subject by remember { mutableStateOf<MetaPreview?>(null) }
    var source by remember { mutableStateOf<TrailerPlaybackSource?>(null) }
    var playing by remember { mutableStateOf(false) }
    var surfaceReady by remember { mutableStateOf(false) }
    // The hero title auto-play has already had its turn at, so dismissing a trailer does not simply
    // arm the same timer to start it again a few seconds later.
    var autoplaySpentKey by remember { mutableStateOf<String?>(null) }

    val heroKey = heroItem?.let { "${it.type}:${it.id}" }

    fun stop() {
        playing = false
        surfaceReady = false
        source = null
        subject = null
    }

    // Announce that a targeted request has somewhere to land, so the hover preview knows whether to
    // offer its trailer button.
    DisposableEffect(enabled) {
        if (enabled) HomeHeroTrailerManualTrigger.addTargetedHost()
        onDispose { if (enabled) HomeHeroTrailerManualTrigger.removeTargetedHost() }
    }

    LaunchedEffect(enabled) {
        if (!enabled) stop()
    }

    // Publishes "a trailer is showing" for the Escape mapping and the hover preview's stand-down.
    LaunchedEffect(playing, enabled) {
        if (enabled) HomeHeroTrailerManualTrigger.setActive(playing)
    }
    DisposableEffect(enabled) {
        onDispose { if (enabled) HomeHeroTrailerManualTrigger.setActive(false) }
    }

    LaunchedEffect(request?.token, enabled) {
        val pending = request ?: return@LaunchedEffect
        if (!enabled || !homeActive) return@LaunchedEffect
        // Toggle: the same input that starts a trailer puts it away.
        if (playing) {
            log.i { "dismissing on request ${pending.token}" }
            stop()
            onDismissed()
            return@LaunchedEffect
        }
        val target = pending.item ?: heroItem ?: return@LaunchedEffect
        if (target.type == "collection") return@LaunchedEffect
        log.i { "resolving ${target.type}:${target.id} (${target.name})" }
        val resolved = HeroTrailerMetadataService.resolve(target.type, target.id)
        if (resolved == null) {
            log.i { "no trailer available for ${target.type}:${target.id}" }
            return@LaunchedEffect
        }
        subject = target
        source = resolved
        surfaceReady = false
        playing = true
    }

    LaunchedEffect(
        enabled,
        heroKey,
        heroOnScreen,
        homeActive,
        playerSettings.heroTvTrailerEnabled,
        playerSettings.heroTvTrailerDelaySeconds,
    ) {
        if (!enabled || !playerSettings.heroTvTrailerEnabled) return@LaunchedEffect
        if (!homeActive || !heroOnScreen || playing) return@LaunchedEffect
        val item = heroItem ?: return@LaunchedEffect
        if (item.type == "collection") return@LaunchedEffect
        if (autoplaySpentKey == heroKey) return@LaunchedEffect
        HomeHeroTrailerGate.startupGraceRemainingMillis().let { grace -> if (grace > 0L) delay(grace) }
        delay(playerSettings.heroTvTrailerDelaySeconds.coerceAtLeast(0) * 1_000L)
        if (playing) return@LaunchedEffect
        autoplaySpentKey = heroKey
        val resolved = HeroTrailerMetadataService.resolve(item.type, item.id) ?: return@LaunchedEffect
        subject = item
        source = resolved
        surfaceReady = false
        playing = true
    }

    val activeSubject = subject
    val activeSource = source
    if (!enabled || !playing || activeSubject == null || activeSource == null) return

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        // The surface stays 1px until its first frame, so without this the window would show
        // whatever was behind it — in Basic that is the hero, i.e. a different title's artwork.
        if (!surfaceReady) {
            val artwork = activeSubject.banner ?: activeSubject.poster
            if (!artwork.isNullOrBlank()) {
                NuvioAsyncImage(
                    model = artwork,
                    contentDescription = activeSubject.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }

        HomeHeroTrailerSurface(
            sourceUrl = activeSource.videoUrl,
            sourceAudioUrl = activeSource.audioUrl,
            playWhenReady = playing,
            muted = muted,
            volume = volume,
            backgroundColor = Color.Black,
            logoUrl = activeSubject.logo,
            title = activeSubject.name,
            meta = activeSubject.trailerMetaLine(),
            description = activeSubject.description
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                .orEmpty(),
            modifier = Modifier.fillMaxSize(),
            // Nobody navigates during a full-screen trailer, so no edge band steals it away.
            navDismissEdge = "none",
            onReady = { surfaceReady = true },
            onEnded = {
                stop()
                onDismissed()
            },
            onError = {
                log.i { "playback error for ${activeSubject.type}:${activeSubject.id}" }
                stop()
                onDismissed()
            },
            onVolumeChange = { HeroTrailerAudioState.setVolume(it) },
            onSurfaceDisposed = onDismissed,
        )
    }
}

/** The subject's own genre/year/runtime line — never the hero's, which is a different title. */
private fun MetaPreview.trailerMetaLine(): String =
    buildList {
        genres.firstOrNull()?.takeIf(String::isNotBlank)?.let(::add)
        releaseInfo?.takeIf(String::isNotBlank)
            ?.let(::formatReleaseDateForDisplay)?.takeIf(String::isNotBlank)?.let(::add)
        formatRuntimeForDisplay(runtime)?.takeIf(String::isNotBlank)?.let(::add)
    }.joinToString("   •   ")
