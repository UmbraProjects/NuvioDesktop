package com.nuvio.app.features.preroll

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.playbackEpisodeNumber
import com.nuvio.app.features.details.playbackSeasonNumber
import com.nuvio.app.features.library.LibraryClock
import com.nuvio.app.features.player.PlayerAutoPlayMode
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.watched.WatchedRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.preshow_starting
import nuvio.composeapp.generated.resources.preshow_starting_hint
import org.jetbrains.compose.resources.stringResource

private val log = Logger.withTag("PreviouslyOn")

private const val DETAILS_WAIT_MS = 8_000L
private const val PACK_WAIT_MS = 12_000L
private const val ARTWORK_WAIT_MS = 2_500L
private const val WAITING_INDICATOR_DELAY_MS = 800L
private const val HOUR_MS = 3_600_000L

/** A series episode started from the beginning, with the feature on: the only playback that can get a recap. */
internal fun previouslyOnEligible(launch: PlayerLaunch): Boolean =
    PreshowSettings.previouslyOnEnabled.value &&
        launch.parentMetaType == "series" &&
        launch.seasonNumber != null && launch.episodeNumber != null &&
        launch.initialPositionMs <= 0L &&
        !launch.disableProgressTracking &&
        launch.autoPlayMode != PlayerAutoPlayMode.Playlist

/** The episode to recap for this launch, or null when there is none or the break has not been long enough. */
internal fun previouslyOnTargetForLaunch(meta: MetaDetails, launch: PlayerLaunch, nowMs: Long): PreviouslyOnTarget? {
    val season = launch.seasonNumber ?: return null
    val episode = launch.episodeNumber ?: return null
    val episodes = meta.videos.mapNotNull { video ->
        val s = video.playbackSeasonNumber() ?: return@mapNotNull null
        val e = video.playbackEpisodeNumber() ?: return@mapNotNull null
        PreviouslyOnEpisode(
            season = s,
            episode = e,
            title = video.title,
            overview = video.overview,
            watchedAtMs = WatchedRepository.watchedAtEpochMs(launch.parentMetaId, launch.parentMetaType, s, e),
        )
    }
    val target = previouslyOnTargetFor(episodes, season, episode) ?: return null
    val gap = PreshowSettings.previouslyOnBreakHours.value * HOUR_MS
    return target.takeIf {
        previouslyOnGapPassed(it.watchedAtMs, nowMs, PreshowSettings.previouslyOnBreakOnly.value, gap)
    }
}

/**
 * Plays the "previously on" cards before the episode's player is created, then lets the player in.
 * Same placement as the pre-show gate and for the same reason: the player must not exist yet.
 * Returns true when the player may be shown.
 */
@Composable
fun previouslyOnGateCleared(launchKey: String, launch: PlayerLaunch): Boolean {
    val eligible = remember(launchKey) { previouslyOnEligible(launch) }
    var cleared by remember(launchKey) { mutableStateOf(!eligible) }
    var pack by remember(launchKey) { mutableStateOf<TriviaPack?>(null) }
    var artwork by remember(launchKey) { mutableStateOf(TriviaArtwork()) }
    var showWaiting by remember(launchKey) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    if (cleared) return true

    LaunchedEffect(launchKey) {
        val ready = runCatching {
            val meta = MetaDetailsRepository.peekAny(launch.parentMetaType, launch.parentMetaId)
                ?: withTimeoutOrNull(DETAILS_WAIT_MS) {
                    runCatching { MetaDetailsRepository.fetch(launch.parentMetaType, launch.parentMetaId) }.getOrNull()
                }
            val target = meta?.let { previouslyOnTargetForLaunch(it, launch, LibraryClock.nowEpochMs()) }
            if (meta == null || target == null) {
                null
            } else {
                PreviouslyOnRepository.packFor(meta, target, PACK_WAIT_MS)?.also {
                    log.i { "previously on ${target.label}: ${it.slides.size} cards, ${it.questions.size} questions" }
                }
            }
        }.onFailure { log.w { "previously on failed: $it" } }.getOrNull()
        if (ready == null) {
            cleared = true
            return@LaunchedEffect
        }
        artwork = withTimeoutOrNull(ARTWORK_WAIT_MS) {
            TriviaArtworkRepository.forTitle(launch.parentMetaType, launch.parentMetaId)
        } ?: TriviaArtwork()
        pack = ready
    }
    LaunchedEffect(pack) {
        if (pack == null) {
            delay(WAITING_INDICATOR_DELAY_MS)
            showWaiting = true
        } else {
            showWaiting = false
        }
    }
    LaunchedEffect(pack) { runCatching { focus.requestFocus() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                // While the cards are up they take the keys themselves; before that, Back lets the episode start.
                if (event.type != KeyEventType.KeyDown || pack != null) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Escape, Key.ButtonB, Key.Back -> { cleared = true; true }
                    else -> false
                }
            },
    ) {
        pack?.let { shown ->
            TriviaPreShow(
                pack = shown,
                title = launch.title,
                onFinished = { cleared = true },
                modifier = Modifier.fillMaxSize(),
                artwork = artwork,
                onSkipAll = { cleared = true },
            )
        }
        if (pack == null && showWaiting) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator(color = Color.White.copy(alpha = 0.8f))
                Text(
                    text = stringResource(Res.string.preshow_starting),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.85f),
                )
                Text(
                    text = stringResource(Res.string.preshow_starting_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.55f),
                )
            }
        }
    }
    return false
}
