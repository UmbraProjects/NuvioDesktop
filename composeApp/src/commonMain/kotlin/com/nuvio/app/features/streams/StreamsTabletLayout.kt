package com.nuvio.app.features.streams

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.NuvioAsyncImage as AsyncImage
import com.nuvio.app.isIos
import com.nuvio.app.isDesktop
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun TabletStreamsLayout(
    isEpisode: Boolean,
    contentId: String?,
    contentType: String?,
    title: String,
    logo: String?,
    poster: String?,
    background: String?,
    episodeThumbnail: String?,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    uiState: StreamsUiState,
    debridEnabled: Boolean,
    appendInstantServiceToDefaultName: Boolean,
    resumePositionMs: Long?,
    resumeProgressFraction: Float?,
    providerListState: LazyListState,
    streamListState: LazyListState,
    focusedStream: StreamItem?,
    onStreamSelected: (stream: StreamItem, resumePositionMs: Long?, resumeProgressFraction: Float?) -> Unit,
    onStreamLongPress: (StreamItem) -> Unit,
    modifier: Modifier = Modifier,
    // See StreamList: the screen owns this so navigation and rendering share one ordering.
    displayGroups: List<AddonStreamGroup>? = null,
    showHtpcChip: Boolean = false,
) {
    val hazeState = rememberHazeState()
    val tabletBackdrop = remember(background, poster) {
        background ?: poster
    }
    var backdropVisible by remember(tabletBackdrop) { mutableStateOf(false) }

    LaunchedEffect(tabletBackdrop) {
        backdropVisible = tabletBackdrop == null
        if (tabletBackdrop != null) {
            backdropVisible = true
        }
    }

    val backdropAlpha by animateFloatAsState(
        targetValue = if (backdropVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 520),
        label = "tablet_backdrop_alpha",
    )
    val backdropScale by animateFloatAsState(
        targetValue = if (backdropVisible) 1f else 1.05f,
        animationSpec = tween(durationMillis = 620),
        label = "tablet_backdrop_scale",
    )

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(state = hazeState),
        ) {
            if (tabletBackdrop != null) {
                AsyncImage(
                    model = tabletBackdrop,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = backdropAlpha
                            scaleX = backdropScale
                            scaleY = backdropScale
                        },
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.2f),
                                Color.Black.copy(alpha = 0.4f),
                                Color.Black.copy(alpha = 0.6f),
                            ),
                        ),
                    ),
            )
        }

        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(if (isDesktop) 1f else 0.4f)
                    .fillMaxHeight()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (isEpisode && seasonNumber != null && episodeNumber != null) {
                    TabletEpisodeInfoPanel(
                        logo = logo,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                        episodeTitle = episodeTitle,
                        showTitle = title,
                    )
                } else {
                    TabletMovieInfoPanel(
                        title = title,
                        logo = logo,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .then(
                        if (isDesktop) {
                            Modifier
                                .fillMaxWidth(0.46f)
                                .widthIn(max = 760.dp)
                        } else {
                            Modifier.weight(0.6f)
                        },
                    )
                    .fillMaxHeight()
                    .padding(
                        top = if (isIos) 20.dp else 60.dp,
                        end = 12.dp,
                        bottom = 12.dp,
                    ),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(24.dp))
                        .hazeEffect(state = hazeState)
                        .background(Color.Black.copy(alpha = 0.22f)),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                    ) {
                        if ((resumePositionMs != null && resumePositionMs > 0L) || (resumeProgressFraction != null && resumeProgressFraction > 0f)) {
                            ResumeBanner(
                                positionMs = resumePositionMs,
                                progressFraction = resumeProgressFraction,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }

                        ProviderFilterRow(
                            groups = uiState.groups,
                            selectedFilter = uiState.selectedFilter,
                            listState = providerListState,
                            onFilterSelected = { addonId -> StreamsRepository.selectFilter(addonId) },
                            showHtpcChip = showHtpcChip,
                        )

                        ActiveScrapersStatusBlock(
                            groups = uiState.groups,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )

                        StreamList(
                            uiState = uiState,
                            displayGroups = displayGroups,
                            debridEnabled = debridEnabled,
                            appendInstantServiceToDefaultName = appendInstantServiceToDefaultName,
                            onStreamSelected = onStreamSelected,
                            onStreamLongPress = onStreamLongPress,
                            resumePositionMs = resumePositionMs,
                            resumeProgressFraction = resumeProgressFraction,
                            listState = streamListState,
                            focusedStream = focusedStream,
                            isEpisode = isEpisode,
                            contentId = contentId,
                            contentType = contentType,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TabletMovieInfoPanel(
    title: String,
    logo: String?,
    modifier: Modifier = Modifier,
) {
    var logoLoadError by remember(logo) { mutableStateOf(false) }
    val logoUrl = logo?.takeIf { it.isNotBlank() }

    Column(
        modifier = modifier.fillMaxWidth(0.8f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (logoUrl != null && !logoLoadError) {
            AsyncImage(
                model = logoUrl,
                contentDescription = title,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentScale = ContentScale.Fit,
                onError = { logoLoadError = true },
            )
        } else if (title.isNotBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.5).sp,
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.8f),
                        offset = Offset(0f, 2f),
                        blurRadius = 4f,
                    ),
                ),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                text = stringResource(Res.string.streams_no_metadata),
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 16.sp,
                    fontStyle = FontStyle.Italic,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun TabletEpisodeInfoPanel(
    logo: String?,
    seasonNumber: Int,
    episodeNumber: Int,
    episodeTitle: String?,
    showTitle: String,
    modifier: Modifier = Modifier,
) {
    var logoLoadError by remember(logo) { mutableStateOf(false) }
    val logoUrl = logo?.takeIf { it.isNotBlank() }
    val textShadow = Shadow(
        color = Color.Black,
        offset = Offset(0f, 0f),
        blurRadius = 4f,
    )

    Column(
        modifier = modifier.fillMaxWidth(0.8f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (logoUrl != null && !logoLoadError) {
            AsyncImage(
                model = logoUrl,
                contentDescription = showTitle,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentScale = ContentScale.Fit,
                onError = { logoLoadError = true },
            )
        } else {
            Text(
                text = showTitle,
                style = MaterialTheme.typography.displayMedium.copy(
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.5).sp,
                    shadow = textShadow,
                ),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(
                Res.string.streams_episode_title_with_name,
                seasonNumber,
                episodeNumber,
                episodeTitle?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.streams_episode_fallback_title),
            ),
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 16.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.SemiBold,
                shadow = textShadow,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.95f),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ActiveScrapersStatusBlock(
    groups: List<AddonStreamGroup>,
    modifier: Modifier = Modifier,
) {
    val activeScrapers = remember(groups) {
        groups.filter { it.isLoading }.map { it.addonName }.distinct()
    }
    if (activeScrapers.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(Res.string.streams_active_scrapers),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            activeScrapers.forEach { addonName ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (LocalStreamCardStyle.current == StreamCardStyle.Glass) {
                                StreamGlass.Rest
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            },
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = addonName,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Normal,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * The source list as a self-contained panel: what is being played, then the same resume banner,
 * provider chips, scraper status and list as the full-screen layout. No artwork of its own — it
 * sits in the details hero or over a dimmed screen, both of which already show the title.
 */
@Composable
internal fun PanelStreamsLayout(
    isEpisode: Boolean,
    contentId: String?,
    contentType: String?,
    title: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    uiState: StreamsUiState,
    debridEnabled: Boolean,
    appendInstantServiceToDefaultName: Boolean,
    resumePositionMs: Long?,
    resumeProgressFraction: Float?,
    providerListState: LazyListState,
    streamListState: LazyListState,
    focusedStream: StreamItem?,
    onStreamSelected: (stream: StreamItem, resumePositionMs: Long?, resumeProgressFraction: Float?) -> Unit,
    onStreamLongPress: (StreamItem) -> Unit,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    /**
     * Docked in the details hero: barely-there cards, and the content held to a readable width
     * at the start of the area rather than stretched across all of it.
     */
    glass: Boolean = false,
    twoColumns: Boolean = false,
    /** False when the panel is too narrow for two columns; the layout toggle is hidden. */
    twoColumnsFit: Boolean = true,
    displayGroups: List<AddonStreamGroup>? = null,
    showHtpcChip: Boolean = false,
) {
    val resumeText = resumeLabel(resumePositionMs, resumeProgressFraction)
    CompositionLocalProvider(
        LocalStreamCardStyle provides if (glass) StreamCardStyle.Glass else StreamCardStyle.Solid,
    ) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            // Two columns earn the extra width; one column reads better held narrower.
            .then(if (glass) Modifier.widthIn(max = if (twoColumns) 1120.dp else 760.dp) else Modifier),
    ) {
        // Docked, the details page around the panel already names the show and the episode (whose
        // card is outlined), so the panel opens straight on the chips.
        if (!glass) Row(
            modifier = Modifier
                .fillMaxWidth()
                // The provider chips and the list below both inset 12dp; matching it puts the
                // title's first letter over the "All" chip.
                .padding(horizontal = 12.dp)
                .padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // The episode is the one thing a docked panel must name: the episode row below it
                // can be scrolled to a different season than the one being played.
                if (isEpisode && seasonNumber != null && episodeNumber != null) {
                    Text(
                        text = stringResource(
                            Res.string.streams_episode_title_with_name,
                            seasonNumber,
                            episodeNumber,
                            episodeTitle?.takeIf { it.isNotBlank() }
                                ?: stringResource(Res.string.streams_episode_fallback_title),
                        ),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = listOfNotNull(title, resumeText).joinToString(" \u2022 "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (resumeText != null) {
                            Text(
                                text = " \u2022 $resumeText",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }

        ProviderFilterRow(
            groups = uiState.groups,
            selectedFilter = uiState.selectedFilter,
            listState = providerListState,
            onFilterSelected = { addonId -> StreamsRepository.selectFilter(addonId) },
            showHtpcChip = showHtpcChip,
            trailing = {
                // Shows the layout a click switches to.
                if (twoColumnsFit) PanelHeaderIconButton(
                    icon = if (twoColumns) Icons.Rounded.ViewAgenda else Icons.Rounded.GridView,
                    contentDescription = if (twoColumns) "Single column" else "Two columns",
                    onClick = { StreamBadgeSettingsRepository.setListTwoColumns(!twoColumns) },
                )
                PanelHeaderIconButton(
                    icon = Icons.Rounded.Refresh,
                    contentDescription = stringResource(Res.string.streams_refresh),
                    onClick = onRefresh,
                )
                PanelHeaderIconButton(
                    icon = Icons.Rounded.Close,
                    contentDescription = stringResource(Res.string.action_close),
                    onClick = onClose,
                )
            },
        )

        ActiveScrapersStatusBlock(
            groups = uiState.groups,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        StreamList(
            uiState = uiState,
            displayGroups = displayGroups,
            debridEnabled = debridEnabled,
            appendInstantServiceToDefaultName = appendInstantServiceToDefaultName,
            onStreamSelected = onStreamSelected,
            onStreamLongPress = onStreamLongPress,
            resumePositionMs = resumePositionMs,
            resumeProgressFraction = resumeProgressFraction,
            listState = streamListState,
            focusedStream = focusedStream,
            isEpisode = isEpisode,
            contentId = contentId,
            contentType = contentType,
            modifier = Modifier
                .weight(1f)
                .scrollEdgeFade(streamListState),
            columns = if (twoColumns) 2 else 1,
        )
    }
    }
}

/**
 * Fades the list out where it is cut off — the bottom while more rows follow, the top once
 * scrolled — so it runs into its surroundings instead of ending on a hard line.
 */
private fun Modifier.scrollEdgeFade(state: LazyListState, fade: Dp = 48.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fadePx = fade.toPx().coerceAtMost(size.height / 2f)
        if (state.canScrollBackward) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black),
                    startY = 0f,
                    endY = fadePx,
                ),
                size = Size(size.width, fadePx),
                blendMode = BlendMode.DstIn,
            )
        }
        if (state.canScrollForward) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Black, Color.Transparent),
                    startY = size.height - fadePx,
                    endY = size.height,
                ),
                topLeft = Offset(0f, size.height - fadePx),
                size = Size(size.width, fadePx),
                blendMode = BlendMode.DstIn,
            )
        }
    }

@Composable
private fun PanelHeaderIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(
                if (LocalStreamCardStyle.current == StreamCardStyle.Glass) {
                    StreamGlass.Rest
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(18.dp),
        )
    }
}
