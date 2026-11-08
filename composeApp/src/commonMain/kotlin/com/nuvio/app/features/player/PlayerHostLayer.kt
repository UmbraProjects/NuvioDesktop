package com.nuvio.app.features.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.youtube.clampMiniBox
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/*
 * fork:pip-browse
 *
 * A player App.kt hosts outside the navigation graph, so it can outlive its route: the route
 * publishes the player's content here instead of drawing it, and [PlayerHostLayer] draws it over
 * the screens, filling the window while the route is the current screen. For PiP that leaves the
 * app usable ([PlayerBrowsePip]) the route is popped and the player carries on in the corner box,
 * [detached] from any route; its exit button pushes a route for the same launch again, which picks
 * the same player back up, and its X ends it.
 */
@Stable
internal class HostedPlayer(val launchId: Long) {
    /** The player's route was taken away for the corner box: nothing on the back stack owns it. */
    var detached by mutableStateOf(false)

    /** The player itself (PlayerScreen and what the route wraps it in), as its route last built it. */
    var content: @Composable (Modifier) -> Unit by mutableStateOf({})
}

/** How long a new player waits for the one it replaces to be gone; mirrors the playlist handoff's wait. */
private const val HOSTED_PLAYER_SWAP_TIMEOUT_MS = 1_500L

/**
 * Draws [hosted]: filling the window while its route is the current screen ([currentPlayerLaunchId]),
 * in the corner box while [HostedPlayer.detached], and not at all otherwise (its route covered by
 * another screen, as an in-route player would be). Placed over the NavHost so it covers the screens
 * as the player route did; overlays drawn after it stay over it.
 */
@Composable
internal fun PlayerHostLayer(hosted: HostedPlayer?, currentPlayerLaunchId: Long?, modifier: Modifier = Modifier) {
    val player = hosted?.takeIf { it.detached || it.launchId == currentPlayerLaunchId }
    // Two players must never be composed at once (PlayerHandoff): a different one waits until the
    // last has gone, a frame or two at most.
    var shownId by remember { mutableStateOf<Long?>(null) }
    val wantedId = player?.launchId
    LaunchedEffect(wantedId) {
        if (wantedId == shownId) return@LaunchedEffect
        shownId = null
        if (wantedId == null) return@LaunchedEffect
        withTimeoutOrNull(HOSTED_PLAYER_SWAP_TIMEOUT_MS) { PlayerHandoff.activeRoutes.first { it == 0 } }
        shownId = wantedId
    }
    // Only this layer's player may go to the corner box; one drawn inside its route has classic PiP only.
    DisposableEffect(player) {
        PlayerBrowsePip.setHostedPlayerAvailable(player != null)
        onDispose { PlayerBrowsePip.setHostedPlayerAvailable(false) }
    }
    if (player == null || player.launchId != shownId) return
    val boxState by PlayerBrowsePip.box.collectAsState()
    BoxWithConstraints(modifier.fillMaxSize()) {
        PlayerBrowsePip.frameDp = maxWidth.value to maxHeight.value
        val inCorner = player.detached
        // A window made smaller since keeps the box inside it.
        val box = clampMiniBox(boxState, maxWidth.value, maxHeight.value)
        key(player.launchId) {
            Box(
                if (inCorner) {
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = box.rightDp.dp, bottom = box.bottomDp.dp)
                        .size(box.widthDp.dp, box.heightDp.dp)
                } else {
                    Modifier.fillMaxSize()
                },
            ) {
                // What the route counted while it drew the player: one player alive, playback going.
                DisposableEffect(Unit) {
                    PlayerHandoff.routeEntered()
                    com.nuvio.app.features.librarypvr.LibraryPvrScheduler.setPlaybackActive(true)
                    onDispose {
                        com.nuvio.app.features.librarypvr.LibraryPvrScheduler.setPlaybackActive(false)
                        PlayerHandoff.routeLeft()
                        PlayerBrowsePip.setActive(false)
                    }
                }
                player.content(Modifier.fillMaxSize())
            }
        }
    }
}
