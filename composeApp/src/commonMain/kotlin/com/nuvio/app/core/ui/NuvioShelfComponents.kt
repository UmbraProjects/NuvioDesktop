package com.nuvio.app.core.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

enum class NuvioPosterShape {
    Poster,
    Square,
    Landscape,
}

enum class NuvioViewAllPillSize {
    Default,
    Compact,
}

@Composable
fun <T> NuvioShelfSection(
    title: String,
    entries: List<T>,
    modifier: Modifier = Modifier,
    headerHorizontalPadding: Dp = 0.dp,
    rowContentPadding: PaddingValues = PaddingValues(0.dp),
    itemSpacing: Dp = 10.dp,
    showHeaderAccent: Boolean = true,
    onViewAllClick: (() -> Unit)? = null,
    viewAllPillSize: NuvioViewAllPillSize = NuvioViewAllPillSize.Default,
    key: ((T) -> Any)? = null,
    itemContent: @Composable (T) -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (title.isNotBlank()) {
            NuvioShelfSectionHeader(
                title = title,
                modifier = Modifier.padding(horizontal = headerHorizontalPadding),
                showAccent = showHeaderAccent,
                onViewAllClick = onViewAllClick,
                viewAllPillSize = viewAllPillSize,
            )
        }
        LazyRow(
            contentPadding = rowContentPadding,
            horizontalArrangement = Arrangement.spacedBy(itemSpacing),
        ) {
            if (key != null) {
                items(
                    items = entries,
                    key = key,
                ) { entry ->
                    itemContent(entry)
                }
            } else {
                items(entries) { entry ->
                    itemContent(entry)
                }
            }
        }
    }
}

@Composable
fun NuvioPosterCard(
    title: String,
    imageUrl: String?,
    modifier: Modifier = Modifier,
    shape: NuvioPosterShape = NuvioPosterShape.Poster,
    detailLine: String? = null,
    isWatched: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val posterCardStyle = rememberPosterCardStyleUiState()
    val cardWidth = shape.cardWidth(basePosterWidthDp = posterCardStyle.widthDp)
    val cardShape = RoundedCornerShape(posterCardStyle.cornerRadiusDp.dp)

    Column(
        modifier = modifier.width(cardWidth),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(shape.aspectRatio)
                .clip(cardShape)
                .background(MaterialTheme.colorScheme.surface)
                .posterCardClickable(onClick = onClick, onLongClick = onLongClick),
            contentAlignment = Alignment.Center,
        ) {
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = title,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    text = title,
                    modifier = Modifier.padding(horizontal = 14.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            NuvioAnimatedWatchedBadge(
                isVisible = isWatched,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!detailLine.isNullOrBlank()) {
            Text(
                text = detailLine,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Box(modifier = Modifier.height(0.dp))
        }
    }
}

@Composable
private fun NuvioShelfSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    showAccent: Boolean = true,
    onViewAllClick: (() -> Unit)? = null,
    viewAllPillSize: NuvioViewAllPillSize = NuvioViewAllPillSize.Default,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showAccent) {
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .width(60.dp)
                        .height(4.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(999.dp),
                    ),
                )
            }
        }
        if (onViewAllClick != null) {
            NuvioViewAllPill(
                onClick = onViewAllClick,
                size = viewAllPillSize,
            )
        }
    }
}

@Composable
private fun NuvioViewAllPill(
    onClick: (() -> Unit)?,
    size: NuvioViewAllPillSize,
) {
    val colorScheme = MaterialTheme.colorScheme
    val isAmoled = colorScheme.background == androidx.compose.ui.graphics.Color.Black && colorScheme.surface == androidx.compose.ui.graphics.Color(0xFF050505)
    val horizontalPadding = if (size == NuvioViewAllPillSize.Compact) 12.dp else 18.dp
    val verticalPadding = if (size == NuvioViewAllPillSize.Compact) 9.dp else 14.dp
    val textStyle = if (size == NuvioViewAllPillSize.Compact) {
        MaterialTheme.typography.labelLarge
    } else {
        MaterialTheme.typography.titleMedium
    }
    val iconSpacing = if (size == NuvioViewAllPillSize.Compact) 2.dp else 4.dp

    Row(
        modifier = Modifier
            .background(
                color = if (isAmoled) androidx.compose.ui.graphics.Color(0xFF0D0D0D) else colorScheme.surface,
                shape = RoundedCornerShape(20.dp),
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        horizontalArrangement = Arrangement.spacedBy(iconSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "View All",
            style = textStyle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.height(if (size == NuvioViewAllPillSize.Compact) 16.dp else 20.dp),
        )
    }
}

private val NuvioPosterShape.aspectRatio: Float
    get() = when (this) {
        NuvioPosterShape.Poster -> 0.675f
        NuvioPosterShape.Square -> 1f
        NuvioPosterShape.Landscape -> 1.77f
    }

private const val LandscapeWidthScale = 180f / 110f

private fun NuvioPosterShape.cardWidth(basePosterWidthDp: Int): Dp =
    when (this) {
        NuvioPosterShape.Poster -> basePosterWidthDp.dp
        NuvioPosterShape.Square -> basePosterWidthDp.dp
        NuvioPosterShape.Landscape -> (basePosterWidthDp * LandscapeWidthScale).dp
    }

@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.posterCardClickable(
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
): Modifier =
    if (onClick != null || onLongClick != null) {
        combinedClickable(
            onClick = { onClick?.invoke() },
            onLongClick = onLongClick,
        )
    } else {
        this
    }
