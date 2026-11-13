package com.nuvio.app.features.tonight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.features.details.HeroTrailerAudioState
import com.nuvio.app.features.details.components.HeroTrailerPlayerSurface
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.trailer.HeroTrailerMetadataService
import com.nuvio.app.features.trailer.TrailerPlaybackSource
import kotlinx.coroutines.delay

/*
 * fork:tonight - phase 3: a trailer in a fixed 16:9 frame, on the same player and resolver as the
 * Home and details heroes (HeroTrailerMetadataService, HeroTrailerPlayerSurface). The video is a
 * native window, so nothing in Compose can be drawn over it: every label sits around the frame.
 */

/**
 * Plays [title]'s trailer inside [modifier] (give it a fixed 16:9 size). Calls [onFinished] when
 * the trailer ends, after [limitMs] when set (a 20 second taste), or once it is clear there is
 * no trailer to play (after a short note, so the screen can move on).
 */
@Composable
internal fun TonightTrailerFrame(
    title: TonightTitle,
    modifier: Modifier,
    limitMs: Long? = null,
    onFinished: (played: Boolean) -> Unit,
    onReclaimFocus: () -> Unit,
) {
    val volume by HeroTrailerAudioState.volume.collectAsStateWithLifecycle()
    val finished by rememberUpdatedState(onFinished)
    var source by remember(title.key) { mutableStateOf<TrailerPlaybackSource?>(null) }
    var missing by remember(title.key) { mutableStateOf(false) }
    var ready by remember(title.key) { mutableStateOf(false) }

    LaunchedEffect(title.key) {
        val resolved = runCatching { HeroTrailerMetadataService.resolve(title.type, title.id) }.getOrNull()
        if (resolved == null) {
            missing = true
            delay(1_500)
            finished(false)
        } else {
            source = resolved
        }
    }
    if (limitMs != null) {
        LaunchedEffect(title.key, ready) {
            if (!ready) return@LaunchedEffect
            delay(limitMs)
            finished(true)
        }
    }

    Box(
        modifier = modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF101018)),
        contentAlignment = Alignment.Center,
    ) {
        // The backdrop shows while the trailer is found and buffers, so the frame is never black.
        val art = title.backdrop ?: title.poster
        if (art != null && !ready) {
            NuvioAsyncImage(model = art, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
        }
        val current = source
        when {
            missing -> Text(
                "No trailer for ${title.name}. Moving on…",
                color = Color.White, fontSize = 22.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp),
            )
            current == null -> Text("Finding the trailer…", color = Color.White, fontSize = 22.sp)
            else -> {
                if (!ready) Text("Starting…", color = Color.White, fontSize = 22.sp)
                HeroTrailerPlayerSurface(
                    sourceUrl = current.videoUrl,
                    sourceAudioUrl = current.audioUrl,
                    sourceHeaders = current.headers, // fork:trailer-signed-in
                    playWhenReady = true,
                    muted = false,
                    volume = volume,
                    keyboardNavigationEnabled = false,
                    modifier = Modifier.fillMaxSize(),
                    onReady = { ready = true },
                    onEnded = { finished(true) },
                    onError = {
                        // The resolved URLs can be refused: try the next way to play the same
                        // trailer, and once those are used up, drop the cached answer and move on.
                        val next = current.fallback
                        if (next != null) {
                            source = next
                        } else {
                            missing = true
                            TonightPool.background { HeroTrailerMetadataService.invalidate(title.type, title.id) }
                            finished(false)
                        }
                    },
                    onReclaimFocus = onReclaimFocus,
                )
            }
        }
    }
}

/** A title the user wants to keep for another night (Channel Flick's Up). */
internal fun TonightTitle.toMetaPreview(): MetaPreview = MetaPreview(
    id = id,
    type = type,
    name = name,
    poster = poster,
    banner = backdrop,
    releaseInfo = year?.toString(),
    genres = genres,
    imdbRating = rating?.toString(),
)
