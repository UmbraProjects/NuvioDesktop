package com.nuvio.app.features.music.library

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

// Shared by the Shelf and Eras views: the shelf's former private cover tile.
internal val TILE_SIZE = 112.dp
internal val GOLD = Color(0xFFE8B84A)
internal val ON_GOLD = Color(0xFF1A1307)

/**
 * One release as a cover with its title and year under it. Hovering slides the record out of the right
 * edge (the tile widens to make room, so the neighbours shift instead of being covered) and shows the
 * star that pins an album to the top five. A ranked album carries its gold number.
 */
@Composable
internal fun CoverTile(
    tile: ShelfTile,
    reducedMotion: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    /** Drawn faded: a release that cannot be played here (the Eras view shows these; the shelf never does). */
    dimmed: Boolean = false,
) {
    val colors = MaterialTheme.nuvio.colors
    val size = TILE_SIZE
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val peek by animateDpAsState(if (hovered) size * 0.16f else 0.dp, tween(220))
    val release = tile.release
    val pinnable = release.kind == ReleaseKind.Album || release.kind == ReleaseKind.EP

    Column(
        Modifier.width(size + peek).graphicsLayer { alpha = if (dimmed) 0.4f else 1f }.hoverable(source),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(size + peek).height(size)) {
            if (hovered || peek > 0.dp) {
                val diameter = size * 0.92f
                VinylDisc(
                    release = release,
                    diameter = diameter,
                    spinning = hovered && !reducedMotion,
                    modifier = Modifier.align(Alignment.CenterStart).offset(x = size + peek - diameter),
                )
            }
            ReleaseCover(release, Modifier.size(size).clickable(onClick = onClick))
            tile.rank?.let { rank ->
                Box(
                    Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp).clip(CircleShape).background(GOLD),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$rank", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ON_GOLD)
                }
            }
            if (pinnable && (hovered || tile.pinned)) {
                Box(
                    Modifier.align(Alignment.TopStart).offset(x = size - 32.dp, y = 6.dp).size(26.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = onTogglePin),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Star,
                        contentDescription = if (tile.pinned) "Remove from top albums" else "Pin to top albums",
                        tint = if (tile.pinned) GOLD else Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        Column(Modifier.width(size), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                release.title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (tile.featured) FontWeight.SemiBold else FontWeight.Normal,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(release.year, release.kind.label.takeIf { release.kind != ReleaseKind.Album }).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The record behind the sleeve: the printed disc when a source has one (round, transparent PNG), else a
 * plain black record with a label in the release's colour. It turns while the cover is hovered.
 */
@Composable
private fun VinylDisc(release: LibraryRelease, diameter: Dp, spinning: Boolean, modifier: Modifier) {
    val angle = if (spinning) {
        val transition = rememberInfiniteTransition(label = "disc")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(5000, easing = LinearEasing)),
            label = "discAngle",
        ).value
    } else {
        0f
    }
    val printed = release.art.cdArt
    var printedOk by remember(printed) { mutableStateOf(printed != null) }
    val black = Color(0xFF0C0C0E)
    Box(
        modifier.size(diameter).graphicsLayer { rotationZ = angle }.clip(CircleShape).background(black),
        contentAlignment = Alignment.Center,
    ) {
        if (printed != null && printedOk) {
            NuvioAsyncImage(
                model = printed,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = { printedOk = false },
            )
        } else {
            Box(Modifier.fillMaxSize().padding(diameter * 0.08f).border(1.dp, Color.White.copy(alpha = 0.07f), CircleShape))
            Box(Modifier.fillMaxSize().padding(diameter * 0.2f).border(1.dp, Color.White.copy(alpha = 0.05f), CircleShape))
            Box(Modifier.size(diameter * 0.34f).clip(CircleShape).background(release.palette?.let { Color(it.accent) } ?: GOLD.copy(alpha = 0.85f)))
            Box(Modifier.size(6.dp).clip(CircleShape).background(black))
        }
    }
}
