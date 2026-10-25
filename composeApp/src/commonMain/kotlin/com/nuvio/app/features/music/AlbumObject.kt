package com.nuvio.app.features.music

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import com.nuvio.app.features.music.library.LibraryRelease

/**
 * Board 3: the album as an object. A case at a slight tilt with a disc behind it. [flipped] turns the
 * case to its printed back cover (TheAudioDB's back image when there is one, otherwise a back cover
 * drawn here from the tracklist, label, date and a barcode graphic); [discOut] slides the disc out of
 * the case, and [discSpinning] turns it.
 *
 * Every picture has a fallback: no 3D case render means the flat cover with a tilt, no back image
 * means the drawn back, no CD art means the drawn disc (MusicCdSpinner). Reduced motion snaps the
 * flip and the slide and leaves the disc still.
 *
 * The composable is `size` high and `size * (1 + peek)` wide.
 */
@Composable
internal fun AlbumObject(
    album: MusicAlbum,
    release: LibraryRelease?,
    flipped: Boolean,
    discOut: Boolean,
    discSpinning: Boolean,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 300.dp,
    peek: Float = 0.34f,
) {
    val art = release?.art
    val cover = album.thumbnailUrl ?: art?.cover
    val case3dUrl = art?.case3d
    val backUrl = art?.back
    var case3dFailed by remember(case3dUrl) { mutableStateOf(false) }
    var backFailed by remember(backUrl) { mutableStateOf(false) }
    val use3d = case3dUrl != null && !case3dFailed
    val useBackImage = backUrl != null && !backFailed
    val backCover = remember(album, release) { buildBackCover(album, release) }

    val flip by animateFloatAsState(
        targetValue = if (flipped) 180f else 0f,
        animationSpec = if (reducedMotion) snap() else tween(750, easing = FastOutSlowInEasing),
        label = "caseFlip",
    )
    // The resting tilt eases to nothing as the case turns, so the back cover is read square on.
    // A 3D render is already in perspective, so it only leans a little.
    val restingTilt = if (use3d) 6f else 20f
    val showBack = flip > 90f

    val discSize = size * 0.96f
    val out by animateDpAsState(
        targetValue = if (discOut) size * peek else size * 0.06f,
        animationSpec = if (reducedMotion) snap() else tween(650, easing = FastOutSlowInEasing),
        label = "albumDiscSlide",
    )

    Box(modifier.width(size * (1f + peek)).height(size), contentAlignment = Alignment.CenterStart) {
        MusicCdSpinner(
            coverUrl = cover,
            cdArtUrl = art?.cdArt,
            spinning = discSpinning && !reducedMotion,
            size = discSize,
            modifier = Modifier.offset(x = out + (size - discSize) / 2),
        )
        Box(
            Modifier
                .size(size)
                .graphicsLayer {
                    rotationY = flip - restingTilt * (1f - flip / 180f)
                    cameraDistance = 18f * density
                },
        ) {
            if (!showBack) {
                if (use3d) {
                    NuvioAsyncImage(
                        model = case3dUrl,
                        contentDescription = "${album.title} case",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        onError = { case3dFailed = true },
                    )
                } else {
                    MusicCover(cover, description = "${album.title} cover", size = size)
                }
            } else {
                // Turned half way round the case is mirrored; turning the face back by 180 degrees reads it right.
                Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) {
                    if (useBackImage) {
                        NuvioAsyncImage(
                            model = backUrl,
                            contentDescription = "${album.title} back cover",
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)),
                            contentScale = ContentScale.Crop,
                            onError = { backFailed = true },
                        )
                    } else {
                        GeneratedBackCover(backCover, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

/** The back cover drawn from data: title and artist, the tracklist, label and date, a barcode graphic. */
@Composable
private fun GeneratedBackCover(back: BackCover, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(colors.surfaceElevated, colors.background)))
            .border(1.dp, colors.borderDefault, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            back.title,
            color = colors.textPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (back.artist.isNotEmpty()) {
            Text(back.artist, color = colors.accent, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(6.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            back.lines.forEach { line ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        line.number.toString().padStart(2, '0'),
                        color = colors.textMuted,
                        fontSize = 10.sp,
                        modifier = Modifier.width(22.dp),
                    )
                    Text(
                        line.title,
                        color = colors.textSecondary,
                        fontSize = 10.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(line.duration, color = colors.textMuted, fontSize = 10.sp)
                }
            }
            if (back.hiddenCount > 0) {
                Text("+ ${back.hiddenCount} more", color = colors.textMuted, fontSize = 10.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                back.credit.orEmpty(),
                color = colors.textMuted,
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            Barcode(back.barcode, Modifier.width(84.dp).height(34.dp))
        }
    }
}

@Composable
private fun Barcode(bars: List<Int>, modifier: Modifier = Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(2.dp)).background(Color.White)) {
        val total = bars.sum().coerceAtLeast(1)
        val unit = size.width * 0.9f / total
        var x = size.width * 0.05f
        bars.forEachIndexed { i, w ->
            val width = w * unit
            if (i % 2 == 0) {
                drawRect(Color.Black, topLeft = Offset(x, size.height * 0.1f), size = Size(width, size.height * 0.8f))
            }
            x += width
        }
    }
}

/** An outlined pill for the album object's two toggles ("Flip to back", "Slide disc out"). */
@Composable
internal fun AlbumObjectButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(18.dp)
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = colors.textPrimary,
        modifier = modifier
            .clip(shape)
            .border(1.dp, colors.borderDefault, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/** Soft ambient backdrop for the album page: the cover, blurred and dimmed, fading into the page. */
@Composable
internal fun AlbumAmbientBackdrop(coverUrl: String?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.nuvio.colors
    if (coverUrl == null) return
    Box(modifier.fillMaxSize()) {
        NuvioAsyncImage(
            model = coverUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize().blur(72.dp).graphicsLayer { alpha = 0.38f },
            contentScale = ContentScale.Crop,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, colors.background.copy(alpha = 0.92f))),
            ),
        )
    }
}

/**
 * The liner notes: label and date, then genre chips with the mood chip in the accent, then the
 * description (four lines until opened).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AlbumLinerNotes(
    notes: LinerNotes,
    descriptionOpen: Boolean,
    onToggleDescription: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    if (notes.isEmpty) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val credit = listOfNotNull(notes.label, notes.date, "Explicit".takeIf { notes.explicit }).joinToString(" · ")
        if (credit.isNotEmpty()) {
            Text(credit, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
        }
        if (notes.genres.isNotEmpty() || notes.mood != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                notes.mood?.let { mood ->
                    val shape = RoundedCornerShape(16.dp)
                    Text(
                        mood,
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.accent,
                        modifier = Modifier
                            .clip(shape)
                            .background(colors.accent.copy(alpha = 0.14f))
                            .border(1.dp, colors.accent.copy(alpha = 0.5f), shape)
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
                notes.genres.forEach { genre ->
                    val shape = RoundedCornerShape(16.dp)
                    Text(
                        genre,
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.textPrimary,
                        modifier = Modifier.clip(shape).border(1.dp, colors.borderDefault, shape)
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
            }
        }
        notes.description?.let { text ->
            Column(
                Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onToggleDescription).padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = if (descriptionOpen) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(if (descriptionOpen) "Show less" else "Read more", style = MaterialTheme.typography.bodySmall, color = colors.accent)
            }
        }
    }
}
