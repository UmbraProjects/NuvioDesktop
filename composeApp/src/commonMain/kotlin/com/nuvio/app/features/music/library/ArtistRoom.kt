package com.nuvio.app.features.music.library

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
import com.nuvio.app.features.music.MusicMessage
import com.nuvio.app.features.music.identity.HeroSource
import com.nuvio.app.features.music.identity.ReleasePaletteResolver
import com.nuvio.app.features.music.identity.identity

/**
 * Phase 4: the artist room. One page for both doors (Search and Library), drawn from the artist's
 * identity: era-tinted backdrop, logo, cut-out and passport on the left; Play bar, era timeline and
 * the selected era's releases on the right. Every picture has a fallback, so a bare artist (no art,
 * no palette, no dates) still draws a readable page in the app's own colours.
 *
 * Under ~840dp wide the two columns stack. All play / dialog / Add logic stays in [MusicArtistScreen];
 * this only draws and reports taps.
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
    // The ruler is built from every release, so filters change the cards, never the eras.
    val timeline = remember(artist.releases) { buildEraTimeline(artist.releases) }
    var picked by rememberSaveable(artist.id) { mutableStateOf<Int?>(null) }
    val selected = picked?.takeIf { it in timeline.eras.indices } ?: timeline.defaultEraIndex(latest)
    val era = timeline.eras.getOrNull(selected)

    // Covers worth a palette: each era's lead (its colour) and everything in the selected era.
    val wantedKeys = remember(timeline, selected) {
        (timeline.eras.map { it.lead.primary.key } + era?.groups.orEmpty().map { it.primary.key }).toSet()
    }
    val missing = artist.releases.count { it.key in wantedKeys && it.needsPalette }
    LaunchedEffect(artist.id, missing) { ReleasePaletteResolver.ensure(context, artist, keys = wantedKeys) }

    val eraPalette = era?.palette(artist.palette)
    val tint by animateColorAsState(eraPalette?.let { Color(it.tint) } ?: colors.surfaceElevated, tween(500))

    BoxWithConstraints(modifier.background(colors.background)) {
        val wide = maxWidth >= 840.dp
        RoomBackdrop(artist, tint, wide)
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.width(440.dp).fillMaxHeight().verticalScroll(rememberScrollState())
                        .padding(start = 24.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    RoomIdentity(artist, discography, wide = true, saved, refreshing, onBack, onAbout, onRefresh)
                }
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                        .padding(start = 8.dp, end = 32.dp, top = 24.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    RoomEras(
                        artist, discography, timeline, selected, { picked = it }, playTarget, canShuffle, notice, working,
                        filter, playableOnly, demosOpen, onPlay, onShuffle, onLinks, onOpenArtist, onRemove, onOpen, onPlayableOnly, onFilter, onToggleDemos,
                    )
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    RoomIdentity(artist, discography, wide = false, saved, refreshing, onBack, onAbout, onRefresh)
                }
                RoomEras(
                    artist, discography, timeline, selected, { picked = it }, playTarget, canShuffle, notice, working,
                    filter, playableOnly, demosOpen, onPlay, onShuffle, onLinks, onOpenArtist, onRemove, onOpen, onPlayableOnly, onFilter, onToggleDemos,
                )
            }
        }
    }
}

// region backdrop

/**
 * Hero picture (fallback chain from the identity), washed with the era's colour and darkened toward the
 * content. With no picture at all it is the palette's ground colour. Fades in when the room opens.
 */
@Composable
private fun RoomBackdrop(artist: LibraryArtist, tint: Color, wide: Boolean) {
    val colors = MaterialTheme.nuvio.colors
    val identity = remember(artist) { artist.identity() }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val fade by animateFloatAsState(if (shown) 1f else 0f, tween(600))
    val dark = colors.background
    Box(Modifier.fillMaxSize().background(identity.palette?.let { Color(it.ground) } ?: dark)) {
        identity.hero?.let { hero ->
            val blurred = hero.source == HeroSource.BlurredCover
            NuvioAsyncImage(
                model = hero.url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().alpha(fade * if (blurred) 0.5f else 0.6f).let { if (blurred) it.blur(40.dp) else it },
                contentScale = ContentScale.Crop,
            )
        }
        Box(Modifier.fillMaxSize().background(tint.copy(alpha = 0.35f)))
        Box(
            Modifier.fillMaxSize().background(
                if (wide) {
                    Brush.horizontalGradient(0f to dark.copy(alpha = 0.15f), 0.46f to dark.copy(alpha = 0.82f), 1f to dark.copy(alpha = 0.96f))
                } else {
                    Brush.verticalGradient(0f to dark.copy(alpha = 0.55f), 1f to dark.copy(alpha = 0.94f))
                },
            ),
        )
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(260.dp)
                .background(Brush.verticalGradient(0f to Color.Transparent, 1f to dark)),
        )
    }
}

// endregion

// region left: logo, cut-out, passport

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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoomIdentity(
    artist: LibraryArtist,
    discography: ArtistDiscography,
    wide: Boolean,
    saved: Boolean,
    refreshing: String?,
    onBack: () -> Unit,
    onAbout: () -> Unit,
    onRefresh: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    NuvioBackButton(onClick = onBack)

    val logo = artist.art.bestLogo
    if (logo != null) {
        NuvioAsyncImage(
            model = logo,
            contentDescription = artist.name,
            modifier = Modifier.fillMaxWidth().height(if (wide) 96.dp else 72.dp),
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
        )
    } else {
        Text(
            artist.name,
            style = MaterialTheme.typography.displaySmall,
            fontFamily = FontFamily.Serif,
            color = colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }

    // Clear art often has the band's logo printed in it; with a logo already on show, prefer the plain cut-out.
    val cutout = if (logo != null) artist.art.cutout ?: artist.art.clearArt else artist.art.bestClearArt
    val thumb = artist.art.thumb
    when {
        cutout != null -> NuvioAsyncImage(
            model = cutout,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().height(if (wide) 320.dp else 220.dp).fadeEdges(),
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomStart,
        )
        thumb != null -> NuvioAsyncImage(
            model = thumb,
            contentDescription = null,
            modifier = Modifier.size(if (wide) 160.dp else 120.dp).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    }

    // The passport.
    artistTagline(artist)?.let { tagline ->
        Text(
            tagline,
            style = MaterialTheme.typography.headlineSmall,
            fontFamily = FontFamily.Serif,
            color = colors.textPrimary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAbout),
        )
    }
    val line = passportLine(artist)
    if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)

    val (mood, genres) = passportChips(artist)
    if (mood != null || genres.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            mood?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onAccent,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(colors.accent).padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
            genres.forEach { InfoChip(it) }
        }
    }

    val linkCount = groupLinks(artist.links).values.sumOf { it.size }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        artist.fans?.let { RoomStat(formatCount(it), "fans") }
        RoomStat("${discography.totalCount}", "releases")
        RoomStat("${discography.playableCount}", "playable")
        if (linkCount > 0) RoomStat("$linkCount", "links")
    }

    if (saved) {
        TextButton(
            onClick = onRefresh,
            enabled = refreshing == null,
            colors = ButtonDefaults.textButtonColors(contentColor = colors.accent, disabledContentColor = colors.textMuted),
        ) { Text(refreshing ?: "Refresh") }
    } else if (refreshing != null) {
        Text(refreshing, style = MaterialTheme.typography.labelSmall, color = colors.textMuted)
    }
    if (artist.sources.isNotEmpty()) {
        Text("Metadata from ${artist.sources.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = colors.textMuted)
    }
}

@Composable
private fun RoomStat(value: String, label: String) {
    val colors = MaterialTheme.nuvio.colors
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Bottom) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.textPrimary)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
    }
}

// endregion

// region right: bar, timeline, era releases

private fun ReleaseGroup.passes(playableOnly: Boolean, filter: ReleaseFilter): Boolean =
    (!playableOnly || isPlayable) && matches(filter)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoomEras(
    artist: LibraryArtist,
    discography: ArtistDiscography,
    timeline: EraTimeline,
    selected: Int,
    onSelectEra: (Int) -> Unit,
    playTarget: LibraryRelease?,
    canShuffle: Boolean,
    notice: String?,
    working: Boolean,
    filter: ReleaseFilter,
    playableOnly: Boolean,
    demosOpen: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onLinks: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onRemove: () -> Unit,
    onOpen: (LibraryRelease) -> Unit,
    onPlayableOnly: (Boolean) -> Unit,
    onFilter: (ReleaseFilter) -> Unit,
    onToggleDemos: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    var showFilters by rememberSaveable { mutableStateOf(false) }
    val era = timeline.eras.getOrNull(selected)

    if (artist.channelMismatch) NamesakeNotice(artist, onOpenArtist)

    // Play bar.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "ERAS",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.accent,
            modifier = Modifier.padding(end = 8.dp),
        )
        NuvioPrimaryButton(text = "Play", modifier = Modifier.width(128.dp), height = 44.dp, enabled = playTarget != null, onClick = onPlay)
        OutlinedAction(Icons.Rounded.Shuffle, "Shuffle", enabled = canShuffle, onClick = onShuffle)
        MusicAddToLibraryButton(artistId = artist.id, onSavedClick = onRemove)
        val linkCount = groupLinks(artist.links).values.sumOf { it.size }
        if (linkCount > 0) ActionPill("Links · $linkCount", onClick = onLinks)
        ActionPill("Filter", selected = showFilters || playableOnly || filter != ReleaseFilter.All) { showFilters = !showFilters }
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

    if (showFilters) {
        FilterPanel(discography, filter, playableOnly, onPlayableOnly, onFilter)
    }

    if (timeline.eras.isNotEmpty()) {
        EraRuler(artist, timeline, selected, onSelectEra)
    }

    if (era != null && filter != ReleaseFilter.Demos) {
        val eraAccent = era.palette(artist.palette)?.accent?.let { Color(it) } ?: colors.accent
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                era.years,
                fontFamily = FontFamily.Serif,
                fontSize = 44.sp,
                color = colors.textPrimary,
            )
            Column(Modifier.padding(bottom = 6.dp)) {
                Text(era.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = eraAccent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val summary = listOfNotNull(
                    "${era.releaseCount} release${if (era.releaseCount == 1) "" else "s"}",
                    era.trackTotal.takeIf { it > 0 }?.let { "$it track${if (it == 1) "" else "s"}" },
                ).joinToString(" · ")
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            }
        }
        val shown = era.groups.filter { it.passes(playableOnly, filter) }
        if (shown.isEmpty()) {
            Text("Nothing in this era matches the filter.", style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                shown.forEach { group -> ReleaseCard(group, onClick = { onOpen(group.primary) }) }
            }
        }
    }

    val undated = if (filter == ReleaseFilter.Demos) emptyList() else timeline.undated.filter { it.passes(playableOnly, filter) }
    if (undated.isNotEmpty()) {
        Text("Undated", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.textPrimary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            undated.forEach { group -> ReleaseCard(group, onClick = { onOpen(group.primary) }) }
        }
    }

    if (discography.demos.isNotEmpty()) {
        DemosArchive(discography.demos, open = demosOpen, onToggle = onToggleDemos, onOpen = onOpen)
    }

    if (timeline.isEmpty && discography.demos.isEmpty()) {
        MusicMessage(
            when {
                working -> "Looking up releases…"
                else -> "No releases found."
            },
        )
    }
}

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

/** Ruler with release dots (sized by track count) and a clickable block per era underneath. */
@Composable
private fun EraRuler(artist: LibraryArtist, timeline: EraTimeline, selected: Int, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    BoxWithConstraints(Modifier.fillMaxWidth().height(142.dp)) {
        val w = maxWidth.value
        val lineY = 52.dp
        fun eraColor(i: Int): Color = timeline.eras[i].palette(artist.palette)?.accent?.let { Color(it) } ?: colors.accent
        fun onEra(i: Int): Color = timeline.eras[i].palette(artist.palette)?.onAccent?.let { Color(it) } ?: colors.onAccent

        Box(Modifier.offset(y = lineY).fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.22f)))
        rulerTicks(timeline.startYear, timeline.endYear).forEach { year ->
            val x = rulerX(year.toDouble(), timeline.startYear, timeline.spanYears, w)
            Box(Modifier.offset(x = x.dp, y = lineY - 6.dp).width(1.dp).height(14.dp).background(Color.White.copy(alpha = 0.35f)))
            Text(
                year.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textMuted,
                modifier = Modifier.offset(x = (x - 14f).dp, y = lineY + 12.dp),
            )
        }
        // Unselected dots first so the chosen era's sit on top.
        timeline.dots.sortedBy { it.eraIndex == selected }.forEach { dot ->
            val size = timelineDotSize(dot.trackCount).toFloat()
            val x = rulerX(dot.position, timeline.startYear, timeline.spanYears, w)
            val on = dot.eraIndex == selected
            Box(
                Modifier
                    .offset(x = (x - size / 2f).dp, y = lineY + 1.dp - (size / 2f).dp)
                    .size(size.dp)
                    .clip(CircleShape)
                    .background(eraColor(dot.eraIndex).copy(alpha = if (on) 1f else 0.35f))
                    .let { if (on) it.border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape) else it }
                    .clickable { onSelect(dot.eraIndex) },
            )
        }
        @Composable
        fun EraBlock(i: Int, modifier: Modifier) {
            val era = timeline.eras[i]
            val on = i == selected
            Column(
                modifier
                    .height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) eraColor(i) else Color.White.copy(alpha = 0.1f))
                    .clickable { onSelect(i) }
                    .padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                val fg = if (on) onEra(i) else colors.textSecondary
                Text(era.years, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = fg, maxLines = 1)
                Text(era.name, style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (eraBlocksOverflow(timeline.eras.size, w, minWidth = 90f)) {
            // Too many eras to share the width: fixed-size blocks in a strip that scrolls, kept on the chosen era.
            val strip = rememberScrollState()
            val density = LocalDensity.current
            LaunchedEffect(selected, w) {
                val target = with(density) { ((selected * (SCROLL_BLOCK_WIDTH + 4f) - w / 2f + SCROLL_BLOCK_WIDTH / 2f).coerceAtLeast(0f)).dp.roundToPx() }
                strip.animateScrollTo(target)
            }
            Row(
                Modifier.offset(y = 96.dp).fillMaxWidth().horizontalScroll(strip),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                timeline.eras.indices.forEach { i -> EraBlock(i, Modifier.width(SCROLL_BLOCK_WIDTH.dp)) }
            }
        } else {
            val slots = layoutEraBlocks(timeline.eras, timeline.startYear, timeline.spanYears, w, minWidth = 90f)
            timeline.eras.indices.forEach { i ->
                val slot = slots[i]
                EraBlock(i, Modifier.offset(x = slot.left.dp, y = 96.dp).width(slot.width.dp))
            }
        }
    }
}

// endregion

/** Width of one era block when there are too many to share the ruler's width. Wide enough for "1979 – 1980". */
private const val SCROLL_BLOCK_WIDTH = 116f

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

