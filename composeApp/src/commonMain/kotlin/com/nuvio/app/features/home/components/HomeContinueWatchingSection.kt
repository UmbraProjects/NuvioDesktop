package com.nuvio.app.features.home.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioAsyncImage as AsyncImage
import com.nuvio.app.core.ui.NuvioProgressBar
import com.nuvio.app.core.ui.NuvioCardDepthSurface
import com.nuvio.app.core.ui.nuvioCardDepth
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.core.ui.PosterLandscapeAspectRatio
import com.nuvio.app.core.ui.ExtraLargePosterCardWidthDp
import com.nuvio.app.core.ui.landscapePosterHeightForWidth
import com.nuvio.app.core.ui.landscapePosterWidth
import com.nuvio.app.core.ui.posterCardClickable
import com.nuvio.app.core.ui.rememberHomePosterCardStyleUiState
import com.nuvio.app.features.cloud.CloudLibraryContentType
import com.nuvio.app.features.cloud.cloudLibraryDisplayArtworkUrl
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingArtworkDiagnostics
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watchprogress.computeAirDateBadgeText
import kotlin.math.roundToInt
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

private val ContinueWatchingStatusBadgeShape = RoundedCornerShape(4.dp)
private val ContinueWatchingNewEpisodeBadgeColor = Color(0xFF1D4ED8)
private val ContinueWatchingNewSeasonBadgeColor = Color(0xFFB45309)
private const val ContinueWatchingLandscapeCardScale = 1.2f

internal fun continueWatchingLandscapeCardWidth(basePosterWidthDp: Int): Dp =
    (landscapePosterWidth(basePosterWidthDp).value * ContinueWatchingLandscapeCardScale).dp

internal fun continueWatchingLandscapeCardHeight(basePosterWidthDp: Int): Dp =
    landscapePosterHeightForWidth(continueWatchingLandscapeCardWidth(basePosterWidthDp))

private fun continueWatchingProgressPercent(progressFraction: Float): Int =
    (progressFraction * 100f).roundToInt().coerceIn(1, 99)

@Composable
private fun localizedContinueWatchingMetaLine(item: ContinueWatchingItem): String =
    when {
        item.seasonNumber != null && item.episodeNumber != null ->
            stringResource(Res.string.compose_player_episode_code_full, item.seasonNumber, item.episodeNumber)
        item.isCloudLibraryItem() ->
            stringResource(Res.string.library_source_cloud)
        else ->
            stringResource(Res.string.media_movie)
    }

private fun ContinueWatchingItem.isCloudLibraryItem(): Boolean =
    parentMetaType.equals(CloudLibraryContentType, ignoreCase = true)

private fun ContinueWatchingItem.continueWatchingArtworkUrls(
    useEpisodeThumbnails: Boolean,
): List<String> = when {
    isNextUp && useEpisodeThumbnails -> artworkChain(
        episodeThumbnail,
        poster,
        background,
        imageUrl,
    )
    isNextUp -> artworkChain(
        poster,
        background,
        episodeThumbnail,
        imageUrl,
    )
    useEpisodeThumbnails -> artworkChain(
        episodeThumbnail,
        poster,
        background,
        imageUrl,
    )
    else -> artworkChain(
        poster,
        background,
        episodeThumbnail,
        imageUrl,
    )
}

private fun ContinueWatchingItem.continueWatchingPosterArtworkUrls(
    useEpisodeThumbnails: Boolean,
): List<String> {
    if (seasonNumber == null || episodeNumber == null) {
        return continueWatchingArtworkUrls(useEpisodeThumbnails)
    }

    val normalizedEpisodeThumbnail = episodeThumbnail?.trim()?.takeIf { it.isNotBlank() }
    val nonEpisodeImageUrl = imageUrl
        ?.trim()
        ?.takeIf { it.isNotBlank() && it != normalizedEpisodeThumbnail }

    return artworkChain(
        poster,
        background,
        nonEpisodeImageUrl,
        if (useEpisodeThumbnails) episodeThumbnail else null,
        imageUrl,
    )
}

private fun ContinueWatchingItem.continueWatchingCardArtworkUrls(
    useEpisodeThumbnails: Boolean,
    preferBackdropForNextUp: Boolean,
): List<String> = when {
    isNextUp && preferBackdropForNextUp -> artworkChain(
        background,
        poster,
        episodeThumbnail,
        imageUrl,
    )
    isNextUp && useEpisodeThumbnails -> artworkChain(
        episodeThumbnail,
        background,
        poster,
        imageUrl,
    )
    isNextUp -> artworkChain(
        background,
        poster,
        episodeThumbnail,
        imageUrl,
    )
    useEpisodeThumbnails -> artworkChain(
        episodeThumbnail,
        background,
        poster,
        imageUrl,
    )
    else -> artworkChain(
        background,
        poster,
        episodeThumbnail,
        imageUrl,
    )
}

/**
 * The preferred artwork first, then everything else the card could fall back to. Only the head of
 * the chain was ever used before; a URL that 404s or fails to decode left the tile empty even
 * though a perfectly good poster sat in the same item.
 */
private fun artworkChain(vararg values: String?): List<String> = values
    .mapNotNull { value -> value?.trim()?.takeIf(String::isNotBlank) }
    .distinct()

private fun firstNonBlank(vararg values: String?): String? =
    values.firstOrNull { value -> !value.isNullOrBlank() }?.trim()

/**
 * Artwork URLs that failed to load this session.
 *
 * A Continue Watching card is the one surface in the app where a dead artwork URL leaves nothing
 * at all on screen — every other card draws its title over the image. Episode stills are also the
 * most fragile artwork the app handles: providers publish them late, swap CDN hosts, and hand back
 * URLs for images that were never uploaded. Remembering the failures lets every card for the same
 * show skip straight to the fallback instead of each one re-requesting the dead URL.
 *
 * Session-scoped on purpose: a URL that failed because the network was down deserves a fresh try
 * on the next launch.
 */
private object ContinueWatchingArtworkFailures {
    private val failedUrls = mutableStateMapOf<String, Unit>()

    fun hasFailed(url: String): Boolean = url in failedUrls

    fun markFailed(url: String) {
        if (failedUrls.put(url, Unit) == null) {
            ContinueWatchingArtworkDiagnostics.logArtworkLoadFailure(url)
        }
    }
}

/**
 * The first URL in [candidates] that has not already failed to load, plus the callback that
 * retires it when it fails too. Returns null only once every candidate is exhausted.
 */
@Composable
private fun rememberContinueWatchingArtwork(candidates: List<String>): Pair<String?, () -> Unit> {
    val url = candidates.firstOrNull { candidate ->
        !ContinueWatchingArtworkFailures.hasFailed(candidate)
    }
    return url to remember(url) {
        { url?.let(ContinueWatchingArtworkFailures::markFailed) ?: Unit }
    }
}

@Composable
internal fun HomeContinueWatchingSection(
    items: List<ContinueWatchingItem>,
    title: String? = null,
    style: ContinueWatchingSectionStyle,
    useEpisodeThumbnails: Boolean = true,
    blurNextUp: Boolean = false,
    modifier: Modifier = Modifier,
    sectionPadding: Dp? = null,
    layout: ContinueWatchingLayout? = null,
    basePosterWidthDpOverride: Int? = null,
    focusedItemIndex: Int? = null,
    rowState: androidx.compose.foundation.lazy.LazyListState? = null,
    onHoverItem: ((Int) -> Unit)? = null,
    isKeyboardNavigation: Boolean = false,
    // TV Mode's row-jump dots, rendered on the header line next to the title. Null everywhere else.
    headerTrailingContent: (@Composable () -> Unit)? = null,
    onItemClick: ((ContinueWatchingItem) -> Unit)? = null,
    onItemLongPress: ((ContinueWatchingItem) -> Unit)? = null,
) {
    if (items.isEmpty()) return
    val effectiveRowState = rowState ?: androidx.compose.foundation.lazy.rememberLazyListState()

    if (sectionPadding != null && layout != null) {
        HomeContinueWatchingSectionContent(
            items = items,
            title = title,
            style = style,
            useEpisodeThumbnails = useEpisodeThumbnails,
            blurNextUp = blurNextUp,
            modifier = modifier.fillMaxWidth(),
            sectionPadding = sectionPadding,
            layout = layout,
            basePosterWidthDpOverride = basePosterWidthDpOverride,
            focusedItemIndex = focusedItemIndex,
            rowState = effectiveRowState,
            onHoverItem = onHoverItem,
            isKeyboardNavigation = isKeyboardNavigation,
            headerTrailingContent = headerTrailingContent,
            onItemClick = onItemClick,
            onItemLongPress = onItemLongPress,
        )
    } else {
        BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
            HomeContinueWatchingSectionContent(
                items = items,
                title = title,
                style = style,
                useEpisodeThumbnails = useEpisodeThumbnails,
                blurNextUp = blurNextUp,
                modifier = Modifier.fillMaxWidth(),
                sectionPadding = homeSectionHorizontalPaddingForWidth(maxWidth.value),
                layout = rememberContinueWatchingLayout(maxWidth.value),
                basePosterWidthDpOverride = basePosterWidthDpOverride,
                focusedItemIndex = focusedItemIndex,
                rowState = effectiveRowState,
                onHoverItem = onHoverItem,
                isKeyboardNavigation = isKeyboardNavigation,
                headerTrailingContent = headerTrailingContent,
                onItemClick = onItemClick,
                onItemLongPress = onItemLongPress,
            )
        }
    }
}

@Composable
private fun HomeContinueWatchingSectionContent(
    items: List<ContinueWatchingItem>,
    title: String?,
    style: ContinueWatchingSectionStyle,
    useEpisodeThumbnails: Boolean,
    blurNextUp: Boolean,
    modifier: Modifier,
    sectionPadding: Dp,
    layout: ContinueWatchingLayout,
    basePosterWidthDpOverride: Int?,
    focusedItemIndex: Int?,
    rowState: androidx.compose.foundation.lazy.LazyListState,
    onHoverItem: ((Int) -> Unit)?,
    isKeyboardNavigation: Boolean,
    headerTrailingContent: (@Composable () -> Unit)?,
    onItemClick: ((ContinueWatchingItem) -> Unit)?,
    onItemLongPress: ((ContinueWatchingItem) -> Unit)?,
) {
    val homeCatalogSettings by remember {
        HomeCatalogSettingsRepository.snapshot()
        HomeCatalogSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val posterCardStyle = rememberHomePosterCardStyleUiState()
    val effectiveStyle = effectiveContinueWatchingStyle(
        requestedStyle = style,
        tvModeEnabled = homeCatalogSettings.tvModeEnabled,
        catalogLandscapeModeEnabled = posterCardStyle.catalogLandscapeModeEnabled,
    )

    val itemOrderKey = remember(items) {
        items.joinToString(separator = "|") { item -> item.continueWatchingRowOrderKey() }
    }

    key(itemOrderKey) {
        NuvioShelfSection(
            title = title ?: stringResource(Res.string.compose_settings_page_continue_watching),
            entries = items,
            modifier = modifier,
            headerHorizontalPadding = sectionPadding,
            rowContentPadding = PaddingValues(horizontal = sectionPadding),
            itemSpacing = layout.itemGap,
            showHeaderAccent = !homeCatalogSettings.hideCatalogUnderline,
            focusedItemIndex = focusedItemIndex,
            onHoverItem = onHoverItem,
            isKeyboardNavigation = isKeyboardNavigation,
            headerTrailingContent = headerTrailingContent,
            key = { item -> item.videoId },
            rowState = rowState,
        ) { item ->
            when (effectiveStyle) {
                ContinueWatchingSectionStyle.Card -> ContinueWatchingCard(
                    item = item,
                    basePosterWidthDpOverride = basePosterWidthDpOverride,
                    useEpisodeThumbnails = useEpisodeThumbnails,
                    blurNextUp = blurNextUp,
                    onClick = onItemClick?.let { { it(item) } },
                    onLongClick = onItemLongPress?.let { { it(item) } },
                )
                ContinueWatchingSectionStyle.Wide -> ContinueWatchingWideCard(
                    item = item,
                    layout = layout,
                    useEpisodeThumbnails = useEpisodeThumbnails,
                    blurNextUp = blurNextUp,
                    onClick = onItemClick?.let { { it(item) } },
                    onLongClick = onItemLongPress?.let { { it(item) } },
                )
                ContinueWatchingSectionStyle.Poster -> ContinueWatchingPosterCard(
                    item = item,
                    layout = layout,
                    useEpisodeThumbnails = useEpisodeThumbnails,
                    blurNextUp = blurNextUp,
                    onClick = onItemClick?.let { { it(item) } },
                    onLongClick = onItemLongPress?.let { { it(item) } },
                )
            }
        }
    }
}

private val TvModeContinueWatchingStyles = setOf(
    ContinueWatchingSectionStyle.Card,
    ContinueWatchingSectionStyle.Wide,
    ContinueWatchingSectionStyle.Poster,
)

/** TV Mode respects the saved choice unless its shorter landscape shelf requires landscape cards. */
internal fun effectiveContinueWatchingStyle(
    requestedStyle: ContinueWatchingSectionStyle,
    tvModeEnabled: Boolean,
    catalogLandscapeModeEnabled: Boolean,
): ContinueWatchingSectionStyle =
    when {
        tvModeEnabled && catalogLandscapeModeEnabled -> ContinueWatchingSectionStyle.Card
        !tvModeEnabled || requestedStyle in TvModeContinueWatchingStyles -> requestedStyle
        else -> ContinueWatchingSectionStyle.Card
    }

private fun ContinueWatchingItem.continueWatchingRowOrderKey(): String =
    buildString {
        append(if (isNextUp) "next" else "progress")
        append(':')
        append(parentMetaId)
        append(':')
        append(videoId)
        append(':')
        append(seasonNumber)
        append('x')
        append(episodeNumber)
        append(":seed=")
        append(nextUpSeedSeasonNumber)
        append('x')
        append(nextUpSeedEpisodeNumber)
    }

@Composable
fun ContinueWatchingStylePreview(
    style: ContinueWatchingSectionStyle,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .padding(horizontal = 12.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (style) {
            ContinueWatchingSectionStyle.Card -> CardStylePreview()
            ContinueWatchingSectionStyle.Wide -> WideCardPreview()
            ContinueWatchingSectionStyle.Poster -> PosterCardPreview()
        }
    }
}

@Composable
private fun CardStylePreview() {
    Box(
        modifier = Modifier
            .width(100.dp)
            .height(56.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.60f to MaterialTheme.colorScheme.background.copy(alpha = 0.45f),
                            1.0f to MaterialTheme.colorScheme.background.copy(alpha = 0.90f),
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(5.dp)
                .width(26.dp)
                .height(9.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.80f)),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(7.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(28.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.55f)),
            )
            Box(
                modifier = Modifier
                    .width(58.dp)
                    .height(7.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.75f)),
            )
        }
        NuvioProgressBar(
            progress = 0.55f,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 5.dp, vertical = 3.dp)
                .fillMaxWidth(),
            height = 3.dp,
            trackColor = Color.Black.copy(alpha = 0.30f),
        )
    }
}

@Composable
private fun WideCardPreview() {
    Row(
        modifier = Modifier
            .width(100.dp)
            .height(60.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)),
    ) {
        Box(
            modifier = Modifier
                .width(40.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
        )
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
                .padding(4.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f)),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)),
            )
            NuvioProgressBar(
                progress = 0.6f,
                modifier = Modifier.fillMaxWidth(),
                height = 4.dp,
                trackColor = MaterialTheme.colorScheme.surfaceTint.copy(alpha = 0.16f),
            )
        }
    }
}

@Composable
private fun PosterCardPreview() {
    Column(
        modifier = Modifier
            .width(60.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 6.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            ) {
                NuvioProgressBar(
                    progress = 0.45f,
                    modifier = Modifier.width(40.dp),
                    height = 4.dp,
                    trackColor = MaterialTheme.colorScheme.surfaceTint.copy(alpha = 0.16f),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .height(7.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.55f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                )
            }
            Box(
                modifier = Modifier
                    .padding(start = 6.dp, top = 1.dp)
                    .width(16.dp)
                    .height(7.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)),
            )
        }
    }
}

private data class ContinueWatchingLandscapeCardMetrics(
    val width: Dp,
    val cornerRadius: Dp,
    val contentPadding: Dp,
    val textGap: Dp,
    val badgeInset: Dp,
    val badgeHorizontalPadding: Dp,
    val badgeVerticalPadding: Dp,
    val progressHorizontalPadding: Dp,
    val progressBottomPadding: Dp,
    val progressHeight: Dp,
    val titleTextSize: TextUnit,
    val metaTextSize: TextUnit,
    val badgeTextSize: TextUnit,
)

private fun continueWatchingLandscapeCardMetrics(
    basePosterWidthDp: Int,
    cornerRadiusDp: Int,
    cardWidthOverride: Dp? = null,
): ContinueWatchingLandscapeCardMetrics {
    val width = cardWidthOverride ?: continueWatchingLandscapeCardWidth(basePosterWidthDp)
    return when {
        basePosterWidthDp <= 108 -> ContinueWatchingLandscapeCardMetrics(
            width = width,
            cornerRadius = cornerRadiusDp.dp,
            contentPadding = 8.dp,
            textGap = 1.dp,
            badgeInset = 6.dp,
            badgeHorizontalPadding = 6.dp,
            badgeVerticalPadding = 2.dp,
            progressHorizontalPadding = 8.dp,
            progressBottomPadding = 3.dp,
            progressHeight = 3.dp,
            titleTextSize = 12.sp,
            metaTextSize = 9.sp,
            badgeTextSize = 8.sp,
        )
        basePosterWidthDp <= 120 -> ContinueWatchingLandscapeCardMetrics(
            width = width,
            cornerRadius = cornerRadiusDp.dp,
            contentPadding = 9.dp,
            textGap = 1.dp,
            badgeInset = 6.dp,
            badgeHorizontalPadding = 7.dp,
            badgeVerticalPadding = 3.dp,
            progressHorizontalPadding = 8.dp,
            progressBottomPadding = 3.dp,
            progressHeight = 3.dp,
            titleTextSize = 13.sp,
            metaTextSize = 10.sp,
            badgeTextSize = 9.sp,
        )
        else -> ContinueWatchingLandscapeCardMetrics(
            width = width,
            cornerRadius = cornerRadiusDp.dp,
            contentPadding = 10.dp,
            textGap = 2.dp,
            badgeInset = 7.dp,
            badgeHorizontalPadding = 7.dp,
            badgeVerticalPadding = 3.dp,
            progressHorizontalPadding = 9.dp,
            progressBottomPadding = 4.dp,
            progressHeight = 3.dp,
            titleTextSize = 14.sp,
            metaTextSize = 10.sp,
            badgeTextSize = 10.sp,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueWatchingCard(
    item: ContinueWatchingItem,
    basePosterWidthDpOverride: Int?,
    useEpisodeThumbnails: Boolean,
    blurNextUp: Boolean,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
) {
    val posterCardStyle = rememberHomePosterCardStyleUiState()
    val homeCatalogSettings by remember {
        HomeCatalogSettingsRepository.snapshot()
        HomeCatalogSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val effectiveWidthDp = effectiveContinueWatchingCardBaseWidthDp(
        basePosterWidthDpOverride = basePosterWidthDpOverride,
        savedPosterWidthDp = posterCardStyle.widthDp,
        tvModeEnabled = homeCatalogSettings.tvModeEnabled,
    )
    val cardWidthOverride = basePosterWidthDpOverride?.let(::landscapePosterWidth)
    val cardMetrics = remember(effectiveWidthDp, posterCardStyle.cornerRadiusDp, cardWidthOverride) {
        continueWatchingLandscapeCardMetrics(
            basePosterWidthDp = effectiveWidthDp,
            cornerRadiusDp = posterCardStyle.cornerRadiusDp,
            cardWidthOverride = cardWidthOverride,
        )
    }
    val todayIsoDate = CurrentDateProvider.todayIsoDate()
    val compactAirDateText = if (item.progressFraction <= 0f && item.seasonNumber != null && item.episodeNumber != null) {
        computeAirDateBadgeText(item.released, todayIsoDate, compact = true)
    } else {
        null
    }
    val preferBackdropForNextUp = item.isNextUp && compactAirDateText != null && !item.isReleaseAlert
    val (imageUrl, onArtworkLoadFailed) = rememberContinueWatchingArtwork(
        item.continueWatchingCardArtworkUrls(
            useEpisodeThumbnails = useEpisodeThumbnails,
            preferBackdropForNextUp = preferBackdropForNextUp,
        ),
    )
    val shouldBlurArtwork = blurNextUp && useEpisodeThumbnails && item.isNextUp
    val episodeCode = if (item.seasonNumber != null && item.episodeNumber != null) {
        stringResource(Res.string.streams_episode_badge, item.seasonNumber, item.episodeNumber)
    } else {
        null
    }
    val episodeTitle = item.episodeTitle?.trim()?.takeIf { it.isNotBlank() } ?: compactAirDateText
    val badgeText = continueWatchingCardBadgeText(item = item, compactAirDateText = compactAirDateText)
    val backgroundColor = MaterialTheme.colorScheme.background
    val badgeBackground = when {
        item.isNewSeasonRelease -> ContinueWatchingNewSeasonBadgeColor
        item.isReleaseAlert -> ContinueWatchingNewEpisodeBadgeColor
        else -> backgroundColor.copy(alpha = 0.80f)
    }

    Box(
        modifier = Modifier
            .width(cardMetrics.width)
            .aspectRatio(PosterLandscapeAspectRatio)
            .clip(RoundedCornerShape(cardMetrics.cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .nuvioCardDepth(RoundedCornerShape(cardMetrics.cornerRadius), NuvioCardDepthSurface.ContinueWatching)
            .posterCardClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                zoomImageUrl = imageUrl?.let(::cloudLibraryDisplayArtworkUrl),
                zoomCornerRadius = cardMetrics.cornerRadius,
            ),
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = cloudLibraryDisplayArtworkUrl(imageUrl),
                contentDescription = item.title,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (shouldBlurArtwork) Modifier.blur(18.dp) else Modifier)
                    .drawWithContent {
                        drawContent()

                        val startY = size.height * 0.45f
                        val gradient = Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Transparent,
                                0.60f to backgroundColor.copy(alpha = 0.70f),
                                1.0f to backgroundColor.copy(alpha = 0.95f),
                            ),
                            startY = startY,
                            endY = size.height,
                        )

                        drawRect(
                            brush = gradient,
                            topLeft = Offset(-2f, startY),
                            size = Size(size.width + 4f, (size.height - startY) + 4f),
                        )
                    },
                contentScale = ContentScale.Crop,
                onError = { onArtworkLoadFailed() },
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(cardMetrics.contentPadding),
            verticalArrangement = Arrangement.spacedBy(cardMetrics.textGap),
        ) {
            if (episodeCode != null) {
                Text(
                    text = episodeCode,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = cardMetrics.metaTextSize,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontSize = cardMetrics.titleTextSize,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (episodeTitle != null) {
                Text(
                    text = episodeTitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = cardMetrics.metaTextSize,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(cardMetrics.badgeInset)
                .clip(ContinueWatchingStatusBadgeShape)
                .background(badgeBackground)
                .padding(
                    horizontal = cardMetrics.badgeHorizontalPadding,
                    vertical = cardMetrics.badgeVerticalPadding,
                ),
        ) {
            Text(
                text = badgeText,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = cardMetrics.badgeTextSize,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
            )
        }
        if (item.progressFraction > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        horizontal = cardMetrics.progressHorizontalPadding,
                        vertical = cardMetrics.progressBottomPadding,
                    )
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(999.dp))
                    .height(cardMetrics.progressHeight)
                    .background(Color.Black.copy(alpha = 0.30f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(item.progressFraction.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

internal fun effectiveContinueWatchingCardBaseWidthDp(
    basePosterWidthDpOverride: Int?,
    savedPosterWidthDp: Int,
    tvModeEnabled: Boolean,
): Int = basePosterWidthDpOverride
    ?: if (tvModeEnabled) ExtraLargePosterCardWidthDp else savedPosterWidthDp

@Composable
private fun continueWatchingCardBadgeText(
    item: ContinueWatchingItem,
    compactAirDateText: String?,
): String {
    if (item.progressFraction > 0f) {
        if (item.durationMs <= 0L) {
            return stringResource(
                Res.string.home_continue_watching_watched,
                "${continueWatchingProgressPercent(item.progressFraction)}%",
            )
        }
        val effectivePositionMs = when {
            item.resumePositionMs > 0L -> item.resumePositionMs
            else -> (item.durationMs * item.progressFraction.coerceIn(0f, 1f)).toLong()
        }
        val remainingMinutes = ((item.durationMs - effectivePositionMs).coerceAtLeast(0L) / 60_000L)
            .coerceAtLeast(1L)
        val hours = remainingMinutes / 60L
        val minutes = remainingMinutes % 60L
        return if (hours > 0L) {
            stringResource(Res.string.home_continue_watching_hours_minutes_left, hours, minutes)
        } else {
            stringResource(Res.string.home_continue_watching_minutes_left, remainingMinutes)
        }
    }

    return when {
        item.isReleaseAlert && item.isNewSeasonRelease -> stringResource(Res.string.cw_new_season)
        item.isReleaseAlert -> stringResource(Res.string.cw_new_episode)
        compactAirDateText != null -> compactAirDateText
        else -> stringResource(Res.string.home_continue_watching_up_next)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueWatchingWideCard(
    item: ContinueWatchingItem,
    layout: ContinueWatchingLayout,
    useEpisodeThumbnails: Boolean,
    blurNextUp: Boolean,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
) {
    val (artworkUrl, onArtworkLoadFailed) = rememberContinueWatchingArtwork(
        item.continueWatchingArtworkUrls(useEpisodeThumbnails),
    )
    Row(
        modifier = Modifier
            .width(layout.wideCardWidth)
            .height(layout.wideCardHeight)
            .clip(RoundedCornerShape(layout.cardRadius))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .border(
                width = 1.5.dp,
                color = Color.White.copy(alpha = 0.15f),
                shape = RoundedCornerShape(layout.cardRadius),
            )
            .posterCardClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                zoomImageUrl = artworkUrl?.let(::cloudLibraryDisplayArtworkUrl),
                zoomCornerRadius = layout.cardRadius,
            ),
    ) {
        val shouldBlurArtwork = blurNextUp && useEpisodeThumbnails && item.isNextUp
        ArtworkPanel(
            imageUrl = artworkUrl,
            width = layout.widePosterStripWidth,
            blurred = shouldBlurArtwork,
            contentScale = if (item.isCloudLibraryItem()) ContentScale.Fit else ContentScale.Crop,
            onLoadFailed = onArtworkLoadFailed,
            modifier = Modifier.fillMaxHeight(),
        )
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
                .padding(layout.wideContentPadding),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            val isCompact = layout.wideCardWidth < 350.dp
            val wideMetaLine = localizedContinueWatchingMetaLine(item)
            val episodeTitle = item.episodeTitle?.trim()?.takeIf { it.isNotBlank() }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = item.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = layout.wideTitleSize,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.progressFraction <= 0f && item.seasonNumber != null && item.episodeNumber != null) {
                        val todayIsoDate = CurrentDateProvider.todayIsoDate()
                        val badgeText = when {
                            item.isReleaseAlert -> {
                                if (item.isNewSeasonRelease) stringResource(Res.string.cw_new_season)
                                else stringResource(Res.string.cw_new_episode)
                            }
                            else -> {
                                computeAirDateBadgeText(item.released, todayIsoDate, compact = isCompact)
                                    ?: stringResource(Res.string.home_continue_watching_up_next)
                            }
                        }
                        UpNextBadge(text = badgeText, compact = isCompact, textSize = layout.wideBadgeTextSize)
                    }
                }
                Text(
                    text = wideMetaLine,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = layout.wideMetaSize,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (episodeTitle != null) {
                    Text(
                        text = episodeTitle,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = layout.wideMetaSize,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (item.progressFraction > 0f) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    NuvioProgressBar(
                        progress = item.progressFraction,
                        modifier = Modifier.fillMaxWidth(),
                        height = layout.progressHeight,
                        trackColor = Color.White.copy(alpha = 0.10f),
                    )
                    Text(
                        text = stringResource(
                            Res.string.home_continue_watching_watched,
                            "${continueWatchingProgressPercent(item.progressFraction)}%",
                        ),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = layout.progressLabelSize,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueWatchingPosterCard(
    item: ContinueWatchingItem,
    layout: ContinueWatchingLayout,
    useEpisodeThumbnails: Boolean,
    blurNextUp: Boolean,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
) {
    val (posterArtworkUrl, onArtworkLoadFailed) = rememberContinueWatchingArtwork(
        item.continueWatchingPosterArtworkUrls(useEpisodeThumbnails),
    )
    Column(
        modifier = Modifier.width(layout.posterCardWidth),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(layout.posterCardHeight)
                .clip(RoundedCornerShape(layout.cardRadius))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .nuvioCardDepth(RoundedCornerShape(layout.cardRadius), NuvioCardDepthSurface.ContinueWatching)
                .posterCardClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                    zoomImageUrl = posterArtworkUrl?.let(::cloudLibraryDisplayArtworkUrl),
                    zoomCornerRadius = layout.cardRadius,
                ),
        ) {
            val imageUrl = posterArtworkUrl
            val shouldBlurArtwork = blurNextUp &&
                useEpisodeThumbnails &&
                item.isNextUp &&
                imageUrl == firstNonBlank(item.episodeThumbnail)
            if (imageUrl != null) {
                AsyncImage(
                    model = cloudLibraryDisplayArtworkUrl(imageUrl),
                    contentDescription = item.title,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (shouldBlurArtwork) Modifier.blur(18.dp) else Modifier),
                    contentScale = if (item.isCloudLibraryItem()) ContentScale.Fit else ContentScale.Crop,
                    onError = { onArtworkLoadFailed() },
                )
            }
            if (item.progressFraction <= 0f && item.seasonNumber != null && item.episodeNumber != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                ) {
                    val todayIsoDate = CurrentDateProvider.todayIsoDate()
                    val badgeText = when {
                        item.isReleaseAlert -> {
                            if (item.isNewSeasonRelease) stringResource(Res.string.cw_new_season)
                            else stringResource(Res.string.cw_new_episode)
                        }
                        else -> {
                            computeAirDateBadgeText(item.released, todayIsoDate, compact = true)
                                ?: stringResource(Res.string.home_continue_watching_up_next)
                        }
                    }
                    UpNextBadge(text = badgeText, compact = true, textSize = layout.posterBadgeTextSize)
                }
            }
            if (item.progressFraction > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 10.dp, vertical = 10.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                ) {
                    NuvioProgressBar(
                        progress = item.progressFraction,
                        modifier = Modifier.width(layout.posterCardWidth - 32.dp),
                        height = layout.progressHeight,
                        trackColor = MaterialTheme.colorScheme.surfaceTint.copy(alpha = 0.16f),
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(layout.posterTitleBlockHeight),
            ) {
                Text(
                    text = item.title,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = layout.posterTitleSize,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 18.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.seasonNumber != null && item.episodeNumber != null) {
                Text(
                    text = stringResource(
                        Res.string.streams_episode_badge,
                        item.seasonNumber,
                        item.episodeNumber,
                    ),
                    modifier = Modifier.padding(start = 6.dp),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = layout.posterMetaSize,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ArtworkPanel(
    imageUrl: String?,
    width: Dp,
    blurred: Boolean = false,
    contentScale: ContentScale = ContentScale.Crop,
    onLoadFailed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(width)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = cloudLibraryDisplayArtworkUrl(imageUrl),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (blurred) Modifier.blur(18.dp) else Modifier),
                contentScale = contentScale,
                onError = { onLoadFailed() },
            )
        }
    }
}

@Composable
private fun UpNextBadge(
    text: String,
    compact: Boolean,
    textSize: androidx.compose.ui.unit.TextUnit,
) {
    val chipColor = MaterialTheme.colorScheme.primary
    val chipTextColor = contentColorFor(chipColor)

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(if (compact) 4.dp else 12.dp))
            .background(chipColor)
            .padding(
                horizontal = if (compact) 6.dp else 8.dp,
                vertical = if (compact) 3.dp else 4.dp,
            ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = textSize,
                fontWeight = FontWeight.Bold,
            ),
            color = chipTextColor,
            maxLines = 1,
        )
    }
}

internal data class ContinueWatchingLayout(
    val itemGap: Dp,
    val wideCardWidth: Dp,
    val wideCardHeight: Dp,
    val widePosterStripWidth: Dp,
    val wideContentPadding: Dp,
    val posterCardWidth: Dp,
    val posterCardHeight: Dp,
    val cardRadius: Dp,
    val progressHeight: Dp,
    val wideTitleSize: androidx.compose.ui.unit.TextUnit,
    val wideMetaSize: androidx.compose.ui.unit.TextUnit,
    val posterTitleSize: androidx.compose.ui.unit.TextUnit,
    val posterTitleBlockHeight: Dp,
    val posterMetaSize: androidx.compose.ui.unit.TextUnit,
    val progressLabelSize: androidx.compose.ui.unit.TextUnit,
    val wideBadgeTextSize: androidx.compose.ui.unit.TextUnit,
    val posterBadgeTextSize: androidx.compose.ui.unit.TextUnit,
)

internal fun rememberContinueWatchingLayout(maxWidthDp: Float): ContinueWatchingLayout =
    when {
        maxWidthDp >= 1440f -> ContinueWatchingLayout(
            itemGap = 20.dp,
            wideCardWidth = 400.dp,
            wideCardHeight = 160.dp,
            widePosterStripWidth = 100.dp,
            wideContentPadding = 16.dp,
            posterCardWidth = 180.dp,
            posterCardHeight = 270.dp,
            cardRadius = 18.dp,
            progressHeight = 6.dp,
            wideTitleSize = 20.sp,
            wideMetaSize = 16.sp,
            posterTitleSize = 16.sp,
            posterTitleBlockHeight = 40.dp,
            posterMetaSize = 14.sp,
            progressLabelSize = 14.sp,
            wideBadgeTextSize = 14.sp,
            posterBadgeTextSize = 12.sp,
        )
        maxWidthDp >= 1024f -> ContinueWatchingLayout(
            itemGap = 18.dp,
            wideCardWidth = 350.dp,
            wideCardHeight = 140.dp,
            widePosterStripWidth = 90.dp,
            wideContentPadding = 14.dp,
            posterCardWidth = 160.dp,
            posterCardHeight = 240.dp,
            cardRadius = 16.dp,
            progressHeight = 5.dp,
            wideTitleSize = 18.sp,
            wideMetaSize = 15.sp,
            posterTitleSize = 15.sp,
            posterTitleBlockHeight = 40.dp,
            posterMetaSize = 13.sp,
            progressLabelSize = 13.sp,
            wideBadgeTextSize = 13.sp,
            posterBadgeTextSize = 10.sp,
        )
        maxWidthDp >= 768f -> ContinueWatchingLayout(
            itemGap = 16.dp,
            wideCardWidth = 320.dp,
            wideCardHeight = 130.dp,
            widePosterStripWidth = 85.dp,
            wideContentPadding = 12.dp,
            posterCardWidth = 140.dp,
            posterCardHeight = 210.dp,
            cardRadius = 16.dp,
            progressHeight = 4.dp,
            wideTitleSize = 17.sp,
            wideMetaSize = 14.sp,
            posterTitleSize = 14.sp,
            posterTitleBlockHeight = 38.dp,
            posterMetaSize = 12.sp,
            progressLabelSize = 12.sp,
            wideBadgeTextSize = 12.sp,
            posterBadgeTextSize = 10.sp,
        )
        else -> ContinueWatchingLayout(
            itemGap = 16.dp,
            wideCardWidth = 280.dp,
            wideCardHeight = 120.dp,
            widePosterStripWidth = 80.dp,
            wideContentPadding = 12.dp,
            posterCardWidth = 120.dp,
            posterCardHeight = 180.dp,
            cardRadius = 16.dp,
            progressHeight = 4.dp,
            wideTitleSize = 16.sp,
            wideMetaSize = 13.sp,
            posterTitleSize = 14.sp,
            posterTitleBlockHeight = 38.dp,
            posterMetaSize = 12.sp,
            progressLabelSize = 11.sp,
            wideBadgeTextSize = 12.sp,
            posterBadgeTextSize = 10.sp,
        )
    }
