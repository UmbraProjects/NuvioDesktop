package com.nuvio.app.features.music.library

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioAlertDialog
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioTextField
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.music.MusicCdSleeve
import com.nuvio.app.features.music.MusicCover
import com.nuvio.app.features.music.MusicMessage
import com.nuvio.app.features.music.MusicPlayback
import com.nuvio.app.features.music.defaultMusicCatalog
import com.nuvio.app.features.music.randomPlayableIndex
import kotlinx.coroutines.launch

// region Library tab

/** The artists in the library, as a grid. Opening one goes through [onArtistClick] with its library id. */
@Composable
fun MusicLibraryGrid(
    onArtistClick: (artistId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    LaunchedEffect(Unit) { MusicLibraryRepository.ensureLoaded() }
    val state by MusicLibraryRepository.state.collectAsStateWithLifecycle()
    var showSources by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = when (val n = state.artists.size) {
                    0 -> "No artists yet"
                    1 -> "1 artist"
                    else -> "$n artists"
                },
                style = MaterialTheme.typography.titleMedium,
                color = colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            LibraryIconAction(Icons.Rounded.Refresh, "Refresh all artists", enabled = state.artists.isNotEmpty()) {
                MusicLibraryRepository.refreshAll()
            }
            LibraryIconAction(Icons.Rounded.Settings, "Artwork sources") { showSources = true }
        }
        state.errorMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.warning, modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (state.artists.isEmpty() && state.working.isEmpty()) {
            MusicMessage("Open an artist from Search and choose Add to library. Every album, single and EP comes with it, with artwork and details.")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // An artist being added that has not appeared yet (the first network call is still out).
                val pending = state.working.filterKeys { id -> state.artists.none { it.id == id } }
                items(pending.entries.toList(), key = { "pending:${it.key}" }) { (_, progress) ->
                    LibraryTileFrame(onClick = null) {
                        Box(Modifier.size(112.dp).clip(CircleShape).background(colors.surfaceElevated))
                        Text("Adding…", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                        Text(progress.status, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 2, textAlign = TextAlign.Center)
                    }
                }
                items(state.artists, key = { it.id }) { artist ->
                    LibraryTileFrame(onClick = { onArtistClick(artist.id) }) {
                        MusicCover(artist.art.thumb, artist.name, size = 112.dp, circle = true)
                        Text(
                            artist.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val working = state.working[artist.id]
                        Text(
                            text = working?.status ?: "${artist.releases.size} release${if (artist.releases.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (working != null) colors.accent else colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    if (showSources) {
        ArtworkSourcesDialog(
            settings = state.settings,
            onSave = {
                MusicLibraryRepository.updateSettings(it)
                showSources = false
            },
            onDismiss = { showSources = false },
        )
    }
}

@Composable
private fun LibraryTileFrame(onClick: (() -> Unit)?, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
private fun LibraryIconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = if (enabled) colors.textSecondary else colors.textDisabled, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun ArtworkSourcesDialog(
    settings: MusicLibrarySettings,
    onSave: (MusicLibrarySettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    var fanart by remember { mutableStateOf(settings.fanartApiKey) }
    var audioDb by remember { mutableStateOf(settings.audioDbKey) }
    NuvioAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Artwork sources") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Details and covers come from YouTube Music, MusicBrainz, Deezer, TheAudioDB and the Cover Art Archive with no setup. " +
                        "Fanart.tv adds HD logos, banners, backgrounds and CD art, and needs a free personal key from fanart.tv (Account, Your API keys).",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
                Text("Fanart.tv API key", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                NuvioTextField(value = fanart, onValueChange = { fanart = it.trim() }, modifier = Modifier.fillMaxWidth(), placeholder = "Optional", secret = true)
                Text("TheAudioDB API key", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                NuvioTextField(
                    value = audioDb,
                    onValueChange = { audioDb = it.trim() },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "Optional (the shared test key is used when empty)",
                    secret = true,
                )
                Text(
                    "Keys apply the next time an artist is added or refreshed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(MusicLibrarySettings(fanart, audioDb)) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// endregion

// region Add button

/**
 * The Add to library control for an artist page. Shows the step it is on while the artist is being
 * filled in, and turns into a check when the artist is in the library.
 */
@Composable
fun MusicAddToLibraryButton(
    artistId: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    LaunchedEffect(Unit) { MusicLibraryRepository.ensureLoaded() }
    val state by MusicLibraryRepository.state.collectAsStateWithLifecycle()
    val inLibrary = state.contains(artistId)
    val working = state.working[artistId]
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(shape)
            .then(
                if (inLibrary || working != null) Modifier.border(1.dp, colors.accent, shape)
                else Modifier.background(colors.accent),
            )
            .clickable(enabled = !inLibrary && working == null) { MusicLibraryRepository.add(artistId) }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val tint = if (inLibrary || working != null) colors.accent else colors.onAccent
        Icon(
            imageVector = if (inLibrary) Icons.Rounded.Check else Icons.Rounded.Add,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = when {
                working != null -> "Adding…"
                inLibrary -> "In library"
                else -> "Add to library"
            },
            style = MaterialTheme.typography.titleMedium,
            color = tint,
        )
    }
}

// endregion

// region Artist page

/**
 * An artist in the library, as a two-pane page.
 *
 * Left rail: who they are (portrait, logo, one-line headline, tagline), the things to do (Play,
 * Shuffle), mood/style chips, a short bio and one Links button. Right pane: the newest release,
 * then every release as year "eras", with duplicate versions folded together and demos kept in an
 * archive. Under ~840dp wide the rail simply stacks above the pane.
 *
 * What [buildDiscography] does with the releases is in ArtistPageModel.kt.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MusicLibraryArtistScreen(
    artistId: String,
    onBack: () -> Unit,
    onAlbumClick: (browseId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    LaunchedEffect(Unit) { MusicLibraryRepository.ensureLoaded() }
    val state by MusicLibraryRepository.state.collectAsStateWithLifecycle()
    val artist = state.artist(artistId)
    var playableOnly by rememberSaveable { mutableStateOf(false) }
    var filterName by rememberSaveable { mutableStateOf(ReleaseFilter.All.name) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showLinks by remember { mutableStateOf(false) }
    var demosOpen by rememberSaveable { mutableStateOf(false) }
    var playNotice by remember { mutableStateOf<String?>(null) }
    val expandedSingles = remember { mutableStateMapOf<String, Boolean>() }
    val scope = rememberCoroutineScope()
    val catalog = remember { defaultMusicCatalog() }

    if (artist == null) {
        Column(modifier.fillMaxSize().background(colors.background).padding(top = 12.dp)) {
            Box(Modifier.padding(horizontal = 16.dp)) { NuvioBackButton(onClick = onBack) }
            MusicMessage("This artist is not in your library.")
        }
        return
    }

    val working = state.working[artist.id]
    val filter = ReleaseFilter.entries.firstOrNull { it.name == filterName } ?: ReleaseFilter.All
    val discography = remember(artist.releases, playableOnly, filter) {
        buildDiscography(artist.releases, playableOnly, filter)
    }
    val latest = remember(artist.releases) { latestRelease(artist.releases) }
    val uriHandler = LocalUriHandler.current

    /** Opens [release] on YouTube Music and starts it. The player takes over from there. */
    fun startPlayback(release: LibraryRelease?, shuffled: Boolean) {
        val id = release?.ytBrowseId
        if (release == null || id == null) {
            playNotice = "Nothing here is on YouTube Music yet."
            return
        }
        playNotice = null
        scope.launch {
            val album = runCatching { catalog.album(id) }.getOrNull()
            if (album == null) {
                playNotice = "Couldn't open ${release.title}."
            } else {
                MusicPlayback.player.play(
                    album,
                    trackIndex = if (shuffled) randomPlayableIndex(album) else 0,
                    shuffled = shuffled,
                )
            }
        }
    }

    val playTarget = latest?.takeIf { it.isPlayable }
    val shuffleTarget = artist.releases.filter { it.isPlayable && !it.isDemo() }.randomOrNull()

    BoxWithConstraints(modifier.fillMaxSize().background(colors.background)) {
        val wide = maxWidth >= 840.dp
        ArtistBackdrop(artist)

        val rail: @Composable () -> Unit = {
            ArtistRail(
                artist = artist,
                discography = discography,
                playTarget = playTarget,
                canShuffle = shuffleTarget != null,
                notice = playNotice,
                refreshing = working?.status,
                onBack = onBack,
                onPlay = { startPlayback(playTarget, shuffled = false) },
                onShuffle = { startPlayback(shuffleTarget, shuffled = true) },
                onAbout = { showAbout = true },
                onLinks = { showLinks = true },
                onRefresh = { MusicLibraryRepository.refresh(artist.id) },
                onRemove = { confirmRemove = true },
            )
        }
        val pane: LazyListScope.() -> Unit = {
            artistPane(
                artist = artist,
                latest = latest,
                discography = discography,
                filter = filter,
                playableOnly = playableOnly,
                demosOpen = demosOpen || filter == ReleaseFilter.Demos,
                expandedSingles = expandedSingles,
                working = working != null,
                onPlayLatest = { startPlayback(latest, shuffled = false) },
                onOpen = { selectedKey = it.key },
                onPlayableOnly = { playableOnly = it },
                onFilter = { filterName = it.name },
                onToggleDemos = { demosOpen = !demosOpen },
            )
        }

        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) { rail() }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(start = 12.dp, end = 24.dp, top = 24.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) { pane() }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item(key = "rail") { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { rail() } }
                pane()
            }
        }
    }

    selectedKey?.let { key ->
        artist.releases.firstOrNull { it.key == key }?.let { release ->
            ReleaseDialog(
                artist = artist,
                release = release,
                versions = artist.releases.filter { it.dedupeKey() == release.dedupeKey() },
                onPickVersion = { selectedKey = it },
                onOpen = release.ytBrowseId?.let { id ->
                    {
                        selectedKey = null
                        onAlbumClick(id)
                    }
                },
                onDismiss = { selectedKey = null },
            )
        }
    }

    if (showAbout) AboutDialog(artist) { showAbout = false }
    if (showLinks) {
        LinksDialog(artist.links, onOpen = { runCatching { uriHandler.openUri(it) } }) { showLinks = false }
    }

    if (confirmRemove) {
        NuvioAlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${artist.name}?") },
            text = { Text("The artist and its saved details leave your library. Nothing else changes.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    MusicLibraryRepository.remove(artist.id)
                    onBack()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Keep") } },
        )
    }
}

// region rail

/** The backdrop is only atmosphere now: it fades out well before the content starts. */
@Composable
private fun ArtistBackdrop(artist: LibraryArtist) {
    val colors = MaterialTheme.nuvio.colors
    val backdrop = artist.art.bestBackground ?: artist.art.banner ?: return
    Box(Modifier.fillMaxWidth().height(380.dp)) {
        NuvioAsyncImage(
            model = backdrop,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = 0.32f,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.0f to Color.Black.copy(alpha = 0.2f), 1.0f to colors.background),
            ),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArtistRail(
    artist: LibraryArtist,
    discography: ArtistDiscography,
    playTarget: LibraryRelease?,
    canShuffle: Boolean,
    notice: String?,
    refreshing: String?,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onAbout: () -> Unit,
    onLinks: () -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    NuvioBackButton(onClick = onBack)

    // The artist, cut out when a source has that; otherwise the photo, round.
    val clearArt = artist.art.bestClearArt
    val portrait = clearArt ?: artist.art.thumb
    if (portrait != null) {
        Box(Modifier.fillMaxWidth().height(if (clearArt != null) 230.dp else 150.dp), contentAlignment = Alignment.BottomStart) {
            NuvioAsyncImage(
                model = portrait,
                contentDescription = null,
                modifier = if (clearArt != null) Modifier.fillMaxSize() else Modifier.size(150.dp).clip(CircleShape),
                contentScale = if (clearArt != null) ContentScale.Fit else ContentScale.Crop,
                alignment = Alignment.BottomStart,
            )
        }
    }

    val logo = artist.art.bestLogo
    if (logo != null) {
        NuvioAsyncImage(
            model = logo,
            contentDescription = artist.name,
            modifier = Modifier.height(72.dp).fillMaxWidth(),
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
        )
    } else {
        Text(
            artist.name,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }

    val headline = artistHeadline(artist)
    if (headline.isNotEmpty()) {
        Text(headline, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
    }
    artist.disambiguation?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
    }

    val stats = listOfNotNull(
        artist.fans?.let { "${formatCount(it)} fans" },
        "${discography.totalCount} releases",
        "${discography.playableCount} playable".takeIf { discography.totalCount > 0 },
    ).joinToString("  ·  ")
    Text(stats, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        NuvioPrimaryButton(
            text = "Play",
            modifier = Modifier.width(128.dp),
            height = 44.dp,
            enabled = playTarget != null,
            onClick = onPlay,
        )
        OutlinedAction(Icons.Rounded.Shuffle, "Shuffle", enabled = canShuffle, onClick = onShuffle)
    }
    val playing = notice ?: playTarget?.let { "Plays ${it.title}${it.year?.let { y -> " ($y)" } ?: ""}" }
    if (playing != null) {
        Text(
            playing,
            style = MaterialTheme.typography.bodySmall,
            color = if (notice != null) colors.warning else colors.textMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }

    // Mood and style are the feel of the artist: shown as chips, not as label/value pairs.
    val feel = (listOfNotNull(artist.mood, artist.style) + artist.tags.take(4))
        .distinctBy { it.lowercase() }
        .filterNot { tag -> artist.genres.any { it.equals(tag, ignoreCase = true) } }
    if (feel.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            feel.forEach { InfoChip(it) }
        }
    }

    artist.biography?.let { bio ->
        Column(
            Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onAbout).padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(bio, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Text("About ${artist.name}", style = MaterialTheme.typography.bodySmall, color = colors.accent)
        }
    }

    val linkCount = groupLinks(artist.links).values.sumOf { it.size }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (linkCount > 0) ActionPill("Links · $linkCount", onClick = onLinks)
        if (artist.biography == null) ActionPill("Details", onClick = onAbout)
    }

    // Housekeeping lives at the bottom, quiet.
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onRefresh, enabled = refreshing == null) { Text(refreshing ?: "Refresh") }
        TextButton(onClick = onRemove) { Text("Remove from library") }
    }
    if (artist.sources.isNotEmpty()) {
        Text(
            "Metadata from ${artist.sources.joinToString(", ")}",
            style = MaterialTheme.typography.labelSmall,
            color = colors.textMuted,
        )
    }
}

@Composable
private fun InfoChip(text: String) {
    val colors = MaterialTheme.nuvio.colors
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = colors.textPrimary,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, colors.borderDefault, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

@Composable
private fun OutlinedAction(icon: ImageVector, text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(22.dp)
    val tint = if (enabled) colors.textPrimary else colors.textDisabled
    Row(
        modifier = Modifier
            .height(44.dp)
            .clip(shape)
            .border(1.dp, colors.borderDefault, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, color = tint)
    }
}

// endregion

// region pane

@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.artistPane(
    artist: LibraryArtist,
    latest: LibraryRelease?,
    discography: ArtistDiscography,
    filter: ReleaseFilter,
    playableOnly: Boolean,
    demosOpen: Boolean,
    expandedSingles: MutableMap<String, Boolean>,
    working: Boolean,
    onPlayLatest: () -> Unit,
    onOpen: (LibraryRelease) -> Unit,
    onPlayableOnly: (Boolean) -> Unit,
    onFilter: (ReleaseFilter) -> Unit,
    onToggleDemos: () -> Unit,
) {
    if (latest != null) {
        item(key = "latest") { LatestCard(latest, onPlay = onPlayLatest, onOpen = { onOpen(latest) }) }
    }

    item(key = "filters") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SegmentedPair(
                    left = "Playable ${discography.playableCount}",
                    right = "All ${discography.totalCount}",
                    rightSelected = !playableOnly,
                    onSelect = { all -> onPlayableOnly(!all) },
                )
                Spacer(Modifier.width(8.dp))
                ReleaseFilter.entries.forEach { f ->
                    if (f == ReleaseFilter.All) return@forEach
                    val n = discography.filterCounts[f] ?: return@forEach
                    // Only worth a chip when it narrows something. Tapping the chosen one clears it.
                    if (n == discography.totalCount && f != filter) return@forEach
                    ActionPill("${f.label} $n", selected = f == filter) {
                        onFilter(if (f == filter) ReleaseFilter.All else f)
                    }
                }
            }
            val anyDimmed = discography.eras.any { era -> (era.full + era.singles).any { !it.isPlayable } }
            if (anyDimmed) {
                Text(
                    "Dimmed releases aren't on YouTube Music.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.nuvio.colors.textMuted,
                )
            }
        }
    }

    if (discography.eras.isEmpty() && discography.demos.isEmpty()) {
        item(key = "empty") {
            MusicMessage(
                when {
                    working -> "Looking up releases…"
                    playableOnly -> "Nothing here is on YouTube Music. Switch to All to see every release."
                    else -> "No releases found."
                },
            )
        }
    }

    discography.eras.forEach { era ->
        item(key = "era:${era.year}") {
            EraBlock(
                era = era,
                singlesExpanded = expandedSingles[era.year] == true,
                onToggleSingles = { expandedSingles[era.year] = expandedSingles[era.year] != true },
                onOpen = onOpen,
            )
        }
    }

    if (discography.demos.isNotEmpty()) {
        item(key = "demos") {
            DemosArchive(discography.demos, open = demosOpen, onToggle = onToggleDemos, onOpen = onOpen)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EraBlock(
    era: Era,
    singlesExpanded: Boolean,
    onToggleSingles: () -> Unit,
    onOpen: (LibraryRelease) -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                era.year.ifEmpty { "Undated" },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
            )
            Text(
                "${era.count} release${if (era.count == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.borderDefault))
        if (era.full.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                era.full.forEach { group -> ReleaseCard(group, onClick = { onOpen(group.primary) }) }
            }
        }
        if (era.singles.isNotEmpty()) {
            val shown = if (singlesExpanded) era.singles else era.singles.take(SINGLES_COLLAPSED)
            Column {
                if (era.full.isNotEmpty()) {
                    Text("Singles", style = MaterialTheme.typography.labelLarge, color = colors.textSecondary, modifier = Modifier.padding(bottom = 4.dp))
                }
                shown.forEach { group -> ReleaseRow(group, onClick = { onOpen(group.primary) }) }
                if (era.singles.size > SINGLES_COLLAPSED) {
                    Text(
                        if (singlesExpanded) "Show fewer" else "Show ${era.singles.size - SINGLES_COLLAPSED} more",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.accent,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggleSingles).padding(horizontal = 8.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

private const val SINGLES_COLLAPSED = 4

@Composable
private fun DemosArchive(demos: List<ReleaseGroup>, open: Boolean, onToggle: () -> Unit, onOpen: (LibraryRelease) -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onToggle).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("Demos archive", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.textPrimary)
                Text("${demos.size} demo release${if (demos.size == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            }
            Text(if (open) "Hide" else "Show", style = MaterialTheme.typography.bodyMedium, color = colors.accent)
        }
        if (open) demos.forEach { group -> ReleaseRow(group, onClick = { onOpen(group.primary) }) }
    }
}

@Composable
private fun LatestCard(release: LibraryRelease, onPlay: () -> Unit, onOpen: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceCard)
            .clickable(onClick = onOpen)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReleaseCover(release, Modifier.size(120.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("LATEST RELEASE", style = MaterialTheme.typography.labelSmall, color = colors.accent)
            Text(
                release.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                releaseSubtitle(release),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
            Spacer(Modifier.height(6.dp))
            if (release.isPlayable) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedAction(Icons.Rounded.PlayArrow, "Play", onClick = onPlay)
                }
            } else {
                Text("Not on YouTube Music", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            }
        }
    }
}

private fun releaseSubtitle(release: LibraryRelease): String = listOfNotNull(
    release.secondaryTypes.firstOrNull()?.let { "${release.kind.label} · $it" } ?: release.kind.label,
    release.year,
    release.trackCount?.let { "$it track${if (it == 1) "" else "s"}" },
).joinToString(" · ")

/** A release as a card. Releases YouTube Music does not have are dimmed instead of stamped. */
@Composable
private fun ReleaseCard(group: ReleaseGroup, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val release = group.primary
    Column(
        modifier = Modifier
            .width(150.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .alpha(if (group.isPlayable) 1f else 0.5f),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            ReleaseCover(release, Modifier.fillMaxSize())
            if (group.extraVersions > 0) {
                Text(
                    "${group.versions.size} versions",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(release.title, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val sub = listOfNotNull(
            release.kind.label.takeIf { it != "Album" },
            release.trackCount?.let { "$it track${if (it == 1) "" else "s"}" },
            release.secondaryTypes.firstOrNull(),
        ).joinToString(" · ")
        if (sub.isNotEmpty()) {
            Text(sub, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A release as one line: singles, and everything in the demos archive. */
@Composable
private fun ReleaseRow(group: ReleaseGroup, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val release = group.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .alpha(if (group.isPlayable) 1f else 0.5f)
            .padding(horizontal = 4.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ReleaseCover(release, Modifier.size(44.dp))
        Column(Modifier.weight(1f)) {
            Text(release.title, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(
                (release.releaseDate ?: release.year),
                release.trackCount?.let { "$it track${if (it == 1) "" else "s"}" },
                "${group.versions.size} versions".takeIf { group.extraVersions > 0 },
            ).joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SegmentedPair(left: String, right: String, rightSelected: Boolean, onSelect: (rightSelected: Boolean) -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(20.dp)
    Row(Modifier.clip(shape).background(colors.surfaceCard)) {
        listOf(left to false, right to true).forEach { (text, isRight) ->
            val selected = isRight == rightSelected
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) colors.onAccent else colors.textPrimary,
                modifier = Modifier
                    .clip(shape)
                    .background(if (selected) colors.accent else Color.Transparent)
                    .clickable { onSelect(isRight) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

// endregion

// region dialogs

@Composable
private fun AboutDialog(artist: LibraryArtist, onDismiss: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val facts = listOfNotNull(
        artist.area?.let { "From" to it },
        artist.style?.let { "Style" to it },
        artist.mood?.let { "Mood" to it },
        artist.fans?.let { "Deezer fans" to formatCount(it) },
        artist.rating?.takeIf { it > 0.0 }?.let { "MusicBrainz rating" to "${(it * 10).toInt() / 10.0} / 5" },
        artist.tags.takeIf { it.isNotEmpty() }?.let { "Tags" to it.take(8).joinToString(", ") },
    )
    NuvioAlertDialog(
        onDismissRequest = onDismiss,
        maxWidth = 640.dp,
        title = { Text(artist.name) },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                artist.biography?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
                    artist.biographySource?.let { src -> Text("From $src", style = MaterialTheme.typography.bodySmall, color = colors.textMuted) }
                }
                facts.forEach { (label, value) ->
                    Row {
                        Text("$label  ", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                        Text(value, style = MaterialTheme.typography.bodySmall, color = colors.textPrimary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * Links, one per site and grouped. Each row shows its address too, so a person on a TV (where
 * nothing can open) can still read it off.
 */
@Composable
private fun LinksDialog(links: List<ArtistLink>, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val grouped = groupLinks(links)
    NuvioAlertDialog(
        onDismissRequest = onDismiss,
        maxWidth = 560.dp,
        title = { Text("Links") },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                grouped.forEach { (group, items) ->
                    Text(group.label, style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
                    items.forEach { link ->
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onOpen(link.url) }.padding(horizontal = 6.dp, vertical = 6.dp),
                        ) {
                            Text(link.label, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary)
                            Text(
                                link.url.removePrefix("https://").removePrefix("http://").removePrefix("www."),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

// endregion

// region shared bits

internal fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> "${n / 100_000 / 10.0}M"
    n >= 1_000 -> "${n / 100 / 10.0}K"
    else -> n.toString()
}

@Composable
private fun ActionPill(text: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(20.dp)
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = when {
            !enabled -> colors.textDisabled
            selected -> colors.onAccent
            else -> colors.textPrimary
        },
        modifier = Modifier
            .clip(shape)
            .background(if (selected) colors.accent else colors.surfaceCard)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** A release's cover, falling through its candidate URLs if one fails to load. */
@Composable
private fun ReleaseCover(release: LibraryRelease, modifier: Modifier) {
    val colors = MaterialTheme.nuvio.colors
    val covers = release.art.covers
    var index by remember(release.key, covers) { mutableStateOf(0) }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(colors.surfaceElevated), contentAlignment = Alignment.Center) {
        val url = covers.getOrNull(index)
        if (url != null) {
            NuvioAsyncImage(
                model = url,
                contentDescription = release.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = { if (index < covers.lastIndex) index++ },
            )
        }
    }
}


// endregion

// endregion

// region Release details

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReleaseDialog(
    artist: LibraryArtist,
    release: LibraryRelease,
    versions: List<LibraryRelease>,
    onPickVersion: (key: String) -> Unit,
    onOpen: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val minutes = release.durationSeconds?.let { it / 60 }
    val facts = listOfNotNull(
        "Type" to (release.secondaryTypes.takeIf { it.isNotEmpty() }?.let { "${release.kind.label} · ${it.joinToString(", ")}" } ?: release.kind.label),
        (release.releaseDate ?: release.year)?.let { "Released" to it },
        release.trackCount?.let { "Tracks" to it.toString() },
        minutes?.takeIf { it > 0 }?.let { "Length" to "$it min" },
        release.label?.let { "Label" to it },
        release.genres.takeIf { it.isNotEmpty() }?.let { "Genre" to it.joinToString(", ") },
        release.style?.let { "Style" to it },
        release.mood?.let { "Mood" to it },
        release.explicit?.takeIf { it }?.let { "Content" to "Explicit" },
        release.upc?.let { "UPC" to it },
    )
    NuvioAlertDialog(
        onDismissRequest = onDismiss,
        maxWidth = 640.dp,
        title = { Text(release.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    // The disc turns while the details are open: this is where the CD art is seen.
                    MusicCdSleeve(
                        coverUrl = release.art.cover,
                        cdArtUrl = release.art.cdArt,
                        spinning = true,
                        size = 150.dp,
                        peek = 0.45f,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(artist.name, style = MaterialTheme.typography.titleMedium, color = colors.textSecondary)
                        facts.forEach { (label, value) ->
                            Row {
                                Text("$label  ", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                                Text(value, style = MaterialTheme.typography.bodySmall, color = colors.textPrimary)
                            }
                        }
                    }
                }
                release.description?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 12, overflow = TextOverflow.Ellipsis)
                }
                // Listings of the same record that the page folded into one card.
                if (versions.size > 1) {
                    Text("Versions", style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        versions.forEach { v ->
                            val label = listOfNotNull(
                                v.trackCount?.let { "$it track${if (it == 1) "" else "s"}" },
                                if (v.isPlayable) "YouTube Music" else "not on YouTube Music",
                            ).joinToString(" · ")
                            ActionPill(label, selected = v.key == release.key) { onPickVersion(v.key) }
                        }
                    }
                }
                if (onOpen == null) {
                    Text(
                        "YouTube Music has no matching release, so it can't be played from here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
            }
        },
        confirmButton = {
            if (onOpen != null) TextButton(onClick = onOpen) { Text("Open album") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

// endregion

// region CD art lookup

/**
 * The printed-disc image for the album with YouTube Music id [albumBrowseId], when that album is a
 * release of an artist in the library and a source had its CD art. Null otherwise, and the spinner
 * then draws a disc with the cover as its face.
 */
@Composable
fun rememberCdArtFor(albumBrowseId: String?): String? {
    LaunchedEffect(Unit) { MusicLibraryRepository.ensureLoaded() }
    val state by MusicLibraryRepository.state.collectAsStateWithLifecycle()
    return remember(albumBrowseId, state.artists) {
        albumBrowseId?.let { id ->
            state.artists.firstNotNullOfOrNull { artist ->
                artist.releases.firstOrNull { it.ytBrowseId == id }?.art?.cdArt
            }
        }
    }
}

/**
 * What the library knows about the album with YouTube Music id `albumBrowseId`: the release (label,
 * genres, mood, description, CD art...) and the artist it belongs to.
 */
data class LibraryAlbumInfo(val artist: LibraryArtist, val release: LibraryRelease) {
    /** The artist's other releases YouTube Music can play, one per record, newest first. */
    val more: List<LibraryRelease>
        get() = artist.releases
            .filter { it.isPlayable && it.ytBrowseId != release.ytBrowseId && !it.isDemo() }
            .distinctBy { it.dedupeKey() }
            .sortedByDescending { it.releaseDate ?: it.year ?: "" }
            .take(10)
}

/** The [LibraryAlbumInfo] for an album, or null when it is not a release of an artist in the library. */
@Composable
fun rememberLibraryAlbumInfo(albumBrowseId: String?): LibraryAlbumInfo? {
    LaunchedEffect(Unit) { MusicLibraryRepository.ensureLoaded() }
    val state by MusicLibraryRepository.state.collectAsStateWithLifecycle()
    return remember(albumBrowseId, state.artists) {
        albumBrowseId?.let { id ->
            state.artists.firstNotNullOfOrNull { artist ->
                artist.releases.firstOrNull { it.ytBrowseId == id }?.let { LibraryAlbumInfo(artist, it) }
            }
        }
    }
}

// endregion
