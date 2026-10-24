package com.nuvio.app.features.streams

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.app.core.i18n.localizedByteUnit
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.debrid.DebridProviders
import kotlin.math.round
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.streams_score_rejected
import nuvio.composeapp.generated.resources.streams_season_pack_indicator
import org.jetbrains.compose.resources.stringResource

internal enum class StreamCardStyle {
    /** Dark card that holds up over any backdrop: the full-screen list and the player's lists. */
    Solid,

    /** Docked in the details hero over an already darkened, frosted backdrop: barely a card. */
    Glass,
}

internal val LocalStreamCardStyle = staticCompositionLocalOf { StreamCardStyle.Solid }

/**
 * The glass surface levels, shared by the cards and everything around them in the docked picker
 * (chips, sort, buttons) so the whole panel reads as one material: a faint lift at rest, brighter
 * with a light edge when selected or focused.
 */
internal object StreamGlass {
    val Rest = Color.White.copy(alpha = 0.045f)
    val Raised = Color.White.copy(alpha = 0.13f)
    val Edge = Color.White.copy(alpha = 0.7f)
}

/**
 * Watch party: a label for streams to mark like the playing one ("Party's source"), or null. Set
 * by the picker session while the party watches the title being picked for.
 */
internal val LocalStreamHighlight = staticCompositionLocalOf<(StreamItem) -> String?> { { null } }

/** Watch party: the host's shared stream, offered above the list (see [LocalSharedStream]). */
internal data class SharedStream(
    val label: String,
    val stream: StreamItem,
)

internal val LocalSharedStream = staticCompositionLocalOf<SharedStream?> { null }

@Composable
internal fun StreamCard(
    stream: StreamItem,
    enabled: Boolean,
    appendInstantServiceToDefaultName: Boolean,
    showFileSizeBadges: Boolean,
    showAddonLogo: Boolean,
    badgePlacement: StreamBadgePlacement,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    currentLabel: String? = null,
    focused: Boolean = false,
    scoreContext: StreamScoreContext,
) {
    val highlightLabel = if (isCurrent) null else LocalStreamHighlight.current(stream)
    val marked = isCurrent || highlightLabel != null
    val markedLabel = if (isCurrent) currentLabel else highlightLabel
    val cardShape = RoundedCornerShape(12.dp)
    // Diagnostic overlay, opt-in from the scoring settings page. Computed here rather than passed
    // in so all three source lists (picker, player sources, player episodes) get it for free.
    val scoreProfile by StreamScoreRepository.uiState.collectAsState()
    val score = remember(stream, scoreProfile, scoreContext) {
        // isScorableStream: an addon's diagnostics/age-rating/"notify me" row is not a release,
        // so it gets no badge rather than a meaningless +0.
        if (scoreProfile.enabled && scoreProfile.showScoreOnStreams && stream.isScorableStream) {
            StreamScorer.score(stream, scoreProfile, scoreContext)
        } else {
            null
        }
    }
    // Marks rows detected as season packs. Deliberately narrower than the menu action, which is
    // offered on anything inspectable: the icon's job is to say "this one is a pack", so gating it
    // on mere inspectability would light up every row and mean nothing. Detected packs are a subset
    // of inspectable rows, so the icon still never promises an action the menu withholds.
    val rowActions = LocalStreamRowActions.current
    val seasonPack = remember(stream, rowActions?.browsedSeason, rowActions?.isEpisodeView) {
        if (rowActions?.onDownloadSeason == null) return@remember null
        val traits = StreamTraitDetector.detect(stream)
        val offersRoute = stream.offersSeasonPackRoute(
            traits = traits,
            browsedSeason = rowActions.browsedSeason,
            isEpisodeView = rowActions.isEpisodeView,
        )
        // The pack's own size rides along with the icon. Null on rows where no addon published one
        // — an absent number is better than the file's size relabelled as the folder's.
        if (offersRoute) SeasonPackIndicatorState(traits.packSizeBytes) else null
    }
    val badgeImages = stream.badges.filter { it.imageURL.isNotBlank() }
    val hasBadges = badgeImages.isNotEmpty() || (showFileSizeBadges && stream.behaviorHints.videoSize != null)
    val glass = LocalStreamCardStyle.current == StreamCardStyle.Glass
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .then(
                if (glass) {
                    Modifier
                } else {
                    Modifier.shadow(
                        elevation = 2.dp,
                        shape = cardShape,
                        ambientColor = Color.Black.copy(alpha = 0.04f),
                        spotColor = Color.Black.copy(alpha = 0.04f),
                    )
                },
            )
            .clip(cardShape)
            // Solid: an opaque-enough dark scrim so the light card text stays legible even when the
            // card sits over a busy, blurred backdrop (a near-transparent white tint washed out
            // against bright artwork). Glass only sits on a backdrop that is already frosted and
            // dimmed, where that scrim turned the list into a stack of boxes: a faint lift instead,
            // brighter on the focused row. The current-item highlight is layered on top either way.
            .background(
                when {
                    !glass -> Color.Black.copy(alpha = 0.42f)
                    focused -> StreamGlass.Raised
                    else -> StreamGlass.Rest
                },
            )
            .then(
                if (marked) {
                    Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                } else {
                    Modifier
                },
            )
            .then(
                if (marked) {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.52f),
                        shape = cardShape,
                    )
                } else {
                    Modifier
                },
            )
            .then(
                if (focused && glass) {
                    // The episode cards' focus treatment rather than the accent ring.
                    Modifier.border(
                        width = 1.5.dp,
                        color = StreamGlass.Edge,
                        shape = cardShape,
                    )
                } else if (focused) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = cardShape,
                    )
                } else {
                    Modifier
                },
            )
            .clickSwallowedByScrollingList(enabled = enabled, onClick = onClick)
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (hasBadges && badgePlacement == StreamBadgePlacement.TOP) {
                StreamCardBadgeRow(
                    badgeImages = badgeImages,
                    stream = stream,
                    showFileSizeBadges = showFileSizeBadges,
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            StreamNameWithInstantService(
                stream = stream,
                appendInstantServiceToDefaultName = appendInstantServiceToDefaultName,
            ) {
                if (marked && !markedLabel.isNullOrBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    CurrentStreamBadge(label = markedLabel)
                }
            }

            val subtitle = stream.streamSubtitle
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (hasBadges && badgePlacement == StreamBadgePlacement.BOTTOM) {
                Spacer(modifier = Modifier.height(5.dp))
                StreamCardBadgeRow(
                    badgeImages = badgeImages,
                    stream = stream,
                    showFileSizeBadges = showFileSizeBadges,
                )
            }
        }

        if (score != null || seasonPack != null) {
            Spacer(modifier = Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                score?.let { StreamScoreBadge(it) }
                if (seasonPack != null) {
                    if (score != null) Spacer(modifier = Modifier.height(4.dp))
                    SeasonPackIndicator(sizeBytes = seasonPack.sizeBytes)
                }
            }
        }

        if (showAddonLogo) {
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!stream.addonLogo.isNullOrBlank()) {
                    AsyncImage(
                        model = stream.addonLogo,
                        contentDescription = stream.addonName,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp)),
                        contentScale = ContentScale.Fit,
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stream.addonName,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Shared by the score chip and the pack indicator, which stack in one column and are meant to read
 * as a pair — changing one alone would break that.
 */
private val ScoreBadgeBackground = Color.Black.copy(alpha = 0.62f)

/**
 * The stream's score, tinted by sign so a long list can be scanned at a glance. A rejected stream
 * (below the profile's minimum) is called out explicitly — otherwise a large negative number looks
 * the same as a merely unpopular one, and the whole point of showing this is spotting why something
 * did or didn't get picked.
 */
@Composable
private fun StreamScoreBadge(score: StreamScore) {
    val tokens = MaterialTheme.nuvio
    val color = when {
        score.rejected -> MaterialTheme.colorScheme.error
        score.total > 0 -> tokens.colors.accent
        score.total < 0 -> MaterialTheme.colorScheme.error
        else -> tokens.colors.textMuted
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                // Black rather than a tint of the score's own colour: tinting put a red number on a
                // red wash (and an accent number on an accent wash), which is the one pairing that
                // costs contrast instead of adding it. The number carries the meaning; the chip
                // just has to get out of its way.
                .background(ScoreBadgeBackground)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(
                text = if (score.total > 0) "+${score.total}" else score.total.toString(),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                fontWeight = FontWeight.Bold,
                color = color,
                maxLines = 1,
            )
        }
        if (score.rejected) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(Res.string.streams_score_rejected),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
            )
        }
    }
}

/** What the pack indicator has to say about a row: that it is one, and how big it is. */
private data class SeasonPackIndicatorState(val sizeBytes: Long?)

/**
 * Marks a row whose backing torrent holds a whole season, so the pack route is available on it, and
 * — where an addon published it — how large that whole torrent is. The size is the deciding number
 * before opening the pack: the file size on the card describes one episode, and a viewer choosing
 * whether to grab the season needs the other one.
 *
 * Shape-matched to [StreamScoreBadge] — same corner radius, same tinted-surface treatment — because
 * the two stack in one column and reading as a pair is the point. Muted rather than accented: this
 * says a capability exists, it is not a recommendation.
 */
@Composable
private fun SeasonPackIndicator(sizeBytes: Long?) {
    val color = MaterialTheme.nuvio.colors.textMuted
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(ScoreBadgeBackground)
                .padding(horizontal = 6.dp, vertical = 3.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Layers,
                contentDescription = stringResource(Res.string.streams_season_pack_indicator),
                tint = color,
                modifier = Modifier.size(14.dp),
            )
        }
        if (sizeBytes != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = formatPackSize(sizeBytes),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = color,
                maxLines = 1,
            )
        }
    }
}

/**
 * Whole GB above a gigabyte, whole MB below — this sits under a 14 dp icon in a column beside the
 * score, so a decimal place would cost width the layout does not have and precision nobody reads a
 * pack size for.
 */
private fun formatPackSize(bytes: Long): String {
    val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    if (gib >= 1.0) return "${round(gib).toInt()} ${localizedByteUnit("GB")}"
    val mib = bytes.toDouble() / (1024.0 * 1024.0)
    return "${round(mib).toInt()} ${localizedByteUnit("MB")}"
}

@Composable
private fun StreamCardBadgeRow(
    badgeImages: List<StreamBadge>,
    stream: StreamItem,
    showFileSizeBadges: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        badgeImages.forEach { badge ->
            StreamBadgeImage(badge = badge)
        }
        if (showFileSizeBadges) {
            StreamFileSizeBadge(stream = stream)
        }
    }
}

@Composable
private fun StreamNameWithInstantService(
    stream: StreamItem,
    appendInstantServiceToDefaultName: Boolean,
    trailingContent: @Composable RowScope.() -> Unit = {},
) {
    val nameStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    )
    val instantLabel = if (appendInstantServiceToDefaultName) {
        stream.instantServiceLabel()
    } else {
        null
    }
    val showInstantLabel = instantLabel != null
    val visibleState = remember(stream.streamLabel) {
        MutableTransitionState(showInstantLabel)
    }
    visibleState.targetState = showInstantLabel

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stream.streamLabel,
            modifier = Modifier.weight(1f, fill = false),
            style = nameStyle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        AnimatedVisibility(
            visibleState = visibleState,
            enter = fadeIn(animationSpec = tween(durationMillis = 260)) +
                expandHorizontally(
                    animationSpec = tween(durationMillis = 260),
                    expandFrom = Alignment.Start,
                ),
            exit = fadeOut(animationSpec = tween(durationMillis = 120)) +
                shrinkHorizontally(
                    animationSpec = tween(durationMillis = 120),
                    shrinkTowards = Alignment.Start,
                ),
            label = "streamNameInstantService",
        ) {
            Text(
                text = " ${instantLabel.orEmpty()}",
                style = nameStyle,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        trailingContent()
    }
}

@Composable
private fun CurrentStreamBadge(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun StreamItem.instantServiceLabel(): String? {
    val status = debridCacheStatus ?: return null
    if (status.state != StreamDebridCacheState.CACHED) return null
    val providerLabel = DebridProviders.shortName(status.providerId)
        .ifBlank { status.providerName.trim() }
        .ifBlank { DebridProviders.displayName(status.providerId) }
    return "- $providerLabel Instant"
}

/**
 * A lazy list that is still scrolling (the desktop wheel animates each notch for a few hundred ms)
 * consumes the next press on the Initial pass to "catch" the fling — before it checks whether a
 * mouse can drag it at all, which it can't. The card's own clickable then sees a consumed down and
 * ignores it, so a click made right after scrolling did nothing and needed a second one. This picks
 * up exactly those presses (already consumed when they reach the card, primary button) and clicks on
 * release inside the card. A normal press arrives unconsumed and is left to [combinedClickable].
 */
private fun Modifier.clickSwallowedByScrollingList(
    enabled: Boolean,
    onClick: () -> Unit,
): Modifier = if (!enabled) this else composed {
    val currentOnClick by rememberUpdatedState(onClick)
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!down.isConsumed) return@awaitEachGesture
            if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes
                    .firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (change.isOutOfBounds(size, extendedTouchPadding)) return@awaitEachGesture
                if (!change.pressed) {
                    change.consume()
                    currentOnClick()
                    return@awaitEachGesture
                }
            }
        }
    }
}
