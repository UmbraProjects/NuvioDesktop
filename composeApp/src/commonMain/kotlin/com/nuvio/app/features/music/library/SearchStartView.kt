package com.nuvio.app.features.music.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.nuvio

/**
 * The Music search screen before anything is typed. Rows of artwork cards: ladders to continue, ladder starters, the
 * genres the library holds, and recent searches as chips.
 *
 * Cards are sized from the width of the screen so a whole row is always on screen and none is cut off: 5 across on a
 * wide display, 4 or 3 on a narrower one. Each card is built from what the library already knows: the artists' own
 * pictures laid out as a collage, the colour their artwork gave, the genre's neighbours and the artists' names. A ladder
 * being continued looks its climbed artists' pictures up. Everything is reachable by d-pad (a focus ring and a small
 * scale), nothing is hover-only, and the rows keep 5% margins.
 */
private object StartType {
    val section = 34.sp
    val sectionNote = 20.sp
    val eyebrow = 16.sp
    val cardTitle = 30.sp
    val cardLine = 20.sp
    val chip = 24.sp
    const val FOCUS_SCALE = 1.05f
    const val CARD_ASPECT = 1.18f // width over height
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchStartView(
    recent: List<String>,
    starters: List<LadderStarter>,
    continues: List<ContinueCard>,
    tiles: List<GenreCount>,
    onRecent: (String) -> Unit,
    onLadder: (LadderRequest) -> Unit,
    onGenre: (String) -> Unit,
    modifier: Modifier = Modifier,
    art: Map<String, StartArt> = emptyMap(),
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val margin = maxWidth * 0.05f
        val gap = 20.dp
        val across = when {
            maxWidth >= 1500.dp -> 5
            maxWidth >= 1100.dp -> 4
            else -> 3
        }
        val cardWidth = (maxWidth - margin * 2 - gap * (across - 1)) / across
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = margin, end = margin, top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(30.dp),
        ) {
            if (continues.isNotEmpty()) item {
                StartSection("Continue a ladder", "Pick up where you left off") {
                    continues.take(across).forEach { card ->
                        val tint = card.names.firstNotNullOfOrNull { n -> art.values.firstOrNull { it.name == n }?.tint }
                        PosterCard(
                            eyebrow = "Ladder · ${card.climbed} of ${card.total}",
                            title = card.title,
                            lines = listOf(card.names.take(3).joinToString(", ").ifEmpty { card.detail }),
                            images = emptyList(),
                            lookUp = card.names,
                            tint = tintFor(card.title, tint),
                            progress = card.climbed to card.total,
                            width = cardWidth,
                        ) { onLadder(card.request) }
                    }
                }
            }
            if (starters.isNotEmpty()) item {
                StartSection("Ladder starters", "Genres you barely own: good places to grow") {
                    starters.take(across).forEach { s ->
                        val members = s.artistIds.mapNotNull { art[it] }
                        PosterCard(
                            eyebrow = "Ladder starter",
                            title = s.label,
                            lines = listOfNotNull(
                                members.take(2).joinToString(", ") { it.name }.takeIf { it.isNotEmpty() },
                                buildString {
                                    append(if (s.artists == 1) "1 artist" else "${s.artists} artists")
                                    if (s.neighbours.isNotEmpty()) append(" · near ").append(s.neighbours.joinToString(", "))
                                },
                            ),
                            images = members.mapNotNull { it.picture }.distinct().take(4),
                            lookUp = emptyList(),
                            tint = tintFor(s.genre, members.firstNotNullOfOrNull { it.tint }),
                            progress = null,
                            width = cardWidth,
                        ) { onLadder(s.request) }
                    }
                }
            }
            if (tiles.isNotEmpty()) item {
                StartSection("Your genres", "Search what your library is made of") {
                    tiles.take(across).forEach { t ->
                        val members = t.artistIds.mapNotNull { art[it] }
                        PosterCard(
                            eyebrow = "Genre",
                            title = displayGenre(t.genre),
                            lines = listOfNotNull(
                                members.take(2).joinToString(", ") { it.name }.takeIf { it.isNotEmpty() },
                                if (t.artists == 1) "1 artist" else "${t.artists} artists",
                            ),
                            images = members.mapNotNull { it.picture }.distinct().take(4),
                            lookUp = emptyList(),
                            tint = tintFor(t.genre, members.firstNotNullOfOrNull { it.tint }),
                            progress = null,
                            width = cardWidth,
                        ) { onGenre(t.genre) }
                    }
                }
            }
            if (recent.isNotEmpty()) item {
                val colors = MaterialTheme.nuvio.colors
                Column {
                    Text("Recent searches", fontSize = StartType.section, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                    Spacer(Modifier.height(14.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        maxLines = 2,
                    ) {
                        recent.forEach { text -> RecentChip(text) { onRecent(text) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun StartSection(title: String, note: String, cards: @Composable () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    Column {
        Text(title, fontSize = StartType.section, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
        Text(note, fontSize = StartType.sectionNote, color = colors.textSecondary)
        // Vertical room for a focused card to grow without being clipped.
        Row(
            modifier = Modifier.padding(top = 14.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) { cards() }
    }
}

@Composable
private fun RecentChip(text: String, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.06f else 1f)
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(56.dp)
            .clip(shape)
            .background(colors.surfaceElevated)
            .border(if (focused) BorderStroke(3.dp, colors.focusRing) else BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(24.dp))
        Text(text, fontSize = StartType.chip, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * One artwork card: a collage of pictures filling the card, a scrim in the card's colour, and the text over it.
 * [images] are ready URLs; [lookUp] are artist names whose pictures are fetched (a continued ladder has only names).
 * [progress] draws a segment per rung, climbed ones filled.
 */
@Composable
private fun PosterCard(
    eyebrow: String,
    title: String,
    lines: List<String>,
    images: List<String>,
    lookUp: List<String>,
    tint: Color,
    progress: Pair<Int, Int>?,
    width: Dp,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) StartType.FOCUS_SCALE else 1f)
    val shape = RoundedCornerShape(20.dp)
    val looked by produceState(emptyList<String>(), lookUp) {
        val found = ArrayList<String>()
        for (name in lookUp.take(4)) {
            LadderArtwork.picture(name)?.let { found += it; value = found.toList() }
        }
    }
    val pictures = images.ifEmpty { looked }
    val ground = Color(
        red = tint.red * 0.16f, green = tint.green * 0.16f, blue = tint.blue * 0.16f, alpha = 1f,
    )
    Box(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .width(width)
            .aspectRatio(StartType.CARD_ASPECT)
            .clip(shape)
            .background(ground)
            .border(if (focused) BorderStroke(4.dp, colors.focusRing) else BorderStroke(1.dp, Color.White.copy(alpha = 0.07f)), shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
    ) {
        Collage(pictures, title, tint)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.0f to Color.Transparent,
                    0.38f to ground.copy(alpha = 0.30f),
                    0.78f to ground.copy(alpha = 0.94f),
                    1.0f to ground,
                ),
            ),
        )
        Column(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                eyebrow.uppercase(),
                fontSize = StartType.eyebrow,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.6.sp,
                color = Color.White.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                title,
                fontSize = StartType.cardTitle,
                fontWeight = FontWeight.Bold,
                lineHeight = 34.sp,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            lines.forEach { line ->
                Text(line, fontSize = StartType.cardLine, color = Color.White.copy(alpha = 0.82f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (progress != null) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(progress.second) { i ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(6.dp)
                                .clip(CircleShape)
                                .background(if (i < progress.first) tint.lighten() else Color.White.copy(alpha = 0.22f)),
                        )
                    }
                }
            }
        }
    }
}

/** The pictures filling the card's upper part: one large, two side by side, one large with two stacked, or a 2 by 2. */
@Composable
private fun Collage(pictures: List<String>, description: String, tint: Color) {
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(tint.copy(alpha = 0.55f), tint.copy(alpha = 0.15f))))) {
        @Composable
        fun tile(url: String, modifier: Modifier) = NuvioAsyncImage(
            model = url,
            contentDescription = description,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
        when (pictures.size) {
            0 -> Unit
            1 -> tile(pictures[0], Modifier.fillMaxSize())
            2 -> Row(Modifier.fillMaxSize()) {
                tile(pictures[0], Modifier.weight(1f).fillMaxHeight())
                tile(pictures[1], Modifier.weight(1f).fillMaxHeight())
            }
            3 -> Row(Modifier.fillMaxSize()) {
                tile(pictures[0], Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    tile(pictures[1], Modifier.weight(1f).fillMaxWidth())
                    tile(pictures[2], Modifier.weight(1f).fillMaxWidth())
                }
            }
            else -> Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f)) {
                    tile(pictures[0], Modifier.weight(1f).fillMaxHeight())
                    tile(pictures[1], Modifier.weight(1f).fillMaxHeight())
                }
                Row(Modifier.weight(1f)) {
                    tile(pictures[2], Modifier.weight(1f).fillMaxHeight())
                    tile(pictures[3], Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

/** The colour an artist's artwork gave, else a steady one per name, so a genre keeps its colour wherever it shows up. */
private fun tintFor(name: String, palette: Long?): Color =
    palette?.let { Color(it) } ?: run {
        val hash = genreKey(name).fold(7) { acc, c -> acc * 31 + c.code }
        Color.hsv(((hash % 360) + 360) % 360f, 0.55f, 0.62f)
    }

private fun Color.lighten(): Color = Color(
    red = red + (1f - red) * 0.35f, green = green + (1f - green) * 0.35f, blue = blue + (1f - blue) * 0.35f, alpha = 1f,
)
