package com.nuvio.app.features.details.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.NuvioAsyncImage as AsyncImage
import com.nuvio.app.core.ui.desktopHorizontalListNavigation
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.nuvio.app.features.details.MetaPerson
import com.nuvio.app.core.ui.NuvioCardDepthSurface
import com.nuvio.app.core.ui.nuvioCardDepth
import com.nuvio.app.core.ui.smoothBorder
import com.nuvio.app.features.details.castAvatarSharedTransitionKey
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
fun DetailCastSection(
    cast: List<MetaPerson>,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
    visibleItemCount: Int? = null,
    onCastClick: ((MetaPerson, String?) -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    // True in the fixed-height desktop hero overlay, which never scrolls vertically, so a
    // plain mouse wheel over this row can scroll it horizontally without requiring Shift.
    // False in the regular (tablet/mobile/tab-layout) vertically-scrolling section list.
    compactDesktopLayout: Boolean = false,
    /** Characters view: TVDB's portrait and the character's name, the actor's name beneath. */
    showCharacters: Boolean = false,
    /** Keeps the episode-count line's space even before (or without) counts, so the row can't grow. */
    reserveEpisodeLine: Boolean = false,
    /** False drops the episode-count line, reserved space and all (see [CastEpisodeCountVisibility]). */
    showEpisodeCounts: Boolean = true,
    /** Characters view on and its portraits still being looked up: blank circles, no actor photos. */
    portraitsPending: Boolean = false,
) {
    if (cast.isEmpty()) return

    DetailSection(
        title = stringResource(Res.string.settings_meta_cast),
        modifier = modifier,
        showHeader = showHeader,
    ) {
        BoxWithConstraints {
            val sizing = castSectionSizing(
                maxWidthDp = maxWidth.value,
                visibleItemCount = visibleItemCount,
            )
            val rowState = rememberLazyListState()

            LazyRow(
                state = rowState,
                modifier = Modifier
                    .fillMaxWidth()
                    .desktopHorizontalListNavigation(
                        rowState,
                        treatPlainScrollAsHorizontal = compactDesktopLayout,
                    ),
                horizontalArrangement = Arrangement.spacedBy(sizing.avatarGap),
            ) {
                itemsIndexed(
                    items = cast,
                    key = { index, person -> "${person.name}-${person.role.orEmpty()}-${person.photo.orEmpty()}-$index" },
                ) { index, person ->
                    val sharedTransitionKey = person.tmdbId
                        ?.takeIf { it > 0 }
                        ?.let { castAvatarSharedTransitionKey(it, occurrenceIndex = index) }
                    CastItem(
                        person = person,
                        asCharacter = showCharacters && !person.characterImage.isNullOrBlank(),
                        portraitPending = portraitsPending,
                        reserveEpisodeLine = reserveEpisodeLine,
                        showEpisodeCount = showEpisodeCounts,
                        sharedTransitionKey = sharedTransitionKey,
                        sizing = sizing,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        // No TMDB id (addon-only crew): the handler looks the name up instead.
                        onClick = if (onCastClick != null && person.name.isNotBlank()) {
                            { onCastClick(person, sharedTransitionKey) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
private fun CastItem(
    person: MetaPerson,
    modifier: Modifier = Modifier,
    asCharacter: Boolean = false,
    portraitPending: Boolean = false,
    reserveEpisodeLine: Boolean = false,
    showEpisodeCount: Boolean = true,
    sharedTransitionKey: String? = null,
    sizing: CastSectionSizing,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    onClick: (() -> Unit)? = null,
) {
    val avatarCacheKey = sharedTransitionKey
    val platformContext = LocalPlatformContext.current
    val avatarRequest = if (!person.photo.isNullOrBlank() && !avatarCacheKey.isNullOrBlank()) {
        remember(platformContext, person.photo, avatarCacheKey) {
            ImageRequest.Builder(platformContext)
                .data(person.photo)
                .memoryCacheKey(avatarCacheKey)
                .placeholderMemoryCacheKey(avatarCacheKey)
                .diskCacheKey(person.photo)
                .build()
        }
    } else {
        null
    }

    val avatarSharedElementModifier = if (
        sharedTransitionScope != null &&
            animatedVisibilityScope != null &&
            !sharedTransitionKey.isNullOrBlank()
    ) {
        with(sharedTransitionScope) {
            Modifier.sharedElement(
                sharedContentState = rememberSharedContentState(
                    key = sharedTransitionKey,
                ),
                animatedVisibilityScope = animatedVisibilityScope,
            )
        }
    } else {
        Modifier
    }

    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val interactive = onClick != null
    val avatarScale by animateFloatAsState(
        targetValue = when {
            interactive && pressed -> 0.95f
            interactive && hovered -> 1.06f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = if (pressed) Spring.StiffnessHigh else Spring.StiffnessMedium,
        ),
        label = "cast_avatar_scale",
    )
    val hoverRingAlpha by animateFloatAsState(
        targetValue = if (interactive && hovered) 1f else 0f,
        animationSpec = tween(140),
        label = "cast_avatar_ring",
    )

    Column(
        modifier = modifier
            .width(sizing.itemWidth)
            .then(
                if (onClick != null) {
                    Modifier
                        .hoverable(interactionSource)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onClick,
                        )
                } else {
                    Modifier
                },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .then(avatarSharedElementModifier)
                .size(sizing.avatarSize)
                .graphicsLayer {
                    scaleX = avatarScale
                    scaleY = avatarScale
                }
                .then(
                    if (hoverRingAlpha > 0f) {
                        Modifier.smoothBorder(
                            width = 1.5.dp,
                            color = Color.White.copy(alpha = 0.45f * hoverRingAlpha),
                            shape = CircleShape,
                        )
                    } else {
                        Modifier
                    },
                )
                .nuvioCardDepth(CircleShape, NuvioCardDepthSurface.Cast)
                .clip(CircleShape)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (portraitPending) {
                // The circle's own background only, until the character portraits are known.
            } else if (asCharacter) {
                AsyncImage(
                    model = person.characterImage,
                    contentDescription = person.role ?: person.name,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                )
            } else if (person.photo != null) {
                AsyncImage(
                    model = avatarRequest ?: person.photo,
                    contentDescription = person.name,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    text = person.name.initials(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        val primaryName = if (asCharacter) person.role?.takeIf { it.isNotBlank() } ?: person.name else person.name
        val secondaryName = if (asCharacter) person.name else person.role
        Text(
            text = primaryName,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = sizing.nameLabelSize,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!secondaryName.isNullOrBlank()) {
            Text(
                text = secondaryName,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = sizing.subLabelSize,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val episodeCount = person.episodeCount?.takeIf { it > 0 }
        if (showEpisodeCount && (episodeCount != null || reserveEpisodeLine)) {
            Text(
                text = when (episodeCount) {
                    null -> " "
                    1 -> "1 episode"
                    else -> "$episodeCount episodes"
                },
                // One line that may run a little into the gap: wrapped, a count a hair too wide for the column showed
                // only its first word ("5003", with "episodes" on a second line maxLines dropped) (2026-11-24).
                modifier = Modifier.fillMaxWidth().wrapContentWidth(unbounded = true),
                softWrap = false,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = sizing.subLabelSize,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

private data class CastSectionSizing(
    val avatarSize: androidx.compose.ui.unit.Dp,
    val itemWidth: androidx.compose.ui.unit.Dp,
    val avatarGap: androidx.compose.ui.unit.Dp,
    val nameLabelSize: TextUnit,
    val subLabelSize: TextUnit,
)

private fun castSectionSizing(
    maxWidthDp: Float,
    visibleItemCount: Int? = null,
): CastSectionSizing {
    if (visibleItemCount != null && visibleItemCount > 0 && maxWidthDp > 0f) {
        val gap = 16.dp
        val itemWidthDp = ((maxWidthDp - gap.value * (visibleItemCount - 1)) / visibleItemCount)
            .coerceAtLeast(72f)
        val avatarSizeDp = itemWidthDp.coerceAtMost(80f)
        return CastSectionSizing(
            avatarSize = avatarSizeDp.dp,
            itemWidth = itemWidthDp.dp,
            avatarGap = gap,
            nameLabelSize = 14.sp,
            subLabelSize = 12.sp,
        )
    }

    return when {
        maxWidthDp >= 1200f -> CastSectionSizing(
            avatarSize = 100.dp,
            itemWidth = 112.dp,
            avatarGap = 20.dp,
            nameLabelSize = 16.sp,
            subLabelSize = 14.sp,
        )
        maxWidthDp >= 840f -> CastSectionSizing(
            avatarSize = 90.dp,
            itemWidth = 102.dp,
            avatarGap = 18.dp,
            nameLabelSize = 15.sp,
            subLabelSize = 13.sp,
        )
        maxWidthDp >= 600f -> CastSectionSizing(
            avatarSize = 85.dp,
            itemWidth = 98.dp,
            avatarGap = 16.dp,
            nameLabelSize = 14.sp,
            subLabelSize = 12.sp,
        )
        else -> CastSectionSizing(
            avatarSize = 80.dp,
            itemWidth = 92.dp,
            avatarGap = 16.dp,
            nameLabelSize = 14.sp,
            subLabelSize = 12.sp,
        )
    }
}

private fun String.initials(): String {
    val parts = trim().split(" ").filter { it.isNotBlank() }
    return when {
        parts.size >= 2 -> "${parts.first().first().uppercaseChar()}${parts.last().first().uppercaseChar()}"
        parts.size == 1 -> parts.first().take(2).uppercase()
        else -> ""
    }
}
