package com.nuvio.app.features.music.library
// FORK UI (Spyder): Nick's artist page redesign, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.HoldToSelectState
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.PosterZoomOverlayAction
import com.nuvio.app.core.ui.rememberHoldToSelectState
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.NuvioGlass
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.secondaryClick
import com.nuvio.app.features.music.LENS_GAP
import com.nuvio.app.features.music.LENS_LIBRARY_TINT
import com.nuvio.app.features.music.LENS_OUTER
import com.nuvio.app.features.music.LensBody
import com.nuvio.app.features.music.LensButton
import com.nuvio.app.features.music.LensFact
import com.nuvio.app.features.music.LensFactGrid
import com.nuvio.app.features.music.LensPanel
import com.nuvio.app.features.music.LensPanelHeader
import com.nuvio.app.features.music.LensPanelTitle
import com.nuvio.app.features.music.LensRow
import com.nuvio.app.features.music.LensSectionLabel
import com.nuvio.app.features.music.LocalMusicBarInset
import com.nuvio.app.features.music.MusicAlbum
import com.nuvio.app.features.music.MusicItemMenu
import com.nuvio.app.features.music.musicMenuTrigger
import com.nuvio.app.features.music.rememberMusicMenu
import com.nuvio.app.features.music.MusicPlayback
import com.nuvio.app.features.music.MusicTopSong
import com.nuvio.app.features.music.booklet.SongStories
import com.nuvio.app.features.music.booklet.SongStory
import com.nuvio.app.features.music.defaultMusicCatalog
import com.nuvio.app.features.music.formatMusicTime
import com.nuvio.app.features.music.identity.HeroSource
import com.nuvio.app.features.music.identity.identity
import com.nuvio.app.features.music.lensPanelWidth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * fork:artist-stage - the redesigned artist page (Music settings, "New artist page"), built like the new album page.
 *
 *  - The hero fills the first screen: the artist's photo, darkened, with the logo, the facts, the stats in words, the genres
 *    and a row of labelled buttons, Play Essentials first.
 *  - Moving down scrolls the hero away to three panels as tall as the window: about the artist (or the song the keyboard is
 *    on in Essentials), the lens tabs in the middle (Essentials, Discography), and the library side with Similar taste.
 *
 * Panel sizes depend on the window only, never on what they hold. All play / dialog / Add logic that the old page has stays
 * in MusicArtistScreen; this draws, reports taps, and plays the Essentials queue itself.
 */

/** The new artist page's lens tabs. */
internal enum class ArtistLens(val label: String) {
    Essentials("Essentials"),
    Discography("Discography"),
}

/** Windows narrower than this keep the old artist page even with the setting on (the three panels would not fit). */
internal val ARTIST_STAGE_MIN_WIDTH = 1280.dp

/** How long the keyboard rests on a song before its story is asked for (arrowing down the list asks nothing). */
private const val STAGE_STORY_REST_MS = 700L

@Composable
internal fun ArtistStage(
    artist: LibraryArtist,
    discography: ArtistDiscography,
    notice: String?,
    saved: Boolean,
    refreshing: String?,
    working: Boolean,
    filter: ReleaseFilter,
    playableOnly: Boolean,
    demosOpen: Boolean,
    canShuffle: Boolean,
    topLabel: String?,
    onBack: () -> Unit,
    /** Plays the newest playable release: what Play Essentials falls back to when YouTube Music lists no songs. */
    onPlayLatest: () -> Unit,
    onShuffle: () -> Unit,
    onPlayTop: () -> Unit,
    onLinks: () -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpen: (LibraryRelease) -> Unit,
    onInfo: (LibraryRelease) -> Unit,
    onPlayableOnly: (Boolean) -> Unit,
    onFilter: (ReleaseFilter) -> Unit,
    onToggleDemos: () -> Unit,
    addonKeys: Set<String>,
    /** fork:music-menu - plays [LibraryRelease] (shuffled or not) the way the old page's Play does. */
    onPlayRelease: (LibraryRelease, Boolean) -> Unit,
    /** fork:music-menu - puts the release after what is queued. */
    onQueueRelease: (LibraryRelease) -> Unit,
    /** fork:music-menu - opens an album page by its id (a song's album). */
    onOpenAlbumId: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    val catalog = remember { defaultMusicCatalog() }
    val hold = rememberHoldToSelectState()
    val scope = rememberCoroutineScope()

    // Essentials: YouTube Music's most played list for the artist, then the albums of its songs (length, cover).
    val topSongs by produceState<List<MusicTopSong>?>(null, artist.id) {
        value = try {
            catalog.artist(artist.id)?.topSongs.orEmpty()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            emptyList()
        }
    }
    var albums by remember(artist.id) { mutableStateOf<Map<String, MusicAlbum>>(emptyMap()) }
    LaunchedEffect(topSongs) {
        val songs = topSongs ?: return@LaunchedEffect
        for (id in essentialAlbumIds(songs)) {
            if (id in albums) continue
            val album = try {
                catalog.album(id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                null
            }
            if (album != null) albums = albums + (id to album)
        }
    }
    val rows = remember(topSongs, albums) { essentialRows(topSongs.orEmpty(), albums) }
    val mix = remember(rows, artist.id) { essentialsMix(artist.id, artist.name, artist.art.thumb, rows) }
    fun playEssentials(fromVideoId: String? = null) {
        val queue = mix
        if (queue == null) {
            onPlayLatest()
            return
        }
        val at = fromVideoId?.let { id -> queue.tracks.indexOfFirst { it.videoId == id } }?.takeIf { it >= 0 } ?: 0
        MusicPlayback.player.play(queue, trackIndex = at)
    }

    // fork:music-menu - what a song's and a release's More menus offer.
    fun oneSong(row: EssentialRow): MusicAlbum? {
        val queue = mix ?: return null
        val track = queue.tracks.firstOrNull { it.videoId == row.videoId } ?: return null
        return queue.copy(browseId = "${queue.browseId}:${row.videoId}", title = row.title, tracks = listOf(track.copy(trackNumber = 1)))
    }
    val songActions = SongMenuActions(
        playFrom = { playEssentials(it.videoId) },
        playOne = { row -> oneSong(row)?.let { MusicPlayback.player.play(it) } },
        queueOne = { row -> oneSong(row)?.let { MusicPlayback.player.enqueue(it) } },
        openAlbum = onOpenAlbumId,
    )
    val releaseActions = ReleaseMenuActions(
        play = onPlayRelease,
        queue = onQueueRelease,
        open = onOpen,
        info = onInfo,
        playable = { it.isPlayable || it.key in addonKeys },
    )

    var lens by rememberSaveable(artist.id) { mutableStateOf(ArtistLens.Essentials) }
    // The song the keyboard is on in Essentials: the left panel is about it; null is about the artist.
    var focusRank by remember(artist.id) { mutableStateOf<Int?>(null) }
    val focusRow = focusRank?.let { r -> rows.firstOrNull { it.rank == r } }
    val singles = remember(artist.releases) { singleKeys(artist.releases) }

    BoxWithConstraints(modifier.background(colors.background)) {
        val viewport = maxHeight
        val side = lensPanelWidth(maxWidth)
        val inset = LocalMusicBarInset.current
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            StageHero(
                artist = artist,
                discography = discography,
                notice = notice,
                saved = saved,
                refreshing = refreshing,
                canShuffle = canShuffle,
                topLabel = topLabel,
                onBack = onBack,
                onPlayEssentials = { playEssentials() },
                onShuffle = onShuffle,
                onPlayTop = onPlayTop,
                onLinks = onLinks,
                onRefresh = onRefresh,
                onRemove = onRemove,
                modifier = Modifier.fillMaxWidth().height(viewport),
            )
            Row(
                Modifier.fillMaxWidth().height(viewport)
                    .padding(start = LENS_OUTER, end = LENS_OUTER, top = LENS_OUTER, bottom = inset + 24.dp),
                horizontalArrangement = Arrangement.spacedBy(LENS_GAP),
            ) {
                val left = Modifier.width(side)
                if (focusRow != null) {
                    StageSongPanel(artist, focusRow, single = songKey(focusRow.title) in singles, onPlay = { playEssentials(focusRow.videoId) }, modifier = left)
                } else {
                    StageArtistPanel(artist, discography, canPlay = mix != null || discography.playableCount > 0, onPlay = { playEssentials() }, modifier = left)
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    StageCentreHeader(artist, discography)
                    ArtistLensTabs(lens, onSelect = { lens = it; focusRank = null })
                    when (lens) {
                        ArtistLens.Essentials -> EssentialsLens(
                            rows = rows,
                            loading = topSongs == null,
                            top = remember(artist.topAlbumKeys, artist.releases) { artistTopReleases(artist) },
                            focusRank = focusRank,
                            onFocusSong = { focusRank = it },
                            onPlaySong = { playEssentials(it.videoId) },
                            songActions = songActions,
                            releaseActions = releaseActions,
                            hold = hold,
                            onOpen = onOpen,
                            onInfo = onInfo,
                            addonKeys = addonKeys,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                        ArtistLens.Discography -> DiscographyLens(
                            artist = artist,
                            discography = discography,
                            working = working,
                            filter = filter,
                            playableOnly = playableOnly,
                            demosOpen = demosOpen,
                            onOpen = onOpen,
                            onInfo = onInfo,
                            onPlayableOnly = onPlayableOnly,
                            onFilter = onFilter,
                            onToggleDemos = onToggleDemos,
                            addonKeys = addonKeys,
                            releaseActions = releaseActions,
                            hold = hold,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                    }
                }
                StageLibraryPanel(
                    artist = artist,
                    discography = discography,
                    addonCount = addonKeys.size,
                    canShuffle = canShuffle,
                    onShuffle = onShuffle,
                    onOpenArtist = onOpenArtist,
                    openSimilar = { name, done ->
                        scope.launch {
                            val found = try {
                                rungArtist(catalog, name)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                null
                            }
                            done(found != null)
                            if (found != null) onOpenArtist(found.browseId)
                        }
                    },
                    modifier = Modifier.width(side),
                )
            }
        }
    }
}

// region hero

/**
 * The first screen: the artist's picture filling it (a blurred cover when there is no photo), darkened evenly with a deeper
 * foot, and everything centred over it. Nothing in it depends on another part's size.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StageHero(
    artist: LibraryArtist,
    discography: ArtistDiscography,
    notice: String?,
    saved: Boolean,
    refreshing: String?,
    canShuffle: Boolean,
    topLabel: String?,
    onBack: () -> Unit,
    onPlayEssentials: () -> Unit,
    onShuffle: () -> Unit,
    onPlayTop: () -> Unit,
    onLinks: () -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    val dark = colors.background
    val identity = remember(artist) { artist.identity() }
    Box(modifier.background(Brush.verticalGradient(0f to NuvioGlass.night(dark, colors.accent), 1f to dark))) {
        identity.hero?.let { hero ->
            val blurred = hero.source == HeroSource.BlurredCover
            NuvioAsyncImage(
                model = hero.url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().alpha(if (blurred) 0.55f else 1f).let { if (blurred) it.blur(40.dp) else it },
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to dark.copy(alpha = 0.35f), 0.45f to dark.copy(alpha = 0.55f), 0.92f to dark, 1f to dark),
            ),
        )
        Box(Modifier.padding(start = 24.dp, top = 16.dp)) { NuvioBackButton(onClick = onBack) }

        Column(
            Modifier.align(Alignment.Center).widthIn(max = 1000.dp).padding(horizontal = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val logo = artist.art.bestLogo
            if (logo != null) {
                NuvioAsyncImage(
                    model = logo,
                    contentDescription = artist.name,
                    modifier = Modifier.width(640.dp).height(170.dp),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.Center,
                )
            } else {
                Text(
                    artist.name,
                    style = MaterialTheme.typography.displayLarge,
                    fontFamily = FontFamily.Serif,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val facts = listOfNotNull(
                passportLine(artist).takeIf { it.isNotEmpty() },
                "${discography.totalCount} release${if (discography.totalCount == 1) "" else "s"}",
            ).joinToString("  ·  ")
            Text(facts, style = MaterialTheme.typography.titleMedium, color = colors.textSecondary, textAlign = TextAlign.Center)
            // The stats in words: "25" alone never said what it counted.
            val stats = listOfNotNull(
                artist.fans?.let { "${formatCount(it)} fans" },
                ratingText(artist.rating)?.let { "Rated $it" },
                "${discography.playableCount} playable here",
            ).joinToString("   ·   ")
            Text(stats, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, textAlign = TextAlign.Center)
            val (mood, genres) = passportChips(artist)
            val chips = (listOfNotNull(mood) + genres).take(6)
            if (chips.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    chips.forEach { chip ->
                        Text(
                            chip.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } },
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.textSecondary,
                            modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(999.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            val inLibrary = MusicLibraryRepository.state.collectAsState().value.contains(artist.id)
            val linkCount = groupLinks(artist.links).values.sumOf { it.size }
            val playFocus = remember(artist.id) { FocusRequester() }
            LaunchedEffect(artist.id) {
                // Asked a few times: the page root's own first-control request lands first.
                repeat(10) {
                    delay(80)
                    if (runCatching { playFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
                }
            }
            FlowRow(
                Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HeroButton("Play Essentials", Icons.Rounded.PlayArrow, primary = true, modifier = Modifier.focusRequester(playFocus), onClick = onPlayEssentials)
                HeroButton("Shuffle all", Icons.Rounded.Shuffle, enabled = canShuffle, onClick = onShuffle)
                topLabel?.let { HeroButton("Play $it", Icons.Rounded.LocalFireDepartment, onClick = onPlayTop) }
                HeroButton(
                    if (inLibrary) "In your library" else "Add to library",
                    if (inLibrary) Icons.Rounded.Check else Icons.Rounded.Add,
                    active = inLibrary,
                    onClick = { if (inLibrary) onRemove() else MusicLibraryRepository.add(artist.id) },
                )
                // Links and Refresh live in More, so the row stays one line, centred.
                if (linkCount > 0 || saved) {
                    var moreOpen by remember { mutableStateOf(false) }
                    Box {
                        HeroButton("More", Icons.Rounded.MoreHoriz, onClick = { moreOpen = true })
                        DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                            if (linkCount > 0) {
                                DropdownMenuItem(
                                    text = { Text("Links ($linkCount)") },
                                    leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                                    onClick = { moreOpen = false; onLinks() },
                                )
                            }
                            if (saved) {
                                DropdownMenuItem(
                                    text = { Text(refreshing ?: "Refresh") },
                                    leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                                    enabled = refreshing == null,
                                    onClick = { moreOpen = false; onRefresh() },
                                )
                            }
                        }
                    }
                }
            }
            (notice ?: refreshing)?.let { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium, color = if (notice != null) colors.warning else colors.textMuted, textAlign = TextAlign.Center, maxLines = 2)
            }
        }

        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = LocalMusicBarInset.current + 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(20.dp))
            Text("Down for more about ${artist.name}", style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
        }
    }
}

/** A labelled glass pill for the hero's row; [primary] is filled in the accent, [active] is edged in it. */
@Composable
private fun HeroButton(
    label: String,
    icon: ImageVector,
    primary: Boolean = false,
    active: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(28.dp)
    val fill = when {
        primary -> colors.accent
        active -> colors.accent.copy(alpha = 0.22f)
        else -> Color.Black.copy(alpha = 0.45f)
    }
    val edge = when {
        primary -> Color.White.copy(alpha = 0.85f)
        active -> colors.accent.copy(alpha = 0.7f)
        else -> Color.White.copy(alpha = 0.16f)
    }
    val tint = if (enabled) (if (primary) Color.White else colors.textPrimary) else colors.textDisabled
    Row(
        modifier.height(56.dp).clip(shape).background(fill).border(1.dp, edge, shape)
            .musicKeyFocusRing(shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium, color = tint, maxLines = 1)
    }
}

// endregion

// region side panels

/** Left, with no song in focus: who the artist is, four facts, and their story (the library's biography). */
@Composable
private fun StageArtistPanel(artist: LibraryArtist, discography: ArtistDiscography, canPlay: Boolean, onPlay: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.nuvio.colors
    val own = artist.biography?.trim().orEmpty()
    // No biography in the library: the opening of the artist's English Wikipedia page (the one Liner Notes reads), when it
    // is about a musician.
    val wiki by produceState<String?>(StageWikipedia.peek(artist.name), artist.name, own.isEmpty()) {
        if (own.isEmpty() && value == null) value = StageWikipedia.load(artist.name)
    }
    val bio = own.ifEmpty { wiki.orEmpty() }
    val bioSource = if (own.isNotEmpty()) artist.biographySource else if (bio.isNotEmpty()) "Wikipedia" else null
    LensPanel(modifier) {
        LensPanelHeader(Icons.Rounded.AutoAwesome, "ABOUT THIS ARTIST", pill = bioSource ?: "Library", tint = colors.accent)
        LensPanelTitle(artist.name, passportLine(artist).ifEmpty { "Artist" })
        LensFactGrid(
            remember(artist, discography.totalCount, discography.playableCount) {
                artistStageFacts(artist, discography.totalCount, discography.playableCount).map { (label, value) -> LensFact(label, value) }
            },
        )
        LensSectionLabel("THE STORY", bioSource?.let { "From $it" }.orEmpty())
        LensBody(colors.accent, highlighted = true) {
            Text(
                bio.ifEmpty { "Nothing has been written about ${artist.name} yet." },
                style = MaterialTheme.typography.bodyLarge,
                color = if (bio.isNotEmpty()) colors.textPrimary else colors.textMuted,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        LensButton("Play Essentials", tint = colors.accent, enabled = canPlay, onClick = onPlay)
    }
}

/**
 * Left, with a song in focus in Essentials: the song, four facts, and its story (the same one the album page tells: its
 * Wikipedia article, written up by the AI when a key is set). The story is asked for once the keyboard rests on the song.
 */
@Composable
private fun StageSongPanel(artist: LibraryArtist, row: EssentialRow, single: Boolean, onPlay: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.nuvio.colors
    var story by remember(artist.name, row.title) { mutableStateOf<SongStory?>(SongStories.peek(artist.name, row.title)) }
    var loading by remember(artist.name, row.title) { mutableStateOf(false) }
    LaunchedEffect(artist.name, row.title) {
        val aiReady = com.nuvio.app.features.discover.DiscoverAiSettingsRepository.snapshot().isConfigured
        val kept = story
        if (kept != null && (kept.ai || !aiReady)) return@LaunchedEffect
        loading = kept == null
        delay(STAGE_STORY_REST_MS)
        runCatching { SongStories.load(artist.name, row.title, wantAi = true) }.getOrNull()?.let { story = it }
        loading = false
    }
    val text = lensStoryBox(story)?.text
    val source = when {
        text != null && story?.ai == true -> "AI"
        text != null -> "Wikipedia"
        else -> "Library"
    }
    LensPanel(modifier) {
        LensPanelHeader(Icons.Rounded.AutoAwesome, "ABOUT THIS SONG", pill = source, tint = colors.accent)
        LensPanelTitle(row.title, row.albumTitle?.let { "On $it" } ?: "One of ${artist.name}'s most played")
        LensFactGrid(
            listOf(
                LensFact("Length", row.durationSeconds.takeIf { it > 0 }?.let { formatMusicTime(it * 1000L) } ?: "–"),
                LensFact("Most played", "#${row.rank}"),
                LensFact("Released as a single", if (single) "Yes" else "No"),
                LensFact("Album", row.albumTitle ?: "–"),
            ),
        )
        LensSectionLabel(
            "THE STORY",
            when {
                text != null && story?.ai == true -> "Written from Wikipedia"
                text != null -> "From Wikipedia"
                loading -> "Looking…"
                else -> ""
            },
        )
        LensBody(colors.accent, highlighted = true) {
            Text(
                text ?: if (loading) "Looking for this song's story…" else "Nothing has been written about this song yet.",
                style = MaterialTheme.typography.bodyLarge,
                color = if (text != null) colors.textPrimary else colors.textMuted,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        LensButton("Play the song", tint = colors.accent, enabled = true, onClick = onPlay)
    }
}

/** The artist's Wikipedia opening, asked once per name per session (a miss is kept too, so it is not asked again). */
private object StageWikipedia {
    private val kept = HashMap<String, String?>()

    fun peek(name: String): String? = kept[name.lowercase()]

    suspend fun load(name: String): String? {
        val key = name.lowercase()
        if (kept.containsKey(key)) return kept[key]
        val text = try {
            WikipediaClient.artistText(name)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return null // offline: asked again next time
        }?.takeIf(::looksLikeMusicianText)
        kept[key] = text
        return text
    }
}

/** A similar artist as the right panel lists it: a saved one opens at once, a name from Last.fm is looked up first. */
private class StageSimilar(val name: String, val savedId: String?)

/** Right: the library side. What of the discography plays here, then Similar taste as a list, then Shuffle all. */
@Composable
private fun StageLibraryPanel(
    artist: LibraryArtist,
    discography: ArtistDiscography,
    addonCount: Int,
    canShuffle: Boolean,
    onShuffle: () -> Unit,
    onOpenArtist: (String) -> Unit,
    openSimilar: (name: String, done: (Boolean) -> Unit) -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    val library = MusicLibraryRepository.state.collectAsState().value
    val inLibrary = library.contains(artist.id)
    val saved = remember(artist.id, library.artists.size) { similarInLibrary(artist, library.artists, limit = 4) }
    val names by produceState<List<SimilarArtist>?>(null, artist.name) {
        value = try {
            SimilarTaste.forArtist(artist.name).orEmpty()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            emptyList()
        }
    }
    val similar = remember(saved, names) {
        val savedNames = saved.map { it.name.lowercase() }.toSet()
        saved.map { StageSimilar(it.name, it.id) } +
            names.orEmpty().filter { it.name.lowercase() !in savedNames }.map { StageSimilar(it.name, null) }
    }.take(8)
    var note by remember(artist.id) { mutableStateOf<String?>(null) }
    LensPanel(modifier) {
        LensPanelHeader(Icons.Rounded.LibraryMusic, "IN YOUR LIBRARY", pill = "Library", tint = LENS_LIBRARY_TINT)
        LensPanelTitle(
            if (inLibrary) "Saved in your library" else "Not in your library",
            "${discography.playableCount} of ${discography.totalCount} releases play here",
        )
        val counts = discography.filterCounts
        LensFactGrid(
            listOf(
                LensFact("Albums", "${counts[ReleaseFilter.Albums] ?: 0}"),
                LensFact("Singles & EPs", "${(counts[ReleaseFilter.Singles] ?: 0) + (counts[ReleaseFilter.EPs] ?: 0)}"),
                LensFact("On your addon", if (addonCount == 0) "None" else "$addonCount"),
                LensFact("Demos", if (discography.demos.isEmpty()) "None" else "${discography.demos.size}"),
            ),
        )
        LensSectionLabel("SIMILAR TASTE", note ?: if (names == null) "Looking…" else "")
        LensBody(LENS_LIBRARY_TINT, highlighted = false) {
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (similar.isEmpty() && names != null) {
                    Text("No similar artists found.", style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
                }
                similar.forEach { other ->
                    LensRow(
                        title = other.name,
                        where = if (other.savedId != null) "In your library" else "Similar taste",
                        tag = if (other.savedId != null) "LIBRARY" else "OPEN",
                        onClick = {
                            val id = other.savedId
                            if (id != null) onOpenArtist(id) else openSimilar(other.name) { found -> note = if (found) null else "${other.name} isn't on YouTube Music" }
                        },
                    )
                }
            }
        }
        LensButton("Shuffle all", tint = LENS_LIBRARY_TINT, enabled = canShuffle, onClick = onShuffle)
    }
}

// endregion

// region centre

/** The centre's header: the portrait at the left, the name centred, the top album's cover at the right, the same size. */
@Composable
private fun StageCentreHeader(artist: LibraryArtist, discography: ArtistDiscography) {
    val colors = MaterialTheme.nuvio.colors
    val firstTop = remember(artist.topAlbumKeys, artist.releases) { stageHeaderRelease(artist) }
    Row(Modifier.fillMaxWidth().height(104.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(96.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.06f)).border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)) {
            artist.art.thumb?.let { NuvioAsyncImage(model = it, contentDescription = artist.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Column(Modifier.weight(1f).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "ARTIST · ${discography.totalCount} RELEASES · ${discography.playableCount} PLAYABLE",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.4.sp,
                color = colors.accent,
                maxLines = 1,
            )
            Text(
                artist.name,
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = FontFamily.Serif,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Box(Modifier.size(96.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.06f))) {
            firstTop?.let { ReleaseCover(it, Modifier.fillMaxSize()) }
        }
    }
}

/** The lens tabs: one glass pill, equal slots, the chosen one in the accent (the album page's tabs, for the artist lenses). */
@Composable
private fun ArtistLensTabs(selected: ArtistLens, onSelect: (ArtistLens) -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val pill = RoundedCornerShape(24.dp)
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(pill).background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.09f), pill).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ArtistLens.entries.forEach { lens ->
            val on = lens == selected
            val tab = RoundedCornerShape(20.dp)
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(tab)
                    .background(if (on) colors.accent.copy(alpha = 0.20f) else Color.Transparent)
                    .musicKeyFocusRing(tab)
                    .semantics { role = Role.Tab; this.selected = on }
                    .clickable { onSelect(lens) },
                contentAlignment = Alignment.Center,
            ) {
                Text(lens.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = if (on) colors.accent else colors.textSecondary, maxLines = 1)
            }
        }
    }
}

/** Essentials: the artist's most played songs (a song plays the Essentials queue from there), then the top albums. */
@Composable
private fun EssentialsLens(
    rows: List<EssentialRow>,
    loading: Boolean,
    top: List<LibraryRelease>,
    focusRank: Int?,
    onFocusSong: (Int?) -> Unit,
    onPlaySong: (EssentialRow) -> Unit,
    songActions: SongMenuActions,
    releaseActions: ReleaseMenuActions,
    hold: HoldToSelectState,
    onOpen: (LibraryRelease) -> Unit,
    onInfo: (LibraryRelease) -> Unit,
    addonKeys: Set<String>,
    modifier: Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("#", style = MaterialTheme.typography.labelMedium, color = colors.textMuted, modifier = Modifier.width(36.dp))
            Text("TOP SONGS", style = MaterialTheme.typography.labelMedium, letterSpacing = 1.4.sp, color = colors.textMuted, modifier = Modifier.weight(1f))
            Text("ALBUM", style = MaterialTheme.typography.labelMedium, letterSpacing = 1.4.sp, color = colors.textMuted, modifier = Modifier.width(220.dp))
            Text("TIME", style = MaterialTheme.typography.labelMedium, letterSpacing = 1.4.sp, color = colors.textMuted, textAlign = TextAlign.End, modifier = Modifier.width(56.dp))
        }
        if (rows.isEmpty()) {
            Text(
                if (loading) "Looking up the most played songs…" else "YouTube Music lists no most played songs for this artist.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
        rows.forEach { row ->
            val on = row.rank == focusRank
            val shape = RoundedCornerShape(10.dp)
            val menu = rememberMusicMenu()
            MusicItemMenu(menu) { songMenu(row, songActions) }
            val select = { onFocusSong(row.rank); onPlaySong(row) }
            Row(
                Modifier.fillMaxWidth().height(46.dp).clip(shape)
                    .background(if (on) colors.accent.copy(alpha = 0.10f) else Color.Transparent)
                    .border(1.dp, if (on) colors.accent.copy(alpha = 0.6f) else Color.Transparent, shape)
                    .onFocusChanged { if (it.isFocused) onFocusSong(row.rank) }
                    .musicMenuTrigger(menu, hold, onSelect = select)
                    .musicKeyFocusRing(shape)
                    .clickable(onClick = select)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${row.rank}", style = MaterialTheme.typography.bodyLarge, color = colors.textMuted, modifier = Modifier.width(36.dp))
                Text(row.title, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(row.albumTitle.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(220.dp))
                Text(
                    row.durationSeconds.takeIf { it > 0 }?.let { formatMusicTime(it * 1000L) }.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(56.dp),
                )
            }
        }
        if (top.isNotEmpty()) {
            Text(
                "TOP ALBUMS",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.4.sp,
                color = colors.textSecondary,
                modifier = Modifier.padding(start = 14.dp, top = 20.dp, bottom = 8.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                top.take(5).forEach { release ->
                    StageAlbumTile(release, addon = release.key in addonKeys, onFocus = { onFocusSong(null) }, actions = releaseActions, hold = hold, modifier = Modifier.weight(1f))
                }
                // Fewer than five: the empty slots keep the tiles the same size.
                repeat(5 - top.take(5).size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** A top album: its cover and title; right click (or the release dialog) shows its details. */
@Composable
private fun StageAlbumTile(release: LibraryRelease, addon: Boolean, onFocus: () -> Unit, actions: ReleaseMenuActions, hold: HoldToSelectState, modifier: Modifier) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(12.dp)
    val menu = rememberMusicMenu()
    MusicItemMenu(menu) { releaseMenu(release, actions) }
    val open = { actions.open(release) }
    Column(
        modifier.clip(shape)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .musicMenuTrigger(menu, hold, onSelect = open)
            .musicKeyFocusRing(shape)
            .clickable(onClick = open)
            .alpha(if (release.isPlayable || addon) 1f else 0.5f)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ReleaseCover(release, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)))
        Text(release.title, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(release.year ?: release.releaseDate?.take(4), if (addon) "On your addon" else null).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            maxLines = 1,
        )
    }
}

/** Discography: the old page's filter and release grid, inside the centre panel. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiscographyLens(
    artist: LibraryArtist,
    discography: ArtistDiscography,
    working: Boolean,
    filter: ReleaseFilter,
    playableOnly: Boolean,
    demosOpen: Boolean,
    onOpen: (LibraryRelease) -> Unit,
    onInfo: (LibraryRelease) -> Unit,
    onPlayableOnly: (Boolean) -> Unit,
    onFilter: (ReleaseFilter) -> Unit,
    onToggleDemos: () -> Unit,
    addonKeys: Set<String>,
    releaseActions: ReleaseMenuActions,
    hold: HoldToSelectState,
    modifier: Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    val timeline = remember(artist.releases) { buildEraTimeline(artist.releases) }
    val dated = remember(timeline) {
        timeline.eras.flatMap { it.groups }
            .sortedWith(compareByDescending<ReleaseGroup> { releasePosition(it.primary) ?: 0.0 }.thenBy { it.primary.title })
    }
    val shown = if (filter == ReleaseFilter.Demos) emptyList() else dated.filter { it.passes(playableOnly, filter, addonKeys) }
    val undated = if (filter == ReleaseFilter.Demos) emptyList() else timeline.undated.filter { it.passes(playableOnly, filter, addonKeys) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        FilterPanel(discography, filter, playableOnly, onPlayableOnly, onFilter, addonInPlay = addonKeys.isNotEmpty())
        val all = shown + undated
        if (all.isEmpty() && filter != ReleaseFilter.Demos) {
            Text(
                when {
                    working -> "Looking up releases…"
                    timeline.isEmpty -> "No releases found."
                    playableOnly -> "Nothing here is on YouTube Music. Pick All to see it anyway."
                    else -> "Nothing matches the filter."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
            )
        }
        RELEASE_SECTIONS.forEach { (title, kinds) ->
            val section = all.filter { it.kind in kinds }
            if (section.isEmpty()) return@forEach
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
                Text("${section.size}", style = MaterialTheme.typography.bodyMedium, color = colors.textMuted, modifier = Modifier.padding(bottom = 2.dp))
            }
            // As many columns of about 150 dp as fit, each widened so a row runs edge to edge.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val gap = 14.dp
                val columns = ((maxWidth + gap) / (150.dp + gap)).toInt().coerceAtLeast(1)
                val card = (maxWidth - gap * (columns - 1)) / columns
                FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    section.forEach { group ->
                        val menu = rememberMusicMenu()
                        MusicItemMenu(menu) { releaseMenu(group.primary, releaseActions) }
                        ReleaseCard(
                            group,
                            onClick = { onOpen(group.primary) },
                            addon = group.onAddon(addonKeys),
                            width = card,
                            extra = Modifier.musicMenuTrigger(menu, hold, onSelect = { onOpen(group.primary) }),
                        )
                    }
                }
            }
        }
        if (discography.demos.isNotEmpty()) {
            DemosArchive(discography.demos, open = demosOpen, onToggle = onToggleDemos, onOpen = onOpen, onInfo = onInfo)
        }
        Spacer(Modifier.height(16.dp))
    }
}

// endregion

// region more menu

/** fork:music-menu - what a song's More menu can do. */
internal class SongMenuActions(
    val playFrom: (EssentialRow) -> Unit,
    val playOne: (EssentialRow) -> Unit,
    val queueOne: (EssentialRow) -> Unit,
    val openAlbum: (String) -> Unit,
)

/** fork:music-menu - what a release's More menu can do; [playable] says whether it plays here at all. */
internal class ReleaseMenuActions(
    val play: (LibraryRelease, Boolean) -> Unit,
    val queue: (LibraryRelease) -> Unit,
    val open: (LibraryRelease) -> Unit,
    val info: (LibraryRelease) -> Unit,
    val playable: (LibraryRelease) -> Boolean,
)

private fun songMenu(row: EssentialRow, actions: SongMenuActions): List<PosterZoomOverlayAction> = listOf(
    PosterZoomOverlayAction(icon = Icons.Rounded.PlayArrow, label = "Play from here", onSelected = { actions.playFrom(row) }),
    PosterZoomOverlayAction(icon = Icons.Rounded.MusicNote, label = "Play just this song", onSelected = { actions.playOne(row) }),
    PosterZoomOverlayAction(icon = Icons.Rounded.PlaylistAdd, label = "Add to queue", onSelected = { actions.queueOne(row) }),
    PosterZoomOverlayAction(
        icon = Icons.Rounded.Album,
        label = row.albumTitle?.let { "Open $it" } ?: "Open the album",
        onSelected = { row.albumBrowseId?.let(actions.openAlbum) },
        enabled = row.albumBrowseId != null,
        group = 1,
    ),
)

private fun releaseMenu(release: LibraryRelease, actions: ReleaseMenuActions): List<PosterZoomOverlayAction> {
    val plays = actions.playable(release)
    return listOf(
        PosterZoomOverlayAction(icon = Icons.Rounded.PlayArrow, label = "Play", onSelected = { actions.play(release, false) }, enabled = plays),
        PosterZoomOverlayAction(icon = Icons.Rounded.Shuffle, label = "Shuffle", onSelected = { actions.play(release, true) }, enabled = plays),
        PosterZoomOverlayAction(icon = Icons.Rounded.PlaylistAdd, label = "Add to queue", onSelected = { actions.queue(release) }, enabled = plays),
        PosterZoomOverlayAction(icon = Icons.Rounded.Album, label = "Open", onSelected = { actions.open(release) }, group = 1),
        PosterZoomOverlayAction(icon = Icons.Rounded.Info, label = "Details and versions", onSelected = { actions.info(release) }, group = 1),
    )
}

// endregion
