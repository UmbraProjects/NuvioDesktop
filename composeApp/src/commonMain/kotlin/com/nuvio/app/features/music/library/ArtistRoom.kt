package com.nuvio.app.features.music.library

import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import com.nuvio.app.features.details.components.DetailSecondaryAction
import com.nuvio.app.features.details.components.DetailActionButtons
import androidx.compose.material3.Icon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Link
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.hoverable
import androidx.compose.material.icons.rounded.LocalFireDepartment
import com.nuvio.app.core.ui.NuvioGlass
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.LocalPlatformContext
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.music.LocalMusicBarInset
import com.nuvio.app.features.music.MusicMessage
import com.nuvio.app.features.music.identity.HeroSource
import com.nuvio.app.features.music.identity.ReleasePaletteResolver
import com.nuvio.app.features.music.identity.identity

/**
 * The artist room, laid out like the TV-show details page: the artist's backdrop fills the window
 * under a scrim from the left and a fade at the foot, and a lane down the left holds the logo (or
 * name), the facts line, the stats, the overview (tagline or biography), the genres and the details
 * page's own action row. Under the lane come the top albums, Where next and every release. Down the
 * right runs a slim glass timeline of the discography, newest first: a dot per release with its
 * title and the year under it; clicking one opens it. On a narrow window the timeline moves under
 * the lane.
 *
 * Every picture has a fallback, so a bare artist (no art, no dates) still draws a readable page. All
 * play / dialog / Add logic stays in [MusicArtistScreen]; this only draws and reports taps.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ArtistRoom(
    artist: LibraryArtist,
    discography: ArtistDiscography,
    latest: LibraryRelease?,
    playTarget: LibraryRelease?,
    canShuffle: Boolean,
    notice: String?,
    saved: Boolean,
    refreshing: String?,
    working: Boolean,
    filter: ReleaseFilter,
    playableOnly: Boolean,
    demosOpen: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onPlayTop: () -> Unit,
    topLabel: String?,
    onAbout: () -> Unit,
    onLinks: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
    onOpen: (LibraryRelease) -> Unit,
    onPlayableOnly: (Boolean) -> Unit,
    onFilter: (ReleaseFilter) -> Unit,
    onToggleDemos: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    val context = LocalPlatformContext.current
    // Built from every release, so the filter changes the release grid, never the timeline.
    val timeline = remember(artist.releases) { buildEraTimeline(artist.releases) }
    val dated = remember(timeline) {
        timeline.eras.flatMap { it.groups }
            .sortedWith(compareByDescending<ReleaseGroup> { releasePosition(it.primary) ?: 0.0 }.thenBy { it.primary.title })
    }
    val shown = if (filter == ReleaseFilter.Demos) emptyList() else dated.filter { it.passes(playableOnly, filter) }
    val undated = if (filter == ReleaseFilter.Demos) emptyList() else timeline.undated.filter { it.passes(playableOnly, filter) }
    // The newest covers get a palette (the disc label and a missing cover use it).
    val wantedKeys = remember(dated) { dated.take(TIMELINE_PALETTE_LIMIT).map { it.primary.key }.toSet() }
    val missing = artist.releases.count { it.key in wantedKeys && it.needsPalette }
    LaunchedEffect(artist.id, missing) { ReleasePaletteResolver.ensure(context, artist, keys = wantedKeys) }

    var showFilters by rememberSaveable { mutableStateOf(false) }

    BoxWithConstraints(modifier.background(colors.background)) {
        val wide = maxWidth >= ROOM_WIDE_FROM
        val inset = LocalMusicBarInset.current // fork:music - the backdrop runs under the bar; the content clears it
        RoomBackdrop(artist)
        Row(Modifier.fillMaxSize().padding(bottom = inset)) {
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                Box(Modifier.padding(start = 24.dp, top = 16.dp)) { NuvioBackButton(onClick = onBack) }
                RoomLane(
                    artist = artist,
                    discography = discography,
                    playTarget = playTarget,
                    canShuffle = canShuffle,
                    notice = notice,
                    saved = saved,
                    refreshing = refreshing,
                    filtersOpen = showFilters,
                    filtered = !playableOnly || filter != ReleaseFilter.All,
                    topLabel = topLabel,
                    onAbout = onAbout,
                    onPlay = onPlay,
                    onShuffle = onShuffle,
                    onPlayTop = onPlayTop,
                    onRemove = onRemove,
                    onLinks = onLinks,
                    onRefresh = onRefresh,
                    onOpenArtist = onOpenArtist,
                    onToggleFilters = { showFilters = !showFilters },
                )
                if (!wide && dated.isNotEmpty()) {
                    TimelinePanel(dated, onOpen, Modifier.padding(horizontal = ROOM_LANE_START).fillMaxWidth().height(360.dp))
                }
                // The top albums (the details page's cast row), then where to go next.
                ArtistWidgets(artist, onOpen, Modifier.padding(horizontal = ROOM_LANE_START), showBio = false) // fork:music - HTPC phase 8
                WhereNextStrip(artist, onOpenArtist, Modifier.padding(horizontal = ROOM_LANE_START)) // fork:music - HTPC phase 3

                Column(Modifier.padding(horizontal = ROOM_LANE_START), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Releases", fontSize = 28.sp, color = colors.textPrimary)
                        Text(
                            "${shown.size + undated.size} of ${discography.totalCount}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textMuted,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    if (showFilters) FilterPanel(discography, filter, playableOnly, onPlayableOnly, onFilter)
                    if (shown.isEmpty() && undated.isEmpty() && filter != ReleaseFilter.Demos) {
                        Text(
                            when {
                                working -> "Looking up releases…"
                                timeline.isEmpty -> "No releases found."
                                playableOnly -> "Nothing here is on YouTube Music. Filter → All shows it anyway."
                                else -> "Nothing matches the filter."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textMuted,
                        )
                    }
                    if (shown.isNotEmpty() || undated.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            (shown + undated).forEach { group -> ReleaseCard(group, onClick = { onOpen(group.primary) }) }
                        }
                    }
                    if (discography.demos.isNotEmpty()) {
                        DemosArchive(discography.demos, open = demosOpen, onToggle = onToggleDemos, onOpen = onOpen)
                    }
                }
            }
            if (wide && dated.isNotEmpty()) {
                TimelinePanel(
                    dated,
                    onOpen,
                    // As tall as its entries, up to the window's height; then it scrolls on its own.
                    Modifier.width(TIMELINE_PANEL_WIDTH).padding(top = 24.dp, end = 24.dp, bottom = 24.dp).wrapContentHeight(Alignment.Top),
                )
            }
        }
    }
}

/** From this width the timeline stands on the right; below it, it runs under the lane. */
private val ROOM_WIDE_FROM = 1100.dp

/** The details page's lane: 64dp in from the left, at most 620dp wide for its heading block. */
private val ROOM_LANE_START = 64.dp
private val ROOM_LANE_WIDTH = 620.dp

private val TIMELINE_PANEL_WIDTH = 320.dp

/** How many of the newest releases get a palette worked out as the page opens. */
private const val TIMELINE_PALETTE_LIMIT = 40

private fun ReleaseGroup.passes(playableOnly: Boolean, filter: ReleaseFilter): Boolean =
    (!playableOnly || isPlayable) && matches(filter)

// region backdrop and lane

/**
 * The backdrop as the details page draws it: the hero picture across the window, a scrim from the
 * left that is opaque under the lane and clear by the right edge, and a fade at the foot into the
 * page. With no picture at all it is the page's own night.
 */
@Composable
private fun RoomBackdrop(artist: LibraryArtist) {
    val colors = MaterialTheme.nuvio.colors
    val identity = remember(artist) { artist.identity() }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val fade by animateFloatAsState(if (shown) 1f else 0f, tween(600))
    val dark = colors.background
    Box(Modifier.fillMaxSize().background(NuvioGlass.night(dark, colors.accent))) {
        identity.hero?.let { hero ->
            val blurred = hero.source == HeroSource.BlurredCover
            NuvioAsyncImage(
                model = hero.url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().alpha(fade * if (blurred) 0.5f else 0.85f).let { if (blurred) it.blur(40.dp) else it },
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to dark, 0.34f to dark.copy(alpha = 0.9f), 0.6f to dark.copy(alpha = 0.42f), 0.82f to dark.copy(alpha = 0.12f), 1f to Color.Transparent,
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Transparent, 0.38f to Color.Transparent, 0.68f to dark.copy(alpha = 0.72f), 1f to dark),
            ),
        )
    }
}

/** The heading block, centred in the lane like the details page's: logo, facts, stats, overview, genres, actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoomLane(
    artist: LibraryArtist,
    discography: ArtistDiscography,
    playTarget: LibraryRelease?,
    canShuffle: Boolean,
    notice: String?,
    saved: Boolean,
    refreshing: String?,
    filtersOpen: Boolean,
    filtered: Boolean,
    topLabel: String?,
    onAbout: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onPlayTop: () -> Unit,
    onRemove: () -> Unit,
    onLinks: () -> Unit,
    onRefresh: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onToggleFilters: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    Column(
        Modifier.padding(start = ROOM_LANE_START, end = 24.dp).widthIn(max = ROOM_LANE_WIDTH).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val logo = artist.art.bestLogo
        if (logo != null) {
            NuvioAsyncImage(
                model = logo,
                contentDescription = artist.name,
                modifier = Modifier.fillMaxWidth().height(150.dp),
                contentScale = ContentScale.Fit,
                alignment = Alignment.Center,
            )
        } else {
            Text(
                artist.name,
                style = MaterialTheme.typography.displayMedium,
                fontFamily = FontFamily.Serif,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // The facts line, bulleted like the details page's metadata row.
        val facts = listOfNotNull(
            passportLine(artist).takeIf { it.isNotEmpty() },
            "${discography.totalCount} release${if (discography.totalCount == 1) "" else "s"}",
        ).joinToString("  •  ")
        Text(facts, style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary, textAlign = TextAlign.Center)

        HeroStats(artist, discography)

        // The overview: the tagline, else the opening of the biography. Click for the whole story.
        val overview = artistTagline(artist) ?: artist.biography?.trim()?.takeIf { it.isNotEmpty() }
        if (overview != null) {
            Text(
                overview,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textPrimary.copy(alpha = 0.9f),
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAbout).padding(4.dp),
            )
        }

        val (mood, genres) = passportChips(artist)
        if (mood != null || genres.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                mood?.let { InfoChip(it) }
                genres.forEach { InfoChip(it) }
            }
        }

        if (artist.channelMismatch) NamesakeNotice(artist, onOpenArtist)

        // The details page's own row: Play, then round buttons with their labels on hover.
        val inLibrary = MusicLibraryRepository.state.collectAsState().value.contains(artist.id)
        val linkCount = groupLinks(artist.links).values.sumOf { it.size }
        DetailActionButtons(
            modifier = Modifier.fillMaxWidth(),
            playLabel = "Play",
            isTablet = true,
            onPlayClick = { if (playTarget != null) onPlay() },
            secondaryActions = listOfNotNull(
                DetailSecondaryAction("Shuffle", Icons.Rounded.Shuffle, onClick = { if (canShuffle) onShuffle() }),
                topLabel?.let { DetailSecondaryAction("Play $it", Icons.Rounded.LocalFireDepartment, onClick = onPlayTop) },
                DetailSecondaryAction(
                    label = if (inLibrary) "Remove from library" else "Add to library",
                    icon = if (inLibrary) Icons.Rounded.Check else Icons.Rounded.Add,
                    isActive = inLibrary,
                    onClick = { if (inLibrary) onRemove() else MusicLibraryRepository.add(artist.id) },
                ),
                linkCount.takeIf { it > 0 }?.let { DetailSecondaryAction("Links ($it)", Icons.Rounded.Link, onClick = onLinks) },
                DetailSecondaryAction("Filter releases", Icons.Rounded.FilterList, isActive = filtersOpen || filtered, onClick = onToggleFilters),
                if (saved) DetailSecondaryAction(refreshing ?: "Refresh", Icons.Rounded.Refresh, onClick = { if (refreshing == null) onRefresh() }) else null,
            ),
        )
        val playing = notice ?: playTarget?.let { "Plays ${it.title}${it.year?.let { y -> " (${y})" } ?: ""}" }
            ?: refreshing
        if (playing != null) {
            Text(
                playing,
                style = MaterialTheme.typography.bodySmall,
                color = if (notice != null) colors.warning else colors.textMuted,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (artist.sources.isNotEmpty()) {
            Text("Metadata from ${artist.sources.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = colors.textMuted)
        }
    }
}

/** Fans, rating and playable as small glass tiles, centred under the facts line. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroStats(artist: LibraryArtist, discography: ArtistDiscography) {
    val colors = MaterialTheme.nuvio.colors
    val tiles = buildList {
        artist.fans?.let { add(formatCount(it) to "fans") }
        ratingText(artist.rating)?.let { add("★ $it" to "rating") }
        add("${discography.playableCount}" to "playable")
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tiles.forEach { (value, label) ->
            Row(
                Modifier.musicGlass(RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                Text(label, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, modifier = Modifier.padding(bottom = 1.dp))
            }
        }
    }
}

// endregion

// region timeline

/**
 * The discography as a slim vertical timeline in a glass panel: a spine with a dot per release (lit
 * when it plays here), the title beside it and the year under the title, newest first. Scrolls on
 * its own; clicking an entry opens it.
 */
@Composable
private fun TimelinePanel(releases: List<ReleaseGroup>, onOpen: (LibraryRelease) -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.nuvio.colors
    Column(
        modifier
            .background(NuvioGlass.night(colors.background, colors.accent).copy(alpha = 0.6f), RoundedCornerShape(18.dp))
            .musicGlass(RoundedCornerShape(18.dp))
            .padding(top = 16.dp, bottom = 8.dp),
    ) {
        Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Timeline", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
            Text("${releases.size}", style = MaterialTheme.typography.labelMedium, color = colors.textMuted, modifier = Modifier.padding(bottom = 2.dp))
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth().testTag("artistTimeline")) {
            itemsIndexed(releases, key = { _, group -> "t:${group.primary.key}" }) { index, group ->
                TimelineRow(group, first = index == 0, last = index == releases.lastIndex, onOpen = { onOpen(group.primary) })
            }
        }
    }
}

@Composable
private fun TimelineRow(group: ReleaseGroup, first: Boolean, last: Boolean, onOpen: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val release = group.primary
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(if (hovered || focused) MusicGlassRaised else Color.Transparent)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onOpen)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The spine's piece for this row, cut short at the first and last so it starts and ends at a dot.
        Box(Modifier.width(22.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxHeight().width(2.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth().background(if (first) Color.Transparent else NuvioGlass.EdgeColor))
                Box(Modifier.weight(1f).fillMaxWidth().background(if (last) Color.Transparent else NuvioGlass.EdgeColor))
            }
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (group.isPlayable) colors.accent else NuvioGlass.night(colors.background, colors.accent))
                    .border(1.5.dp, if (group.isPlayable) colors.accent else NuvioGlass.EdgeColor, CircleShape),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(vertical = 7.dp).alpha(if (group.isPlayable) 1f else 0.55f)) {
            Text(release.title, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val under = listOfNotNull(release.year ?: release.releaseDate?.take(4), release.kind.label.takeIf { it != "Album" }).joinToString(" · ")
            if (under.isNotEmpty()) Text(under, style = MaterialTheme.typography.labelSmall, color = colors.textMuted, maxLines = 1)
        }
    }
}

// endregion

/** The one filter system: playable-only, plus the kind chips. Shown on demand so the room stays clean. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterPanel(
    discography: ArtistDiscography,
    filter: ReleaseFilter,
    playableOnly: Boolean,
    onPlayableOnly: (Boolean) -> Unit,
    onFilter: (ReleaseFilter) -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.35f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SegmentedPair(
                left = "Playable ${discography.playableCount}",
                right = "All ${discography.totalCount}",
                rightSelected = !playableOnly,
                onSelect = { all -> onPlayableOnly(!all) },
            )
            ReleaseFilter.entries.forEach { f ->
                if (f == ReleaseFilter.All) return@forEach
                val n = discography.filterCounts[f] ?: return@forEach
                if (n == discography.totalCount && f != filter) return@forEach
                ActionPill("${f.label} $n", selected = f == filter) { onFilter(if (f == filter) ReleaseFilter.All else f) }
            }
        }
        Text("Dimmed releases aren't on YouTube Music.", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
    }
}

/**
 * Shown when the other sources turned out to describe a namesake, so this page carries only what YouTube
 * Music lists. Offers the channel MusicBrainz lists for the real one when it names one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NamesakeNotice(artist: LibraryArtist, onOpenArtist: (String) -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.4f)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("This may not be the ${artist.name} you mean", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
        Text(
            "The releases on this YouTube Music channel don't match the artist of that name on MusicBrainz, " +
                "so their photos and details are left out rather than mixed in.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
        val better = artist.suggestedChannelId
        if (better != null) {
            ActionPill("Open the likely match", onClick = { onOpenArtist(better) })
        } else {
            Text("Try another result in Search, or check the album titles.", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
        }
    }
}

