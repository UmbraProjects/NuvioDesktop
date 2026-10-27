package com.nuvio.app.features.music.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.nuvio

/**
 * The Music search screen before anything is typed (docx item 1). Four rows of large, focusable cards: recent
 * searches, ladder starters, continue-a-ladder and genre tiles. Sizes follow the 10-foot rules in one place
 * ([StartType]); everything is reachable by d-pad (focus ring and a 1.08 scale), nothing is hover-only, and the
 * list keeps 5% margins.
 */
private object StartType {
    val section = 36.sp // the docx asks 48sp at 1080p; kept lower until a real HTPC screenshot says it fits
    val cardTitle = 28.sp
    val cardDetail = 22.sp
    const val FOCUS_SCALE = 1.08f
}

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
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val margin = maxWidth * 0.05f
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = margin, end = margin, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            if (recent.isNotEmpty()) item {
                StartSection("Recent searches") {
                    items(recent) { text -> StartCard(text, null, Color(0xFF2A2A33), small = true) { onRecent(text) } }
                }
            }
            if (continues.isNotEmpty()) item {
                StartSection("Continue a ladder") {
                    items(continues) { card -> StartCard(card.title, card.detail, hueOf(card.title)) { onLadder(card.request) } }
                }
            }
            if (starters.isNotEmpty()) item {
                StartSection("Ladder starters") {
                    items(starters) { s -> StartCard(s.title, s.detail, hueOf(s.genre)) { onLadder(s.request) } }
                }
            }
            if (tiles.isNotEmpty()) item {
                StartSection("Your genres") {
                    items(tiles) { t ->
                        StartCard(t.genre.replaceFirstChar { it.uppercase() }, "${t.artists} artists", hueOf(t.genre)) { onGenre(t.genre) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StartSection(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    Column {
        Text(title, fontSize = StartType.section, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
        Spacer(Modifier.height(12.dp))
        // Vertical padding leaves room for the focused card to grow without being clipped.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 12.dp, horizontal = 8.dp),
            content = content,
        )
    }
}

@Composable
private fun StartCard(title: String, detail: String?, tint: Color, small: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) StartType.FOCUS_SCALE else 1f)
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .width(if (small) 240.dp else 300.dp)
            .height(if (small) 88.dp else 150.dp)
            .clip(shape)
            .background(tint)
            .border(if (focused) BorderStroke(4.dp, colors.focusRing) else BorderStroke(0.dp, Color.Transparent), shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(16.dp),
        contentAlignment = if (small) Alignment.CenterStart else Alignment.BottomStart,
    ) {
        Column {
            Text(
                title,
                fontSize = StartType.cardTitle,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(detail, fontSize = StartType.cardDetail, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A steady dark colour per name, so a genre keeps its card colour wherever it shows up. */
private fun hueOf(name: String): Color {
    val hash = genreKey(name).fold(7) { acc, c -> acc * 31 + c.code }
    return Color.hsv(((hash % 360) + 360) % 360f, 0.5f, 0.42f)
}
