package com.nuvio.app.features.music

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.nuvio.app.features.music.identity.MusicAccentScope
import com.nuvio.app.features.music.library.MusicArtistScreen
import kotlinx.serialization.Serializable

/*
 * fork:music
 *
 * Everything App.kt needs from the music module, in one file, so the upstream file only carries
 * small tagged hooks (`// fork:music`) and a merge of a new upstream release stays cheap.
 *
 * Hooks in App.kt: the `Music` tab in AppScreenTab and its navigation entries, the tab body
 * ([MusicTabHost]), the two routes ([musicRoutes]), the root player hosts ([MusicRootHosts]) and
 * the content inset that keeps the mini-player bar clear of the screens ([rememberMusicBarInset]).
 */

@Serializable
data class MusicAlbumRoute(val browseId: String)

/**
 * An artist, by YouTube Music channel id. The one artist route: Search results and the Library
 * both open it (a library id is the channel id), and the page decides whether it is saved.
 */
@Serializable
data class MusicArtistRoute(val browseId: String)

/**
 * The Music tab. Like Live TV it is its own screen, built and disposed with the tab, rather than a
 * mode of the shared home screen. [navigate] takes one of the music routes above.
 */
@Composable
fun MusicTabHost(
    navigate: (Any) -> Unit,
    topChromePadding: Dp?,
    modifier: Modifier = Modifier,
) {
    MusicTabScreen(
        onAlbumClick = { navigate(MusicAlbumRoute(it)) },
        onArtistClick = { navigate(MusicArtistRoute(it)) },
        modifier = modifier.fillMaxSize(),
        topChromePadding = topChromePadding ?: 0.dp,
    )
}

/**
 * The music player lives at the root so a track keeps playing across screens. A film on screen
 * ([playerHidden]) pauses it and takes the bar away; everywhere else the content makes room for
 * the bar by the returned inset.
 */
@Composable
fun rememberMusicBarInset(playerHidden: Boolean): Dp {
    val state by MusicPlayback.player.state.collectAsStateWithLifecycle()
    val collapsed by MusicPlayback.barCollapsed.collectAsStateWithLifecycle()
    return if (state.isActive && !playerHidden && !collapsed) MusicMiniPlayerHeight else 0.dp
}

/** The lyric view and the mini-player bar, above the app's own content. Place in the root Box. */
@Composable
fun BoxScope.MusicRootHosts(hidden: Boolean) {
    MusicNowPlayingHost(
        hidden = hidden,
        modifier = Modifier.fillMaxSize().zIndex(14f),
    )
    MusicPlayerHost(
        hidden = hidden,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .zIndex(15f),
    )
}

/**
 * The album and artist pages. [guardedBack] is App.kt's guarded pop (one pop per
 * entry, only while that entry is current); [navigate] is its resumed-only navigate.
 */
fun NavGraphBuilder.musicRoutes(
    guardedBack: @Composable (NavBackStackEntry) -> (() -> Unit),
    navigate: (Any) -> Unit,
) {
    composable<MusicAlbumRoute> { backStackEntry ->
        val route = backStackEntry.toRoute<MusicAlbumRoute>()
        MusicAccentScope(viewedAlbumBrowseId = route.browseId, viewedFirst = true) {
            MusicAlbumScreen(
                browseId = route.browseId,
                onBack = guardedBack(backStackEntry),
                onArtistClick = { navigate(MusicArtistRoute(it)) },
                onAlbumClick = { navigate(MusicAlbumRoute(it)) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
    composable<MusicArtistRoute> { backStackEntry ->
        val route = backStackEntry.toRoute<MusicArtistRoute>()
        MusicAccentScope(viewedArtistId = route.browseId, viewedFirst = true) {
            MusicArtistScreen(
                artistId = route.browseId,
                onBack = guardedBack(backStackEntry),
                onAlbumClick = { navigate(MusicAlbumRoute(it)) },
                onArtistClick = { navigate(MusicArtistRoute(it)) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
