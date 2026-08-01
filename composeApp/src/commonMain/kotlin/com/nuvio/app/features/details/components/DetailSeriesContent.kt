package com.nuvio.app.features.details.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.locallibrary.LocalLibraryRepository
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.NuvioAsyncImage as AsyncImage
import co.touchlab.kermit.Logger
import com.nuvio.app.core.i18n.localizedSeasonEpisodeCode
import com.nuvio.app.core.ui.NuvioAnimatedWatchedBadge
import com.nuvio.app.core.ui.NuvioCardDepthSurface
import com.nuvio.app.core.ui.nuvioCardDepth
import com.nuvio.app.core.ui.NuvioProgressBar
import com.nuvio.app.core.ui.NuvioShelfItemSlot
import com.nuvio.app.core.ui.desktopHorizontalListNavigation
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.secondaryClick
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaEpisodeCardStyle
import com.nuvio.app.features.details.MetaTrailer
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.SeasonViewMode
import com.nuvio.app.features.details.SeasonViewModeStorage
import com.nuvio.app.features.details.effectiveEpisodeNumber
import com.nuvio.app.features.details.effectiveSeasonNumber
import com.nuvio.app.features.details.formatRuntimeFromMinutes
import com.nuvio.app.features.details.metaVideoSeasonEpisodeComparator
import com.nuvio.app.features.details.normalizeSeasonNumber
import com.nuvio.app.features.details.progressForEpisodeVideo
import com.nuvio.app.features.details.seasonSortKey
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import com.nuvio.app.features.watching.application.WatchingState
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

private val log = Logger.withTag("SeriesContent")
private const val TRAILER_SELECTOR_SEASON = Int.MIN_VALUE
private const val COLLECTION_SELECTOR_SEASON = Int.MAX_VALUE - 1
private const val MORE_LIKE_THIS_SELECTOR_SEASON = Int.MAX_VALUE

@Composable
fun DetailSeriesContent(
    meta: MetaDetails,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
    preferredSeasonNumber: Int? = null,
    preferredEpisodeNumber: Int? = null,
    episodeCardStyle: MetaEpisodeCardStyle = MetaEpisodeCardStyle.Horizontal,
    progressByVideoId: Map<String, WatchProgressEntry> = emptyMap(),
    watchedKeys: Set<String> = emptySet(),
    episodeRatings: Map<Pair<Int, Int>, Double> = emptyMap(),
    blurUnwatchedEpisodes: Boolean = false,
    onEpisodeClick: ((MetaVideo) -> Unit)? = null,
    onEpisodeLongPress: ((MetaVideo) -> Unit)? = null,
    onSeasonLongPress: ((Int) -> Unit)? = null,
    externalSelectedSeason: Int? = null,
    onSeasonSelected: ((Int) -> Unit)? = null,
    focusedSeasonIndex: Int? = null,
    focusedEpisodeIndex: Int? = null,
    focusedTrailerIndex: Int? = null,
    focusedCollectionIndex: Int? = null,
    focusedMoreLikeThisIndex: Int? = null,
    compactDesktopLayout: Boolean = false,
    trailers: List<MetaTrailer> = emptyList(),
    onTrailerClick: ((MetaTrailer) -> Unit)? = null,
    collectionTitle: String? = null,
    collectionItems: List<MetaPreview> = emptyList(),
    onCollectionItemClick: ((MetaPreview) -> Unit)? = null,
    moreLikeThis: List<MetaPreview> = emptyList(),
    onMoreLikeThisClick: ((MetaPreview) -> Unit)? = null,
) {
    val hasVideos = meta.videos.isNotEmpty()
    if (meta.type != "series" && !hasVideos) return

    // Resolve through the same video-id-aware path used by playback. Anime can use bare absolute
    // episode numbers (no season), and sibling Kitsu/MAL entries can map onto one franchise; a
    // plain season/episode set loses both of those identities and omits the downloaded badge.
    val localLibraryState by LocalLibraryRepository.uiState.collectAsStateWithLifecycle()
    val downloadedEpisodeVideoIds = remember(localLibraryState.items, meta.id, meta.videos) {
        meta.videos.mapNotNullTo(mutableSetOf()) { episode ->
            val videoId = episode.playbackVideoId(meta.id)
            videoId.takeIf {
                LocalLibraryRepository.hasLocalFileForEpisode(
                    metaId = meta.id,
                    videoId = episode.id,
                    season = episode.effectiveSeasonNumber(),
                    episode = episode.effectiveEpisodeNumber(),
                )
            }
        }
    }

    if (meta.videos.isEmpty()) {
        DetailSection(
            title = stringResource(Res.string.settings_meta_episodes),
            modifier = modifier,
            showHeader = showHeader,
        ) {
            Text(
                text = when {
                    meta.status.equals("Not yet aired", ignoreCase = true) || meta.hasScheduledVideos ->
                        stringResource(Res.string.details_series_unpublished)
                    else ->
                        stringResource(Res.string.details_series_no_metadata)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val groupedEpisodes = remember(meta.videos) {
        log.d { "videos count=${meta.videos.size}, type=${meta.type}" }
        val withSeasonOrEp = meta.videos.filter {
            it.effectiveSeasonNumber() != null || it.effectiveEpisodeNumber() != null
        }
        log.d { "videos with season/episode=${withSeasonOrEp.size}" }
        if (meta.videos.isNotEmpty() && withSeasonOrEp.isEmpty()) {
            log.w { "All videos lack season/episode fields! First: ${meta.videos.first()}" }
        }
        if (withSeasonOrEp.isNotEmpty()) {
            withSeasonOrEp
                .sortedWith(metaVideoSeasonEpisodeComparator)
                .groupBy { normalizeSeasonNumber(it.effectiveSeasonNumber()) }
        } else if (meta.type != "series" && meta.videos.isNotEmpty()) {
            // For non-series types (e.g. "other"), show videos without season/episode as a flat list
            mapOf(normalizeSeasonNumber(null) to meta.videos)
        } else {
            emptyMap()
        }
    }

    if (groupedEpisodes.isEmpty()) {
        if (meta.type == "series") {
            DetailSection(
                title = stringResource(Res.string.settings_meta_episodes),
                modifier = modifier,
                showHeader = showHeader,
            ) {
                Text(
                    text = stringResource(Res.string.details_series_missing_numbers),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    val seasons = groupedEpisodes.keys.sortedBy(::seasonSortKey)
    val effectiveEpisodeCardStyle = if (episodeCardStyle == MetaEpisodeCardStyle.List) {
        MetaEpisodeCardStyle.Horizontal
    } else {
        episodeCardStyle
    }
    val mergeTrailersIntoSelector = compactDesktopLayout && trailers.isNotEmpty() && onTrailerClick != null
    val mergeCollectionIntoSelector = compactDesktopLayout && collectionItems.isNotEmpty() && onCollectionItemClick != null
    val mergeMoreLikeThisIntoSelector = compactDesktopLayout && moreLikeThis.isNotEmpty() && onMoreLikeThisClick != null
    val selectorSeasons = buildList {
        if (mergeTrailersIntoSelector) add(TRAILER_SELECTOR_SEASON)
        addAll(seasons)
        if (mergeCollectionIntoSelector) add(COLLECTION_SELECTOR_SEASON)
        if (mergeMoreLikeThisIntoSelector) add(MORE_LIKE_THIS_SELECTOR_SEASON)
    }
    val defaultSeason = preferredSeasonNumber
        ?.takeIf { it in groupedEpisodes }
        ?: seasons.first()
    var selectedSeasonOverride by rememberSaveable(meta.id) { mutableStateOf<Int?>(null) }
    val currentSeason = externalSelectedSeason
        ?.takeIf { it in groupedEpisodes || it in selectorSeasons }
        ?: selectedSeasonOverride?.takeIf { it in groupedEpisodes || it in selectorSeasons }
        ?: defaultSeason
    val onSeasonSelect: (Int) -> Unit = { season ->
        selectedSeasonOverride = season
        onSeasonSelected?.invoke(season)
    }

    var seasonViewMode by remember {
        mutableStateOf(SeasonViewModeStorage.load() ?: SeasonViewMode.Posters)
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val sizing = seriesContentSizing(maxWidth.value, compactDesktopLayout)
        val containerWidthDp = maxWidth.value

        Column(
            verticalArrangement = Arrangement.spacedBy(if (compactDesktopLayout) 14.dp else 16.dp),
        ) {
            if (selectorSeasons.size > 1 || compactDesktopLayout) {
                if (compactDesktopLayout) {
                    SeasonTextChipScrollRow(
                        seasons = selectorSeasons,
                        currentSeason = currentSeason,
                        sizing = sizing,
                        focusedSeasonIndex = focusedSeasonIndex,
                        collectionTitle = collectionTitle,
                        onSelect = onSeasonSelect,
                        onLongPress = onSeasonLongPress?.let { handler ->
                            { season ->
                                if (
                                    season != TRAILER_SELECTOR_SEASON &&
                                    season != COLLECTION_SELECTOR_SEASON &&
                                    season != MORE_LIKE_THIS_SELECTOR_SEASON
                                ) {
                                    handler(season)
                                }
                            }
                        },
                        compactDesktopLayout = true,
                    )
                } else {
                    val hasSeasonPosters = seasons.any { season ->
                        groupedEpisodes[season]
                            .orEmpty()
                            .any { !it.seasonPoster.isNullOrBlank() }
                    }
                    Column(
                        modifier = Modifier.animateContentSize(animationSpec = tween(280)),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(Res.string.details_seasons),
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontSize = sizing.seasonHeaderSize,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            if (hasSeasonPosters) {
                                SeasonViewModeToggle(
                                    mode = seasonViewMode,
                                    sizing = sizing,
                                    onClick = {
                                        val next = seasonViewMode.toggled()
                                        seasonViewMode = next
                                        SeasonViewModeStorage.save(next)
                                    },
                                )
                            }
                        }

                        if (hasSeasonPosters) {
                            Crossfade(
                                targetState = seasonViewMode,
                                animationSpec = tween(280),
                                label = "season_selector_layout",
                            ) { mode ->
                                when (mode) {
                                    SeasonViewMode.Posters -> SeasonPosterScrollRow(
                                        seasons = seasons,
                                        groupedEpisodes = groupedEpisodes,
                                        meta = meta,
                                        currentSeason = currentSeason,
                                        sizing = sizing,
                                        focusedSeasonIndex = focusedSeasonIndex,
                                        onSelect = onSeasonSelect,
                                        onLongPress = onSeasonLongPress,
                                    )
                                    SeasonViewMode.Text -> SeasonTextChipScrollRow(
                                        seasons = seasons,
                                        currentSeason = currentSeason,
                                        sizing = sizing,
                                        focusedSeasonIndex = focusedSeasonIndex,
                                        onSelect = onSeasonSelect,
                                        onLongPress = onSeasonLongPress,
                                    )
                                }
                            }
                        } else {
                            SeasonTextChipScrollRow(
                                seasons = seasons,
                                currentSeason = currentSeason,
                                sizing = sizing,
                                focusedSeasonIndex = focusedSeasonIndex,
                                onSelect = onSeasonSelect,
                                onLongPress = onSeasonLongPress,
                            )
                        }
                    }
                }
            }

            AnimatedContent(
                targetState = currentSeason,
                transitionSpec = {
                    val fromIdx = selectorSeasons.indexOf(initialState).takeIf { it >= 0 } ?: 0
                    val toIdx = selectorSeasons.indexOf(targetState).takeIf { it >= 0 } ?: 0
                    val dir = if (toIdx >= fromIdx) 1 else -1
                    (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { dir * it / 5 })
                        .togetherWith(
                            fadeOut(tween(170)) + slideOutHorizontally(tween(170)) { -dir * it / 5 },
                        )
                },
                label = "season_episodes",
            ) { seasonForContent ->
                val sectionTitle = if (meta.type != "series" && seasons.size == 1 && seasonForContent <= 0) {
                    stringResource(Res.string.details_videos)
                } else {
                    seasonForContent.label(collectionTitle)
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(if (compactDesktopLayout) 12.dp else 16.dp),
                ) {
                    if (!compactDesktopLayout) {
                        DetailSectionTitle(
                            title = sectionTitle,
                        )
                    }
                    if (seasonForContent == TRAILER_SELECTOR_SEASON && mergeTrailersIntoSelector) {
                        CompactTrailerLandscapeRow(
                            trailers = trailers,
                            onTrailerClick = onTrailerClick,
                            focusedItemIndex = focusedTrailerIndex,
                        )
                        return@Column
                    }
                    if (seasonForContent == COLLECTION_SELECTOR_SEASON && mergeCollectionIntoSelector) {
                        CompactMetaPreviewLandscapeRow(
                            items = collectionItems,
                            onItemClick = onCollectionItemClick,
                            focusedItemIndex = focusedCollectionIndex,
                        )
                        return@Column
                    }
                    if (seasonForContent == MORE_LIKE_THIS_SELECTOR_SEASON && mergeMoreLikeThisIntoSelector) {
                        CompactMetaPreviewLandscapeRow(
                            items = moreLikeThis,
                            onItemClick = onMoreLikeThisClick,
                            focusedItemIndex = focusedMoreLikeThisIndex,
                        )
                        return@Column
                    }
                    val seasonEpisodes = groupedEpisodes.getValue(seasonForContent)
                    if (effectiveEpisodeCardStyle == MetaEpisodeCardStyle.Horizontal) {
                        EpisodeHorizontalRow(
                            episodes = seasonEpisodes,
                            maxWidthDp = containerWidthDp,
                            compactDesktopLayout = compactDesktopLayout,
                            parentMetaId = meta.id,
                            metaType = meta.type,
                            watchedKeys = watchedKeys,
                            fallbackImage = meta.background ?: meta.poster,
                            progressByVideoId = progressByVideoId,
                            episodeRatings = episodeRatings,
                            blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                            downloadedEpisodeVideoIds = downloadedEpisodeVideoIds,
                            // Only resume-scroll to the preferred episode on the season the
                            // user is actually up to; other seasons should start at episode 1.
                            // Absolute-numbered anime often carry no season on their episodes, so the
                            // up-next action has a null season — in that case fall through and let the
                            // row's own episode-number match decide where to land (otherwise the list
                            // was stuck at episode 1 for those titles).
                            preferredEpisodeNumber = preferredEpisodeNumber
                                ?.takeIf { preferredSeasonNumber == null || seasonForContent == preferredSeasonNumber },
                            focusedEpisodeIndex = focusedEpisodeIndex,
                            onEpisodeClick = onEpisodeClick,
                            onEpisodeLongPress = onEpisodeLongPress,
                        )
                    } else {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(sizing.cardGap),
                        ) {
                            seasonEpisodes.forEachIndexed { index, episode ->
                                val episodeVideoId = buildPlaybackVideoId(
                                    parentMetaId = meta.id,
                                    seasonNumber = episode.effectiveSeasonNumber(),
                                    episodeNumber = episode.effectiveEpisodeNumber(),
                                    fallbackVideoId = episode.id,
                                )
                                val episodeProgressEntry =
                                    progressByVideoId.progressForEpisodeVideo(episodeVideoId, episode.id)
                                NuvioShelfItemSlot(focused = index == focusedEpisodeIndex) {
                                    EpisodeListCard(
                                        video = episode,
                                        fallbackImage = meta.background ?: meta.poster,
                                        progressEntry = episodeProgressEntry,
                                        imdbRating = episode.seasonEpisodeKey()?.let { episodeRatings[it] },
                                        isDownloaded = episode.playbackVideoId(meta.id) in downloadedEpisodeVideoIds,
                                        isWatched = episodeProgressEntry?.isEffectivelyCompleted == true ||
                                            WatchingState.isEpisodeWatched(
                                                watchedKeys = watchedKeys,
                                                metaType = meta.type,
                                                metaId = meta.id,
                                                episode = episode,
                                            ),
                                        blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                                        sizing = sizing,
                                        onClick = { onEpisodeClick?.invoke(episode) },
                                        onLongPress = { onEpisodeLongPress?.invoke(episode) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DetailCompactMediaSelector(
    trailers: List<MetaTrailer>,
    collectionTitle: String? = null,
    collectionItems: List<MetaPreview> = emptyList(),
    moreLikeThis: List<MetaPreview>,
    modifier: Modifier = Modifier,
    selectedTabKey: Int? = null,
    focusedTabIndex: Int? = null,
    onTabSelected: ((Int) -> Unit)? = null,
    onTrailerClick: (MetaTrailer) -> Unit,
    onCollectionItemClick: (MetaPreview) -> Unit,
    onMoreLikeThisClick: (MetaPreview) -> Unit,
    focusedTrailerIndex: Int? = null,
    focusedCollectionIndex: Int? = null,
    focusedMoreLikeThisIndex: Int? = null,
) {
    val tabs = buildList {
        if (trailers.isNotEmpty()) add(CompactMediaSelectorTab.Trailers)
        if (collectionItems.isNotEmpty()) add(CompactMediaSelectorTab.Collection)
        if (moreLikeThis.isNotEmpty()) add(CompactMediaSelectorTab.MoreLikeThis)
    }
    if (tabs.isEmpty()) return

    var selectedTabOverride by rememberSaveable(trailers.size, collectionItems.size, moreLikeThis.size) {
        mutableStateOf<CompactMediaSelectorTab?>(null)
    }
    val selectedTab = selectedTabKey
        ?.let(CompactMediaSelectorTab::fromSelectorKey)
        ?.takeIf { it in tabs }
        ?: selectedTabOverride?.takeIf { it in tabs }
        ?: tabs.first()
    val selectTab: (CompactMediaSelectorTab) -> Unit = { tab ->
        selectedTabOverride = tab
        onTabSelected?.invoke(tab.selectorKey)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (tabs.size > 1) {
            CompactMediaSelectorTabRow(
                tabs = tabs,
                selectedTab = selectedTab,
                collectionTitle = collectionTitle,
                focusedTabIndex = focusedTabIndex,
                onSelect = selectTab,
            )
        }

        when (selectedTab) {
            CompactMediaSelectorTab.Trailers -> CompactTrailerLandscapeRow(
                trailers = trailers,
                onTrailerClick = onTrailerClick,
                focusedItemIndex = focusedTrailerIndex,
            )
            CompactMediaSelectorTab.Collection -> CompactMetaPreviewLandscapeRow(
                items = collectionItems,
                onItemClick = onCollectionItemClick,
                focusedItemIndex = focusedCollectionIndex,
            )
            CompactMediaSelectorTab.MoreLikeThis -> CompactMetaPreviewLandscapeRow(
                items = moreLikeThis,
                onItemClick = onMoreLikeThisClick,
                focusedItemIndex = focusedMoreLikeThisIndex,
            )
        }
    }
}

private enum class CompactMediaSelectorTab {
    Trailers,
    Collection,
    MoreLikeThis;

    val selectorKey: Int
        get() = when (this) {
            Trailers -> TRAILER_SELECTOR_SEASON
            Collection -> COLLECTION_SELECTOR_SEASON
            MoreLikeThis -> MORE_LIKE_THIS_SELECTOR_SEASON
        }

    companion object {
        fun fromSelectorKey(key: Int): CompactMediaSelectorTab? =
            when (key) {
                TRAILER_SELECTOR_SEASON -> Trailers
                COLLECTION_SELECTOR_SEASON -> Collection
                MORE_LIKE_THIS_SELECTOR_SEASON -> MoreLikeThis
                else -> null
            }
    }
}

@Composable
private fun CompactMediaSelectorTabRow(
    tabs: List<CompactMediaSelectorTab>,
    selectedTab: CompactMediaSelectorTab,
    collectionTitle: String? = null,
    focusedTabIndex: Int? = null,
    onSelect: (CompactMediaSelectorTab) -> Unit,
) {
    val listState = rememberLazyListState()

    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            // Only ever rendered in the fixed-height desktop hero overlay (no episodes, so
            // Trailers/Collection/More Like This are merged into this tab strip), which never
            // scrolls vertically — a plain wheel can scroll this row without requiring Shift.
            .desktopHorizontalListNavigation(listState, treatPlainScrollAsHorizontal = true),
        // Keep the first tab aligned with surrounding section text; trailing padding preserves
        // scroll breathing room at the end of the row.
        contentPadding = PaddingValues(start = 0.dp, end = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(tabs, key = { _, tab -> tab.name }) { index, tab ->
            val isSelected = tab == selectedTab
            val startPadding = if (index == 0) 0.dp else 14.dp
            NuvioShelfItemSlot(focused = index == focusedTabIndex) {
                Box(
                    modifier = Modifier
                        .clickable { onSelect(tab) }
                        .padding(start = startPadding, end = 14.dp, top = 10.dp, bottom = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = when (tab) {
                            CompactMediaSelectorTab.Trailers -> stringResource(Res.string.detail_trailers_title)
                            CompactMediaSelectorTab.Collection -> collectionTitle
                                ?.takeIf { it.isNotBlank() }
                                ?: stringResource(Res.string.settings_meta_collection)
                            CompactMediaSelectorTab.MoreLikeThis -> stringResource(Res.string.details_more_like_this)
                        },
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                        ),
                        // Highlight the active tab with the accent text colour instead of a boxed
                        // background (the box read as clutter); the shelf-slot scale still gives
                        // focus feedback, matching how episode thumbnails behave during navigation.
                        color = if (isSelected) {
                            MaterialTheme.nuvio.colors.accent
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SeasonViewModeToggle(
    mode: SeasonViewMode,
    sizing: SeriesContentSizing,
    onClick: () -> Unit,
) {
    val isPosters = mode == SeasonViewMode.Posters
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isPosters) {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                },
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = if (isPosters) 0.2f else 0.3f),
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (isPosters) {
                stringResource(Res.string.details_season_view_posters)
            } else {
                stringResource(Res.string.details_season_view_text)
            },
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = sizing.seasonToggleTextSize,
                fontWeight = FontWeight.SemiBold,
            ),
            color = if (isPosters) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onBackground
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SeasonTextChipScrollRow(
    seasons: List<Int>,
    currentSeason: Int,
    sizing: SeriesContentSizing,
    onSelect: (Int) -> Unit,
    onLongPress: ((Int) -> Unit)?,
    focusedSeasonIndex: Int? = null,
    collectionTitle: String? = null,
    // True in the fixed-height desktop hero overlay, which never scrolls vertically, so a
    // plain mouse wheel over this row can scroll it horizontally without requiring Shift.
    // False in the regular (tablet/mobile/tab-layout) vertically-scrolling season list, where
    // a plain wheel must still be free to scroll the page past this row.
    compactDesktopLayout: Boolean = false,
) {
    val seasonListState = rememberLazyListState()
    var hasPositionedSeasonRow by remember(seasons) { mutableStateOf(false) }

    LaunchedEffect(seasons, currentSeason) {
        val currentIndex = seasons.indexOf(currentSeason)
        if (currentIndex >= 0) {
            if (hasPositionedSeasonRow) {
                seasonListState.animateScrollToItem(currentIndex)
            } else {
                seasonListState.scrollToItem(currentIndex)
                hasPositionedSeasonRow = true
            }
        }
    }

    LaunchedEffect(focusedSeasonIndex) {
        val target = focusedSeasonIndex
        if (target == null || target !in seasons.indices) return@LaunchedEffect
        val layoutInfo = seasonListState.layoutInfo
        val isFullyVisible = layoutInfo.visibleItemsInfo.any { item ->
            item.index == target &&
                item.offset >= layoutInfo.viewportStartOffset &&
                item.offset + item.size <= layoutInfo.viewportEndOffset
        }
        if (!isFullyVisible) {
            seasonListState.animateScrollToItem(target)
        }
    }

    LazyRow(
        state = seasonListState,
        modifier = Modifier
            .fillMaxWidth()
            .desktopHorizontalListNavigation(
                seasonListState,
                treatPlainScrollAsHorizontal = compactDesktopLayout,
            ),
        // Keep the first chip aligned with surrounding section text; trailing padding preserves
        // scroll breathing room at the end of the row.
        contentPadding = PaddingValues(start = 0.dp, end = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(sizing.seasonChipGap),
    ) {
        itemsIndexed(seasons, key = { _, season -> season }) { index, season ->
            val isSelected = season == currentSeason
            val onSecondaryClick = onLongPress?.let { handler -> { handler(season) } }
            val startPadding = if (index == 0) 0.dp else sizing.seasonChipHorizontalPadding
            NuvioShelfItemSlot(focused = index == focusedSeasonIndex) {
                Box(
                    modifier = Modifier
                        .combinedClickable(
                            onClick = { onSelect(season) },
                            onLongClick = onSecondaryClick,
                        )
                        .secondaryClick(onSecondaryClick)
                        .padding(
                            start = startPadding,
                            end = sizing.seasonChipHorizontalPadding,
                            top = sizing.seasonChipVerticalPadding,
                            bottom = sizing.seasonChipVerticalPadding,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = season.label(collectionTitle),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = sizing.seasonChipTextSize,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                        ),
                        color = if (isSelected) {
                            MaterialTheme.nuvio.colors.accent
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SeasonPosterScrollRow(
    seasons: List<Int>,
    groupedEpisodes: Map<Int, List<MetaVideo>>,
    meta: MetaDetails,
    currentSeason: Int,
    sizing: SeriesContentSizing,
    onSelect: (Int) -> Unit,
    onLongPress: ((Int) -> Unit)?,
    focusedSeasonIndex: Int? = null,
) {
    val seasonListState = rememberLazyListState()
    var hasPositionedSeasonRow by remember(seasons) { mutableStateOf(false) }

    LaunchedEffect(seasons, currentSeason) {
        val currentIndex = seasons.indexOf(currentSeason)
        if (currentIndex >= 0) {
            if (hasPositionedSeasonRow) {
                seasonListState.animateScrollToItem(currentIndex)
            } else {
                seasonListState.scrollToItem(currentIndex)
                hasPositionedSeasonRow = true
            }
        }
    }

    LaunchedEffect(focusedSeasonIndex) {
        val target = focusedSeasonIndex
        if (target == null || target !in seasons.indices) return@LaunchedEffect
        val layoutInfo = seasonListState.layoutInfo
        val isFullyVisible = layoutInfo.visibleItemsInfo.any { item ->
            item.index == target &&
                item.offset >= layoutInfo.viewportStartOffset &&
                item.offset + item.size <= layoutInfo.viewportEndOffset
        }
        if (!isFullyVisible) {
            seasonListState.animateScrollToItem(target)
        }
    }

    LazyRow(
        state = seasonListState,
        modifier = Modifier
            .fillMaxWidth()
            .desktopHorizontalListNavigation(seasonListState),
        horizontalArrangement = Arrangement.spacedBy(sizing.seasonChipGap),
    ) {
        itemsIndexed(seasons, key = { _, season -> season }) { index, season ->
            NuvioShelfItemSlot(focused = index == focusedSeasonIndex) {
                SeasonPosterButton(
                    label = season.label(),
                    imageUrl = groupedEpisodes[season]
                        .orEmpty()
                        .firstNotNullOfOrNull { episode -> episode.seasonPoster }
                        ?: meta.poster
                        ?: meta.background,
                    isSelected = season == currentSeason,
                    sizing = sizing,
                    onClick = { onSelect(season) },
                    onLongClick = onLongPress?.let { handler -> { handler(season) } },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SeasonPosterButton(
    label: String,
    imageUrl: String?,
    isSelected: Boolean,
    sizing: SeriesContentSizing,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .width(sizing.seasonPosterWidth)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .secondaryClick(onLongClick),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(sizing.seasonPosterHeight)
                .clip(RoundedCornerShape(sizing.seasonPosterRadius))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .border(
                    width = if (isSelected) 2.dp else 1.dp,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.White.copy(alpha = 0.1f)
                    },
                    shape = RoundedCornerShape(sizing.seasonPosterRadius),
                ),
        ) {
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = label,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.padding(horizontal = 12.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = sizing.seasonChipTextSize,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
            ),
            color = if (isSelected) {
                MaterialTheme.colorScheme.onBackground
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeHorizontalRow(
    episodes: List<MetaVideo>,
    maxWidthDp: Float,
    parentMetaId: String,
    metaType: String,
    watchedKeys: Set<String>,
    fallbackImage: String?,
    progressByVideoId: Map<String, WatchProgressEntry>,
    episodeRatings: Map<Pair<Int, Int>, Double>,
    blurUnwatchedEpisodes: Boolean,
    /** Playback video ids that resolve to a file in the local library. */
    downloadedEpisodeVideoIds: Set<String>,
    preferredEpisodeNumber: Int? = null,
    focusedEpisodeIndex: Int? = null,
    compactDesktopLayout: Boolean = false,
    onEpisodeClick: ((MetaVideo) -> Unit)?,
    onEpisodeLongPress: ((MetaVideo) -> Unit)?,
) {
    val rowMetrics = rememberEpisodeHorizontalCardMetrics(maxWidthDp, compactDesktopLayout)
    val listState = rememberLazyListState()
    var hasPositioned by remember(episodes) { mutableStateOf(false) }
    val itemExtentPx = with(LocalDensity.current) { (rowMetrics.cardWidth + rowMetrics.itemSpacing).toPx() }

    LaunchedEffect(episodes, preferredEpisodeNumber) {
        val targetIndex = if (preferredEpisodeNumber != null) {
            episodes.indexOfFirst { it.effectiveEpisodeNumber() == preferredEpisodeNumber }
        } else {
            -1
        }
        if (targetIndex >= 0) {
            if (hasPositioned) {
                listState.animateScrollToItem(targetIndex)
            } else {
                listState.scrollToItem(targetIndex)
                hasPositioned = true
            }
        }
    }

    LaunchedEffect(focusedEpisodeIndex) {
        val target = focusedEpisodeIndex
        if (target == null || target !in episodes.indices) return@LaunchedEffect
        val layoutInfo = listState.layoutInfo
        val isFullyVisible = layoutInfo.visibleItemsInfo.any { item ->
            item.index == target &&
                item.offset >= layoutInfo.viewportStartOffset &&
                item.offset + item.size <= layoutInfo.viewportEndOffset
        }
        if (!isFullyVisible) {
            listState.animateScrollToItem(target)
        }
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .desktopHorizontalListNavigation(
                listState,
                scrollStepPx = itemExtentPx,
                treatPlainScrollAsHorizontal = compactDesktopLayout,
                handlePageAndEdgeKeys = true,
            ),
        contentPadding = PaddingValues(
            start = 0.dp,
            top = rowMetrics.rowVerticalPadding,
            end = rowMetrics.rowHorizontalPadding,
            bottom = rowMetrics.rowVerticalPadding,
        ),
        horizontalArrangement = Arrangement.spacedBy(rowMetrics.itemSpacing),
    ) {
        itemsIndexed(
            items = episodes,
            key = { index, episode ->
                "${episode.effectiveSeasonNumber()}:${episode.effectiveEpisodeNumber()}:${episode.id}#$index"
            },
        ) { index, episode ->
            val episodeVideoId = buildPlaybackVideoId(
                parentMetaId = parentMetaId,
                seasonNumber = episode.effectiveSeasonNumber(),
                episodeNumber = episode.effectiveEpisodeNumber(),
                fallbackVideoId = episode.id,
            )
            val episodeProgressEntry = progressByVideoId.progressForEpisodeVideo(episodeVideoId, episode.id)
            NuvioShelfItemSlot(focused = index == focusedEpisodeIndex) {
                EpisodeHorizontalCard(
                    video = episode,
                    fallbackImage = fallbackImage,
                    progressEntry = episodeProgressEntry,
                    imdbRating = episode.seasonEpisodeKey()?.let { episodeRatings[it] },
                    isDownloaded = episodeVideoId in downloadedEpisodeVideoIds,
                    isWatched = episodeProgressEntry?.isEffectivelyCompleted == true ||
                        WatchingState.isEpisodeWatched(
                            watchedKeys = watchedKeys,
                            metaType = metaType,
                            metaId = parentMetaId,
                            episode = episode,
                        ),
                    blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                    metrics = rowMetrics,
                    onClick = { onEpisodeClick?.invoke(episode) },
                    onLongPress = { onEpisodeLongPress?.invoke(episode) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeHorizontalCard(
    video: MetaVideo,
    fallbackImage: String?,
    progressEntry: WatchProgressEntry?,
    imdbRating: Double?,
    isWatched: Boolean,
    isDownloaded: Boolean,
    blurUnwatchedEpisodes: Boolean,
    metrics: EpisodeHorizontalCardMetrics,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
) {
    val cardShape = RoundedCornerShape(metrics.cornerRadius)
    val ratingLabel = remember(imdbRating) { imdbRating?.takeIf { it > 0.0 }?.let(::formatEpisodeRating) }
    val formattedDate = remember(video.released) { video.released?.let { formatEpisodeThumbnailDate(it) } }
    Box(
        modifier = Modifier
            .width(metrics.cardWidth)
            .height(metrics.cardHeight)
            .clip(cardShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .nuvioCardDepth(cardShape, NuvioCardDepthSurface.Episodes)
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.12f),
                shape = cardShape,
            )
            .combinedClickable(
                enabled = onClick != null || onLongPress != null,
                onClick = { onClick?.invoke() },
                onLongClick = onLongPress,
            )
            .secondaryClick(onLongPress),
    ) {
        val imageUrl = video.thumbnail ?: fallbackImage
        val shouldBlurArtwork = blurUnwatchedEpisodes && !isWatched
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = video.title,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (shouldBlurArtwork) Modifier.blur(18.dp) else Modifier),
                contentScale = ContentScale.Crop,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.10f),
                            Color.Black.copy(alpha = 0.42f),
                            Color.Black.copy(alpha = 0.78f),
                        ),
                    ),
                ),
        )

        // Its own overlay rather than a slot in the top row: the date and episode badges either
        // side are different widths, so anything inside that row would sit off-centre.
        if (isDownloaded) {
            EpisodeDownloadedBadge(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(metrics.contentPadding),
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(metrics.contentPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            formattedDate?.let { date ->
                CardOverlayBadge(
                    text = date,
                    textSize = metrics.metaTextSize,
                    radius = metrics.badgeRadius,
                    horizontalPadding = metrics.badgeHorizontalPadding,
                    verticalPadding = metrics.badgeVerticalPadding,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            CardOverlayBadge(
                text = video.episodeBadge(),
                textSize = metrics.badgeTextSize,
                radius = metrics.badgeRadius,
                horizontalPadding = metrics.badgeHorizontalPadding,
                verticalPadding = metrics.badgeVerticalPadding,
            )
        }

        NuvioAnimatedWatchedBadge(
            isVisible = isWatched,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(
                    top = metrics.contentPadding + 24.dp,
                    end = metrics.contentPadding,
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(
                    start = metrics.contentPadding,
                    end = metrics.contentPadding,
                    top = metrics.contentPadding,
                    bottom = metrics.contentBottomPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = video.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = metrics.titleTextSize,
                        fontWeight = FontWeight.ExtraBold,
                        lineHeight = metrics.titleLineHeight,
                    ),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ratingLabel?.let { rating ->
                    ImdbEpisodeRatingBadge(
                        rating = rating,
                        logoWidth = metrics.imdbLogoWidth,
                        logoHeight = metrics.imdbLogoHeight,
                        textSize = metrics.metaTextSize,
                    )
                }
            }

            if (!video.overview.isNullOrBlank()) {
                Text(
                    text = video.overview,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = metrics.bodyTextSize,
                        lineHeight = metrics.bodyLineHeight,
                    ),
                    color = Color.White.copy(alpha = 0.86f),
                    maxLines = metrics.overviewMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }

        }

        progressEntry
            ?.takeIf { it.durationMs > 0L && !it.isCompleted }
            ?.let { entry ->
                NuvioProgressBar(
                    progress = entry.progressFraction,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = metrics.contentPadding, vertical = 8.dp),
                    height = 4.dp,
                    trackColor = Color.White.copy(alpha = 0.22f),
                    fillColor = MaterialTheme.colorScheme.primary,
                )
            }
    }
}

private data class EpisodeHorizontalCardMetrics(
    val rowHorizontalPadding: Dp,
    val rowVerticalPadding: Dp,
    val itemSpacing: Dp,
    val cardWidth: Dp,
    val cardHeight: Dp,
    val cornerRadius: Dp,
    val contentPadding: Dp,
    val contentBottomPadding: Dp,
    val titleTextSize: androidx.compose.ui.unit.TextUnit,
    val titleLineHeight: androidx.compose.ui.unit.TextUnit,
    val bodyTextSize: androidx.compose.ui.unit.TextUnit,
    val bodyLineHeight: androidx.compose.ui.unit.TextUnit,
    val overviewMaxLines: Int,
    val metaTextSize: androidx.compose.ui.unit.TextUnit,
    val badgeTextSize: androidx.compose.ui.unit.TextUnit,
    val badgeRadius: Dp,
    val badgeHorizontalPadding: Dp,
    val badgeVerticalPadding: Dp,
    val imdbLogoWidth: Dp,
    val imdbLogoHeight: Dp,
)

@Composable
private fun CompactTrailerLandscapeRow(
    trailers: List<MetaTrailer>,
    onTrailerClick: (MetaTrailer) -> Unit,
    focusedItemIndex: Int? = null,
) {
    if (trailers.isEmpty()) return

    val listState = rememberLazyListState()
    val cardWidth = 330.dp
    val itemSpacing = 14.dp
    val itemExtentPx = with(LocalDensity.current) { (cardWidth + itemSpacing).toPx() }

    LaunchedEffect(focusedItemIndex, trailers) {
        val target = focusedItemIndex
        if (target == null || target !in trailers.indices) return@LaunchedEffect
        val layoutInfo = listState.layoutInfo
        val isFullyVisible = layoutInfo.visibleItemsInfo.any { item ->
            item.index == target &&
                item.offset >= layoutInfo.viewportStartOffset &&
                item.offset + item.size <= layoutInfo.viewportEndOffset
        }
        if (!isFullyVisible) {
            listState.animateScrollToItem(target)
        }
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            // Always rendered in the fixed-height desktop hero overlay, which never scrolls
            // vertically — a plain wheel can scroll this row without requiring Shift.
            .desktopHorizontalListNavigation(
                listState,
                scrollStepPx = itemExtentPx,
                treatPlainScrollAsHorizontal = true,
            ),
        // Room on every edge so the focus-scale of a highlighted card isn't clipped by the row
        // bounds — most visible on the first card, which has no neighbor to its left to absorb
        // the scale-up into.
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(itemSpacing),
    ) {
        itemsIndexed(
            items = trailers,
            key = { index, trailer -> "${trailer.type}-${trailer.id}-${trailer.seasonNumber ?: 0}#$index" },
        ) { index, trailer ->
            NuvioShelfItemSlot(focused = index == focusedItemIndex) {
                CompactTrailerTextBelowCard(
                    trailer = trailer,
                    width = cardWidth,
                    onClick = { onTrailerClick(trailer) },
                )
            }
        }
    }
}

@Composable
private fun CompactTrailerTextBelowCard(
    trailer: MetaTrailer,
    width: Dp,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(width)
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        ) {
            AsyncImage(
                model = "https://img.youtube.com/vi/${trailer.key}/hqdefault.jpg",
                contentDescription = trailer.displayName ?: trailer.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        Text(
            text = trailer.displayName ?: trailer.name,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 19.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CompactTrailerLandscapeCard(
    trailer: MetaTrailer,
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = "https://img.youtube.com/vi/${trailer.key}/hqdefault.jpg",
            contentDescription = trailer.displayName ?: trailer.name,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )

        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.44f to Color.Black.copy(alpha = 0.08f),
                        1f to Color.Black.copy(alpha = 0.78f),
                    ),
                ),
        )

        CompactLandscapeTopMetaRow(
            startText = trailer.type.takeIf { it.isNotBlank() },
            endText = trailer.publishedAt?.take(4)?.takeIf { it.isNotBlank() },
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = trailer.displayName ?: trailer.name,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 19.sp,
                ),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    trailer.site.takeIf { it.isNotBlank() },
                    trailer.size?.let { "${it}p" },
                ).joinToString(" • ").ifBlank { trailer.type },
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                ),
                color = Color.White.copy(alpha = 0.82f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CompactMetaPreviewLandscapeRow(
    items: List<MetaPreview>,
    onItemClick: (MetaPreview) -> Unit,
    focusedItemIndex: Int? = null,
) {
    if (items.isEmpty()) return

    val listState = rememberLazyListState()
    val cardWidth = 330.dp
    val cardHeight = 196.dp
    val itemSpacing = 14.dp
    val itemExtentPx = with(LocalDensity.current) { (cardWidth + itemSpacing).toPx() }

    LaunchedEffect(focusedItemIndex, items) {
        val target = focusedItemIndex
        if (target == null || target !in items.indices) return@LaunchedEffect
        val layoutInfo = listState.layoutInfo
        val isFullyVisible = layoutInfo.visibleItemsInfo.any { item ->
            item.index == target &&
                item.offset >= layoutInfo.viewportStartOffset &&
                item.offset + item.size <= layoutInfo.viewportEndOffset
        }
        if (!isFullyVisible) {
            listState.animateScrollToItem(target)
        }
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            // Always rendered in the fixed-height desktop hero overlay, which never scrolls
            // vertically — a plain wheel can scroll this row without requiring Shift.
            .desktopHorizontalListNavigation(
                listState,
                scrollStepPx = itemExtentPx,
                treatPlainScrollAsHorizontal = true,
            ),
        // Room on every edge so the focus-scale of a highlighted card isn't clipped by the row
        // bounds — most visible on the first card, which has no neighbor to its left to absorb
        // the scale-up into.
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(itemSpacing),
    ) {
        itemsIndexed(
            items = items,
            key = { index, item -> "${item.type}:${item.id}:$index" },
        ) { index, item ->
            NuvioShelfItemSlot(focused = index == focusedItemIndex) {
                CompactMetaPreviewLandscapeCard(
                    item = item,
                    width = cardWidth,
                    height = cardHeight,
                    onClick = { onItemClick(item) },
                )
            }
        }
    }
}

@Composable
private fun CompactMetaPreviewLandscapeCard(
    item: MetaPreview,
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = item.banner ?: item.posterFallback ?: item.poster,
            contentDescription = item.name,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )

        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.48f to Color.Black.copy(alpha = 0.08f),
                        1f to Color.Black.copy(alpha = 0.74f),
                    ),
                ),
        )

        CompactLandscapeTopMetaRow(
            startText = item.runtime?.takeIf { it.isNotBlank() },
            endText = item.releaseInfo?.takeIf { it.isNotBlank() },
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 19.sp,
                ),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    ),
                    color = Color.White.copy(alpha = 0.82f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val meta = listOfNotNull(
                item.releaseInfo?.takeIf { it.isNotBlank() },
                item.runtime?.takeIf { it.isNotBlank() },
            ).joinToString(" • ")
            if (false && meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = Color.White.copy(alpha = 0.82f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun rememberEpisodeHorizontalCardMetrics(
    maxWidthDp: Float,
    compactDesktopLayout: Boolean = false,
): EpisodeHorizontalCardMetrics {
    return remember(maxWidthDp, compactDesktopLayout) {
        if (compactDesktopLayout) {
            return@remember EpisodeHorizontalCardMetrics(
                rowHorizontalPadding = 10.dp,
                rowVerticalPadding = 8.dp,
                itemSpacing = 14.dp,
                cardWidth = 330.dp,
                cardHeight = 196.dp,
                cornerRadius = 12.dp,
                contentPadding = 12.dp,
                contentBottomPadding = 14.dp,
                titleTextSize = 15.sp,
                titleLineHeight = 19.sp,
                bodyTextSize = 11.sp,
                bodyLineHeight = 15.sp,
                overviewMaxLines = 2,
                metaTextSize = 10.sp,
                badgeTextSize = 9.sp,
                badgeRadius = 5.dp,
                badgeHorizontalPadding = 7.dp,
                badgeVerticalPadding = 3.dp,
                imdbLogoWidth = 22.dp,
                imdbLogoHeight = 11.dp,
            )
        }
        when {
            maxWidthDp >= 1300f -> EpisodeHorizontalCardMetrics(
                rowHorizontalPadding = 10.dp,
                rowVerticalPadding = 8.dp,
                itemSpacing = 18.dp,
                cardWidth = 420.dp,
                cardHeight = 256.dp,
                cornerRadius = 18.dp,
                contentPadding = 16.dp,
                contentBottomPadding = 18.dp,
                titleTextSize = 18.sp,
                titleLineHeight = 24.sp,
                bodyTextSize = 14.sp,
                bodyLineHeight = 20.sp,
                overviewMaxLines = 3,
                metaTextSize = 12.sp,
                badgeTextSize = 11.sp,
                badgeRadius = 8.dp,
                badgeHorizontalPadding = 10.dp,
                badgeVerticalPadding = 5.dp,
                imdbLogoWidth = 28.dp,
                imdbLogoHeight = 14.dp,
            )

            maxWidthDp >= 1000f -> EpisodeHorizontalCardMetrics(
                rowHorizontalPadding = 10.dp,
                rowVerticalPadding = 8.dp,
                itemSpacing = 16.dp,
                cardWidth = 384.dp,
                cardHeight = 236.dp,
                cornerRadius = 16.dp,
                contentPadding = 14.dp,
                contentBottomPadding = 16.dp,
                titleTextSize = 17.sp,
                titleLineHeight = 22.sp,
                bodyTextSize = 13.sp,
                bodyLineHeight = 18.sp,
                overviewMaxLines = 3,
                metaTextSize = 12.sp,
                badgeTextSize = 10.sp,
                badgeRadius = 7.dp,
                badgeHorizontalPadding = 9.dp,
                badgeVerticalPadding = 4.dp,
                imdbLogoWidth = 26.dp,
                imdbLogoHeight = 13.dp,
            )

            maxWidthDp >= 760f -> EpisodeHorizontalCardMetrics(
                rowHorizontalPadding = 10.dp,
                rowVerticalPadding = 8.dp,
                itemSpacing = 14.dp,
                cardWidth = 340.dp,
                cardHeight = 212.dp,
                cornerRadius = 14.dp,
                contentPadding = 12.dp,
                contentBottomPadding = 14.dp,
                titleTextSize = 16.sp,
                titleLineHeight = 21.sp,
                bodyTextSize = 12.sp,
                bodyLineHeight = 17.sp,
                overviewMaxLines = 2,
                metaTextSize = 11.sp,
                badgeTextSize = 10.sp,
                badgeRadius = 6.dp,
                badgeHorizontalPadding = 8.dp,
                badgeVerticalPadding = 4.dp,
                imdbLogoWidth = 24.dp,
                imdbLogoHeight = 12.dp,
            )

            else -> EpisodeHorizontalCardMetrics(
                rowHorizontalPadding = 10.dp,
                rowVerticalPadding = 8.dp,
                itemSpacing = 12.dp,
                cardWidth = 296.dp,
                cardHeight = 184.dp,
                cornerRadius = 14.dp,
                contentPadding = 10.dp,
                contentBottomPadding = 12.dp,
                titleTextSize = 14.sp,
                titleLineHeight = 19.sp,
                bodyTextSize = 11.sp,
                bodyLineHeight = 15.sp,
                overviewMaxLines = 2,
                metaTextSize = 10.sp,
                badgeTextSize = 9.sp,
                badgeRadius = 5.dp,
                badgeHorizontalPadding = 7.dp,
                badgeVerticalPadding = 3.dp,
                imdbLogoWidth = 22.dp,
                imdbLogoHeight = 11.dp,
            )
        }
    }
}

private fun formatEpisodeRuntime(runtimeMinutes: Int): String {
    return formatRuntimeFromMinutes(runtimeMinutes)
}

/**
 * Episode thumbnail date badge: "16 Dec 2021" (day, then month, then year) instead of the
 * shared formatReleaseDateForDisplay's "2021 December 16". Deliberately scoped to this
 * screen's thumbnails rather than changing the shared formatter, which also drives the
 * catalog/home/search date displays elsewhere.
 */
private fun formatEpisodeThumbnailDate(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return raw
    val datePart = trimmed.substringBefore('T').trim()
    val parts = datePart.split('-')
    if (parts.size != 3) return raw
    val year = parts[0].toIntOrNull() ?: return raw
    val month = parts[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return raw
    val day = parts[2].toIntOrNull()?.takeIf { it in 1..31 } ?: return raw
    return "$day ${compactMonthAbbreviation(month)} $year"
}

/**
 * A user-requested, non-standard abbreviation set: unlike the generic 3-letter short month
 * names elsewhere in the app, March/April/May/June/July stay unabbreviated (already short or
 * abbreviating them saves little) and September shortens to "Sept" rather than "Sep".
 */
private fun compactMonthAbbreviation(month: Int): String = when (month) {
    1 -> "Jan"
    2 -> "Feb"
    3 -> "March"
    4 -> "April"
    5 -> "May"
    6 -> "June"
    7 -> "July"
    8 -> "Aug"
    9 -> "Sept"
    10 -> "Oct"
    11 -> "Nov"
    12 -> "Dec"
    else -> month.toString()
}

@Composable
private fun CardOverlayText(
    text: String,
    textSize: androidx.compose.ui.unit.TextUnit,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = textSize,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.sp,
        ),
        color = Color.White.copy(alpha = 0.9f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun CompactLandscapeTopMetaRow(
    startText: String?,
    endText: String?,
) {
    if (startText.isNullOrBlank() && endText.isNullOrBlank()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        startText?.takeIf { it.isNotBlank() }?.let { text ->
            CardOverlayBadge(
                text = text,
                textSize = 10.sp,
                radius = 5.dp,
                horizontalPadding = 7.dp,
                verticalPadding = 3.dp,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        endText?.takeIf { it.isNotBlank() }?.let { text ->
            CardOverlayBadge(
                text = text,
                textSize = 10.sp,
                radius = 5.dp,
                horizontalPadding = 7.dp,
                verticalPadding = 3.dp,
            )
        }
    }
}

/** Marks an episode that already exists in the local library. */
@Composable
private fun EpisodeDownloadedBadge(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.62f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Download,
            contentDescription = stringResource(Res.string.episodes_cd_downloaded),
            tint = Color.White,
            modifier = Modifier.size(12.dp),
        )
    }
}

@Composable
private fun CardOverlayBadge(
    text: String,
    textSize: androidx.compose.ui.unit.TextUnit,
    radius: Dp,
    horizontalPadding: Dp,
    verticalPadding: Dp,
) {
    EpisodeCodeBadge(
        text = text,
        textSize = textSize,
        radius = radius,
        horizontalPadding = horizontalPadding,
        verticalPadding = verticalPadding,
        backgroundAlpha = 0.52f,
    )
}

@Composable
private fun EpisodeCodeBadge(
    text: String,
    textSize: androidx.compose.ui.unit.TextUnit,
    radius: Dp,
    horizontalPadding: Dp,
    verticalPadding: Dp,
    backgroundAlpha: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(radius))
            .background(Color.Black.copy(alpha = backgroundAlpha))
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = textSize,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.sp,
            ),
            color = Color.White.copy(alpha = 0.9f),
            maxLines = 1,
        )
    }
}

@Composable
private fun ImdbEpisodeRatingBadge(
    rating: String,
    logoWidth: Dp,
    logoHeight: Dp,
    textSize: androidx.compose.ui.unit.TextUnit,
) {
    val chipShape = RoundedCornerShape(4.dp)
    Row(
        modifier = Modifier
            .height(logoHeight + 4.dp)
            .clip(chipShape)
            .background(Color.Black.copy(alpha = 0.72f))
            .border(
                width = 0.75.dp,
                color = Color.White.copy(alpha = 0.18f),
                shape = chipShape,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .widthIn(min = logoWidth + 4.dp)
                .fillMaxHeight()
                .background(Color(0xFFF5C518)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(Res.string.source_imdb),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = textSize * 0.74f,
                    lineHeight = textSize * 0.74f,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.2).sp,
                ),
                color = Color.Black,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
        Box(
            modifier = Modifier
                .width(logoWidth + 2.dp)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = rating,
                modifier = Modifier.offset(y = (-1).dp),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = textSize,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = textSize,
                ),
                color = Color.White.copy(alpha = 0.96f),
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeListCard(
    video: MetaVideo,
    fallbackImage: String?,
    progressEntry: WatchProgressEntry?,
    imdbRating: Double?,
    isWatched: Boolean,
    isDownloaded: Boolean,
    blurUnwatchedEpisodes: Boolean,
    sizing: SeriesContentSizing,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
) {
    val cardShape = RoundedCornerShape(sizing.cardRadius)
    val ratingLabel = remember(imdbRating) { imdbRating?.takeIf { it > 0.0 }?.let(::formatEpisodeRating) }
    val formattedDate = remember(video.released) { video.released?.let { formatEpisodeThumbnailDate(it) } }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(sizing.cardHeight)
            .clip(cardShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.1f),
                shape = cardShape,
            )
            .combinedClickable(
                enabled = onClick != null || onLongPress != null,
                onClick = { onClick?.invoke() },
                onLongClick = onLongPress,
            )
            .secondaryClick(onLongPress),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
        ) {
            // Image area - fixed width matching card height per spec
            Box(
                modifier = Modifier
                    .width(sizing.imageWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = sizing.cardRadius, bottomStart = sizing.cardRadius)),
            ) {
                val imageUrl = video.thumbnail ?: fallbackImage
                val shouldBlurArtwork = blurUnwatchedEpisodes && !isWatched
                if (imageUrl != null) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = video.title,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (shouldBlurArtwork) Modifier.blur(18.dp) else Modifier),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface),
                    )
                }

                if (isDownloaded) {
                    EpisodeDownloadedBadge(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(sizing.badgeVerticalPadding),
                    )
                }

                EpisodeCodeBadge(
                    text = video.episodeBadge(),
                    textSize = sizing.badgeTextSize,
                    radius = sizing.badgeRadius,
                    horizontalPadding = sizing.badgeHorizontalPadding,
                    verticalPadding = sizing.badgeVerticalPadding,
                    backgroundAlpha = 0.85f,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 8.dp, top = 8.dp),
                )

                NuvioAnimatedWatchedBadge(
                    isVisible = isWatched,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    .padding(
                        start = sizing.contentHorizontalPadding,
                        end = sizing.contentHorizontalPadding,
                        top = sizing.contentVerticalPadding,
                        bottom = sizing.contentVerticalPadding,
                    ),
                verticalArrangement = Arrangement.spacedBy(sizing.contentSpacing),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = video.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = sizing.titleTextSize,
                            fontWeight = FontWeight.Bold,
                            lineHeight = sizing.titleLineHeight,
                            letterSpacing = 0.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ratingLabel?.let { rating ->
                        ImdbEpisodeRatingBadge(
                            rating = rating,
                            logoWidth = 24.dp,
                            logoHeight = 12.dp,
                            textSize = sizing.metaTextSize,
                        )
                    }
                }

                if (formattedDate != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        formattedDate?.let { date ->
                            Text(
                                text = date,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontSize = sizing.metaTextSize,
                                    fontWeight = FontWeight.Medium,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                if (!video.overview.isNullOrBlank()) {
                    Text(
                        text = video.overview,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = sizing.bodyTextSize,
                            lineHeight = sizing.bodyLineHeight,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = sizing.overviewMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        progressEntry
            ?.takeIf { it.durationMs > 0L && !it.isCompleted }
            ?.let { entry ->
                NuvioProgressBar(
                    progress = entry.progressFraction,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .width(sizing.imageWidth - 24.dp)
                        .padding(start = 12.dp, bottom = 10.dp),
                    height = 5.dp,
                    trackColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.14f),
                    fillColor = MaterialTheme.colorScheme.primary,
                )
            }
    }
}

private data class SeriesContentSizing(
    val seasonHeaderSize: androidx.compose.ui.unit.TextUnit,
    val seasonToggleTextSize: androidx.compose.ui.unit.TextUnit,
    val seasonChipGap: Dp,
    val seasonChipRadius: Dp,
    val seasonChipHorizontalPadding: Dp,
    val seasonChipVerticalPadding: Dp,
    val seasonChipTextSize: androidx.compose.ui.unit.TextUnit,
    val seasonPosterWidth: Dp,
    val seasonPosterHeight: Dp,
    val seasonPosterRadius: Dp,
    val cardHeight: Dp,
    val imageWidth: Dp,
    val cardRadius: Dp,
    val cardGap: Dp,
    val contentHorizontalPadding: Dp,
    val contentVerticalPadding: Dp,
    val contentSpacing: Dp,
    val titleTextSize: androidx.compose.ui.unit.TextUnit,
    val titleLineHeight: androidx.compose.ui.unit.TextUnit,
    val titleMaxLines: Int,
    val bodyTextSize: androidx.compose.ui.unit.TextUnit,
    val bodyLineHeight: androidx.compose.ui.unit.TextUnit,
    val overviewMaxLines: Int,
    val metaTextSize: androidx.compose.ui.unit.TextUnit,
    val badgeTextSize: androidx.compose.ui.unit.TextUnit,
    val badgeRadius: Dp,
    val badgeHorizontalPadding: Dp,
    val badgeVerticalPadding: Dp,
)

private fun seriesContentSizing(
    maxWidthDp: Float,
    compactDesktopLayout: Boolean = false,
): SeriesContentSizing =
    if (compactDesktopLayout) {
        SeriesContentSizing(
            seasonHeaderSize = 24.sp,
            seasonToggleTextSize = 13.sp,
            seasonChipGap = 14.dp,
            seasonChipRadius = 10.dp,
            seasonChipHorizontalPadding = 14.dp,
            seasonChipVerticalPadding = 10.dp,
            seasonChipTextSize = 14.sp,
            seasonPosterWidth = 96.dp,
            seasonPosterHeight = 144.dp,
            seasonPosterRadius = 10.dp,
            cardHeight = 150.dp,
            imageWidth = 150.dp,
            cardRadius = 14.dp,
            cardGap = 14.dp,
            contentHorizontalPadding = 14.dp,
            contentVerticalPadding = 12.dp,
            contentSpacing = 6.dp,
            titleTextSize = 15.sp,
            titleLineHeight = 19.sp,
            titleMaxLines = 2,
            bodyTextSize = 13.sp,
            bodyLineHeight = 18.sp,
            overviewMaxLines = 2,
            metaTextSize = 11.sp,
            badgeTextSize = 10.sp,
            badgeRadius = 4.dp,
            badgeHorizontalPadding = 6.dp,
            badgeVerticalPadding = 2.dp,
        )
    } else
    when {
        maxWidthDp >= 1440f -> SeriesContentSizing(
            seasonHeaderSize = 28.sp,
            seasonToggleTextSize = 16.sp,
            seasonChipGap = 20.dp,
            seasonChipRadius = 16.dp,
            seasonChipHorizontalPadding = 20.dp,
            seasonChipVerticalPadding = 16.dp,
            seasonChipTextSize = 16.sp,
            seasonPosterWidth = 140.dp,
            seasonPosterHeight = 210.dp,
            seasonPosterRadius = 16.dp,
            cardHeight = 200.dp,
            imageWidth = 200.dp,
            cardRadius = 20.dp,
            cardGap = 20.dp,
            contentHorizontalPadding = 20.dp,
            contentVerticalPadding = 18.dp,
            contentSpacing = 8.dp,
            titleTextSize = 18.sp,
            titleLineHeight = 24.sp,
            titleMaxLines = 3,
            bodyTextSize = 15.sp,
            bodyLineHeight = 22.sp,
            overviewMaxLines = 4,
            metaTextSize = 13.sp,
            badgeTextSize = 13.sp,
            badgeRadius = 6.dp,
            badgeHorizontalPadding = 8.dp,
            badgeVerticalPadding = 4.dp,
        )
        maxWidthDp >= 1024f -> SeriesContentSizing(
            seasonHeaderSize = 26.sp,
            seasonToggleTextSize = 15.sp,
            seasonChipGap = 18.dp,
            seasonChipRadius = 14.dp,
            seasonChipHorizontalPadding = 18.dp,
            seasonChipVerticalPadding = 14.dp,
            seasonChipTextSize = 15.sp,
            seasonPosterWidth = 130.dp,
            seasonPosterHeight = 195.dp,
            seasonPosterRadius = 14.dp,
            cardHeight = 180.dp,
            imageWidth = 180.dp,
            cardRadius = 18.dp,
            cardGap = 18.dp,
            contentHorizontalPadding = 18.dp,
            contentVerticalPadding = 16.dp,
            contentSpacing = 8.dp,
            titleTextSize = 17.sp,
            titleLineHeight = 22.sp,
            titleMaxLines = 3,
            bodyTextSize = 14.sp,
            bodyLineHeight = 20.sp,
            overviewMaxLines = 4,
            metaTextSize = 12.sp,
            badgeTextSize = 12.sp,
            badgeRadius = 5.dp,
            badgeHorizontalPadding = 7.dp,
            badgeVerticalPadding = 3.dp,
        )
        maxWidthDp >= 768f -> SeriesContentSizing(
            seasonHeaderSize = 24.sp,
            seasonToggleTextSize = 14.sp,
            seasonChipGap = 16.dp,
            seasonChipRadius = 12.dp,
            seasonChipHorizontalPadding = 16.dp,
            seasonChipVerticalPadding = 12.dp,
            seasonChipTextSize = 17.sp,
            seasonPosterWidth = 120.dp,
            seasonPosterHeight = 180.dp,
            seasonPosterRadius = 12.dp,
            cardHeight = 160.dp,
            imageWidth = 160.dp,
            cardRadius = 16.dp,
            cardGap = 16.dp,
            contentHorizontalPadding = 16.dp,
            contentVerticalPadding = 14.dp,
            contentSpacing = 6.dp,
            titleTextSize = 16.sp,
            titleLineHeight = 20.sp,
            titleMaxLines = 3,
            bodyTextSize = 14.sp,
            bodyLineHeight = 20.sp,
            overviewMaxLines = 3,
            metaTextSize = 12.sp,
            badgeTextSize = 11.sp,
            badgeRadius = 4.dp,
            badgeHorizontalPadding = 6.dp,
            badgeVerticalPadding = 2.dp,
        )
        else -> SeriesContentSizing(
            seasonHeaderSize = 18.sp,
            seasonToggleTextSize = 12.sp,
            seasonChipGap = 16.dp,
            seasonChipRadius = 12.dp,
            seasonChipHorizontalPadding = 16.dp,
            seasonChipVerticalPadding = 12.dp,
            seasonChipTextSize = 15.sp,
            seasonPosterWidth = 100.dp,
            seasonPosterHeight = 150.dp,
            seasonPosterRadius = 8.dp,
            cardHeight = 120.dp,
            imageWidth = 120.dp,
            cardRadius = 16.dp,
            cardGap = 16.dp,
            contentHorizontalPadding = 12.dp,
            contentVerticalPadding = 12.dp,
            contentSpacing = 4.dp,
            titleTextSize = 15.sp,
            titleLineHeight = 18.sp,
            titleMaxLines = 2,
            bodyTextSize = 13.sp,
            bodyLineHeight = 18.sp,
            overviewMaxLines = 2,
            metaTextSize = 12.sp,
            badgeTextSize = 11.sp,
            badgeRadius = 4.dp,
            badgeHorizontalPadding = 6.dp,
            badgeVerticalPadding = 2.dp,
        )
    }

private fun Int.label(collectionTitle: String? = null): String =
    if (this == TRAILER_SELECTOR_SEASON) {
        runBlocking { getString(Res.string.detail_trailers_title) }
    } else if (this == COLLECTION_SELECTOR_SEASON) {
        collectionTitle
            ?.takeIf { it.isNotBlank() }
            ?: runBlocking { getString(Res.string.settings_meta_collection) }
    } else if (this == MORE_LIKE_THIS_SELECTOR_SEASON) {
        runBlocking { getString(Res.string.details_more_like_this) }
    } else if (this <= 0) {
        runBlocking { getString(Res.string.episodes_specials) }
    } else {
        runBlocking { getString(Res.string.episodes_season, this@label) }
    }

private fun MetaVideo.episodeBadge(): String =
    when {
        effectiveEpisodeNumber() != null || effectiveSeasonNumber() != null ->
            localizedSeasonEpisodeCode(
                seasonNumber = effectiveSeasonNumber(),
                episodeNumber = effectiveEpisodeNumber(),
            ).orEmpty()
        else -> runBlocking { getString(Res.string.details_episode_badge_file) }
    }

private fun MetaVideo.seasonEpisodeKey(): Pair<Int, Int>? {
    val seasonNumber = effectiveSeasonNumber() ?: return null
    val episodeNumber = effectiveEpisodeNumber() ?: return null
    return seasonNumber to episodeNumber
}

private fun MetaVideo.playbackVideoId(parentMetaId: String): String =
    buildPlaybackVideoId(
        parentMetaId = parentMetaId,
        seasonNumber = effectiveSeasonNumber(),
        episodeNumber = effectiveEpisodeNumber(),
        fallbackVideoId = id,
    )

private fun formatEpisodeRating(rating: Double): String {
    val roundedTenths = (rating * 10.0).roundToInt()
    val whole = roundedTenths / 10
    val tenth = (roundedTenths % 10).absoluteValue
    return "$whole.$tenth"
}
