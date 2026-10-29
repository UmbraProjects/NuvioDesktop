package com.nuvio.app.features.music.library

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
 * The artist room. One page for both doors (Search and Library), one scroll from top to bottom:
 *
 *  - A hero over the artist's backdrop: logo (or name), tagline, passport, genres, the stat tiles and
 *    the actions (play, shuffle, their hits, save, links, Where next, filter, refresh), with the cut-out
 *    standing at the right. It fades into the page at its foot.
 *  - The discography as a vertical glass timeline, newest first: a spine down the middle with one glass
 *    card per release (cover, title, the year under it), alternating sides; on a narrow window the spine
 *    runs down the left with every card on its right. The filter applies to it.
 *  - Then the top albums (and biography) and Where next.
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
    // Built from every release, so the filter changes what is shown, never the order.
    val timeline = remember(artist.releases) { buildEraTimeline(artist.releases) }
    val dated = remember(timeline) {
        timeline.eras.flatMap { it.groups }
            .sortedWith(compareByDescending<ReleaseGroup> { releasePosition(it.primary) ?: 0.0 }.thenBy { it.primary.title })
    }
    val shown = dated.filter { it.passes(playableOnly, filter) }.takeIf { filter != ReleaseFilter.Demos }.orEmpty()
    val undated = if (filter == ReleaseFilter.Demos) emptyList() else timeline.undated.filter { it.passes(playableOnly, filter) }
    // Covers on the timeline get a palette (the disc label and a missing cover use it).
    val wantedKeys = remember(dated) { dated.take(TIMELINE_PALETTE_LIMIT).map { it.primary.key }.toSet() }
    val missing = artist.releases.count { it.key in wantedKeys && it.needsPalette }
    LaunchedEffect(artist.id, missing) { ReleasePaletteResolver.ensure(context, artist, keys = wantedKeys) }

    var showFilters by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    BoxWithConstraints(modifier.background(colors.background)) {
        val wide = maxWidth >= 840.dp
        val heroHeight = (maxHeight * 0.86f).coerceAtLeast(560.dp)
        val inset = LocalMusicBarInset.current // fork:music - the page runs under the bar; its foot clears it
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag("artistRoomList"),
            contentPadding = PaddingValues(bottom = inset + 48.dp),
        ) {
            item(key = "hero") {
                RoomHero(
                    artist = artist,
                    discography = discography,
                    wide = wide,
                    height = heroHeight,
                    playTarget = playTarget,
                    canShuffle = canShuffle,
                    notice = notice,
                    saved = saved,
                    refreshing = refreshing,
                    filtersOpen = showFilters,
                    filtered = !playableOnly || filter != ReleaseFilter.All,
                    topLabel = topLabel,
                    onBack = onBack,
                    onAbout = onAbout,
                    onPlay = onPlay,
                    onShuffle = onShuffle,
                    onPlayTop = onPlayTop,
                    onRemove = onRemove,
                    onLinks = onLinks,
                    onRefresh = onRefresh,
                    onOpenArtist = onOpenArtist,
                    onToggleFilters = { showFilters = !showFilters },
                    onWhereNext = {
                        scope.launch {
                            val index = listState.layoutInfo.totalItemsCount - 1
                            if (index >= 0) listState.animateScrollToItem(index)
                        }
                    },
                )
            }
            if (showFilters) {
                item(key = "filters") {
                    Box(Modifier.fillMaxWidth().padding(horizontal = ROOM_SIDE, vertical = 8.dp)) {
                        FilterPanel(discography, filter, playableOnly, onPlayableOnly, onFilter)
                    }
                }
            }
            item(key = "timeline-heading") {
                Row(
                    Modifier.fillMaxWidth().padding(start = ROOM_SIDE, end = ROOM_SIDE, top = 8.dp, bottom = 18.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Discography", style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary)
                    Text(
                        "${shown.size + undated.size} of ${discography.totalCount}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textMuted,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
            }
            if (shown.isEmpty() && undated.isEmpty() && filter != ReleaseFilter.Demos) {
                item(key = "timeline-empty") {
                    MusicMessage(
                        when {
                            working -> "Looking up releases…"
                            timeline.isEmpty -> "No releases found."
                            playableOnly -> "Nothing here is on YouTube Music. Filter → All shows it anyway."
                            else -> "Nothing matches the filter."
                        },
                    )
                }
            }
            itemsIndexed(shown, key = { _, group -> "release:${group.primary.key}" }) { index, group ->
                TimelineEntry(
                    group = group,
                    leftSide = wide && index % 2 == 0,
                    wide = wide,
                    first = index == 0,
                    last = index == shown.lastIndex,
                    onOpen = { onOpen(group.primary) },
                )
            }
            if (undated.isNotEmpty()) {
                item(key = "undated") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = ROOM_SIDE, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Undated", style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            undated.forEach { group -> ReleaseCard(group, onClick = { onOpen(group.primary) }) }
                        }
                    }
                }
            }
            if (discography.demos.isNotEmpty()) {
                item(key = "demos") {
                    Box(Modifier.fillMaxWidth().padding(horizontal = ROOM_SIDE, vertical = 12.dp)) {
                        DemosArchive(discography.demos, open = demosOpen, onToggle = onToggleDemos, onOpen = onOpen)
                    }
                }
            }
            item(key = "widgets") {
                ArtistWidgets(artist, onOpen, Modifier.padding(start = ROOM_SIDE, end = ROOM_SIDE, top = 32.dp)) // fork:music - HTPC phase 8
            }
            // Last, so the hero's Where next button scrolls to the end of the list.
            item(key = "where-next") {
                WhereNextStrip(artist, onOpenArtist, Modifier.padding(start = ROOM_SIDE, end = ROOM_SIDE, top = 28.dp)) // fork:music - HTPC phase 3
            }
        }
    }
}

/** The page's side margin: the shelf's TV-safe 48dp. */
private val ROOM_SIDE = 48.dp

/** How many of the newest releases get a palette worked out as the page opens. */
private const val TIMELINE_PALETTE_LIMIT = 40

private fun ReleaseGroup.passes(playableOnly: Boolean, filter: ReleaseFilter): Boolean =
    (!playableOnly || isPlayable) && matches(filter)

// region hero

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoomHero(
    artist: LibraryArtist,
    discography: ArtistDiscography,
    wide: Boolean,
    height: Dp,
    playTarget: LibraryRelease?,
    canShuffle: Boolean,
    notice: String?,
    saved: Boolean,
    refreshing: String?,
    filtersOpen: Boolean,
    filtered: Boolean,
    topLabel: String?,
    onBack: () -> Unit,
    onAbout: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onPlayTop: () -> Unit,
    onRemove: () -> Unit,
    onLinks: () -> Unit,
    onRefresh: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onToggleFilters: () -> Unit,
    onWhereNext: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    Box(Modifier.fillMaxWidth().height(height)) {
        HeroBackdrop(artist, wide)

        // The cut-out stands at the right, melting into the backdrop; with none, the round photo.
        val logo = artist.art.bestLogo
        val cutout = if (logo != null) artist.art.cutout ?: artist.art.clearArt else artist.art.bestClearArt
        if (wide && cutout != null) {
            NuvioAsyncImage(
                model = cutout,
                contentDescription = null,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = ROOM_SIDE).fillMaxWidth(0.42f).fillMaxHeight(0.78f).fadeEdges(),
                contentScale = ContentScale.Fit,
                alignment = Alignment.BottomEnd,
            )
        }

        Box(Modifier.padding(start = 24.dp, top = 16.dp)) { NuvioBackButton(onClick = onBack) }

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = ROOM_SIDE, end = ROOM_SIDE, bottom = 40.dp)
                .widthIn(max = if (wide) 760.dp else 2000.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!wide || cutout == null) {
                artist.art.thumb?.takeIf { logo == null }?.let { thumb ->
                    NuvioAsyncImage(
                        model = thumb,
                        contentDescription = null,
                        modifier = Modifier.size(120.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            if (logo != null) {
                NuvioAsyncImage(
                    model = logo,
                    contentDescription = artist.name,
                    modifier = Modifier.fillMaxWidth(0.8f).height(if (wide) 110.dp else 80.dp),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                )
            } else {
                Text(
                    artist.name,
                    style = MaterialTheme.typography.displayMedium,
                    fontFamily = FontFamily.Serif,
                    color = colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            artistTagline(artist)?.let { tagline ->
                Text(
                    tagline,
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Serif,
                    color = colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAbout),
                )
            }
            val line = passportLine(artist)
            if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary)

            val (mood, genres) = passportChips(artist)
            if (mood != null || genres.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    mood?.let { InfoChip(it) }
                    genres.forEach { InfoChip(it) }
                }
            }

            HeroStats(artist, discography)

            if (artist.channelMismatch) NamesakeNotice(artist, onOpenArtist)

            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NuvioPrimaryButton(text = "Play", modifier = Modifier.width(128.dp), height = 44.dp, enabled = playTarget != null, onClick = onPlay)
                OutlinedAction(Icons.Rounded.Shuffle, "Shuffle", enabled = canShuffle, onClick = onShuffle)
                // The quick play: the artist's top five albums, one after the other.
                if (topLabel != null) OutlinedAction(Icons.Rounded.LocalFireDepartment, topLabel, onClick = onPlayTop)
                MusicAddToLibraryButton(artistId = artist.id, onSavedClick = onRemove)
                val linkCount = groupLinks(artist.links).values.sumOf { it.size }
                if (linkCount > 0) HeroAction(Icons.Rounded.Link, "Links · $linkCount", onClick = onLinks)
                HeroAction(Icons.Rounded.Explore, "Where next", onClick = onWhereNext)
                HeroAction(Icons.Rounded.FilterList, "Filter", selected = filtersOpen || filtered, onClick = onToggleFilters)
                if (saved) HeroAction(Icons.Rounded.Refresh, refreshing ?: "Refresh", enabled = refreshing == null, onClick = onRefresh)
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
            if (!saved && refreshing != null) {
                Text(refreshing, style = MaterialTheme.typography.labelSmall, color = colors.textMuted)
            }
            if (artist.sources.isNotEmpty()) {
                Text("Metadata from ${artist.sources.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = colors.textMuted)
            }
        }
    }
}

/**
 * The hero picture (fallback chain from the identity) under two shades: one from the left that keeps
 * the text readable, and one at the foot that fades the picture into the page, so the timeline below
 * reads as its continuation. With no picture at all it is the page's own night.
 */
@Composable
private fun HeroBackdrop(artist: LibraryArtist, wide: Boolean) {
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
                modifier = Modifier.fillMaxSize().alpha(fade * if (blurred) 0.5f else 0.75f).let { if (blurred) it.blur(40.dp) else it },
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                if (wide) {
                    Brush.horizontalGradient(0f to dark.copy(alpha = 0.88f), 0.5f to dark.copy(alpha = 0.45f), 1f to dark.copy(alpha = 0.1f))
                } else {
                    Brush.verticalGradient(0f to dark.copy(alpha = 0.4f), 1f to dark.copy(alpha = 0.85f))
                },
            ),
        )
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.45f)
                .background(Brush.verticalGradient(0f to Color.Transparent, 1f to dark)),
        )
    }
}

/** A hero button the height of [OutlinedAction], in the glass, lit in the accent while [selected]. */
@Composable
private fun HeroAction(icon: ImageVector, text: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(22.dp)
    val tint = when {
        !enabled -> colors.textDisabled
        selected -> colors.onAccent
        else -> colors.textPrimary
    }
    Row(
        Modifier
            .height(44.dp)
            .musicGlass(shape, selected = selected)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, color = tint, maxLines = 1)
    }
}

/** Fans, releases, playable, rating and links as small glass tiles. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroStats(artist: LibraryArtist, discography: ArtistDiscography) {
    val colors = MaterialTheme.nuvio.colors
    val tiles = buildList {
        artist.fans?.let { add(formatCount(it) to "fans") }
        add("${discography.totalCount}" to "releases")
        add("${discography.playableCount}" to "playable")
        ratingText(artist.rating)?.let { add("★ $it" to "rating") }
        groupLinks(artist.links).values.sumOf { it.size }.takeIf { it > 0 }?.let { add("$it" to "links") }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.forEach { (value, label) ->
            Row(
                Modifier.musicGlass(RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
    }
}

/** Fades a picture out toward its edges so a cut-out melts into the backdrop. */
private fun Modifier.fadeEdges(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val reach = maxOf(size.width, size.height) * 0.62f
        drawRect(
            brush = Brush.radialGradient(0.55f to Color.Black, 1f to Color.Transparent, center = center, radius = reach),
            blendMode = BlendMode.DstIn,
        )
    }

// endregion

// region timeline

private val SPINE_WIDTH = 44.dp
/** Every card the same width (or the half-width it has, if less), so the two columns line up. */
private val TIMELINE_CARD_MAX = 420.dp

/**
 * One release on the vertical timeline: a glass card (cover, title, the year and kind under it) beside
 * the spine, with a dot on the spine level with it. [leftSide] puts the card left of the spine (wide
 * windows alternate); on a narrow window the spine runs down the left. The spine is drawn per entry,
 * cut short at the [first] and [last] so it starts and ends at a dot.
 */
@Composable
private fun TimelineEntry(
    group: ReleaseGroup,
    leftSide: Boolean,
    wide: Boolean,
    first: Boolean,
    last: Boolean,
    onOpen: () -> Unit,
) {
    val card: @Composable (Modifier) -> Unit = { m -> TimelineCard(group, onOpen, m) }
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = ROOM_SIDE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (wide) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (leftSide) card(Modifier.fillMaxWidth().widthIn(max = TIMELINE_CARD_MAX))
            }
        }
        TimelineSpine(first, last, playable = group.isPlayable)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (!leftSide) card(Modifier.fillMaxWidth().widthIn(max = TIMELINE_CARD_MAX))
        }
    }
}

/** The spine's piece for one entry: a glass line, top to bottom, and a dot (lit in the accent when it plays here). */
@Composable
private fun TimelineSpine(first: Boolean, last: Boolean, playable: Boolean) {
    val colors = MaterialTheme.nuvio.colors
    Box(Modifier.width(SPINE_WIDTH).fillMaxHeight(), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxHeight().width(2.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth().background(if (first) Color.Transparent else NuvioGlass.EdgeColor))
            Box(Modifier.weight(1f).fillMaxWidth().background(if (last) Color.Transparent else NuvioGlass.EdgeColor))
        }
        Box(
            Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(NuvioGlass.night(colors.background, colors.accent))
                .border(2.dp, if (playable) colors.accent else NuvioGlass.EdgeColor, CircleShape),
        )
    }
}

@Composable
private fun TimelineCard(group: ReleaseGroup, onOpen: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.nuvio.colors
    val release = group.primary
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    Row(
        modifier
            .padding(vertical = 8.dp)
            .musicGlass(RoundedCornerShape(14.dp), raised = hovered || focused, keyFocused = focused)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onOpen)
            .alpha(if (group.isPlayable) 1f else 0.5f)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ReleaseCover(release, Modifier.size(72.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(release.title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val under = listOfNotNull(
                release.year ?: release.releaseDate?.take(4),
                release.kind.label.takeIf { it != "Album" },
                "${group.versions.size} versions".takeIf { group.extraVersions > 0 },
            ).joinToString(" · ")
            if (under.isNotEmpty()) {
                Text(under, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
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

