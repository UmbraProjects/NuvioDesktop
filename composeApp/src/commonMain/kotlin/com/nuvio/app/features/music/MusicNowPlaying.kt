package com.nuvio.app.features.music
// FORK UI (Spyder): Nick's Now Playing stage, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import com.nuvio.app.features.music.library.musicKeyFocusRing
import androidx.compose.foundation.layout.wrapContentWidth
import com.nuvio.app.features.music.library.musicGlass
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.music.identity.LocalArtistIdentity
import com.nuvio.app.features.music.identity.MotionProfile
import com.nuvio.app.features.music.identity.MusicAccentScope
import com.nuvio.app.features.music.identity.StageMotionSpec
import com.nuvio.app.features.music.identity.identity
import com.nuvio.app.features.music.identity.playingArtist
import com.nuvio.app.features.music.identity.stageMotionSpec
import com.nuvio.app.features.music.library.MusicLibraryRepository
import com.nuvio.app.features.music.library.rememberCdArtFor
import kotlinx.coroutines.delay

/** Lines light up this long before their stamp, so the eye is on the line as it is sung. */
internal const val NOW_PLAYING_LYRIC_LEAD_MS = 250L

/** Tries at starting the lyric sync (the stream may not be readable the moment it opens), and the wait between. */
private const val LYRIC_SYNC_ATTEMPTS = 3
private const val LYRIC_SYNC_RETRY_MS = 4_000L

/** What one tap of the lyric nudge moves the timing by. */
private const val LYRIC_NUDGE_MS = 500L

private sealed interface LyricsUi {
    data object Loading : LyricsUi
    data class Ready(val lyrics: Lyrics) : LyricsUi
    data object Missing : LyricsUi

    /** The lyric services could not be reached (so "none" is not known); the lookup is repeated on its own. */
    data object Unreachable : LyricsUi
}

private enum class StageView { Stage, Video }

/**
 * The Now Playing stage (board 4): artist fanart drifting behind the words, the artist's cut-out
 * and logo, the spinning disc, the line being sung lit in the artist's accent and centred; a tap on a
 * line seeks to it. Sits over the app but under the mini-player, which keeps its controls. Opened by
 * [MusicPlayback.nowPlayingOpen].
 *
 * How it moves comes from the artist's mood (slow drift for calm, a tighter pulse for driven; see
 * [stageMotionSpec]) and is switched off by the reduced-motion setting.
 */
@Composable
fun MusicNowPlayingHost(
    hidden: Boolean,
    modifier: Modifier = Modifier,
    player: MusicPlayer = MusicPlayback.player,
    onOpenArtist: (String) -> Unit = {},
    onOpenAlbum: (String) -> Unit = {},
    onOpenMusicTab: () -> Unit = {},
) {
    val state by player.state.collectAsStateWithLifecycle()
    val open by MusicPlayback.nowPlayingOpen.collectAsStateWithLifecycle()
    // The view goes with the player: a stopped player has nothing to show next time it starts.
    LaunchedEffect(state.isActive) {
        if (!state.isActive) MusicPlayback.nowPlayingOpen.value = false
    }
    AnimatedVisibility(
        visible = open && state.isActive && !hidden,
        enter = fadeIn(tween(250)),
        exit = fadeOut(tween(200)),
        modifier = modifier,
    ) {
        // Wears the playing artist's accent, as the mini-player under it does.
        MusicAccentScope {
            MusicNowPlayingScreen(
                state = state,
                player = player,
                onSeek = player::seekTo,
                onClose = { MusicPlayback.nowPlayingOpen.value = false },
                onOpenArtist = onOpenArtist,
                onOpenAlbum = onOpenAlbum,
                onOpenMusicTab = onOpenMusicTab,
                modifier = Modifier.fillMaxSize(), // takes the whole window; the content clears the bar itself
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MusicNowPlayingScreen(
    state: MusicPlayerState,
    player: MusicPlayer,
    onSeek: (Long) -> Unit,
    onClose: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenMusicTab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    val scope = rememberCoroutineScope()
    val catalog = remember { defaultMusicCatalog() }
    val track = state.track
    val album = state.album
    val artist = (track?.artists?.takeIf { it.isNotEmpty() } ?: album?.artists.orEmpty())
        .joinToString(", ") { it.name }
    val primaryArtist = (track?.artists?.firstOrNull() ?: album?.artists?.firstOrNull())?.name.orEmpty()

    val baseLyrics by produceState<LyricsUi>(LyricsUi.Loading, track?.videoId) {
        value = LyricsUi.Loading
        if (track == null) {
            value = LyricsUi.Missing
            return@produceState
        }
        // An outage is not "no lyrics": say so, and keep asking (20 s, 40 s, then every minute) until the track
        // changes, so lyrics appear on their own once a service is back.
        var attempt = 0
        while (true) {
            when (val answer = LyricsRepository.lookup(track, album)) {
                is LyricsLookup.Found -> { value = LyricsUi.Ready(answer.lyrics); return@produceState }
                LyricsLookup.Missing -> { value = LyricsUi.Missing; return@produceState }
                LyricsLookup.Unreachable -> {
                    value = LyricsUi.Unreachable
                    delay(lyricsRetryDelayMs(attempt++))
                }
            }
        }
    }
    val cdArt = rememberCdArtFor(album?.browseId)

    // The artist as the library knows them (saved, else previewed): logo, cut-out, backgrounds, mood.
    val library by MusicLibraryRepository.state.collectAsStateWithLifecycle()
    val libraryArtist = remember(track, album, library.artists, library.preview) { playingArtist(track, album, library) }
    val identity = remember(libraryArtist) { libraryArtist?.identity() }
    val fetchedFanart by produceState(emptyList<String>(), primaryArtist, identity?.backgrounds?.isEmpty()) {
        value = if (identity?.backgrounds.isNullOrEmpty()) ArtistFanartRepository.forArtist(primaryArtist) else emptyList()
    }
    val backgrounds = identity?.backgrounds.orEmpty().ifEmpty { fetchedFanart }

    val reduced = rememberMusicReducedMotion()
    val spec = remember(identity?.motion, reduced) { stageMotionSpec(identity?.motion ?: MotionProfile.Steady, reduced) }

    // Asking for Video from here: the mode switch is asynchronous, so the video opens once it has landed.
    var wantVideo by remember { mutableStateOf(false) }
    var noticeAtRequest by remember { mutableStateOf<String?>(null) }
    val realVideo = state.mode == MusicPlayMode.Video && !state.lyricVideo
    LaunchedEffect(wantVideo, state.mode, state.lyricVideo, state.notice) {
        if (!wantVideo) return@LaunchedEffect
        when {
            // The video opens right here, above the lyrics.
            realVideo -> wantVideo = false
            // A new line from the player ("No video for this track") means the switch did not happen.
            state.notice != null && state.notice != noticeAtRequest -> wantVideo = false
        }
    }
    // Never leave the request armed: a video that turns up much later must not pop open by itself.
    LaunchedEffect(wantVideo) {
        if (wantVideo) {
            delay(15_000)
            wantVideo = false
        }
    }

    // The video is often another cut than the album track the lyrics were timed to: find how far
    // apart they are once the stream's length is known, and shift the lines by it.
    val videoStream = realVideo || state.videoCutPlaying
    val syncKey = track?.let { lyricSyncKey(it, videoStream, state.videoId) }
    val legacySyncKey = track?.let { lyricSyncKey(it, videoStream) }?.takeIf { it != syncKey }
    val syncStates by MusicLyricSync.states.collectAsStateWithLifecycle()
    val syncState = syncKey?.let { syncStates[it] } ?: LyricSyncState.Idle
    val streamKnown = state.durationMs > 0L && !state.isLoading
    // LRCLIB often holds a version timed to this very stream (the video's cut): use it when there is one.
    val baseSynced = (baseLyrics as? LyricsUi.Ready)?.lyrics as? Lyrics.Synced
    val streamLyrics = rememberStreamLyrics(track, album, baseSynced, state.loadId, state.durationMs, streamKnown)
    val streamSynced = streamLyrics.lyrics
    val lyrics: LyricsUi = if (baseSynced != null && streamSynced != null) LyricsUi.Ready(streamSynced) else baseLyrics
    val syncLines = streamSynced?.lines
    // The length of the recording the lines were timed to: the lyrics' own when LRCLIB gives it, else the album track's.
    val trackDurationMs = streamSynced?.durationMs?.takeIf { it > 0L } ?: ((track?.durationSeconds ?: 0) * 1_000L)
    LaunchedEffect(syncKey, syncLines, state.loadId, streamKnown, streamLyrics.resolved) {
        if (syncKey == null || syncLines == null || !streamKnown || !streamLyrics.resolved) return@LaunchedEffect
        // A video's non-music intro, for when the analysis cannot hear the vocals (see introFallbackOffsetMs).
        val introEndMs = state.videoId?.takeIf { videoStream }
            ?.let { id -> musicIntroSegment(MusicSponsorBlock.segments(id), state.durationMs)?.endMs }
        repeat(LYRIC_SYNC_ATTEMPTS) {
            MusicLyricSync.sync(syncKey, syncLines, trackDurationMs, state.durationMs, state.loadId, legacySyncKey, introEndMs)
            if (MusicLyricSync.stateOf(syncKey) !is LyricSyncState.Idle) return@LaunchedEffect
            delay(LYRIC_SYNC_RETRY_MS)
        }
    }
    val syncOffsetMs = syncState.offsetMs()
    val shownLyrics = remember(lyrics, syncOffsetMs) {
        (lyrics as? LyricsUi.Ready)?.let { LyricsUi.Ready(it.lyrics.shiftedBy(syncOffsetMs)) } ?: lyrics
    }

    // HTPC phase 7: the widgets fade out a few seconds after the last input or track change.
    var inputTick by remember { mutableIntStateOf(0) }
    var widgetsVisible by remember { mutableStateOf(true) }
    val fadeQueue = MusicLibraryRepository.state.collectAsStateWithLifecycle().value.settings.fadeQueue
    val lyricStyle = MusicLibraryRepository.state.collectAsStateWithLifecycle().value.settings.lyricStyle
    val lyricsOn = MusicLibraryRepository.state.collectAsStateWithLifecycle().value.settings.lyricsOn
    // fork:booklet - the album's sleeve notes for the third panel of a wide Stage.
    val bookletUi = rememberBookletUi(album, libraryArtist)
    // fork:visualiser - the playing track's map, from the player surface's background analysis (null for another load).
    val trackMap = com.nuvio.app.features.music.visualiser.TrackMapLive.state.collectAsStateWithLifecycle().value
        ?.takeIf { it.loadId == state.loadId }
    LaunchedEffect(inputTick, track?.videoId) {
        widgetsVisible = true
        delay(WIDGET_IDLE_MS)
        widgetsVisible = false
    }

    Box(
        modifier
            .background(Color.Black)
            .musicKeyboardRoot(trapFocus = true)
            .onPreviewKeyEvent { inputTick++; false }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        inputTick++
                    }
                }
            },
    ) {
        // While a video plays the backdrop holds still (the blurred cover, no slideshow) so the picture is what moves.
        FanartBackdrop(
            images = if (realVideo) emptyList() else backgrounds,
            coverUrl = state.coverUrl,
            spec = if (realVideo) spec.copy(animated = false) else spec,
        )
        StageGlow(color = colors.accent, spec = spec)
        // The view is its own player (NowPlayingControls); the bar, dock and card step aside, so nothing is kept for them.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 860.dp
            val cutout = identity?.cutoutUrl.takeIf { wide }
            // fork:stage - three panels (player, lyrics, booklet over the artist) when there is room; each keeps to its own.
            // The layout depends on the window only, never on whether the liner notes have anything to show: nothing on this page
            // may move or resize when the notes are missing (user rule, Nov 3 2026). A missing booklet leaves its slot empty.
            val threePanels = wide && stageUsesThreePanels(maxWidth.value)
            // The video, lyrics and sync controls sit in the middle of the space right of the player column, except when the
            // full-height cut-out stands beside the lyrics and they keep to the left of it.
            val centredMiddle = threePanels || realVideo || cutout == null
            // fork:stage - the right panel's glass runs from the top of the album case to the progress bar, so the three columns share
            // two lines (symmetry is a rule of this page). Measured in the window's pixels; null until the first layout.
            var caseTopPx by remember { mutableStateOf<Float?>(null) }
            var barLinePx by remember { mutableStateOf<Float?>(null) }
            var rightTopPx by remember { mutableStateOf<Float?>(null) }
            val stageDensity = LocalDensity.current
            // Small in a corner while a video plays, so it stays clear of the video and the lyric sync controls.
            if (cutout != null && !threePanels) {
                StageCutout(
                    cutout,
                    if (realVideo) Modifier.align(Alignment.BottomEnd).fillMaxHeight(0.28f).widthIn(max = 180.dp)
                    else Modifier.align(Alignment.BottomEnd).fillMaxHeight(0.94f).widthIn(max = 460.dp),
                )
            }
            if (wide) {
                Row(
                    Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 72.dp, bottom = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Centred while it fits; when Up next is long it takes the height that is left and scrolls in it.
                    // Everything in the column is centred on one line, the player and queue below included. The album's case
                    // (not the disc peeking out beside it) is what sits on that line, and the logo above it.
                    Column(
                        Modifier.width(STAGE_COLUMN_WIDTH).fillMaxHeight(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        StageLogo(identity?.logoUrl, identity?.name ?: primaryArtist, maxHeight = 104.dp, maxWidth = 280.dp, centred = true)
                        Spacer(Modifier.height(18.dp))
                        MusicCdSleeve(
                            modifier = Modifier
                                .fillMaxWidth()
                                .wrapContentWidth(Alignment.Start, unbounded = true)
                                .padding(start = (STAGE_COLUMN_WIDTH - STAGE_CASE_SIZE) / 2)
                                .onGloballyPositioned { caseTopPx = it.positionInRoot().y },
                            coverUrl = state.coverUrl,
                            cdArtUrl = cdArt,
                            spinning = state.isPlaying && spec.discSpins,
                            slideOut = state.isPlaying || reduced,
                            secondsPerTurn = spec.discSecondsPerTurn,
                            instant = reduced,
                            size = STAGE_CASE_SIZE,
                            peek = 0.3f,
                        )
                        Spacer(Modifier.height(20.dp))
                        Text(
                            // Just the song: the guests ("(feat. …)") are in the queue row, which shows the whole title.
                            stageSongTitle(track?.title.orEmpty()),
                            color = Color.White,
                            fontFamily = FontFamily.Serif,
                            fontSize = 44.sp,
                            lineHeight = 46.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        val artistRefs = (track?.artists?.takeIf { it.isNotEmpty() } ?: album?.artists.orEmpty())
                        val albumId = track?.albumBrowseId ?: album?.browseId
                        // Each part leads somewhere: an artist to their page, the album to its page, a genre to a ladder.
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            artistRefs.forEachIndexed { i, ref ->
                                StageLink(ref.name + if (i < artistRefs.lastIndex) ", " else "", enabled = true) {
                                    scope.launch {
                                        val id = ref.browseId ?: com.nuvio.app.features.music.library.rungArtist(catalog, ref.name)?.browseId
                                        if (id != null) { onClose(); onOpenArtist(id) }
                                    }
                                }
                            }
                            val albumName = state.albumTitle
                            if (!albumName.isNullOrBlank()) {
                                StageLinkText(if (artistRefs.isNotEmpty()) " · " else "")
                                StageLink(albumName, enabled = !albumId.isNullOrBlank()) { albumId?.let { onClose(); onOpenAlbum(it) } }
                            }
                            state.album?.year?.let { StageLinkText(" · $it") }
                        }
                        val chips = listOfNotNull(libraryArtist?.mood) + libraryArtist?.genres.orEmpty().take(2)
                        if (chips.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                chips.forEachIndexed { i, chip ->
                                    val isMood = i == 0 && libraryArtist?.mood != null
                                    // A genre searches for it in the Music tab; the mood is only a label.
                                    val genre = if (isMood) null else com.nuvio.app.features.music.library.canonicalGenre(chip)
                                    StageChip(
                                        chip,
                                        accented = isMood,
                                        onClick = genre?.let { g ->
                                            {
                                                // A genre chip searches that genre (the Ladder is hidden): the music home shows its genre card.
                                                MusicTabState.search(g)
                                                onClose()
                                                onOpenMusicTab()
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        NowPlayingControls(
                            state, player,
                            // The seek box is 28 dp high with its line in the middle: the panel ends on the line's lower edge.
                            barModifier = Modifier.onGloballyPositioned { barLinePx = it.positionInRoot().y + it.size.height / 2f + with(stageDensity) { 2.dp.toPx() } },
                        )
                        Spacer(Modifier.height(16.dp))
                        NowPlayingWidgets(
                            state = state,
                            player = player,
                            libraryArtist = libraryArtist,
                            lyricsMissing = lyrics is LyricsUi.Missing,
                            visible = widgetsVisible || !fadeQueue,
                            modifier = Modifier.weight(1f, fill = false),
                            onOpenArtist = onOpenArtist,
                        )
                    }
                    Column(
                        // Under a video nothing is held back for the cut-out (it is small, in the corner), so the picture is centred on the
                        // area right of the sidebar column rather than shifted left of it.
                        Modifier.weight(1f).fillMaxSize().padding(end = if (cutout != null && !realVideo && !threePanels) CUTOUT_CLEARANCE else 0.dp),
                        // Under a video the column is centred: the picture, then the lyric strip beneath it.
                        horizontalAlignment = if (centredMiddle) Alignment.CenterHorizontally else Alignment.Start,
                    ) {
                    // With a video: as large as the column allows, and four lines of lyrics under it.
                    if (realVideo) InlineVideoSlot()
                    LyricsPane(
                        shownLyrics, state.positionMs, state.isPlaying, onSeek, reduced,
                        if (realVideo) Modifier.fillMaxWidth() else Modifier.weight(1f).fillMaxWidth(),
                        compact = realVideo,
                        style = lyricStyle,
                        syncState = syncState,
                        onNudge = { delta -> syncKey?.let { MusicLyricSync.nudge(it, delta) } },
                        onResetSync = { syncKey?.let { MusicLyricSync.reset(it) } },
                        onTapSync = { lineTimeMs, positionMs -> syncKey?.let { MusicLyricSync.tapSync(it, lineTimeMs, positionMs) } },
                        centredSync = centredMiddle,
                        visible = lyricsOn,
                    )
                    }
                    if (threePanels) {
                        // The right panel: the booklet in glass, and under it the artist, so the cut-out cannot reach the lyrics.
                        val caseTop = caseTopPx
                        val barLine = barLinePx
                        val rightTop = rightTopPx
                        // The gap above the glass (down to the case's top) and the height of the glass itself, once all three are known.
                        val glassTop = if (caseTop != null && rightTop != null) with(stageDensity) { (caseTop - rightTop).coerceAtLeast(0f).toDp() } else null
                        val glassHeight = if (caseTop != null && barLine != null && barLine > caseTop) with(stageDensity) { (barLine - caseTop).toDp() } else null
                        Column(
                            Modifier.width(STAGE_COLUMN_WIDTH).fillMaxHeight().onGloballyPositioned { rightTopPx = it.positionInRoot().y },
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            // fork:visualiser - the glass is always there at the same size: the notes, or the visualiser without them.
                            val boxModifier = if (glassTop != null && glassHeight != null) {
                                Modifier.padding(top = glassTop).height(glassHeight).fillMaxWidth()
                            } else {
                                Modifier.weight(if (cutout != null) 0.62f else 1f).fillMaxWidth()
                            }
                            NowPlayingLinerBox(
                                bookletUi = bookletUi,
                                album = album,
                                playingVideoId = track?.videoId,
                                reduced = reduced,
                                live = trackMap,
                                positionMs = state.positionMs,
                                playing = state.isPlaying,
                                durationMs = state.durationMs,
                                lyricLines = ((shownLyrics as? LyricsUi.Ready)?.lyrics as? Lyrics.Synced)?.lines,
                                coverUrl = state.coverUrl,
                                onJumpToTrack = { index -> player.jumpTo(index) },
                                onOpenAlbum = { id -> onClose(); onOpenAlbum(id) },
                                modifier = boxModifier,
                            )
                            if (cutout != null) {
                                StageCutout(cutout, Modifier.weight(1f).fillMaxWidth())
                            }
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 64.dp, bottom = 24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Cover(state.coverUrl, Modifier.size(64.dp))
                        Column {
                            Text(track?.title.orEmpty(), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(artist, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    NowPlayingControls(state, player)
                    Spacer(Modifier.height(12.dp))
                    if (realVideo) InlineVideoSlot()
                    LyricsPane(
                        shownLyrics, state.positionMs, state.isPlaying, onSeek, reduced,
                        if (realVideo) Modifier.fillMaxWidth() else Modifier.weight(1f).fillMaxWidth(),
                        compact = realVideo,
                        style = lyricStyle,
                        syncState = syncState,
                        onNudge = { delta -> syncKey?.let { MusicLyricSync.nudge(it, delta) } },
                        onResetSync = { syncKey?.let { MusicLyricSync.reset(it) } },
                        onTapSync = { lineTimeMs, positionMs -> syncKey?.let { MusicLyricSync.tapSync(it, lineTimeMs, positionMs) } },
                        visible = lyricsOn,
                    )
                }
            }
        }
        StageTopBar(
            title = state.albumTitle.orEmpty(),
            view = if (state.mode == MusicPlayMode.Video) StageView.Video else StageView.Stage,
            reduced = reduced,
            canFullscreen = realVideo,
            onClose = onClose,
            onView = { picked ->
                when {
                    picked == StageView.Stage -> if (state.mode == MusicPlayMode.Video) player.switchMode(MusicPlayMode.Audio)
                    realVideo -> Unit // already showing, above the lyrics
                    state.mode == MusicPlayMode.Audio -> {
                        noticeAtRequest = state.notice
                        wantVideo = true
                        player.switchMode(MusicPlayMode.Video)
                    }
                }
            },
            onToggleMotion = { setMusicReducedMotion(!reduced) },
            lyricStyle = lyricStyle,
            lyricsOn = lyricsOn,
            onToggleLyrics = {
                val settings = MusicLibraryRepository.state.value.settings
                MusicLibraryRepository.updateSettings(settings.copy(lyricsOn = !settings.lyricsOn))
            },
            onCycleLyricStyle = {
                val settings = MusicLibraryRepository.state.value.settings
                MusicLibraryRepository.updateSettings(settings.copy(lyricStyle = settings.lyricStyle.next()))
            },
            fadeQueue = fadeQueue,
            onToggleFadeQueue = {
                val settings = MusicLibraryRepository.state.value.settings
                MusicLibraryRepository.updateSettings(settings.copy(fadeQueue = !settings.fadeQueue))
            },
            onFullscreen = { MusicPlayback.setVideoView(MusicVideoView.Fullscreen) },
            modifier = Modifier.align(Alignment.TopStart),
        )
        state.notice?.let { note ->
            Text(
                note,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 60.dp),
            )
        }
    }
}

/** Collapse, the album, the Stage / Video switch, Fullscreen (when there is a real video) and the Options menu at the far right. */
@Composable
private fun StageTopBar(
    title: String,
    view: StageView,
    reduced: Boolean,
    canFullscreen: Boolean,
    onClose: () -> Unit,
    onView: (StageView) -> Unit,
    onToggleMotion: () -> Unit,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    fadeQueue: Boolean = true,
    onToggleFadeQueue: () -> Unit = {},
    lyricStyle: LyricStyle = LyricStyle.Scroll,
    onCycleLyricStyle: () -> Unit = {},
    lyricsOn: Boolean = true,
    onToggleLyrics: () -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.height(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).musicKeyFocusRing(CircleShape).clickable(onClick = onClose)
                .padding(start = 12.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            Text("Collapse", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Text(
            title,
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        MusicSegmented(
            options = listOf(StageView.Stage to "Stage", StageView.Video to "Video"),
            selected = view,
            onSelect = onView,
        )
        if (canFullscreen) MusicTextChip("Fullscreen", onFullscreen)
        StageOptionsMenu(
            lyricsOn = lyricsOn,
            lyricStyle = lyricStyle,
            fadeQueue = fadeQueue,
            reduced = reduced,
            onToggleLyrics = onToggleLyrics,
            onCycleLyricStyle = onCycleLyricStyle,
            onToggleFadeQueue = onToggleFadeQueue,
            onToggleMotion = onToggleMotion,
        )
    }
}

@Composable
private fun StageChip(text: String, accented: Boolean, onClick: (() -> Unit)? = null) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(14.dp)
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = if (accented) FontWeight.SemiBold else FontWeight.Normal,
        color = if (accented) colors.onAccent else Color.White.copy(alpha = 0.9f),
        modifier = Modifier
            .clip(shape)
            .then(
                if (accented) Modifier.background(colors.accent)
                else Modifier.border(1.dp, Color.White.copy(alpha = 0.3f), shape),
            )
            .then(if (onClick != null) Modifier.musicKeyFocusRing(shape).clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

private val StageLinkStyle = 16.sp

/** The plain separators and year beside the links in the line under the title. */
@Composable
private fun StageLinkText(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.8f), fontSize = StageLinkStyle)
}

/** A name in the line under the title that opens its page; underlined on hover would need more, so it is brighter and focusable. */
@Composable
private fun StageLink(text: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Text(
        text,
        color = Color.White.copy(alpha = if (enabled && hovered) 1f else 0.8f),
        fontSize = StageLinkStyle,
        textDecoration = if (enabled && hovered) androidx.compose.ui.text.style.TextDecoration.Underline else null,
        modifier = Modifier
            .clip(shape)
            .then(
                if (enabled) Modifier.hoverable(source).musicKeyFocusRing(shape).clickable(interactionSource = source, indication = null, onClick = onClick)
                else Modifier,
            ),
    )
}

/** The artist's logo; with none (or one that fails to load) their name is set in text. */
@Composable
private fun StageLogo(url: String?, name: String, maxHeight: Dp = 72.dp, maxWidth: Dp = 300.dp, centred: Boolean = false) {
    var failed by remember(url) { mutableStateOf(false) }
    if (url != null && !failed) {
        NuvioAsyncImage(
            model = url,
            contentDescription = name,
            modifier = Modifier.heightIn(max = maxHeight).widthIn(max = maxWidth),
            contentScale = ContentScale.Fit,
            alignment = if (centred) Alignment.Center else Alignment.CenterStart,
            onError = { failed = true },
        )
    } else if (name.isNotBlank()) {
        Text(name, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Room kept free of lyrics on the right when the artist's cut-out stands there. The cut-out is up to 460.dp wide and fades in over its
 * left third, so what is solid starts about 300.dp in; the lyrics (and the sync status under them) stop short of it.
 */
private val CUTOUT_CLEARANCE = 300.dp

/** The artist cut out on nothing, standing in front of the backdrop and fading into it on the left. */
@Composable
private fun StageCutout(url: String, modifier: Modifier = Modifier) {
    var failed by remember(url) { mutableStateOf(false) }
    if (failed) return
    NuvioAsyncImage(
        model = url,
        contentDescription = null,
        modifier = modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.horizontalGradient(0f to Color.Transparent, 0.35f to Color.Black),
                    blendMode = BlendMode.DstIn,
                )
            },
        contentScale = ContentScale.Fit,
        alignment = Alignment.BottomEnd,
        onError = { failed = true },
    )
}

@Composable
private fun Cover(url: String?, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.08f))) {
        if (url != null) {
            NuvioAsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

/** A warm glow in the artist's accent behind the stage that breathes at the motion profile's pace, and holds still when reduced. */
@Composable
internal fun StageGlow(color: Color, spec: StageMotionSpec) {
    val breath = if (spec.animated && spec.pulseMs > 0) {
        val transition = rememberInfiniteTransition(label = "stageGlow")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(spec.pulseMs / 2, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "breath",
        ).value
    } else {
        0.5f
    }
    val alpha = (0.20f + spec.pulseDepth * (breath - 0.5f)).coerceIn(0f, 1f)
    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(
                colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                radius = 1100f,
            ),
        ),
    )
}

/**
 * Fanart that crossfades every [StageMotionSpec.holdMs] while each picture drifts from
 * [StageMotionSpec.scaleFrom] to [StageMotionSpec.scaleTo]. With no fanart the album cover stands in,
 * blurred, so the view is never a flat black. When the spec is not animated the first picture stays
 * put, still.
 */
@Composable
private fun FanartBackdrop(images: List<String>, coverUrl: String?, spec: StageMotionSpec) {
    val usingCover = images.isEmpty()
    val pool = if (usingCover) listOfNotNull(coverUrl) else images
    var index by remember(pool) { mutableIntStateOf(0) }
    LaunchedEffect(pool, spec.animated, spec.holdMs) {
        while (spec.animated && pool.size > 1) {
            delay(spec.holdMs)
            index = (index + 1) % pool.size
        }
    }
    Box(Modifier.fillMaxSize()) {
        if (pool.isNotEmpty()) {
            // Crossfaded by the picture itself, not its place in the list: the outgoing picture is still drawn while it
            // fades, and when the next track's list was shorter its old index ran past the end and crashed the app.
            Crossfade(
                targetState = pool[index.coerceIn(0, pool.size - 1)],
                animationSpec = tween(if (spec.animated) 1800 else 0),
                label = "fanart",
            ) { picture ->
                key(picture) {
                    val push = remember { Animatable(spec.scaleFrom) }
                    LaunchedEffect(spec.animated, spec.scaleFrom, spec.scaleTo, spec.driftMs) {
                        if (spec.animated) {
                            push.snapTo(spec.scaleFrom)
                            push.animateTo(spec.scaleTo, tween(spec.driftMs, easing = LinearEasing))
                        } else {
                            push.snapTo(spec.scaleFrom)
                        }
                    }
                    NuvioAsyncImage(
                        model = picture,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (usingCover) Modifier.blur(56.dp) else Modifier)
                            .graphicsLayer {
                                scaleX = push.value
                                scaleY = push.value
                                // The picture also slides a little as it grows, like the mock's drift.
                                val span = spec.scaleTo - spec.scaleFrom
                                val progress = if (span > 0f) ((push.value - spec.scaleFrom) / span).coerceIn(0f, 1f) else 0f
                                translationX = -spec.slideDp * density * progress
                            },
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
        // Dark enough that white words read on any picture, a little heavier towards the bottom.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.82f))),
            ),
        )
    }
}

@Composable
private fun LyricsPane(
    ui: LyricsUi,
    positionMs: Long,
    playing: Boolean,
    onSeek: (Long) -> Unit,
    reduced: Boolean,
    modifier: Modifier,
    syncState: LyricSyncState = LyricSyncState.Idle,
    onNudge: (Long) -> Unit = {},
    onResetSync: () -> Unit = {},
    onTapSync: (shownLineTimeMs: Long, positionMs: Long) -> Unit = { _, _ -> },
    /** Under a video: four lines (the one before, the sung one, the next two) and the sync strip, at a fixed height. */
    compact: Boolean = false,
    /** How the Stage sets the lyrics (the compact strip under a video ignores it). */
    style: LyricStyle = LyricStyle.Scroll,
    /** fork:stage - the sync strip centred under the lyrics (the three-panel Stage) instead of at the bottom-left. */
    centredSync: Boolean = false,
    /** fork:stage - the Options menu's lyrics switch: off shows nothing here. */
    visible: Boolean = true,
) {
    // Tap to sync: the next line tapped is the one being heard, not a seek.
    var tapping by remember { mutableStateOf(false) }
    if (!visible) {
        Box(modifier)
        return
    }
    if (compact) {
        Column(modifier.height(COMPACT_LYRICS_HEIGHT)) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (ui) {
                    LyricsUi.Loading -> Unit
                    LyricsUi.Missing -> Message("No lyrics found for this track")
                    LyricsUi.Unreachable -> Message("Can't reach the lyrics services. Trying again…")
                    is LyricsUi.Ready -> when (val lyrics = ui.lyrics) {
                        Lyrics.Instrumental -> Message("Instrumental")
                        is Lyrics.Plain -> PlainLyrics(lyrics.lines)
                        is Lyrics.Synced -> CompactSyncedLyrics(lyrics.lines, positionMs, onSeek, reduced, tapping) { lineTimeMs, tappedAtMs ->
                            onTapSync(lineTimeMs, tappedAtMs)
                            tapping = false
                        }
                    }
                }
            }
            val compactSynced = (ui as? LyricsUi.Ready)?.lyrics as? Lyrics.Synced
            if (compactSynced != null) {
                Spacer(Modifier.height(26.dp))
                LyricSyncStrip(syncState, tapping, { tapping = !tapping }, onNudge, onResetSync, Modifier.align(Alignment.CenterHorizontally), note = compactSynced.sourceNote())
            }
        }
        return
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        when (ui) {
            LyricsUi.Loading -> Unit
            LyricsUi.Missing -> Message("No lyrics found for this track")
            LyricsUi.Unreachable -> Message("Can't reach the lyrics services. Trying again…")
            is LyricsUi.Ready -> when (val lyrics = ui.lyrics) {
                Lyrics.Instrumental -> Message("Instrumental")
                is Lyrics.Plain -> PlainLyrics(lyrics.lines)
                is Lyrics.Synced -> {
                    val onTap: (Long, Long) -> Unit = { lineTimeMs, tappedAtMs ->
                        onTapSync(lineTimeMs, tappedAtMs)
                        tapping = false
                    }
                    when (style) {
                        LyricStyle.Calm -> PosterLyrics(lyrics.lines, positionMs, playing, reduced, tapping, onSeek, onTap)
                        LyricStyle.Loud -> LoudLyrics(lyrics.lines, positionMs, playing, reduced, tapping, onSeek, onTap)
                        // The list stops above the source note and sync buttons at the pane's foot and fades out into that edge,
                        // so the last visible line is never printed over them.
                        LyricStyle.Scroll -> Box(Modifier.fillMaxSize().padding(bottom = SCROLL_SYNC_BAND).fadeBottomEdge(SCROLL_FADE)) {
                            SyncedLyrics(lyrics.lines, positionMs, playing, onSeek, reduced, tapping = tapping, onTapLine = onTap)
                        }
                    }
                    LyricSyncStrip(
                        syncState, tapping, { tapping = !tapping }, onNudge, onResetSync,
                        // Down at the pane's foot, apart from the lines, so it does not read as one of them. On the left, under
                        // where the lines start: the right-hand corner is the artist's cut-out and logo, which swallowed it.
                        if (centredSync) Modifier.align(Alignment.BottomCenter).offset(y = 36.dp)
                        else Modifier.align(Alignment.BottomStart).offset(x = 12.dp, y = 36.dp),
                        note = lyrics.sourceNote(),
                        horizontalAlignment = if (centredSync) Alignment.CenterHorizontally else Alignment.Start,
                    )
                }
            }
        }
    }
}

/** Room kept under the scrolling lyrics for the source note and the sync buttons. */
private val SCROLL_SYNC_BAND = 96.dp

/** How far up from the list's lower edge the lines fade out. */
private val SCROLL_FADE = 56.dp

/** Fades what is drawn to nothing over the last [fade] of its height. */
private fun Modifier.fadeBottomEdge(fade: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val h = fade.toPx().coerceAtMost(size.height)
        drawRect(
            Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = size.height - h, endY = size.height),
            blendMode = BlendMode.DstIn,
        )
    }

/** What the lyric timing is doing, with a nudge for when the automatic answer is off or missing. */
@Composable
private fun LyricSyncStrip(
    state: LyricSyncState,
    tapping: Boolean,
    onToggleTap: () -> Unit,
    onNudge: (Long) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    /** How the lyrics are timed and by whom ("Word-synced \u00B7 Unison"), shown when there is no sync status to show instead. */
    note: String? = null,
    /** Centred under a video; left-aligned with the lines on the Stage. */
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
) {
    val offset = state.offsetMs()
    // A word or two above the buttons: the offset itself, or what is going on.
    val label = when {
        tapping -> "Click the line being sung"
        state is LyricSyncState.Checking -> "Syncing\u2026"
        state is LyricSyncState.Unsure -> "Couldn't auto-sync"
        offset != 0L -> formatLyricOffset(offset)
        else -> note
    }
    Column(modifier.padding(bottom = 4.dp), horizontalAlignment = horizontalAlignment, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label.orEmpty(), color = Color.White.copy(alpha = if (tapping) 0.9f else 0.7f), fontSize = 12.sp, maxLines = 1)
        // Grouped in one glass pill, as icons, so the controls read as a set apart from the lyrics.
        Row(Modifier.musicGlass(RoundedCornerShape(percent = 50)).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SyncIcon(
                if (tapping) Icons.Rounded.Close else Icons.Rounded.Sync,
                if (tapping) "Cancel" else "Sync to a line: click the line being sung",
                active = tapping,
                onClick = onToggleTap,
            )
            SyncIcon(Icons.Rounded.FastRewind, "Lyrics 0.5 s earlier") { onNudge(-LYRIC_NUDGE_MS) }
            SyncIcon(Icons.Rounded.FastForward, "Lyrics 0.5 s later") { onNudge(LYRIC_NUDGE_MS) }
            if (offset != 0L) SyncIcon(Icons.Rounded.RestartAlt, "Reset the lyric timing", onClick = onReset)
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.6f), fontSize = 22.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun PlainLyrics(lines: List<String>) {
    // Unsynced lyrics have no line to follow, so they read as a block centred in the pane (on the Stage and under a video alike).
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        itemsIndexed(lines) { _, line ->
            Text(
                text = line,
                color = Color.White.copy(alpha = 0.88f),
                fontSize = 22.sp,
                lineHeight = 30.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun SyncedLyrics(
    lines: List<LyricLine>,
    positionMs: Long,
    playing: Boolean,
    onSeek: (Long) -> Unit,
    reduced: Boolean,
    tapping: Boolean = false,
    onTapLine: (lineTimeMs: Long, positionMs: Long) -> Unit = { _, _ -> },
) {
    val accent = MaterialTheme.nuvio.colors.accent
    // The player reports its position a few times a second; the smooth clock carries it between reports
    // so the word sweep moves every frame (see rememberSmoothPositionMs).
    val smooth = rememberSmoothPositionMs(positionMs, playing)
    val active by remember(lines) { derivedStateOf { activeLyricIndex(lines, smooth.value + NOW_PLAYING_LYRIC_LEAD_MS) } }
    val chorus = remember(lines) { chorusLineIndices(lines) }
    val listState = rememberLazyListState()
    // Keep the sung line a third of the way down: far enough from the top to see what is coming.
    LaunchedEffect(active, lines) {
        if (active >= 0) {
            val height = listState.layoutInfo.viewportSize.height
            if (reduced) listState.scrollToItem(active, scrollOffset = -(height / 3))
            else listState.animateScrollToItem(active, scrollOffset = -(height / 3))
        }
    }
    // fork:stage - centred, and each line set as large as fits the pane in two rows (32 sp down to 22 sp); only a line that
    // still will not fit is cut, with an ellipsis. Measured at the pane's own width, so it follows a resized window.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The sung line grows by a tenth, and the words sit in a flow with gaps: measure a little narrower than the pane.
        val widthPx = with(density) { ((maxWidth - 24.dp) / 1.12f).toPx() }
        fun rowsAt(text: String, sizeSp: Float): Int =
            measurer.measure(
                AnnotatedString(text),
                TextStyle(fontSize = sizeSp.sp, lineHeight = (sizeSp * 1.27f).sp, fontWeight = FontWeight.Bold),
                constraints = Constraints(maxWidth = widthPx.toInt().coerceAtLeast(1)),
            ).lineCount
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 220.dp),
        ) {
            itemsIndexed(lines) { index, line ->
                val target = when {
                    active < 0 -> 0.55f
                    index == active -> 1f
                    kotlin.math.abs(index - active) == 1 -> 0.6f
                    else -> 0.35f
                }
                val alpha by animateFloatAsState(target, tween(if (reduced) 0 else 350), label = "lineAlpha")
                val scale by animateFloatAsState(if (index == active && !reduced) 1.1f else 1f, tween(if (reduced) 0 else 300), label = "lineScale")
                val shownText = line.text.ifBlank { "\u266A" }
                val sizeSp = remember(shownText, widthPx) { fitFontSizeSp(SCROLL_LYRIC_SP, SCROLL_LYRIC_MIN_SP, 2f) { rowsAt(shownText, it) <= 2 } }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .musicKeyFocusRing(RoundedCornerShape(12.dp)).clickable { if (tapping) onTapLine(line.timeMs, positionMs) else onSeek(line.timeMs) }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0.5f, 0.5f)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (index == active && line.text.isNotBlank()) {
                        // The sung line: each word is lit as it is sung, using the source's own word times when it
                        // has them (Netease, Unison, AMLL, LRCMux) and an estimate from word length when it has not.
                        val allWords = remember(lines, index) { lineWords(lines, index) }
                        val keep = remember(allWords, sizeSp, widthPx) {
                            fitWordCount(allWords.size) { rowsAt(allWords.take(it).joinToString(" ") { w -> w.text }, sizeSp) <= 2 }
                        }
                        val words = remember(allWords, keep) {
                            if (keep >= allWords.size) allWords
                            else allWords.take(keep).let { kept -> kept.dropLast(1) + kept.last().copy(text = withEllipsis(kept.last().text)) }
                        }
                        // The words are laid out in the width the size was fitted to, not the whole pane: across the whole
                        // pane a long line's rows ran edge to edge, and the sung line's tenth of growth pushed its outer
                        // words past the clip (both ends of a two-row line were cut off).
                        Box(Modifier.width(with(density) { widthPx.toDp() }), contentAlignment = Alignment.Center) {
                            ActiveLine(
                                words = words,
                                progress = { lineProgress(lines, index, smooth.value) },
                                accent = accent,
                                isChorus = index in chorus,
                                emphasis = false,
                                effects = !reduced,
                                entrance = lineEntranceFor(index),
                                fontSize = sizeSp.sp,
                                centred = true,
                            )
                        }
                    } else {
                        Text(
                            text = shownText,
                            color = Color.White.copy(alpha = alpha),
                            fontSize = sizeSp.sp,
                            lineHeight = (sizeSp * 1.27f).sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Ideal and smallest size of a line in the scrolling list; each line is fitted between them (two rows at most). */
private const val SCROLL_LYRIC_SP = 32f
private const val SCROLL_LYRIC_MIN_SP = 22f

/** How long the Stage widgets stay up after the last input. */
private const val WIDGET_IDLE_MS = 6_000L

/**
 * The place for a real video above the lyrics: 16:9, as large as the room left above the four lyric lines allows.
 * Compose cannot draw over the native video, so this only reserves the space and publishes where it is
 * ([MusicPlayback.inlineVideoFrame]); the player moves its video surface onto it.
 */
@Composable
private fun ColumnScope.InlineVideoSlot() {
    // With the player's own bezel over the picture the rim drawn here would show through it.
    val bezel by MusicPlayback.inlineBezel.collectAsStateWithLifecycle()
    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        // Room is kept all round for the glass rim that frames the picture (see below).
        val width = minOf(maxWidth - VIDEO_RIM * 2, (maxHeight - VIDEO_RIM * 2) * (16f / 9f)).coerceAtLeast(1.dp)
        Box(
            Modifier
                .width(width)
                .height(width * (9f / 16f))
                // fork:music-stage - the native picture is cut to 16 dp corners by the player; behind it this draws the glass
                // rim (a soft halo, a pane, a hairline) that stands a few dp outside it, as the panels' borders do.
                .drawBehind {
                    if (bezel) return@drawBehind
                    val rim = VIDEO_RIM.toPx()
                    val corner = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx() + rim)
                    drawRoundRect(
                        Color.Black.copy(alpha = 0.25f),
                        topLeft = androidx.compose.ui.geometry.Offset(-rim - 6.dp.toPx(), -rim - 2.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(size.width + (rim + 6.dp.toPx()) * 2, size.height + (rim + 6.dp.toPx()) * 2),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx() + rim + 6.dp.toPx()),
                    )
                    drawRoundRect(
                        Color.White.copy(alpha = 0.08f),
                        topLeft = androidx.compose.ui.geometry.Offset(-rim, -rim),
                        size = androidx.compose.ui.geometry.Size(size.width + rim * 2, size.height + rim * 2),
                        cornerRadius = corner,
                    )
                    drawRoundRect(
                        Color.White.copy(alpha = 0.22f),
                        topLeft = androidx.compose.ui.geometry.Offset(-rim, -rim),
                        size = androidx.compose.ui.geometry.Size(size.width + rim * 2, size.height + rim * 2),
                        cornerRadius = corner,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()),
                    )
                }
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black)
                .onGloballyPositioned { c ->
                    val at = c.positionInRoot()
                    MusicPlayback.inlineVideoFrame.value =
                        MusicInlineVideoFrame(at.x, at.y, c.size.width.toFloat(), c.size.height.toFloat())
                },
        )
    }
    DisposableEffect(Unit) { onDispose { MusicPlayback.inlineVideoFrame.value = null } }
    Spacer(Modifier.height(16.dp))
}

/**
 * The glass rim round the Now Playing picture. Wide enough that the square corners of the player's window, which the rounded
 * picture leaves dark, sit inside the rim's rounded corners instead of poking through them (16 dp + this, times 0.29, is less than this).
 */
private val VIDEO_RIM = 9.dp

/** The lyric strip under a video: four line slots and the sync controls. */
private val COMPACT_LYRICS_HEIGHT = 284.dp

/**
 * Four lines around the sung one: the line before, the sung line (lit, up to two lines tall), the next two. Each slot
 * has a fixed height so the video above never jumps as the lines change. A click seeks there (or syncs, while tapping).
 */
@Composable
private fun CompactSyncedLyrics(
    lines: List<LyricLine>,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    reduced: Boolean,
    tapping: Boolean,
    onTapLine: (lineTimeMs: Long, positionMs: Long) -> Unit,
) {
    val accent = MaterialTheme.nuvio.colors.accent
    val active = activeLyricIndex(lines, positionMs + NOW_PLAYING_LYRIC_LEAD_MS)
    val centre = active.coerceAtLeast(0)
    Column(Modifier.fillMaxWidth()) {
        for (offset in -1..2) {
            val index = centre + offset
            val line = lines.getOrNull(index)
            val sung = index == active
            val alpha by animateFloatAsState(
                when {
                    line == null -> 0f
                    sung -> 1f
                    offset == 1 -> 0.6f
                    else -> 0.38f
                },
                tween(if (reduced) 0 else 300),
                label = "compactLine",
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(if (offset == 0) 72.dp else 40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .then(if (line != null) Modifier.musicKeyFocusRing(RoundedCornerShape(10.dp)).clickable { if (tapping) onTapLine(line.timeMs, positionMs) else onSeek(line.timeMs) } else Modifier)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (line != null) {
                    Text(
                        text = line.text.ifBlank { "\u266A" },
                        color = (if (sung) accent else Color.White).copy(alpha = alpha),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        fontSize = if (offset == 0) 28.sp else 22.sp,
                        lineHeight = if (offset == 0) 34.sp else 28.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = if (offset == 0) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    val accent = MaterialTheme.nuvio.colors.accent
    com.nuvio.app.core.ui.NuvioPosterHoverTooltip(title = label) {
        Box(Modifier.size(34.dp).clip(CircleShape).musicKeyFocusRing(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, tint = if (active) accent else Color.White.copy(alpha = 0.55f), modifier = Modifier.size(18.dp))
        }
    }
}

/** The Stage's left column, and the album case in it (the disc peeks out to the right of the case). */
private val STAGE_COLUMN_WIDTH = 390.dp
private val STAGE_CASE_SIZE = 280.dp

/** A bracketed guest credit: "(feat. X)", "[ft. X]", "(featuring X)", "(with X)". Version notes like "(Remix)" stay. */
private val STAGE_CREDIT_BRACKET = Regex("""\s*[(\[]\s*(feat\.?|ft\.?|featuring|with)\s[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)

/** A credit after the title with no brackets: "Song feat. X", "Song ft. X". Not a bare "with", which real titles use. */
private val STAGE_CREDIT_TRAILING = Regex("""\s+(feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)

/**
 * The title as the stage shows it under the album art: the song alone, without its guest credits, so a long
 * "(feat. A, B & C)" does not take three lines of the big serif title. Falls back to the full title if nothing is left.
 */
internal fun stageSongTitle(title: String): String =
    title.replace(STAGE_CREDIT_BRACKET, "").replace(STAGE_CREDIT_TRAILING, "").trim().ifEmpty { title }
