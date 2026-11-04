package com.nuvio.app.features.music

import com.nuvio.app.features.music.library.musicKeyFocusRing
import com.nuvio.app.features.music.nebula.NebulaMusicSource
import com.nuvio.app.features.music.nebula.NebulaSourceRow
import com.nuvio.app.features.music.nebula.rememberNebulaAlbumController
import androidx.compose.foundation.background
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
import com.nuvio.app.features.music.playlist.PlaylistPicker
import com.nuvio.app.features.music.playlist.MusicPlaylistTracks.forPlaylist
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import com.nuvio.app.features.music.library.LibraryRelease
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import com.nuvio.app.core.ui.NuvioPosterHoverTooltip
import com.nuvio.app.features.details.components.DetailIconAction
import com.nuvio.app.features.music.nebula.MusicSource
import com.nuvio.app.features.music.nebula.NebulaOffer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.nuvio.app.core.ui.arrowFocusDirection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.music.library.LibraryAlbumInfo
import com.nuvio.app.features.music.library.MusicLibraryRepository
import com.nuvio.app.features.music.library.rememberLibraryAlbumInfo
import com.nuvio.app.features.music.booklet.AlbumBookletRepository
import com.nuvio.app.features.music.booklet.SongNotes
import com.nuvio.app.features.music.booklet.PinKeys
import com.nuvio.app.features.music.booklet.Pins
import com.nuvio.app.features.music.booklet.SONG_PART_MADE
import com.nuvio.app.features.music.booklet.TrackNotes
import com.nuvio.app.features.music.booklet.StartHere
import com.nuvio.app.features.music.library.ArtistTopSongs
import com.nuvio.app.features.music.library.ESSENTIAL_ALBUM_COUNT
import com.nuvio.app.features.music.library.EssentialSong
import com.nuvio.app.features.music.library.essentialSongs
import com.nuvio.app.features.music.library.playTopAlbums
import com.nuvio.app.features.music.library.selectTopAlbums
import com.nuvio.app.features.music.library.songRank
import com.nuvio.app.features.music.library.starterPath
import com.nuvio.app.features.music.library.singleKeys
import com.nuvio.app.features.music.library.songKey
import com.nuvio.app.features.music.library.trackPopularity
import com.nuvio.app.features.music.library.songVersions
import com.nuvio.app.features.music.library.versionCounts
import com.nuvio.app.features.music.booklet.SongStories
import com.nuvio.app.features.music.booklet.CutPicks
import com.nuvio.app.features.music.library.CUT_DEFAULT_MINUTES
import com.nuvio.app.features.music.library.CUT_TIMES
import com.nuvio.app.features.music.library.CutMood
import com.nuvio.app.features.music.library.bestOfPick
import com.nuvio.app.features.music.library.cutLength
import com.nuvio.app.features.music.library.cutToFit
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Schedule

/**
 * An album: cover, a Play/Shuffle pair, the Audio/Video choice, the track list, and more from the artist.
 *
 * This is where playback starts. Play and Shuffle hand the album to [player]; a tap on a track
 * jumps inside the queue when this album is already loaded, and starts the album at that track
 * when it is not (see [musicTrackTap]). Flipping Audio/Video while this album plays switches the
 * same song at the same position; otherwise it only sets the mode the next Play starts in.
 */
@Composable
fun MusicAlbumScreen(
    browseId: String,
    onBack: () -> Unit,
    onArtistClick: (browseId: String) -> Unit,
    modifier: Modifier = Modifier,
    onAlbumClick: (browseId: String) -> Unit = {},
    catalog: MusicCatalog = remember { defaultMusicCatalog() },
    player: MusicPlayer = MusicPlayback.player,
) {
    val colors = MaterialTheme.nuvio.colors
    var attempt by remember { mutableStateOf(0) }
    val load = rememberMusicLoad(browseId, attempt) {
        // fork:nebula - an album only the music addon has is built from the addon's own track list, not looked up on YouTube Music.
        val addonAlbum = com.nuvio.app.features.music.nebula.parseAddonAlbumId(browseId)
        if (addonAlbum != null) NebulaMusicSource.openAddonAlbum(addonAlbum)
        else catalog.album(browseId)?.let { libraryEdition(catalog, it) }
    }

    Box(modifier.fillMaxSize().background(colors.background)) {
    // The cover, blurred, as the page's atmosphere (it does not move, so reduced motion needs nothing).
    if (load is MusicLoad.Loaded) AlbumAmbientBackdrop(load.value.thumbnailUrl)
    // The backdrop above runs to the window's bottom edge; only the content makes room for the player bar.
    Column(Modifier.fillMaxSize().padding(top = 12.dp, bottom = LocalMusicBarInset.current)) {
        Box(Modifier.padding(horizontal = 16.dp)) { NuvioBackButton(onClick = onBack) }
        when (load) {
            MusicLoad.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent)
            }
            MusicLoad.Failed -> MusicMessage(
                text = if (MusicRateLimit.isBlocked()) {
                    "YouTube Music is limiting requests right now. Try again in about ${MusicRateLimit.secondsLeft().coerceAtLeast(15)} seconds."
                } else {
                    "Couldn't load this album."
                },
                actionLabel = "Try again",
                onAction = { attempt++ },
            )
            is MusicLoad.Loaded -> AlbumContent(load.value, onArtistClick, onAlbumClick, player, catalog)
        }
    }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlbumContent(
    album: MusicAlbum,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    player: MusicPlayer,
    catalog: MusicCatalog,
) {
    val colors = MaterialTheme.nuvio.colors
    val state by player.state.collectAsStateWithLifecycle()
    var chosenMode by rememberSaveable(album.browseId) { mutableStateOf(player.state.value.mode) }
    var descriptionOpen by rememberSaveable(album.browseId) { mutableStateOf(false) }
    var flipped by rememberSaveable(album.browseId) { mutableStateOf(false) }
    // null follows playback (the disc comes out while the album plays); a tap on the button takes over.
    var discChoice by rememberSaveable(album.browseId) { mutableStateOf<Boolean?>(null) }
    val reducedMotion = rememberMusicReducedMotion()
    // Looks the album's artist up in the background (not saved) so an album opened from Search gets
    // the same label, mood, CD art and palette as one from the library. A saved artist is left alone.
    val albumArtistId = albumArtistToPreview(album)
    LaunchedEffect(albumArtistId) {
        albumArtistId?.let { MusicLibraryRepository.openPreview(it) }
    }
    // What the music library knows about this album: set when it is a release of a saved or previewed artist.
    val info = rememberLibraryAlbumInfo(album.browseId)

    val loaded = state.holds(album)
    // fork:nebula - null unless a music addon link is set; then the album can be played from the addon.
    var nebulaRetry by remember(album.browseId) { mutableStateOf(0) }
    val nebula = rememberNebulaAlbumController(album, player, nebulaRetry)
    val playingNebula = state.track?.let(NebulaMusicSource::owns) == true
    val wantNebula = nebula?.active == true
    // The source the person picked is not the one this album is playing from: Play starts it over in the chosen one.
    val startsFresh = !loaded || playingNebula != wantNebula
    // Which tracks can play depends on the source: a track YouTube Music greys out may exist on the addon, and the reverse.
    val view = if (wantNebula) nebula?.preparedAlbum ?: album else album
    // The keyboard starts on the first song that plays, not on the object (turning the sleeve is for fun, not the reason
    // the page was opened). Asked a few times: the page root's own first-control request lands first.
    val firstTrackFocus = remember(album.browseId) { FocusRequester() }
    val firstPlayable = view.tracks.indexOfFirst { it.isAvailable }
    LaunchedEffect(album.browseId, firstPlayable >= 0) {
        if (firstPlayable < 0) return@LaunchedEffect
        repeat(10) {
            delay(80)
            if (runCatching { firstTrackFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    fun startAlbum(trackIndex: Int, mode: MusicPlayMode, shuffled: Boolean) {
        if (wantNebula) nebula?.play(trackIndex, mode, shuffled)
        else player.play(album, trackIndex = trackIndex, mode = mode, shuffled = shuffled)
    }
    // While this album plays, the player's mode is the truth: a failed switch to video leaves it on audio.
    val mode = if (loaded) state.mode else chosenMode

    val playing = loaded && state.isPlaying
    val discOut = discChoice ?: playing
    // fork:essentials - the artist's best songs and albums for the right-hand column, and the popularity bars and SINGLE
    // badges in the track list. Deezer's top songs and the library's own ranking; nothing here needs the AI.
    val artistInfo = info?.artist
    val deezerId = artistInfo?.deezerId
    LaunchedEffect(deezerId) { deezerId?.let { ArtistTopSongs.ensure(it) } }
    LaunchedEffect(artistInfo?.id) { artistInfo?.id?.let { MusicLibraryRepository.ensureTopAlbums(it) } }
    val topSongs = deezerId?.let { ArtistTopSongs.state.collectAsStateWithLifecycle().value[it] }.orEmpty()
    val artistReleases = artistInfo?.releases.orEmpty()
    val essentialSongList = remember(topSongs, artistReleases) { essentialSongs(topSongs, artistReleases) }
    val popularity = remember(topSongs, view.tracks) { trackPopularity(view.tracks.map { it.title }, topSongs) }
    val singles = remember(artistReleases) { singleKeys(artistReleases) }
    val hereKeys = remember(view.tracks) { view.tracks.map { songKey(it.title) }.toSet() }
    val hereReleaseKey = info?.release?.key
    val essentialAlbums = remember(artistInfo, album.browseId, hereReleaseKey) {
        artistInfo?.let { a ->
            val top = selectTopAlbums(a, ESSENTIAL_ALBUM_COUNT)
            // Only the really ranked ones: the rest of the five are just the newest, which is what this column replaces.
            top.releases.take(top.rankedCount).mapIndexed { i, r ->
                EssentialAlbum(r, i + 1, here = r.ytBrowseId == album.browseId || r.key == hereReleaseKey)
            }
        }.orEmpty()
    }
    // "Start here": one short AI recommendation per artist, asked once the albums and songs are known (a kept one shows at once).
    val artistName = artistInfo?.name
    val startAlbums = remember(essentialAlbums) { essentialAlbums.map { a -> a.release.title + a.release.year?.let { y -> " ($y)" }.orEmpty() } }
    val startReady = startAlbums.size >= 2 && (deezerId == null || topSongs.isNotEmpty())
    var startHere by remember(artistName) { mutableStateOf(artistName?.let { StartHere.peek(it) }) }
    LaunchedEffect(artistName, startReady) {
        if (artistName != null && startReady && startHere == null) {
            startHere = StartHere.forArtist(artistName, startAlbums, essentialSongList.map { it.title }.take(5))
        }
    }
    // The album's notes under the case: the cache's (free), and the live ones while this album plays, as on the Stage.
    val bookletRequest = remember(album.browseId, artistInfo) { bookletRequestFor(album, artistInfo) }
    // Bumped after a pin is saved or cleared, so what is kept (and the pins laid over it) is read again.
    var pinTick by remember { mutableStateOf(0) }
    val cachedBooklet = remember(bookletRequest, loaded, pinTick) { bookletRequest?.let { AlbumBookletRepository.peek(it) } }
    val liveUi = if (loaded) rememberBookletUi(album, artistInfo) else null
    val liveBooklet = liveUi as? BookletUi.Ready
    val booklet = liveBooklet?.booklet ?: cachedBooklet
    // A note for every song: the AI's (one request for the album) with MusicBrainz's writers and producers as the fallback.
    // Asked for once the album is playing and its booklet has settled, so the AI is given the album's intro to write from.
    var songNotes by remember(bookletRequest) { mutableStateOf(bookletRequest?.let { TrackNotes.peek(it) } ?: SongNotes()) }
    val notesSettled = liveUi != null && liveUi !is BookletUi.Loading
    LaunchedEffect(bookletRequest, notesSettled) {
        if (bookletRequest != null && notesSettled) songNotes = TrackNotes.load(bookletRequest, booklet?.intro)
    }
    // The line about the song playing: the notes' own word on it, else what is known of it (no AI needed).
    val playingTrack = if (loaded) view.tracks.getOrNull(state.trackIndex) else null
    val playingNote = playingTrack?.let { t ->
        booklet?.trackNotes?.get(state.trackIndex + 1) ?: songNotes.lineFor(state.trackIndex + 1) ?: listOfNotNull(
            "Released as a single".takeIf { songKey(t.title) in singles },
            songRank(t.title, topSongs)?.let { "No. $it of ${artistName ?: "the artist"}'s most played songs" },
            "Track ${state.trackIndex + 1} of ${view.tracks.size}",
        ).joinToString(" · ")
    }
    // The starter path: the albums the recommendation names, in the order it names them, played one after the other.
    val pathScope = rememberCoroutineScope()
    val pathReleases = remember(startHere, essentialAlbums) {
        startHere?.let { text -> starterPath(text, essentialAlbums.map { it.release.title }).map { essentialAlbums[it].release } }.orEmpty()
    }
    val playPath: (() -> Unit)? =
        if (pathReleases.size >= 2) ({ pathScope.launch { playTopAlbums(catalog, pathReleases, shuffled = false) }; Unit }) else null
    // Board 3: the object (case, disc and its two toggles) on the left, everything about the record on the right.
    val objectPanel: @Composable (Dp, Boolean) -> Unit = { caseSize, discUp ->
        AlbumObjectPanel(
            album = album,
            info = info,
            flipped = flipped,
            discOut = discOut || discUp,
            discSpinning = (discOut || discUp) && playing,
            reducedMotion = reducedMotion,
            caseSize = caseSize,
            discUp = discUp,
            onFlip = { flipped = !flipped },
            onDisc = { out -> discChoice = out },
        )
    }
    val actionRow: @Composable () -> Unit = {
        val canPlay = view.tracks.any { it.isAvailable }
        val addonReady = nebula?.offer is NebulaOffer.Available
        // The artist page's round buttons, each named by its tooltip: Play / Pause, Shuffle, Audio, Video and, when the
        // addon has the album, the lossless addon copy as a toggle.
        val actions = listOfNotNull(
            AlbumAction(
                label = when {
                    startsFresh -> "Play"
                    state.playWhenReady -> "Pause"
                    else -> "Resume"
                },
                icon = if (!startsFresh && state.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                active = !startsFresh && state.playWhenReady,
                onClick = {
                    if (!canPlay) Unit
                    else if (!startsFresh) player.togglePlayback()
                    else startAlbum(0, mode, shuffled = false)
                },
            ),
            AlbumAction(
                label = if (!startsFresh && state.shuffled) "Shuffle on" else "Shuffle",
                icon = Icons.Rounded.Shuffle,
                active = !startsFresh && state.shuffled,
                onClick = {
                    if (!canPlay) Unit
                    else if (!startsFresh) player.setShuffled(!state.shuffled)
                    else startAlbum(randomPlayableIndex(view), mode, shuffled = true)
                },
            ),
            AlbumAction("Audio", Icons.Rounded.Headphones, active = mode == MusicPlayMode.Audio, onClick = {
                chosenMode = MusicPlayMode.Audio
                if (loaded) player.switchMode(MusicPlayMode.Audio)
            }),
            AlbumAction("Video", Icons.Rounded.Movie, active = mode == MusicPlayMode.Video, onClick = {
                chosenMode = MusicPlayMode.Video
                if (loaded) player.switchMode(MusicPlayMode.Video)
            }),
            nebula?.takeIf { addonReady }?.let { addon ->
                val onAddon = addon.chosen == MusicSource.Addon
                AlbumAction(
                    label = if (onAddon) "Playing your addon's lossless copy (switch to YouTube Music)" else "Play your addon's lossless copy",
                    icon = MusicIcons.Lossless,
                    active = onAddon,
                    onClick = { addon.choose(if (onAddon) MusicSource.YouTube else MusicSource.Addon) },
                )
            },
            // fork:music-playlists - the album's playable songs into a playlist (YouTube Music's ids, whichever source plays).
            AlbumAction("Add the album to a playlist", Icons.Rounded.PlaylistAdd, active = false, onClick = {
                PlaylistPicker.request(album.tracks.filter { it.isAvailable }.map { it.forPlaylist(album) })
            }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
            actions.forEach { action ->
                NuvioPosterHoverTooltip(title = action.label) {
                    DetailIconAction(label = action.label, icon = action.icon, active = action.active, size = 56.dp, onClick = action.onClick)
                }
            }
        }
    }
    val noteLines: @Composable () -> Unit = {
        // The lines under the buttons say only what needs saying, centred like everything above them.
        nebula?.let { NebulaSourceRow(it, onRetry = { nebulaRetry++ }) } // fork:nebula
        val note = when {
            loaded && state.notice != null -> state.notice
            mode == MusicPlayMode.Video -> "Tracks without a music video are skipped."
            else -> null
        }
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = if (loaded && state.notice != null) colors.warning else colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
        val unavailable = view.tracks.count { !it.isAvailable }
        if (unavailable > 0) {
            Text(
                "$unavailable track${if (unavailable == 1) "" else "s"} unavailable${if (wantNebula) " on your addon" else " on YouTube Music"}.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                textAlign = TextAlign.Center,
            )
        }
        // Where the library and YouTube Music disagree is worth a line: it explains a "missing" song.
        val listed = info?.release?.trackCount
        if (listed != null && listed != album.tracks.size) {
            Text(
                "Your library lists $listed tracks for this release; YouTube Music has ${album.tracks.size}.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
    val facts: @Composable () -> Unit = {
        AlbumFacts(album = album, info = info, onArtistClick = onArtistClick)
        info?.release?.let { release ->
            // Liner notes: genre chips with the mood in the accent, and the description.
            AlbumLinerNotes(
                notes = buildLinerNotes(release, album.year),
                descriptionOpen = descriptionOpen,
                onToggleDescription = { descriptionOpen = !descriptionOpen },
            )
        }
        actionRow()
        noteLines()
    }

    fun tapTrack(index: Int) {
        if (loaded && startsFresh) {
            // fork:nebula - the album is playing from the other source: start it over in the chosen one.
            startAlbum(index, chosenMode, shuffled = false)
        } else when (val tap = musicTrackTap(state, view, index, chosenMode)) {
            is MusicTrackTap.JumpInQueue -> player.jumpTo(tap.trackIndex)
            is MusicTrackTap.StartAlbum -> startAlbum(tap.trackIndex, tap.mode, shuffled = false)
        }
    }
    // fork:essentials - a best song on this very record plays from here; one on another record opens that record.
    val onEssentialSong: (EssentialSong) -> Unit = { song ->
        val key = songKey(song.title)
        val index = view.tracks.indexOfFirst { it.isAvailable && songKey(it.title) == key }
        if (index >= 0) tapTrack(index) else song.release?.ytBrowseId?.let(onAlbumClick)
    }
    val pane: LazyListScope.() -> Unit = {
        item(key = "legend") { TrackLegend() }
        itemsIndexed(view.tracks) { index, track ->
            TrackRow(
                album = album,
                track = track,
                current = loaded && state.trackIndex == index,
                modifier = if (index == firstPlayable) Modifier.focusRequester(firstTrackFocus) else Modifier,
                onAddToPlaylist = album.tracks.getOrNull(index)?.let { own -> { PlaylistPicker.request(listOf(own.forPlaylist(album))) } },
                popularity = popularity.getOrNull(index),
                single = songKey(track.title) in singles,
                onClick = { tapTrack(index) },
            )
        }
    }
    val more = info?.more.orEmpty()
    val moreTitle = "More from ${info?.artist?.name ?: "this artist"}"

    // fork:lenses - the redesigned page (Music settings, "New album page"), on a window wide enough for its three columns.
    val lensesOn = MusicLibraryRepository.state.collectAsStateWithLifecycle().value.settings.albumLenses
    var lens by rememberSaveable(album.browseId) { mutableStateOf(AlbumLens.SongBySong) }
    // The song the lens is about: the one the keyboard or a click is on, else the one playing, else the first that plays.
    var lensFocus by remember(album.browseId) { mutableStateOf<Int?>(null) }
    val lensIndex = (lensFocus ?: state.trackIndex.takeIf { loaded } ?: firstPlayable).takeIf { it in view.tracks.indices }
    val lensTrack = lensIndex?.let { view.tracks[it] }
    val lensArtist = artistName ?: album.artists.firstOrNull()?.name
    val lensSong = lensIndex?.let { i ->
        val t = view.tracks[i]
        LensSong(
            title = t.title,
            trackNumber = t.trackNumber.takeIf { it > 0 } ?: (i + 1),
            trackCount = view.tracks.size,
            durationSeconds = t.durationSeconds,
            single = songKey(t.title) in singles,
            popularity = popularity.getOrNull(i),
            rank = songRank(t.title, topSongs),
            artistName = lensArtist ?: "the artist",
        )
    }
    val lensVersions = remember(lensIndex, view.tracks, topSongs, artistReleases) {
        lensIndex?.let { i ->
            songVersions(i, view.tracks.map { it.title }, view.tracks.map { it.durationSeconds }, album.title, topSongs, artistReleases)
        }.orEmpty()
    }
    val lensMarked = remember(lensVersions) { lensVersions.mapNotNull { it.trackIndex }.toSet() }
    // The story: what is kept shows at once; a new one is asked only once the cursor has rested on the song (arrowing
    // down the list never starts a request per row), one at a time.
    val storyKey = lensTrack?.title
    var lensStory by remember(lensArtist, storyKey) {
        mutableStateOf(if (lensArtist != null && storyKey != null) SongStories.peek(lensArtist, storyKey) else null)
    }
    var lensStoryLoading by remember(lensArtist, storyKey) { mutableStateOf(false) }
    LaunchedEffect(lensesOn, lens, lensArtist, storyKey) {
        if (!lensesOn || lens != AlbumLens.SongBySong || lensArtist == null || storyKey == null) return@LaunchedEffect
        // A kept Wikipedia-only story stays on screen while the AI is asked to write it up (when a key is set); a kept AI
        // story, or one with no key to improve it, needs nothing.
        val aiReady = com.nuvio.app.features.discover.DiscoverAiSettingsRepository.snapshot().isConfigured
        val kept = lensStory
        if (kept != null && (kept.ai || !aiReady)) return@LaunchedEffect
        lensStoryLoading = kept == null
        delay(LENS_STORY_REST_MS)
        runCatching { SongStories.load(lensArtist, storyKey, wantAi = true) }.getOrNull()?.let { lensStory = it }
        lensStoryLoading = false
    }
    // The album's own note on the song (one AI request for the whole album, or MusicBrainz's credits), for a song with no
    // article. The classic page asks for these only while the album plays; the lens asks once the page is open.
    LaunchedEffect(lensesOn, bookletRequest) {
        if (!lensesOn || bookletRequest == null || songNotes.ai.isNotEmpty() || songNotes.credits.isNotEmpty()) return@LaunchedEffect
        delay(LENS_STORY_REST_MS)
        songNotes = runCatching { TrackNotes.load(bookletRequest, booklet?.intro) }.getOrDefault(songNotes)
    }
    val lensNote = lensIndex?.let { i ->
        val n = view.tracks[i].trackNumber.takeIf { it > 0 } ?: (i + 1)
        // The person's own note on the song beats everything the booklet and the AI wrote.
        val pinnedNote = bookletRequest?.let { Pins.read(PinKeys.trackNote(it.artist, it.album, n)) }
        pinnedNote?.let { LensNote(it.text, ai = false, pinned = true) }
            ?: booklet?.let { b -> b.trackNotes[n]?.let { LensNote(it, ai = b.source == com.nuvio.app.features.music.booklet.BookletSource.Ai) } }
            ?: songNotes.ai[n]?.let { LensNote(it, ai = true) }
            ?: songNotes.credits[n]?.let { LensNote(it, ai = false) }
    }
    // Editing the song panel's words: which pins its Edit control writes, and what to read again once one is saved.
    val lensEditing = if (lensArtist != null) {
        LensEditing(
            madeKey = { title -> PinKeys.songPart(lensArtist, title, SONG_PART_MADE) },
            noteKey = { n -> bookletRequest?.let { PinKeys.trackNote(it.artist, it.album, n) } },
            onChanged = {
                lensStory = if (storyKey != null) SongStories.peek(lensArtist, storyKey) else null
                bookletRequest?.let { songNotes = TrackNotes.peek(it) }
                pinTick++
            },
        )
    } else null
    // Cut to fit: the time and mood chosen (per album, kept while the page is), the mood's pick, and the cut it gives.
    var cutMinutes by rememberSaveable(album.browseId) { mutableStateOf<Int?>(CUT_DEFAULT_MINUTES) }
    var cutMood by rememberSaveable(album.browseId) { mutableStateOf(CutMood.BestOf) }
    val cutAiReady = CutPicks.available()
    val cutTitles = remember(view.tracks) { view.tracks.map { it.title } }
    val cutDurations = remember(view.tracks) { view.tracks.map { it.durationSeconds } }
    val bestOf = remember(cutTitles, popularity) { bestOfPick(cutTitles, popularity) }
    var cutAiPick by remember(album.browseId, cutMood) { mutableStateOf(CutPicks.peek(album.browseId, cutMood, cutTitles.size)) }
    var cutLoading by remember(album.browseId, cutMood) { mutableStateOf(false) }
    LaunchedEffect(lensesOn, lens, cutMood, album.browseId) {
        if (!lensesOn || lens != AlbumLens.CutToFit || !cutMood.ai || cutAiPick != null || !cutAiReady) return@LaunchedEffect
        cutLoading = true
        cutAiPick = runCatching {
            CutPicks.load(album.browseId, lensArtist ?: "", album.title, cutTitles, cutDurations, cutMood)
        }.getOrNull()
        cutLoading = false
    }
    // Only songs that can play are cut; a mood the AI could not answer falls back to Best of (said so in its note).
    val cutPick = (if (cutMood.ai) cutAiPick else bestOf) ?: bestOf
    val cutPlayable = remember(cutPick, view.tracks) { cutPick.order.filter { view.tracks.getOrNull(it)?.isAvailable == true } }
    val cut = remember(cutPlayable, cutDurations, cutMinutes) { cutToFit(cutPlayable, cutDurations, cutMinutes) }
    val cutOrder = remember(cut) { cut.order }
    val albumSeconds = cutDurations.sum()

    // A song with no other copies: the artist's best songs instead (not the one in focus), so the box always plays something.
    val lensSuggestions = remember(lensTrack?.title, essentialSongList, view.tracks) {
        val focusKey = lensTrack?.let { songKey(it.title) }
        essentialSongList.filter { songKey(it.title) != focusKey }.take(LENS_SUGGESTIONS).map { song ->
            val key = songKey(song.title)
            val here = view.tracks.indexOfFirst { it.isAvailable && songKey(it.title) == key }
            LensSuggestion(
                title = song.title,
                where = if (here >= 0) "This album · track ${here + 1}" else song.albumTitle.ifBlank { "Another record" },
                tag = if (here >= 0) "ON THIS ALBUM" else "BEST SONG",
                onClick = if (here >= 0 || song.release?.ytBrowseId != null) ({ onEssentialSong(song) }) else null,
            )
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Wide enough for three columns: the object, the record and its songs, then the artist's best down the right. The two
        // side columns are the same width (and padded as mirrors), so the record is centred on the window, always in the same
        // place however long the track list is, and a window with nothing for the right keeps the space. Narrower, More from
        // goes under the songs.
        // The case grows with the window (a TV is read from the sofa) and the column grows with it, so the unused strip
        // beside the case stays the same. A function of the window width only, never of content: nothing moves.
        val grownCase = albumCaseSize(maxWidth)
        val grownColumn = albumObjectColumnWidth(grownCase)
        // 1320 dp and up (the record keeps at least 520 dp): both sides are the object column's width, or half of what the
        // record leaves when the window is not wide enough for that (never under 400 dp), and the case shrinks to fit.
        val sideRight = maxWidth >= 1320.dp
        val sideWidth = if (sideRight) minOf(grownColumn, ((maxWidth - 520.dp) / 2).coerceAtLeast(400.dp)) else grownColumn
        // The case and the disc peeking out of it are 1.3 times as wide as the case, and must fit the column's 44 dp of padding.
        // Three columns: the flat case is as wide as the cards below it (edge to edge), its top level with the first of the best
        // songs opposite and its foot a gap above the In brief card; the disc is always out, a crescent over its top. When the
        // window is too short for that the case shrinks. Window size only, never content.
        val caseTop = ESSENTIALS_FIRST_ROW_TOP
        val caseSize = if (sideRight) {
            minOf(sideWidth - 44.dp, maxHeight - 32.dp - AI_CARD_HEIGHT - 24.dp - caseTop).coerceAtLeast(300.dp)
        } else grownCase
        val essentialsOn = essentialSongList.isNotEmpty() || essentialAlbums.isNotEmpty()
        if (lensesOn && sideRight) {
            // fork:lenses - three columns of fixed size: the two panels are mirrors, the middle takes the rest. Every size here
            // comes from the window; a lens fills boxes, it never moves one.
            val panel = lensPanelWidth(maxWidth)
            Row(
                Modifier.fillMaxSize().padding(start = LENS_OUTER, end = LENS_OUTER, top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(LENS_GAP),
            ) {
                val panelModifier = Modifier.width(panel)
                when (lens) {
                    AlbumLens.SongBySong -> SongBySongLeft(
                        song = lensSong,
                        story = lensStory,
                        note = lensNote,
                        storyLoading = lensStoryLoading,
                        onPlay = { lensIndex?.let { tapTrack(it) } },
                        modifier = panelModifier,
                        edit = lensEditing,
                    )
                    AlbumLens.CutToFit -> CutSidePanel(
                        icon = Icons.Rounded.Schedule,
                        label = "YOUR TIME",
                        pill = "Library",
                        tint = colors.accent,
                        title = "How long have you got?",
                        subtitle = "The cut keeps the album's own order",
                        dial = CutDialState(
                            big = cutMinutes?.toString() ?: "All",
                            small = if (cutMinutes != null) "minutes" else "of the pick",
                            fraction = if (albumSeconds > 0) cut.seconds.toFloat() / albumSeconds else 0f,
                        ),
                        choices = CUT_TIMES.map { m ->
                            CutChoice(m?.let { "$it min" } ?: "Full", selected = m == cutMinutes, enabled = true, onClick = { cutMinutes = m })
                        },
                        facts = listOf(
                            "Songs in the cut" to "${cut.indices.size} of ${view.tracks.size}",
                            "Running time" to cutLength(cut.seconds),
                        ),
                        noteLabel = "HOW THE TIME IS SPENT",
                        note = "Songs are taken from the top of the mood's list while they fit; a long one that doesn't is passed over for a shorter one.",
                        noteFooter = null,
                        button = "Play the cut",
                        buttonEnabled = cut.indices.isNotEmpty(),
                        onButton = {
                            val tracks = cut.indices.mapNotNull { view.tracks.getOrNull(it) }
                            if (tracks.isNotEmpty()) player.play(view.copy(tracks = tracks), trackIndex = 0, mode = chosenMode, shuffled = false)
                        },
                        modifier = panelModifier,
                    )
                    else -> LensComingSoon(lens, left = true, modifier = panelModifier)
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    LensAlbumHeader(album, info, onArtistClick)
                    Spacer(Modifier.height(12.dp))
                    actionRow()
                    // The lines under the buttons in a box of fixed height, so the tabs and the songs never move when one appears.
                    Column(
                        Modifier.fillMaxWidth().height(LENS_NOTE_LINES_HEIGHT).clipToBounds().padding(top = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) { noteLines() }
                    AlbumLensTabs(selected = lens, onSelect = { lens = it })
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        // The dock stands over the bottom of the middle column: the last song scrolls clear of it.
                        contentPadding = PaddingValues(top = 16.dp, bottom = LENS_LIST_BOTTOM),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        item(key = "legend") { TrackLegend(if (lens == AlbumLens.CutToFit) "IN CUT" else "POPULAR") }
                        itemsIndexed(view.tracks) { index, track ->
                            TrackRow(
                                album = album,
                                track = track,
                                current = loaded && state.trackIndex == index,
                                modifier = (if (index == firstPlayable) Modifier.focusRequester(firstTrackFocus) else Modifier)
                                    .onFocusChanged { if (it.hasFocus) lensFocus = index },
                                onAddToPlaylist = album.tracks.getOrNull(index)?.let { own -> { PlaylistPicker.request(listOf(own.forPlaylist(album))) } },
                                popularity = popularity.getOrNull(index),
                                single = songKey(track.title) in singles,
                                marked = lens == AlbumLens.SongBySong && index in lensMarked,
                                inlineCredit = true,
                                cutView = lens == AlbumLens.CutToFit,
                                cutOrder = if (lens == AlbumLens.CutToFit) cutOrder[index] else null,
                                onClick = { lensFocus = index; tapTrack(index) },
                            )
                        }
                    }
                }
                when (lens) {
                    AlbumLens.SongBySong -> SongBySongRight(
                        title = lensTrack?.title,
                        versions = lensVersions,
                        counts = versionCounts(lensVersions),
                        suggestions = lensSuggestions,
                        onVersion = { v ->
                            val i = v.trackIndex
                            if (i != null) { lensFocus = i; tapTrack(i) } else v.release?.ytBrowseId?.let(onAlbumClick)
                        },
                        onQueue = {
                            val picks = (listOfNotNull(lensIndex) + lensVersions.mapNotNull { it.trackIndex }).distinct().sorted()
                            val tracks = picks.mapNotNull { view.tracks.getOrNull(it) }.filter { it.isAvailable }
                            if (tracks.isNotEmpty()) player.enqueue(view.copy(tracks = tracks))
                        },
                        modifier = panelModifier,
                    )
                    AlbumLens.CutToFit -> CutSidePanel(
                        icon = Icons.Rounded.AutoAwesome,
                        label = "YOUR MOOD",
                        pill = if (cutMood.ai) "AI" else "Library",
                        tint = LENS_LIBRARY_TINT,
                        title = "What's it for?",
                        subtitle = if (cutAiReady) "The AI picks what fits" else "Set an AI key for the moods",
                        dial = CutDialState(big = cutMood.label, small = "mood", segments = CutMood.entries.size, lit = cutMood.ordinal),
                        choices = CutMood.entries.map { m ->
                            CutChoice(m.label, selected = m == cutMood, enabled = !m.ai || cutAiReady, onClick = { cutMood = m })
                        },
                        facts = listOf(
                            "Energy" to (cutPick.energy ?: if (cutMood.ai && cutLoading) "Asking…" else "As the album goes"),
                            "Leaves out" to (cutPick.leavesOut ?: "Nothing"),
                        ),
                        noteLabel = "WHY ${cutMood.label.uppercase()}",
                        note = when {
                            cutMood.ai && cutLoading -> "Asking the AI which songs suit this…"
                            cutMood.ai && cutAiPick == null -> "The AI could not answer for this mood, so the cut uses Best of for now."
                            else -> cutPick.why ?: "Picked for the mood."
                        },
                        noteFooter = if (cutPick.ai) "Written by AI, may contain mistakes" else null,
                        button = "Save as playlist",
                        buttonEnabled = cut.indices.isNotEmpty(),
                        onButton = {
                            PlaylistPicker.request(cut.indices.mapNotNull { album.tracks.getOrNull(it)?.forPlaylist(album) })
                        },
                        modifier = panelModifier,
                    )
                    else -> LensComingSoon(lens, left = false, modifier = panelModifier)
                }
            }
        } else if (maxWidth >= 840.dp) {
            Row(Modifier.fillMaxSize()) {
                if (sideRight) {
                    // The In brief card stands on the column's bottom edge, level with Start here opposite it: the same height
                    // and the same 32 dp from the bottom, so the two lines match whatever is in them.
                    Box(Modifier.width(sideWidth).fillMaxHeight()) {
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                .padding(start = 32.dp, end = 12.dp, top = (caseTop - caseSize * DISC_PEEK).coerceAtLeast(8.dp), bottom = 32.dp + AI_CARD_HEIGHT + 14.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) { objectPanel(caseSize, true) }
                        booklet?.let {
                            AlbumInBriefCard(it, playingNote, Modifier.align(Alignment.BottomCenter).padding(start = 32.dp, end = 12.dp, bottom = 32.dp).height(AI_CARD_HEIGHT))
                        }
                    }
                } else Column(
                    Modifier.width(sideWidth).fillMaxHeight().verticalScroll(rememberScrollState())
                        .padding(start = 32.dp, end = 12.dp, top = 24.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    objectPanel(caseSize, false)
                    booklet?.let { AlbumInBriefCard(it, playingNote) }
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    // Mirrored side columns: 12 dp from each, so the record is centred on the window (level with the docked player).
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = if (sideRight) 12.dp else 32.dp,
                        top = 16.dp,
                        bottom = 40.dp,
                    ),
                    // The record sits centred between the object on the left and More from on the right.
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    item(key = "facts") {
                        Column(
                            Modifier.widthIn(max = 720.dp).padding(bottom = 18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) { facts() }
                    }
                    pane()
                    if (!sideRight && more.isNotEmpty()) item(key = "more") { MoreFromRow(moreTitle, more, onAlbumClick) }
                }
                if (sideRight) {
                    when {
                        essentialsOn -> EssentialsColumn(
                            artist = artistName ?: "This artist",
                            songs = essentialSongList,
                            hereKeys = hereKeys,
                            albums = essentialAlbums,
                            startHere = startHere,
                            onPlayPath = playPath,
                            onSongClick = onEssentialSong,
                            onAlbumClick = onAlbumClick,
                            tile = ((sideWidth - 44.dp - 42.dp) / 4).coerceAtMost(108.dp),
                            modifier = Modifier.width(sideWidth).fillMaxHeight(),
                        )
                        more.isNotEmpty() -> MoreFromColumn(moreTitle, more, onAlbumClick, sideWidth)
                        else -> Spacer(Modifier.width(sideWidth))
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            ) {
                item(key = "object") {
                    Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { objectPanel(240.dp, false) }
                }
                booklet?.let { item(key = "brief") { AlbumInBriefCard(it, playingNote, Modifier.padding(bottom = 16.dp)) } }
                item(key = "facts") {
                    Column(
                        Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) { facts() }
                }
                pane()
                if (more.isNotEmpty()) item(key = "more") { MoreFromRow(moreTitle, more, onAlbumClick) }
            }
        }
    }
}

/** Narrow enough that a song's length reads with its title, as on a record sleeve. */
private val TRACK_ROW_MAX_WIDTH = 600.dp

/** One round button in the album's action row. */
private class AlbumAction(val label: String, val icon: ImageVector, val active: Boolean, val onClick: () -> Unit)
private val MORE_TILE = 150.dp
private const val TOP_ROW_SCROLL_DELAY_MS = 180L
private val MORE_GAP = 16.dp
private val OBJECT_COLUMN_WIDTH = 520.dp
private val CASE_SIZE_MIN = 340.dp
private val CASE_SIZE_MAX = 420.dp
private val CASE_GROWS_FROM = 1500.dp
/** The case plus the disc that slides out of it (see the `peek` passed to AlbumObject), as a share of the case's height. */
private const val CASE_WIDTH_FACTOR = 1.3f

/** fork:lenses - how long the cursor rests on a song before its story is asked for. */
private const val LENS_STORY_REST_MS = 600L

/** fork:lenses - room under the last song for the docked player, so it never covers a row. */
private val LENS_LIST_BOTTOM = 96.dp

/** fork:lenses - how many of the artist's best songs stand in for a song with no other copies. */
private const val LENS_SUGGESTIONS = 4

/** fork:lenses - the box under the buttons for the addon line and the notices: up to two lines, never more. */
private val LENS_NOTE_LINES_HEIGHT = 52.dp

/** fork:case - how far the always-out disc stands over the case's top, as a share of the case's height. */
private const val DISC_PEEK = 0.22f

/** fork:case - where the first of the Essentials' best songs starts down the page; the case's top stands level with it. */
private val ESSENTIALS_FIRST_ROW_TOP = 165.dp

/**
 * The album case's height for a window [width]: 340 dp up to 1500 dp wide (as before), then growing to 420 dp by about
 * 1920 dp, so on a TV the cover is the hero of the page. Window width only; the cover, the tracks and the artist never
 * change it.
 */
internal fun albumCaseSize(width: Dp): Dp {
    val grown = CASE_SIZE_MIN.value + (width.value - CASE_GROWS_FROM.value).coerceAtLeast(0f) * 0.19f
    return grown.coerceAtMost(CASE_SIZE_MAX.value).dp
}

/** The object column's width for a case: 520 dp at the smallest case, wider by the case's growth times its width factor. */
internal fun albumObjectColumnWidth(caseSize: Dp): Dp =
    OBJECT_COLUMN_WIDTH + (caseSize - CASE_SIZE_MIN) * CASE_WIDTH_FACTOR

/** More from the artist down the right of a wide window: two columns of covers, scrolling on their own. */
@Composable
private fun MoreFromColumn(title: String, more: List<LibraryRelease>, onAlbumClick: (String) -> Unit, width: Dp) {
    val colors = MaterialTheme.nuvio.colors
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = gridState,
        modifier = Modifier.width(width).fillMaxHeight(),
        contentPadding = PaddingValues(start = 12.dp, end = 32.dp, top = 24.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(MORE_GAP),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(key = "title", span = { GridItemSpan(maxLineSpan) }) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.textPrimary)
        }
        gridItemsIndexed(more, key = { _, release -> release.key }) { index, release ->
            val topRow = index < 2
            Box(
                Modifier
                    // Arriving on the top row scrolls back to the title, so the column is seen from its start again. After the
                    // focused tile's own scroll into view, which starts on the same frame and would cancel this one.
                    .onFocusChanged {
                        if (it.hasFocus && topRow) scope.launch {
                            delay(TOP_ROW_SCROLL_DELAY_MS)
                            gridState.animateScrollToItem(0)
                        }
                    }
                    .onKeyEvent { event ->
                        topRow && event.type == KeyEventType.KeyDown && arrowFocusDirection(event) == FocusDirection.Up
                    },
            ) { MoreFromTile(release, MORE_TILE, onAlbumClick) }
        }
    }
}

/** More from the artist under the songs, on a narrower window: one sideways row. */
@Composable
private fun MoreFromRow(title: String, more: List<LibraryRelease>, onAlbumClick: (String) -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.textPrimary)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(more, key = { it.key }) { release -> MoreFromTile(release, 124.dp, onAlbumClick) }
        }
    }
}

@Composable
private fun MoreFromTile(release: LibraryRelease, size: Dp, onAlbumClick: (String) -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    Column(
        Modifier.width(size).clip(RoundedCornerShape(10.dp)).musicKeyFocusRing(RoundedCornerShape(10.dp)).clickable { release.ytBrowseId?.let(onAlbumClick) },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MusicCover(release.art.cover, release.title, size = size)
        Text(release.title, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(release.year, release.kind.label.takeIf { it != "Album" }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            maxLines = 1,
        )
    }
}

/** The object itself (click to flip, drag to slide the disc), with a hint and a line saying where the art comes from. */
@Composable
private fun AlbumObjectPanel(
    album: MusicAlbum,
    info: LibraryAlbumInfo?,
    flipped: Boolean,
    discOut: Boolean,
    discSpinning: Boolean,
    reducedMotion: Boolean,
    caseSize: Dp,
    discUp: Boolean,
    onFlip: () -> Unit,
    onDisc: (out: Boolean) -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // The case turns to its back cover (a click) and the disc slides out of it (a sideways drag); both snap
        // under reduced motion.
        AlbumObject(
            album = album,
            release = info?.release,
            flipped = flipped,
            discOut = discOut,
            discSpinning = discSpinning,
            reducedMotion = reducedMotion,
            size = caseSize,
            peek = if (discUp) DISC_PEEK else 0.3f,
            discUp = discUp,
            onFlip = onFlip,
            onDisc = onDisc,
        )
    }
}

/** Kicker, serif title, artist line (photo, name, label, date) and the length line, as on board 3. */
@Composable
private fun AlbumFacts(album: MusicAlbum, info: LibraryAlbumInfo?, onArtistClick: (String) -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val artistId = album.artists.firstOrNull()?.browseId
    val minutes = album.tracks.sumOf { it.durationSeconds } / 60
    val kicker = listOfNotNull(
        album.kind,
        album.year,
        "${album.tracks.size} track${if (album.tracks.size == 1) "" else "s"}",
        "$minutes min".takeIf { minutes > 0 },
    ).joinToString(" · ").uppercase()
    val notes = buildLinerNotes(info?.release, album.year)
    val credit = listOfNotNull(notes.label, notes.date, "Explicit".takeIf { notes.explicit }).joinToString(" · ")
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = kicker,
            style = MaterialTheme.typography.labelLarge,
            color = colors.accent,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.6.sp,
        )
        Text(
            text = album.title,
            style = MaterialTheme.typography.displaySmall,
            fontFamily = FontFamily.Serif,
            color = colors.textPrimary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (album.artists.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = if (artistId != null) Modifier.clip(RoundedCornerShape(8.dp)).musicKeyFocusRing(RoundedCornerShape(8.dp)).clickable { onArtistClick(artistId) } else Modifier,
            ) {
                info?.artist?.art?.thumb?.let { MusicCover(it, description = null, size = 30.dp, circle = true) }
                Text(
                    text = album.artists.joinToString(", ") { it.name },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (artistId != null) colors.accent else colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (credit.isNotEmpty()) {
                    Text("· $credit", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The column names above the songs, on the same columns as [TrackRow]: number, title, how played, length. */
@Composable
private fun TrackLegend(popularLabel: String = "POPULAR") {
    val colors = MaterialTheme.nuvio.colors
    val label = MaterialTheme.typography.labelSmall
    Row(
        Modifier.widthIn(max = TRACK_ROW_MAX_WIDTH).fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("#", Modifier.width(32.dp), style = label, letterSpacing = 1.2.sp, color = colors.textMuted)
        Text("TITLE", Modifier.weight(1f), style = label, letterSpacing = 1.2.sp, color = colors.textMuted)
        Spacer(Modifier.width(28.dp + 12.dp)) // the add button's place, and the gap after it
        Text(popularLabel, Modifier.width(56.dp), style = label, letterSpacing = 0.6.sp, color = colors.textMuted, maxLines = 1, softWrap = false)
        Spacer(Modifier.width(14.dp))
        Text("TIME", Modifier.width(44.dp), style = label, letterSpacing = 1.2.sp, color = colors.textMuted, textAlign = TextAlign.End)
    }
}

@Composable
private fun TrackRow(
    album: MusicAlbum,
    track: MusicTrack,
    current: Boolean,
    onClick: () -> Unit,
    /** fork:music-playlists - shown as a + on hover. */
    onAddToPlaylist: (() -> Unit)? = null,
    /** fork:essentials - how played the song is next to the artist's others (0..1), null when unknown: the bar stays empty. */
    popularity: Float? = null,
    /** fork:essentials - the artist released this song as a single. */
    single: Boolean = false,
    /** fork:lenses - another copy of the song in focus (Song by song): a bar in the library tint at its left edge. */
    marked: Boolean = false,
    /** fork:lenses - the featured artist's chip on the title's line, so every row is the same height. */
    inlineCredit: Boolean = false,
    /** fork:lenses - Cut to fit: the row's place in the cut in the popularity bar's slot; with [cutView] and none, it is dimmed. */
    cutOrder: Int? = null,
    cutView: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    val colors = MaterialTheme.nuvio.colors
    val albumArtists = album.artists.map { it.name }
    val trackArtists = track.artists.map { it.name }
    // Only when the song has someone the album does not (a feature), not on every row.
    val credit = trackArtists.takeIf { it.isNotEmpty() && it != albumArtists }?.joinToString(", ")
    Row(
        modifier = modifier
            .widthIn(max = TRACK_ROW_MAX_WIDTH) // the length stays near the title however wide the column gets
            .fillMaxWidth()
            .alpha(if (cutView && cutOrder == null) 0.42f else 1f)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    current -> colors.accent.copy(alpha = 0.12f)
                    marked -> LENS_LIBRARY_TINT.copy(alpha = 0.10f)
                    else -> Color.Transparent
                },
            )
            .then(if (marked) Modifier.drawBehind { drawRect(LENS_LIBRARY_TINT, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)) } else Modifier)
            .hoverable(hoverSource)
            .musicKeyFocusRing(RoundedCornerShape(8.dp)).clickable(enabled = track.isAvailable, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) {
            if (current) {
                MusicEqualiser(colors.accent, modifier = Modifier.semantics { contentDescription = "Now playing" })
            } else {
                Text(
                    text = track.trackNumber.takeIf { it > 0 }?.toString().orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (track.isAvailable) colors.textMuted else colors.textDisabled,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = when {
                        !track.isAvailable -> colors.textDisabled
                        current -> colors.accent
                        else -> colors.textPrimary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (single) {
                    Text(
                        "SINGLE",
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 0.8.sp,
                        color = if (track.isAvailable) colors.textSecondary else colors.textDisabled,
                        maxLines = 1,
                        modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
                if (credit != null && inlineCredit) {
                    // fork:lenses - on the title's line (it gives way first when the row is short of room).
                    Text(
                        text = credit,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (track.isAvailable) colors.textSecondary else colors.textDisabled,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.10f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            if (credit != null && !inlineCredit) {
                // A credit chip for the featured artist(s), as on board 3.
                val chip = RoundedCornerShape(8.dp)
                Text(
                    text = credit,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (track.isAvailable) colors.textSecondary else colors.textDisabled,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp).clip(chip)
                        .background(Color.White.copy(alpha = 0.10f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        if (onAddToPlaylist != null && track.isAvailable) {
            // Always laid out (only shown on hover), so the length does not jump when the pointer arrives.
            // Shown on hover, or while the keyboard is on it (Right from the track row).
            var addFocused by remember { mutableStateOf(false) }
            NuvioPosterHoverTooltip(title = "Add to playlist") {
                Box(
                    Modifier.size(28.dp).alpha(if (hovered || addFocused) 1f else 0f)
                        .onFocusChanged { addFocused = it.hasFocus }
                        .clip(CircleShape).musicKeyFocusRing(CircleShape).clickable(onClick = onAddToPlaylist),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "Add to playlist", tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                }
            }
        } else {
            Spacer(Modifier.size(28.dp)) // keeps the bar and the length in their columns on a song with no add button
        }
        Spacer(Modifier.width(12.dp))
        if (cutView) {
            // fork:lenses - the same 56 dp slot as the bar, so the lengths stay in their column.
            Box(Modifier.width(56.dp), contentAlignment = Alignment.CenterStart) {
                if (cutOrder != null) {
                    Text(
                        cutOrder.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.accent,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(30.dp).clip(RoundedCornerShape(10.dp)).background(colors.accent.copy(alpha = 0.18f)).padding(vertical = 2.dp),
                    )
                } else {
                    Text("Out", style = MaterialTheme.typography.labelMedium, color = colors.textMuted, maxLines = 1)
                }
            }
        } else {
            // Always laid out, so the lengths line up whether or not Deezer knows how played the song is.
            Box(Modifier.width(56.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.12f))) {
                if (popularity != null) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(popularity.coerceIn(0f, 1f)).background(if (current) colors.accent else colors.accent.copy(alpha = 0.7f)))
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = if (track.durationSeconds > 0) formatMusicTime(track.durationSeconds * 1000L) else "",
            modifier = Modifier.width(44.dp),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodyMedium,
            color = if (track.isAvailable) colors.textMuted else colors.textDisabled,
        )
    }
}

/**
 * The channel to look up in the background when [album] is opened, or null to skip it. Compilations
 * credited to "Various Artists" (or to nobody linkable) would pull in a broad channel that is not the
 * record's artist, so they get no look-up; every other album looks up its first artist, as before.
 */
internal fun albumArtistToPreview(album: MusicAlbum): String? {
    val first = album.artists.firstOrNull() ?: return null
    val name = first.name.trim().lowercase()
    if (name == "various artists" || name == "various" || name == "va") return null
    return first.browseId
}
