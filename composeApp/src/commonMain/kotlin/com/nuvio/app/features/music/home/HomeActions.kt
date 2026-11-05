package com.nuvio.app.features.music.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.PosterZoomOverlayAction
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.music.MusicAlbum
import com.nuvio.app.features.music.MusicAlbumSummary
import com.nuvio.app.features.music.MusicArtistSummary
import com.nuvio.app.features.music.MusicCatalog
import com.nuvio.app.features.music.MusicItemMenu
import com.nuvio.app.features.music.MusicPlayback
import com.nuvio.app.features.music.MusicTopResult
import com.nuvio.app.features.music.MusicTrack
import com.nuvio.app.features.music.library.musicKeyFocusRing
import com.nuvio.app.features.music.rememberMusicMenu
import com.nuvio.app.features.music.youTubeSingleAlbum
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

/*
 * fork:music-home
 *
 * What the first screen's buttons and menus do (phase 4): the action row that takes the letter strip's place once Down
 * is pressed with a top match, the More menus on the result rows, and the fanart behind the screen. Starting music goes
 * through the player like everywhere else; an artist's Essentials come from its YouTube Music page (one request, kept
 * by the catalog for 30 minutes, and asked for as soon as the match settles).
 */

/** How to start something: now, shuffled, after the song playing, or at the end of the queue. */
internal enum class PlayHow { Now, Shuffled, Next, Queue }

/** Starts music for the first screen and opens pages; long jobs (opening albums, an artist page) run in [scope]. */
internal class HomeRunner(
    private val scope: CoroutineScope,
    private val catalog: MusicCatalog,
    val openAlbum: (String) -> Unit,
    val openArtist: (String) -> Unit,
) {
    private val player get() = MusicPlayback.player

    private fun start(album: MusicAlbum, how: PlayHow, label: String) {
        when (how) {
            PlayHow.Now -> player.play(album)
            PlayHow.Shuffled -> player.play(album, shuffled = true)
            PlayHow.Next -> if (player.playNext(album)) NuvioToastController.show("Playing next: $label")
            PlayHow.Queue -> if (player.enqueue(album)) NuvioToastController.show("Queued $label")
        }
    }

    fun track(track: MusicTrack, how: PlayHow) = start(youTubeSingleAlbum(track), how, track.title)

    fun album(browseId: String, title: String, how: PlayHow) {
        scope.launch {
            val album = catalog.album(browseId)
            if (album == null) NuvioToastController.show("Couldn't open $title") else start(album, how, title)
        }
    }

    fun essentials(artistId: String, name: String, how: PlayHow) {
        scope.launch {
            val mix = catalog.artist(artistId)?.let(::searchEssentials)
            if (mix == null) {
                NuvioToastController.show("YouTube Music lists no most played songs for $name")
            } else {
                start(mix, how, "$name: Essentials")
            }
        }
    }

    fun topAlbums(artistId: String, name: String) {
        scope.launch {
            val ids = catalog.artist(artistId)?.let(::topAlbumIds).orEmpty()
            val albums = ids.map { id -> async { catalog.album(id) } }.awaitAll().filterNotNull()
            if (albums.isEmpty()) NuvioToastController.show("Couldn't open $name's albums") else player.playAlbums(albums)
        }
    }

    /** [tracks] as one queue, in order or shuffled: the centre panel's remixes, live takes or acoustic versions. */
    fun playAll(tracks: List<MusicTrack>, title: String, top: MusicTopResult, shuffled: Boolean) {
        if (tracks.isEmpty()) {
            NuvioToastController.show("Nothing to play here yet")
            return
        }
        val queue = MusicAlbum(
            browseId = "home:" + title.lowercase(),
            title = title,
            artists = top.artists,
            year = null,
            kind = "Mix",
            thumbnailUrl = top.thumbnailUrl,
            tracks = tracks.distinctBy { it.videoId }.mapIndexed { i, t -> t.copy(trackNumber = i + 1) },
            audioPlaylistId = null,
        )
        player.play(queue, shuffled = shuffled)
    }

    /**
     * What one press of an action-row button does for [top]. More is opened by the row itself. [kind] and [tracks] are
     * the chip in the line and what the centre panel can play, for Play all and Shuffle all.
     */
    fun run(action: HomeAction, top: MusicTopResult, kind: QueryKind? = null, tracks: List<MusicTrack> = emptyList()) {
        val artistId = top.artists.firstOrNull()?.browseId
        when (action) {
            HomeAction.PlayEssentials -> artistId?.let { essentials(it, top.title, PlayHow.Now) }
            HomeAction.ShuffleEssentials -> artistId?.let { essentials(it, top.title, PlayHow.Shuffled) }
            HomeAction.PlayTopAlbums -> artistId?.let { topAlbums(it, top.title) }
            HomeAction.OpenArtist -> artistId?.let(openArtist)
            HomeAction.PlayAlbum -> top.albumBrowseId?.let { album(it, top.title, PlayHow.Now) }
            HomeAction.ShuffleAlbum -> top.albumBrowseId?.let { album(it, top.title, PlayHow.Shuffled) }
            HomeAction.QueueAlbum -> top.albumBrowseId?.let { album(it, top.title, PlayHow.Queue) }
            HomeAction.OpenAlbum -> top.albumBrowseId?.let(openAlbum)
            HomeAction.PlayTrack -> top.videoId?.let { track(topTrack(top, it), PlayHow.Now) }
            HomeAction.QueueTrack -> top.videoId?.let { track(topTrack(top, it), PlayHow.Queue) }
            HomeAction.PlayAll -> playAll(tracks, "${top.title}: ${kind?.plural ?: "results"}", top, shuffled = false)
            HomeAction.ShuffleAll -> playAll(tracks, "${top.title}: ${kind?.plural ?: "results"}", top, shuffled = true)
            HomeAction.More -> Unit
        }
    }

    /** The action row's More: what the row has no room for. */
    fun moreFor(top: MusicTopResult): List<PosterZoomOverlayAction> {
        val artistId = top.artists.firstOrNull()?.browseId
        return when (topMatchKind(top)) {
            TopMatchKind.Artist -> listOfNotNull(
                artistId?.let { PosterZoomOverlayAction(Icons.Rounded.SkipNext, "Play Essentials next", onSelected = { essentials(it, top.title, PlayHow.Next) }) },
                artistId?.let { PosterZoomOverlayAction(Icons.Rounded.PlaylistAdd, "Add Essentials to queue", onSelected = { essentials(it, top.title, PlayHow.Queue) }) },
                artistId?.let { PosterZoomOverlayAction(Icons.Rounded.Person, "Open artist page", onSelected = { openArtist(it) }, group = 1) },
            )
            TopMatchKind.Album -> listOfNotNull(
                top.albumBrowseId?.let { PosterZoomOverlayAction(Icons.Rounded.SkipNext, "Play next", onSelected = { album(it, top.title, PlayHow.Next) }) },
                artistId?.let { PosterZoomOverlayAction(Icons.Rounded.Person, "Go to the artist", onSelected = { openArtist(it) }, group = 1) },
            )
            TopMatchKind.Track -> listOfNotNull(
                top.videoId?.let { PosterZoomOverlayAction(Icons.Rounded.SkipNext, "Play next", onSelected = { track(topTrack(top, it), PlayHow.Next) }) },
                artistId?.let { PosterZoomOverlayAction(Icons.Rounded.Person, "Go to the artist", onSelected = { openArtist(it) }, group = 1) },
            )
        }
    }

    /** A YouTube Music song row's More menu. */
    fun songMenu(row: MergedRow): List<PosterZoomOverlayAction> {
        val song = row.song ?: return emptyList()
        return listOfNotNull(
            PosterZoomOverlayAction(Icons.Rounded.PlayArrow, "Play", onSelected = { track(song, PlayHow.Now) }),
            PosterZoomOverlayAction(Icons.Rounded.SkipNext, "Play next", onSelected = { track(song, PlayHow.Next) }),
            PosterZoomOverlayAction(Icons.Rounded.PlaylistAdd, "Add to queue", onSelected = { track(song, PlayHow.Queue) }),
            row.video?.let { video -> PosterZoomOverlayAction(Icons.Rounded.SmartDisplay, "Play the YouTube video", onSelected = { track(video, PlayHow.Now) }) },
            song.albumBrowseId?.let { id ->
                PosterZoomOverlayAction(Icons.Rounded.Album, song.albumTitle?.let { "Open $it" } ?: "Open the album", onSelected = { openAlbum(id) }, group = 1)
            },
            song.artists.firstOrNull()?.browseId?.let { id -> PosterZoomOverlayAction(Icons.Rounded.Person, "Go to the artist", onSelected = { openArtist(id) }, group = 1) },
        )
    }

    fun albumMenu(summary: MusicAlbumSummary): List<PosterZoomOverlayAction> = listOfNotNull(
        PosterZoomOverlayAction(Icons.Rounded.PlayArrow, "Play", onSelected = { album(summary.browseId, summary.title, PlayHow.Now) }),
        PosterZoomOverlayAction(Icons.Rounded.Shuffle, "Shuffle", onSelected = { album(summary.browseId, summary.title, PlayHow.Shuffled) }),
        PosterZoomOverlayAction(Icons.Rounded.SkipNext, "Play next", onSelected = { album(summary.browseId, summary.title, PlayHow.Next) }),
        PosterZoomOverlayAction(Icons.Rounded.PlaylistAdd, "Add to queue", onSelected = { album(summary.browseId, summary.title, PlayHow.Queue) }),
        PosterZoomOverlayAction(Icons.Rounded.Album, "Open", onSelected = { openAlbum(summary.browseId) }, group = 1),
        summary.artists.firstOrNull()?.browseId?.let { id -> PosterZoomOverlayAction(Icons.Rounded.Person, "Go to the artist", onSelected = { openArtist(id) }, group = 1) },
    )

    fun videoMenu(video: MusicTrack): List<PosterZoomOverlayAction> = listOf(
        PosterZoomOverlayAction(Icons.Rounded.PlayArrow, "Play", onSelected = { track(video, PlayHow.Now) }),
        PosterZoomOverlayAction(Icons.Rounded.SkipNext, "Play next", onSelected = { track(video, PlayHow.Next) }),
        PosterZoomOverlayAction(Icons.Rounded.PlaylistAdd, "Add to queue", onSelected = { track(video, PlayHow.Queue) }),
    )

    fun artistMenu(artist: MusicArtistSummary): List<PosterZoomOverlayAction> = listOf(
        PosterZoomOverlayAction(Icons.Rounded.PlayArrow, "Play Essentials", onSelected = { essentials(artist.browseId, artist.name, PlayHow.Now) }),
        PosterZoomOverlayAction(Icons.Rounded.Shuffle, "Shuffle Essentials", onSelected = { essentials(artist.browseId, artist.name, PlayHow.Shuffled) }),
        PosterZoomOverlayAction(Icons.Rounded.PlaylistAdd, "Add Essentials to queue", onSelected = { essentials(artist.browseId, artist.name, PlayHow.Queue) }),
        PosterZoomOverlayAction(Icons.Rounded.Person, "Open artist page", onSelected = { openArtist(artist.browseId) }, group = 1),
    )
}

/** The top match as a track, for a song or video match. */
internal fun topTrack(top: MusicTopResult, videoId: String) =
    MusicTrack(videoId = videoId, title = top.title, artists = top.artists, durationSeconds = 0, trackNumber = 1, isAvailable = true, coverUrl = top.thumbnailUrl)

private fun HomeAction.icon(): ImageVector = when (this) {
    HomeAction.PlayEssentials, HomeAction.PlayAlbum, HomeAction.PlayTrack, HomeAction.PlayAll -> Icons.Rounded.PlayArrow
    HomeAction.ShuffleEssentials, HomeAction.ShuffleAlbum, HomeAction.ShuffleAll -> Icons.Rounded.Shuffle
    HomeAction.PlayTopAlbums -> Icons.Rounded.LocalFireDepartment
    HomeAction.OpenArtist -> Icons.Rounded.Person
    HomeAction.QueueAlbum, HomeAction.QueueTrack -> Icons.Rounded.PlaylistAdd
    HomeAction.OpenAlbum -> Icons.Rounded.Album
    HomeAction.More -> Icons.Rounded.MoreHoriz
}

/**
 * The action row, in the letter strip's slot and at its height. The first button is the main action and takes the
 * focus ([primaryFocus]). Up goes back to the letter strip ([onUp]); Down goes on to the panels.
 */
@Composable
internal fun HomeActionRow(
    top: MusicTopResult,
    kind: QueryKind?,
    tracks: List<MusicTrack>,
    primaryFocus: FocusRequester,
    runner: HomeRunner,
    onUp: () -> Unit,
) {
    val actions = remember(top, kind) { homeActions(top, kind) }
    val menu = rememberMusicMenu()
    MusicItemMenu(menu) { runner.moreFor(top) }
    Row(
        Modifier.onPreviewKeyEvent { event ->
            if (event.key == Key.DirectionUp && event.type == KeyEventType.KeyDown) {
                onUp()
                true
            } else {
                false
            }
        },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEachIndexed { index, action ->
            val more = action == HomeAction.More
            HomeActionButton(
                action = action,
                label = action.labelFor(kind),
                primary = index == 0,
                modifier = Modifier
                    .then(if (index == 0) Modifier.focusRequester(primaryFocus) else Modifier)
                    .then(if (more) Modifier.onGloballyPositioned { menu.bounds = it.boundsInWindow() } else Modifier),
                onClick = { if (more) menu.openFromKeys() else runner.run(action, top, kind, tracks) },
            )
        }
    }
}

@Composable
private fun HomeActionButton(action: HomeAction, label: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(24.dp)
    val content = if (primary) colors.onAccent else colors.textPrimary
    Row(
        modifier
            .height(48.dp)
            .musicKeyFocusRing(shape)
            .clip(shape)
            .background(if (primary) colors.accent else Color.Black.copy(alpha = 0.35f), shape)
            .border(1.dp, if (primary) Color.Transparent else Color.White.copy(alpha = 0.14f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(action.icon(), contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal, color = content, maxLines = 1)
    }
}

/**
 * The top match's picture behind the whole screen (an artist's fanart, else the match's own cover), crossfading as the
 * match changes and gone when the line is cleared. A wash of the page colour over it keeps the text readable.
 */
@Composable
internal fun HomeBackdrop(url: String?, reducedMotion: Boolean) {
    val ground = MaterialTheme.nuvio.colors.background
    Crossfade(targetState = url, animationSpec = tween(if (reducedMotion) 0 else 600), label = "homeBackdrop") { shown ->
        if (shown != null) {
            Box(Modifier.fillMaxSize()) {
                NuvioAsyncImage(model = shown, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(ground.copy(alpha = 0.55f), ground.copy(alpha = 0.78f), ground.copy(alpha = 0.94f))),
                    ),
                )
            }
        }
    }
}
