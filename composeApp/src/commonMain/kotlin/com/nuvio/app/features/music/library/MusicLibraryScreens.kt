package com.nuvio.app.features.music.library

import com.nuvio.app.core.ui.collectIsKeyboardFocusedAsState
import com.nuvio.app.features.music.MusicAlbum
import com.nuvio.app.features.music.nebula.AddonAlbumRef
import com.nuvio.app.features.music.nebula.NebulaMusicSource
import com.nuvio.app.features.music.nebula.addonAlbumId
import com.nuvio.app.core.ui.secondaryClick
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
                        val releaseCount = remember(artist.releases) { countReleaseCards(artist.releases) }
                        Text(
                            text = working?.status ?: "$releaseCount release${if (releaseCount == 1) "" else "s"}",
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
}

@Composable
private fun LibraryTileFrame(onClick: (() -> Unit)?, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.musicKeyFocusRing(RoundedCornerShape(12.dp)).clickable(onClick = onClick) else Modifier)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
internal fun LibraryIconAction(
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
            .musicKeyFocusRing(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = if (enabled) colors.textSecondary else colors.textDisabled, modifier = Modifier.size(22.dp))
    }
}

// endregion

// region Add button

/**
 * The Add / Saved control of an artist page, the one difference between the two doors to it.
 * Add saves the artist (a previewed one is kept as it is, not fetched again); Saved is its
 * filled-in state and, when [onSavedClick] is given, takes it back out. While a saved artist is
 * being filled in it shows the step it is on; a merely previewed artist's background look-up
 * is not "adding", so the button stays a plain Add.
 */
@Composable
fun MusicAddToLibraryButton(
    artistId: String,
    modifier: Modifier = Modifier,
    onSavedClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.nuvio.colors
    LaunchedEffect(Unit) { MusicLibraryRepository.ensureLoaded() }
    val state by MusicLibraryRepository.state.collectAsStateWithLifecycle()
    val inLibrary = state.contains(artistId)
    val adding = inLibrary && state.working[artistId] != null
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(shape)
            .then(
                if (inLibrary) Modifier.border(1.dp, colors.accent, shape)
                else Modifier.background(colors.accent),
            )
            .musicKeyFocusRing(shape).clickable(enabled = !inLibrary || (onSavedClick != null && !adding)) {
                if (inLibrary) onSavedClick?.invoke() else MusicLibraryRepository.add(artistId)
            }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val tint = if (inLibrary) colors.accent else colors.onAccent
        Icon(
            imageVector = if (inLibrary) Icons.Rounded.Check else Icons.Rounded.Add,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = when {
                adding -> "Adding…"
                inLibrary -> "Saved"
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
 * An artist, as a two-pane page. The one artist page of the Music tab: Search results and the
 * Library both open it, and it looks the same from either door. The only difference is the
 * Add / Saved control: a saved artist reads from the library, an unsaved one is looked up in the
 * background (see [MusicLibraryRepository.openPreview]) and saved only when the person taps Add.
 *
 * Left rail: who they are (portrait, logo, one-line headline, tagline), the things to do (Play,
 * Shuffle, Add / Saved), mood/style chips, a short bio and one Links button. Right pane: the newest
 * release, then every release as year "eras", with duplicate versions folded together and demos
 * kept in an archive. Under ~840dp wide the rail simply stacks above the pane.
 *
 * [artistId] is the artist's YouTube Music channel id, which is also its library id.
 * What [buildDiscography] does with the releases is in ArtistPageModel.kt.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MusicArtistScreen(
    artistId: String,
    onBack: () -> Unit,
    onAlbumClick: (browseId: String) -> Unit,
    modifier: Modifier = Modifier,
    /** Opens another artist's page; the namesake notice uses it to jump to the channel MusicBrainz lists. */
    onArtistClick: (browseId: String) -> Unit = {},
) {
    val colors = MaterialTheme.nuvio.colors
    var attempt by remember { mutableStateOf(0) }
    // Looks the artist up unless it is saved or already previewed; a Try again restarts it.
    LaunchedEffect(artistId, attempt) {
        MusicLibraryRepository.ensureLoaded()
        MusicLibraryRepository.openPreview(artistId)
    }
    val state by MusicLibraryRepository.state.collectAsStateWithLifecycle()
    val artist = state.artistOrPreview(artistId)
    val saved = state.contains(artistId)
    // Playable albums first: what is not on YouTube Music stays one tap away under Filter → All.
    var playableOnly by rememberSaveable { mutableStateOf(true) }
    var filterName by rememberSaveable { mutableStateOf(ReleaseFilter.All.name) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showLinks by remember { mutableStateOf(false) }
    var demosOpen by rememberSaveable { mutableStateOf(false) }
    var playNotice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val catalog = remember { defaultMusicCatalog() }

    if (artist == null) {
        Column(modifier.fillMaxSize().background(colors.background).padding(top = 12.dp)) {
            Box(Modifier.padding(horizontal = 16.dp)) { NuvioBackButton(onClick = onBack) }
            if (artistId in state.previewFailed) {
                MusicMessage(
                    text = "Couldn't load this artist.",
                    actionLabel = "Try again",
                    onAction = { attempt++ },
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accent)
                }
            }
        }
        return
    }

    val working = state.working[artist.id]
    val filter = ReleaseFilter.entries.firstOrNull { it.name == filterName } ?: ReleaseFilter.All
    // fork:nebula - releases YouTube Music lacks that the music addon has: not dimmed, and kept by Playable. Empty without an addon link.
    val addon = com.nuvio.app.features.music.nebula.rememberAddonReleases(artist)
    val addonKeys = addon.keys
    val discography = remember(artist.releases, playableOnly, filter, addonKeys) {
        buildDiscography(artist.releases, playableOnly, filter, addonKeys)
    }
    val latest = remember(artist.releases) { latestRelease(artist.releases) }
    val uriHandler = LocalUriHandler.current

    /**
     * fork:nebula - the music addon's copy of [release], when YouTube Music has none and the addon is known to have the record.
     * The addon was asked about one listing of a record, so any listing of it stands for the same album.
     */
    fun addonRefOf(release: LibraryRelease?): AddonAlbumRef? {
        if (release == null || release.ytBrowseId != null) return null
        return artist.releases.firstNotNullOfOrNull { r ->
            if (r.ytBrowseId != null || r.dedupeKey() != release.dedupeKey()) null
            else addon.albumIdOf(r.key)?.let { AddonAlbumRef(it, artist.id, r.key) }
        }
    }

    /** The id the album page opens for [release]: its YouTube Music id, else the music addon's. Null when neither has it. */
    fun albumPageIdOf(release: LibraryRelease?): String? =
        release?.ytBrowseId ?: addonRefOf(release)?.let { addonAlbumId(it) }

    /** The album to play or queue for [release]: from YouTube Music by its id, else from the music addon. Null when it cannot be opened. */
    suspend fun albumOf(release: LibraryRelease): MusicAlbum? {
        val id = release.ytBrowseId
        if (id != null) return runCatching { catalog.album(id) }.getOrNull()
        val ref = addonRefOf(release) ?: return null
        return runCatching { NebulaMusicSource.openAddonAlbum(ref) }.getOrNull()
    }

    /** Opens [release] (on YouTube Music, else on the music addon) and starts it. The player takes over from there. */
    fun startPlayback(release: LibraryRelease?, shuffled: Boolean) {
        if (release == null || albumPageIdOf(release) == null) {
            playNotice = "Nothing here is on YouTube Music yet."
            return
        }
        playNotice = null
        scope.launch {
            val album = albumOf(release)
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

    val top = remember(artist) { selectTopAlbums(artist) }
    val topLabel = when {
        top.releases.isEmpty() -> null
        top.rankedCount > 0 -> "Top ${top.releases.size}"
        else -> "Latest ${top.releases.size}"
    }

    val playTarget = latest?.takeIf { it.isPlayable }
    val shuffleTarget = artist.releases.filter { (it.isPlayable || addon.albumIdOf(it.key) != null) && !it.isDemo() }.randomOrNull()

    ArtistRoom(
        artist = artist,
        discography = discography,
        latest = latest,
        playTarget = playTarget,
        canShuffle = shuffleTarget != null,
        notice = playNotice,
        saved = saved,
        refreshing = working?.status,
        working = working != null,
        filter = filter,
        playableOnly = playableOnly,
        demosOpen = demosOpen || filter == ReleaseFilter.Demos,
        onBack = onBack,
        onPlay = { startPlayback(playTarget, shuffled = false) },
        onShuffle = { startPlayback(shuffleTarget, shuffled = true) },
        onPlayTop = {
            playNotice = null
            scope.launch {
                if (!playTopAlbums(catalog, top.releases, shuffled = false)) playNotice = "Nothing here is on YouTube Music yet."
            }
        },
        topLabel = topLabel,
        onAbout = { showAbout = true },
        onLinks = { showLinks = true },
        onOpenArtist = onArtistClick,
        onRefresh = { MusicLibraryRepository.refresh(artist.id) },
        onRemove = { confirmRemove = true },
        onOpen = { release ->
            val id = release.ytBrowseId
            val addonId = albumPageIdOf(release)
            when {
                id == null && addonId != null -> onAlbumClick(addonId)
                id == null -> selectedKey = release.key
                release.playsOnClick() -> startPlayback(release, shuffled = false)
                else -> onAlbumClick(id)
            }
        },
        onInfo = { selectedKey = it.key },
        onPlayableOnly = { playableOnly = it },
        onFilter = { filterName = it.name },
        onToggleDemos = { demosOpen = !demosOpen },
        addonKeys = addonKeys,
        modifier = modifier.fillMaxSize(),
    )

    selectedKey?.let { key ->
        artist.releases.firstOrNull { it.key == key }?.let { release ->
            ReleaseDialog(
                artist = artist,
                release = release,
                versions = artist.releases.filter { it.dedupeKey() == release.dedupeKey() },
                addonKeys = addonKeys,
                onPickVersion = { selectedKey = it },
                onOpen = albumPageIdOf(release)?.let { id ->
                    {
                        selectedKey = null
                        onAlbumClick(id)
                    }
                },
                onPlay = albumPageIdOf(release)?.let {
                    {
                        selectedKey = null
                        startPlayback(release, shuffled = false)
                    }
                },
                onQueue = albumPageIdOf(release)?.let {
                    {
                        selectedKey = null
                        scope.launch {
                            val album = albumOf(release)
                            playNotice = when {
                                album == null -> "Couldn't open ${release.title}."
                                MusicPlayback.player.enqueue(album) -> "${release.title} is up next."
                                else -> null
                            }
                        }
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
                    // The page stays: the artist turns back into an unsaved one, with Add.
                    MusicLibraryRepository.remove(artist.id)
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Keep") } },
        )
    }
}

// region rail



@Composable
internal fun InfoChip(text: String) {
    val colors = MaterialTheme.nuvio.colors
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = colors.textPrimary,
        modifier = Modifier
            .musicGlass(RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

@Composable
internal fun OutlinedAction(icon: ImageVector, text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(22.dp)
    val tint = if (enabled) colors.textPrimary else colors.textDisabled
    Row(
        modifier = Modifier
            .height(44.dp)
            .musicGlass(shape)
            .musicKeyFocusRing(shape).clickable(enabled = enabled, onClick = onClick)
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




@Composable
internal fun DemosArchive(demos: List<ReleaseGroup>, open: Boolean, onToggle: () -> Unit, onOpen: (LibraryRelease) -> Unit, onInfo: (LibraryRelease) -> Unit = {}) {
    val colors = MaterialTheme.nuvio.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).musicKeyFocusRing(RoundedCornerShape(10.dp)).clickable(onClick = onToggle).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("Demos archive", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.textPrimary)
                Text("${demos.size} demo release${if (demos.size == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            }
            Text(if (open) "Hide" else "Show", style = MaterialTheme.typography.bodyMedium, color = colors.accent)
        }
        if (open) demos.forEach { group -> ReleaseRow(group, onClick = { onOpen(group.primary) }, onSecondary = { onInfo(group.primary) }) }
    }
}



/** A single (or any one-track release) plays on a click; anything longer opens its album page. */
internal fun LibraryRelease.playsOnClick(): Boolean = kind == ReleaseKind.Single || trackCount == 1

/**
 * A release as a card. Releases YouTube Music does not have are dimmed instead of stamped, unless [addon]: the music
 * addon has it, so it is drawn in full and says so.
 */
@Composable
internal fun ReleaseCard(group: ReleaseGroup, onClick: () -> Unit, onSecondary: (() -> Unit)? = null, addon: Boolean = false) {
    val colors = MaterialTheme.nuvio.colors
    val release = group.primary
    Column(
        modifier = Modifier
            .width(150.dp)
            .clip(RoundedCornerShape(10.dp))
            .secondaryClick(onSecondary)
            .musicKeyFocusRing(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .alpha(if (group.isPlayable || addon) 1f else 0.5f),
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
            release.extraTypes().firstOrNull(),
        ).joinToString(" · ")
        if (sub.isNotEmpty()) {
            Text(sub, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (addon) {
            // fork:nebula - the same wording as the badge on a YouTube Music album row.
            Text("Lossless on your addon", style = MaterialTheme.typography.labelSmall, color = colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A release as one line: singles, and everything in the demos archive. */
@Composable
internal fun ReleaseRow(group: ReleaseGroup, onClick: () -> Unit, onSecondary: (() -> Unit)? = null) {
    val colors = MaterialTheme.nuvio.colors
    val release = group.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .secondaryClick(onSecondary)
            .musicKeyFocusRing(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
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
internal fun SegmentedPair(left: String, right: String, rightSelected: Boolean, onSelect: (rightSelected: Boolean) -> Unit) {
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
                    .musicKeyFocusRing(shape).clickable { onSelect(isRight) }
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
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).musicKeyFocusRing(RoundedCornerShape(8.dp)).clickable { onOpen(link.url) }.padding(horizontal = 6.dp, vertical = 6.dp),
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
internal fun ActionPill(text: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(20.dp)
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val keyFocused by source.collectIsKeyboardFocusedAsState()
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = when {
            !enabled -> colors.textDisabled
            selected -> colors.onAccent
            else -> colors.textPrimary
        },
        modifier = Modifier
            .musicGlass(shape, raised = hovered && enabled, selected = selected, keyFocused = keyFocused)
            .hoverable(source)
            .clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** A release's cover, falling through its candidate URLs if one fails to load. */
@Composable
internal fun ReleaseCover(release: LibraryRelease, modifier: Modifier) {
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
        } else {
            // No cover at all: a generated one, the title on the release's palette (else the app's surface).
            val wash = release.palette?.let { Color(it.tint) } ?: colors.surfaceElevated
            Box(Modifier.fillMaxSize().background(wash).padding(8.dp), contentAlignment = Alignment.BottomStart) {
                Text(
                    release.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                    color = colors.textPrimary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
    /** Keys of the releases the music addon has that YouTube Music lacks. */
    addonKeys: Set<String> = emptySet(),
    onPickVersion: (key: String) -> Unit,
    onOpen: (() -> Unit)?,
    onPlay: (() -> Unit)?,
    onQueue: (() -> Unit)?,
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
                                when {
                                    v.isPlayable -> "YouTube Music"
                                    v.key in addonKeys -> "your music addon"
                                    else -> "not on YouTube Music"
                                },
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
                } else if (release.ytBrowseId == null) {
                    // fork:nebula - a release YouTube Music lacks that the music addon has.
                    Text(
                        "Not on YouTube Music. It plays from your music addon, lossless.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onQueue != null) TextButton(onClick = onQueue) { Text("Add to queue") }
                if (onPlay != null) TextButton(onClick = onPlay) { Text("Play") }
                // A single is its one song; only a longer release has a page worth opening.
                if (onOpen != null && !release.playsOnClick()) TextButton(onClick = onOpen) { Text("Open album") }
            }
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
    return remember(albumBrowseId, state.artists, state.preview) {
        albumBrowseId?.let { id ->
            state.artistOfAlbum(id)?.releaseOfAlbum(id)?.art?.cdArt
        }
    }
}

/**
 * What the library knows about the album with YouTube Music id `albumBrowseId`: the release (label,
 * genres, mood, description, CD art...) and the artist it belongs to. [artist] may be an unsaved,
 * previewed artist: an album opened from Search shows the same details once its artist is looked up.
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

/** The [LibraryAlbumInfo] for an album, or null when it is not a release of a saved or previewed artist. */
@Composable
fun rememberLibraryAlbumInfo(albumBrowseId: String?): LibraryAlbumInfo? {
    LaunchedEffect(Unit) { MusicLibraryRepository.ensureLoaded() }
    val state by MusicLibraryRepository.state.collectAsStateWithLifecycle()
    return remember(albumBrowseId, state.artists, state.preview) {
        albumBrowseId?.let { id ->
            state.artistOfAlbum(id)?.let { artist ->
                artist.releaseOfAlbum(id)?.let { LibraryAlbumInfo(artist, it) }
            }
        }
    }
}

// endregion
