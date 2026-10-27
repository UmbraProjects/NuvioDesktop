package com.nuvio.app.features.music.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.LocalPlatformContext
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.music.MusicMessage
import com.nuvio.app.features.music.identity.MusicPaletteResolver
import com.nuvio.app.features.music.identity.identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Library views, phase C3: the Constellation drawn. Each placed artist is a star in their palette accent, sized by
 * release count, ringed in gold when a favourite; a line joins stars that sound alike; groups of two or more stars
 * get a faint halo and the genre's name. A legend names what the picture encodes, and artists with no tags yet wait
 * in the "Not placed yet" tray under the map. With fewer than [CONSTELLATION_MIN_ARTISTS] placeable artists the map
 * is replaced by a message.
 *
 * The map itself is built by [buildConstellationModel] (C0 to C2) off the main thread, and only when something that
 * moves it changes ([constellationKey]); palettes and favourites are read live, so they never rebuild it. This phase
 * is the picture only: hover, click, pan, zoom, filter and the side panel are C4.
 *
 * Hosted by [MusicLibraryShelf] behind one `// fork:music` switch.
 */
@Composable
internal fun ConstellationView(
    artists: List<LibraryArtist>,
    pending: List<String>,
    onArtistClick: (artistId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    val context = LocalPlatformContext.current
    val byId = remember(artists) { artists.associateBy { it.id } }

    // Star colours come from the artist palettes the shelf already caches; derive any that are missing, quietly.
    val missingPalettes = remember(artists) { artists.count { it.identity().needsPalette } }
    LaunchedEffect(missingPalettes, artists.size) {
        artists.forEach { MusicPaletteResolver.ensure(context, it.identity()) }
    }

    var model by remember { mutableStateOf<ConstellationModel?>(null) }
    LaunchedEffect(artists) {
        val current = model?.key
        val next = withContext(Dispatchers.Default) {
            val key = constellationKey(artists)
            if (key == current) null else buildConstellationModel(artists, key)
        }
        if (next != null) model = next
    }

    Column(modifier.fillMaxWidth()) {
        pending.forEach { status ->
            Text(
                "Adding… $status",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val m = model
            when {
                m == null -> Text(
                    "Mapping your library…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted,
                    modifier = Modifier.align(Alignment.Center),
                )
                !m.graph.canDraw -> MusicMessage(
                    "A constellation needs a few artists with tags. " +
                        if (m.graph.placedCount == 0) "None of your artists have tags yet." else "${m.graph.placedCount} placed so far.",
                )
                else -> {
                    ConstellationCanvas(m, byId, fallback = colors.accent, modifier = Modifier.fillMaxSize())
                    ConstellationLegend(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 8.dp))
                }
            }
        }
        val waiting = model?.graph?.untagged?.mapNotNull { byId[it.id] }.orEmpty()
        if (waiting.isNotEmpty()) NotPlacedTray(waiting, onArtistClick)
    }
}

// region map

@Composable
private fun ConstellationCanvas(
    model: ConstellationModel,
    byId: Map<String, LibraryArtist>,
    fallback: Color,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.62f))
    val points = remember(model) { model.layout.points.associateBy { it.id } }
    val labelled = remember(model) { model.clusters.clusters.filter(::drawsClusterLabel) }
    val labels = remember(model, labelStyle) {
        labelled.associate { it.key to measurer.measure(it.label, labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }

    fun colourOf(id: String): Color = byId[id]?.palette?.let { Color(it.accent) } ?: fallback

    Canvas(modifier) {
        val pad = 24.dp.toPx()
        val inner = mapFrame(size.width - 2 * pad, size.height - 2 * pad)
        val frame = MapFrame(inner.left + pad, inner.top + pad, inner.width, inner.height)

        // 1. Halos: a faint ellipse round each group (the box is stretched along one side, so the halo is too).
        for (cluster in labelled) {
            val lead = clusterLead(cluster, model.layout) ?: continue
            val c = colourOf(lead)
            val rx = ((cluster.haloRadius + HALO_PAD) * frame.width).toFloat()
            val ry = ((cluster.haloRadius + HALO_PAD) * frame.height).toFloat()
            val topLeft = Offset(frame.x(cluster.x) - rx, frame.y(cluster.y) - ry)
            drawOval(c.copy(alpha = 0.06f), topLeft, Size(2 * rx, 2 * ry))
            drawOval(c.copy(alpha = 0.14f), topLeft, Size(2 * rx, 2 * ry), style = Stroke(1.dp.toPx()))
        }

        // 2. Lines: a gradient between the two stars' colours, firmer for closer matches.
        for (link in model.graph.links) {
            val a = points[link.a] ?: continue
            val b = points[link.b] ?: continue
            val from = Offset(frame.x(a.x), frame.y(a.y))
            val to = Offset(frame.x(b.x), frame.y(b.y))
            val alpha = linkAlpha(link.similarity, link.mutual)
            drawLine(
                brush = Brush.linearGradient(listOf(colourOf(a.id).copy(alpha = alpha), colourOf(b.id).copy(alpha = alpha)), start = from, end = to),
                start = from,
                end = to,
                strokeWidth = (if (link.mutual) 1.6.dp else 1.1.dp).toPx(),
                cap = StrokeCap.Round,
            )
        }

        // 3. Stars, with a soft glow and a gold ring for a favourite.
        for (p in model.layout.points) {
            val center = Offset(frame.x(p.x), frame.y(p.y))
            val r = frame.radius(p.radius)
            val c = colourOf(p.id)
            drawCircle(
                brush = Brush.radialGradient(listOf(c.copy(alpha = 0.28f), Color.Transparent), center = center, radius = r * 2.4f),
                radius = r * 2.4f,
                center = center,
            )
            drawCircle(c, r, center)
            drawCircle(Color.Black.copy(alpha = 0.25f), r, center, style = Stroke(1.dp.toPx()))
            if (byId[p.id]?.favourite == true) drawCircle(GOLD, r + 3.dp.toPx(), center, style = Stroke(2.dp.toPx()))
        }

        // 4. Group names, just above each halo and kept on the canvas.
        for (cluster in labelled) {
            val text = labels[cluster.key] ?: continue
            val margin = 4.dp.toPx()
            val ry = ((cluster.haloRadius + HALO_PAD) * frame.height).toFloat()
            val x = (frame.x(cluster.x) - text.size.width / 2f).coerceIn(margin, (size.width - text.size.width - margin).coerceAtLeast(margin))
            val y = (frame.y(cluster.y) - ry - text.size.height - margin).coerceAtLeast(margin)
            drawText(text, topLeft = Offset(x, y))
        }
    }
}

/** Air between the farthest star and its halo, in unit-box units. */
private const val HALO_PAD = 0.01

// endregion

// region legend and tray

private enum class LegendGlyph { Size, Favourite, Line, Halo }

/** What the picture encodes. Only what is drawn so far; later phases add their own rows (filter dimming, found via ladder). */
@Composable
private fun ConstellationLegend(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.nuvio.colors
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.32f)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for ((glyph, text) in listOf(
            LegendGlyph.Size to "Bigger star, more releases",
            LegendGlyph.Favourite to "Gold ring, a favourite",
            LegendGlyph.Line to "Line, sounds alike",
            LegendGlyph.Halo to "Halo, a genre group",
        )) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LegendMark(glyph)
                Text(text, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun LegendMark(glyph: LegendGlyph) {
    val accent = MaterialTheme.nuvio.colors.accent
    Canvas(Modifier.size(width = 28.dp, height = 16.dp)) {
        val mid = Offset(size.width / 2, size.height / 2)
        when (glyph) {
            LegendGlyph.Size -> {
                drawCircle(accent, 3.dp.toPx(), Offset(size.width * 0.25f, mid.y))
                drawCircle(accent, 6.dp.toPx(), Offset(size.width * 0.7f, mid.y))
            }
            LegendGlyph.Favourite -> {
                drawCircle(accent, 4.dp.toPx(), mid)
                drawCircle(GOLD, 7.dp.toPx(), mid, style = Stroke(1.6.dp.toPx()))
            }
            LegendGlyph.Line -> {
                drawLine(accent.copy(alpha = 0.6f), Offset(size.width * 0.2f, mid.y), Offset(size.width * 0.8f, mid.y), strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(accent, 3.dp.toPx(), Offset(size.width * 0.2f, mid.y))
                drawCircle(accent, 3.dp.toPx(), Offset(size.width * 0.8f, mid.y))
            }
            LegendGlyph.Halo -> {
                drawOval(accent.copy(alpha = 0.08f), Offset(0f, 0f), size)
                drawOval(accent.copy(alpha = 0.3f), Offset(0f, 0f), size, style = Stroke(1.dp.toPx()))
            }
        }
    }
}

/** Artists with no genres or tags yet: they have no place on the map until the enricher fills those in. */
@Composable
private fun NotPlacedTray(artists: List<LibraryArtist>, onArtistClick: (String) -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "NOT PLACED YET · ${artists.size}",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.accent,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Text(
            "No genres or tags yet. They join the map once their tags arrive.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textMuted,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(artists, key = { it.id }) { artist ->
                Text(
                    artist.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .clickable { onArtistClick(artist.id) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

// endregion
