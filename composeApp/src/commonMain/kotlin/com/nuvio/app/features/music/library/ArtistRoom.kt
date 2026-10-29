package com.nuvio.app.features.music.library

import com.nuvio.app.core.ui.NuvioPosterHoverTooltip
import com.nuvio.app.features.details.components.DetailIconAction
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Groups
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
 * The artist room, laid out like the TV-show details page. One scroll, top to bottom:
 *
 *  - The hero: the artist's backdrop, bright at the top and fading into the page, under the details
 *    page's scrim from the left. Over it, a lane holds the logo (or name), the facts line, the stats as
 *    icons, the genres as text and the details page's own action row; then the top albums and Where
 *    next, still over the fading picture. The backdrop scrolls away with them.
 *  - Below, on the page's own dark ground: every release as a grid, and beside it down the right a
 *    thin timeline of the discography, newest first: a dot per release on a spine, its title and year
 *    beside it, alternating sides. Clicking an entry opens it. On a narrow window the timeline sits
 *    above the grid.
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
    // The filter panel opens above the release grid, well down the page; opening it brings that into view.
    val releasesInView = remember { BringIntoViewRequester() }
    LaunchedEffect(showFilters) { if (showFilters) releasesInView.bringIntoView() }

    BoxWithConstraints(modifier.background(colors.background)) {
        val wide = maxWidth >= ROOM_WIDE_FROM
        val backdropHeight = maxHeight.coerceAtLeast(640.dp)
        val inset = LocalMusicBarInset.current // fork:music - the page runs under the bar; its foot clears it
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = inset + 48.dp)) {
            // The hero and what follows it on the picture; the backdrop scrolls away with them.
            Box(Modifier.fillMaxWidth()) {
                RoomBackdrop(artist, Modifier.fillMaxWidth().height(backdropHeight))
                Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
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
                        onPlay = onPlay,
                        onShuffle = onShuffle,
                        onPlayTop = onPlayTop,
                        onRemove = onRemove,
                        onLinks = onLinks,
                        onRefresh = onRefresh,
                        onOpenArtist = onOpenArtist,
                        onToggleFilters = { showFilters = !showFilters },
                    )
                    ArtistWidgets(artist, onOpen, Modifier.padding(horizontal = ROOM_LANE_START), showBio = false) // fork:music - HTPC phase 8
                    SimilarTasteStrip(artist, onOpenArtist, Modifier.padding(horizontal = ROOM_LANE_START)) // fork:music
                }
            }

            if (!wide && dated.isNotEmpty()) {
                ReleaseTimeline(dated, onOpen, Modifier.padding(top = 24.dp).fillMaxWidth())
            }
            Row(Modifier.fillMaxWidth().padding(top = 28.dp)) {
                Column(
                    // The whole section, so opening the filter scrolls its top (header and panel) to the top of the window.
                    Modifier.weight(1f).bringIntoViewRequester(releasesInView).padding(start = ROOM_LANE_START, end = if (wide) 16.dp else ROOM_LANE_START),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        Modifier.testTag("artistReleases"),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
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
                    // Split by kind, albums first, so a long discography is browsable: the singles no longer bury them.
                    val all = shown + undated
                    RELEASE_SECTIONS.forEach { (title, kinds) ->
                        val section = all.filter { it.kind in kinds }
                        if (section.isEmpty()) return@forEach
                        Row(
                            Modifier.padding(top = 10.dp),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(title, style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
                            Text("${section.size}", style = MaterialTheme.typography.bodyMedium, color = colors.textMuted, modifier = Modifier.padding(bottom = 2.dp))
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            section.forEach { group -> ReleaseCard(group, onClick = { onOpen(group.primary) }) }
                        }
                    }
                    if (discography.demos.isNotEmpty()) {
                        DemosArchive(discography.demos, open = demosOpen, onToggle = onToggleDemos, onOpen = onOpen)
                    }
                }
                if (wide && dated.isNotEmpty()) {
                    ReleaseTimeline(dated, onOpen, Modifier.width(TIMELINE_WIDTH).padding(end = ROOM_LANE_START))
                }
            }
        }
    }
}

/** The release grid's sections, in order, and the kinds each holds. */
private val RELEASE_SECTIONS = listOf(
    "Albums" to setOf(ReleaseKind.Album),
    "Singles & EPs" to setOf(ReleaseKind.Single, ReleaseKind.EP),
    "Live & Compilations" to setOf(ReleaseKind.Live, ReleaseKind.Compilation),
    "Other" to setOf(ReleaseKind.Other),
)

/** From this width the timeline stands beside the releases; below it, it runs above them. */
private val ROOM_WIDE_FROM = 1100.dp

/** The details page's lane: 64dp in from the left, at most 620dp wide for its heading block. */
private val ROOM_LANE_START = 64.dp
private val ROOM_LANE_WIDTH = 620.dp

private val TIMELINE_WIDTH = 340.dp

/** How many of the newest releases get a palette worked out as the page opens. */
private const val TIMELINE_PALETTE_LIMIT = 40

private fun ReleaseGroup.passes(playableOnly: Boolean, filter: ReleaseFilter): Boolean =
    (!playableOnly || isPlayable) && matches(filter)

// region backdrop and lane

/**
 * The backdrop as the details page draws it: the hero picture at full strength, the details page's
 * scrim from the left (opaque under the lane, clear by the right edge) and a fade at the foot into the
 * page's ground. With no picture at all it is a dark wash of the page's own colour.
 */
@Composable
private fun RoomBackdrop(artist: LibraryArtist, modifier: Modifier) {
    val colors = MaterialTheme.nuvio.colors
    val identity = remember(artist) { artist.identity() }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val fade by animateFloatAsState(if (shown) 1f else 0f, tween(600))
    val dark = colors.background
    Box(modifier.background(Brush.verticalGradient(0f to NuvioGlass.night(dark, colors.accent), 1f to dark))) {
        identity.hero?.let { hero ->
            val blurred = hero.source == HeroSource.BlurredCover
            NuvioAsyncImage(
                model = hero.url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().alpha(fade * if (blurred) 0.55f else 1f).let { if (blurred) it.blur(40.dp) else it },
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
                Brush.verticalGradient(0f to Color.Transparent, 0.42f to Color.Transparent, 0.72f to dark.copy(alpha = 0.72f), 1f to dark),
            ),
        )
    }
}

/** The heading block, centred in the lane like the details page's: logo, facts, stat icons, genres, actions. */
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
        ).joinToString("  •  ") { it.titleCased() }
        Text(facts, style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary, textAlign = TextAlign.Center)

        HeroStats(artist, discography)

        val (mood, genres) = passportChips(artist)
        val genreLine = (listOfNotNull(mood) + genres).joinToString("  ·  ") { it.titleCased() }
        if (genreLine.isNotEmpty()) {
            Text(genreLine, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, textAlign = TextAlign.Center)
        }

        if (artist.channelMismatch) NamesakeNotice(artist, onOpenArtist)

        // The details page's own row: Play, then round buttons with their labels on hover.
        val inLibrary = MusicLibraryRepository.state.collectAsState().value.contains(artist.id)
        val linkCount = groupLinks(artist.links).values.sumOf { it.size }
        val actions = listOfNotNull(
                DetailSecondaryAction("Play", Icons.Rounded.PlayArrow, onClick = { if (playTarget != null) onPlay() }),
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
        )
        // The details page's round buttons, Play among them, each with its label on hover.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
            actions.forEach { action ->
                NuvioPosterHoverTooltip(title = action.label) {
                    DetailIconAction(label = action.label, icon = action.icon, active = action.isActive, size = 56.dp, onClick = action.onClick)
                }
            }
        }
        // Only what needs saying: a play that failed, or a refresh under way.
        (notice ?: refreshing)?.let { line ->
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = if (notice != null) colors.warning else colors.textMuted,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "alternative rock" as "Alternative Rock"; a word already in capitals ("EDM", "K-Pop") is left as it is. */
private fun String.titleCased(): String =
    split(' ').joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }

/** Fans, rating and playable releases as icons with their numbers, centred under the facts line. */
@Composable
private fun HeroStats(artist: LibraryArtist, discography: ArtistDiscography) {
    val colors = MaterialTheme.nuvio.colors
    val stats = buildList {
        artist.fans?.let { add(Triple(Icons.Rounded.Groups, formatCount(it), "fans")) }
        ratingText(artist.rating)?.let { add(Triple(Icons.Rounded.Star, it, "rating")) }
        add(Triple(Icons.Rounded.PlayCircle, "${discography.playableCount}", "playable"))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
        stats.forEach { (icon, value, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(icon, contentDescription = label, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
            }
        }
    }
}

// endregion

// region timeline

private val TIMELINE_ROW = 44.dp

/**
 * The discography as a thin vertical timeline: a glass spine with a dot per release (filled in the
 * accent when it plays here, hollow when not), its title and year beside the dot, alternating sides
 * so neighbours never collide. No panel behind it: it sits straight on the page.
 */
@Composable
private fun ReleaseTimeline(releases: List<ReleaseGroup>, onOpen: (LibraryRelease) -> Unit, modifier: Modifier) {
    Column(modifier.testTag("artistTimeline")) {
        releases.forEachIndexed { index, group ->
            TimelineRow(group, leftSide = index % 2 == 0, first = index == 0, last = index == releases.lastIndex, onOpen = { onOpen(group.primary) })
        }
    }
}

@Composable
private fun TimelineRow(group: ReleaseGroup, leftSide: Boolean, first: Boolean, last: Boolean, onOpen: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val lit = hovered || focused
    val label: @Composable (Alignment.Horizontal) -> Unit = { align ->
        val release = group.primary
        Column(
            Modifier.alpha(if (group.isPlayable) 1f else 0.55f).padding(horizontal = 12.dp),
            horizontalAlignment = align,
        ) {
            Text(
                release.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (lit) colors.accent else colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (align == Alignment.End) TextAlign.End else TextAlign.Start,
            )
            val under = listOfNotNull(release.year ?: release.releaseDate?.take(4), release.kind.label.takeIf { it != "Album" }).joinToString(" · ")
            if (under.isNotEmpty()) Text(under, style = MaterialTheme.typography.labelSmall, color = colors.textMuted, maxLines = 1)
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(TIMELINE_ROW)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { if (leftSide) label(Alignment.End) }
        // The spine's piece for this row, cut short at the first and last so it starts and ends at a dot.
        Box(Modifier.width(16.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxHeight().width(2.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth().background(if (first) Color.Transparent else NuvioGlass.EdgeColor))
                Box(Modifier.weight(1f).fillMaxWidth().background(if (last) Color.Transparent else NuvioGlass.EdgeColor))
            }
            Box(
                Modifier
                    .size(if (lit) 12.dp else 9.dp)
                    .clip(CircleShape)
                    .background(if (group.isPlayable) colors.accent else colors.background)
                    .border(1.5.dp, if (group.isPlayable) colors.accent else NuvioGlass.EdgeColor, CircleShape),
            )
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { if (!leftSide) label(Alignment.Start) }
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

