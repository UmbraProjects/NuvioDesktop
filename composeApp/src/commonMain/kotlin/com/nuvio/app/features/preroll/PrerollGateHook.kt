package com.nuvio.app.features.preroll

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nuvio.app.features.discover.DiscoverAiSettingsRepository
import com.nuvio.app.features.player.PlayerAutoPlayMode
import com.nuvio.app.features.player.PlayerLaunch

/*
 * fork:preroll
 *
 * App.kt's player route uses this as an early return, so PlayerScreen keeps its place and its
 * indentation in the upstream file:
 *
 *     if (!prerollGateCleared(route.launchId.toString(), launch)) return@composable
 *     PlayerScreen(...)
 *
 * While the pre-show (trailers, clips, trivia) is up it owns the screen and this returns false;
 * once it finishes or is skipped, or when playback is not eligible for one, it returns true and
 * the gate leaves composition.
 */
@Composable
fun prerollGateCleared(launchKey: String, launch: PlayerLaunch): Boolean {
    // Fresh, tracked playback only: not a resume, trailer, rewatch or playlist. And only when some part
    // of the pre-show is switched on; otherwise the gate would hold the film on a black screen while it
    // looked up details and artwork for nothing.
    val eligible = launch.initialPositionMs <= 0L &&
        !launch.disableProgressTracking &&
        launch.autoPlayMode != PlayerAutoPlayMode.Playlist &&
        preshowHasAnythingToShow()
    var cleared by remember(launchKey) { mutableStateOf(!eligible) }
    if (!cleared) {
        PrerollGate(
            launchKey = launchKey,
            metaType = launch.parentMetaType,
            metaId = launch.parentMetaId,
            eligible = true,
            title = launch.title,
        ) {
            // The gate calls this once it has finished; the player takes over on the next frame.
            SideEffect { cleared = true }
        }
    }
    // The "previously on" cards come after the pre-show, so the viewer sees trailers and then the recap.
    return cleared && previouslyOnGateCleared(launchKey, launch)
}

/** Trailers, a clip slot or trivia is on. Theme songs only play behind trivia, so they do not count. */
private fun preshowHasAnythingToShow(): Boolean =
    PrerollSettings.trailerCount.value > 0 ||
        PreshowSettings.enabledSlots.value.isNotEmpty() ||
        DiscoverAiSettingsRepository.snapshot().triviaEnabled
