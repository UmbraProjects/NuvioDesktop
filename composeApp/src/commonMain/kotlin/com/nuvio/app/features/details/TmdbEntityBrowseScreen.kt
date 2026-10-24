package com.nuvio.app.features.details

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioBackButton
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import com.nuvio.app.core.ui.landscapePosterHeightForWidth
import com.nuvio.app.core.ui.landscapePosterWidth
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.features.home.HeroDiscoveryBadgeTarget
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.HomeContentMode
import com.nuvio.app.features.home.HomeScreen
import com.nuvio.app.features.home.stableKey
import com.nuvio.app.features.tmdb.TmdbEntityKind
import com.nuvio.app.features.tmdb.TmdbEntityMediaType
import com.nuvio.app.features.tmdb.TmdbEntityRail
import com.nuvio.app.features.tmdb.TmdbEntityRailPageResult
import com.nuvio.app.features.tmdb.TmdbEntityRailType
import com.nuvio.app.features.tmdb.TmdbMetadataService
import com.nuvio.app.features.watched.WatchedRepository
import kotlinx.coroutines.launch

private sealed interface RailBrowseUiState {
    data object Loading : RailBrowseUiState
    data class Error(val message: String) : RailBrowseUiState
    data class Success(val rails: List<TmdbEntityRail>) : RailBrowseUiState
}

/** The production-company / network browse opened from a details-page logo. */
@Composable
fun TmdbEntityBrowseScreen(
    entityKind: TmdbEntityKind,
    entityId: Int,
    entityName: String,
    sourceType: String,
    onBack: () -> Unit,
    onOpenMeta: (MetaPreview) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The header name TMDB returns can differ from the name the logo carried; keep whichever the
    // fetch settled on so the rail titles and the load-failed message agree.
    var resolvedName by remember(entityKind, entityId) { mutableStateOf(entityName) }
    TmdbRailBrowseScreen(
        key = "${entityKind.routeValue}:$entityId",
        name = resolvedName,
        loadRails = {
            TmdbMetadataService.fetchEntityBrowse(
                entityKind = entityKind,
                entityId = entityId,
                sourceType = sourceType,
                fallbackName = entityName,
            )?.also { resolvedName = it.header.name }?.rails
        },
        loadNextPage = { rail ->
            TmdbMetadataService.fetchNextEntityRailPage(
                entityKind = entityKind,
                entityId = entityId,
                rail = rail,
            )
        },
        onBack = onBack,
        onOpenMeta = onOpenMeta,
        modifier = modifier,
    )
}

/** The browse opened by clicking a hero discovery badge — see [HeroDiscoveryBadgeTarget]. */
@Composable
fun HeroBadgeBrowseScreen(
    target: HeroDiscoveryBadgeTarget,
    title: String,
    sourceType: String,
    onBack: () -> Unit,
    onOpenMeta: (MetaPreview) -> Unit,
    modifier: Modifier = Modifier,
) {
    TmdbRailBrowseScreen(
        key = "badge:$target",
        name = title,
        loadRails = { TmdbMetadataService.fetchBadgeBrowse(target = target, sourceType = sourceType) },
        loadNextPage = { rail -> TmdbMetadataService.fetchNextBadgeRailPage(target = target, rail = rail) },
        onBack = onBack,
        onOpenMeta = onOpenMeta,
        modifier = modifier,
    )
}

/**
 * A full-screen set of paginated TMDB rails with a back button: the shell shared by the
 * company/network browse and the hero badge browse. Callers supply the two fetches; everything
 * else — load state, retry, per-rail paging, the Home-style catalog rendering — is the same.
 *
 * [loadRails] returns null when the source is unavailable (no TMDB key) and an empty list when
 * it answered with nothing; both show the error state, since a badge browse with no rails is
 * indistinguishable from a failed one to the viewer.
 */
@Composable
internal fun TmdbRailBrowseScreen(
    key: String,
    name: String,
    loadRails: suspend () -> List<TmdbEntityRail>?,
    loadNextPage: suspend (TmdbEntityRail) -> TmdbEntityRailPageResult,
    onBack: () -> Unit,
    onOpenMeta: (MetaPreview) -> Unit,
    modifier: Modifier = Modifier,
) {
    var uiState by remember(key) { mutableStateOf<RailBrowseUiState>(RailBrowseUiState.Loading) }
    val watchedUiState by remember {
        WatchedRepository.ensureLoaded()
        WatchedRepository.uiState
    }.collectAsStateWithLifecycle()
    val loadFailedMessage = stringResource(Res.string.details_browse_load_failed, name)
    val coroutineScope = rememberCoroutineScope()
    // Retry has no other key to change: the source is the same one that just failed, so without a
    // generation counter the effect below never re-runs and the button only ever redraws the
    // skeleton. Reset with the key so a fresh screen starts from zero.
    var loadGeneration by remember(key) { mutableStateOf(0) }

    LaunchedEffect(key, loadGeneration) {
        uiState = RailBrowseUiState.Loading
        val rails = loadRails()
        uiState = if (!rails.isNullOrEmpty()) {
            RailBrowseUiState.Success(rails)
        } else {
            RailBrowseUiState.Error(loadFailedMessage)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Crossfade(
            targetState = uiState,
            label = "RailBrowseCrossfade",
        ) { state ->
            when (state) {
                is RailBrowseUiState.Loading -> EntityBrowseSkeleton()
                is RailBrowseUiState.Error -> EntityBrowseError(
                    message = state.message,
                    onRetry = { loadGeneration += 1 },
                )
                is RailBrowseUiState.Success -> {
                    val labels = EntityCatalogLabels(
                        movies = stringResource(Res.string.media_movies),
                        series = stringResource(Res.string.media_series),
                        popular = stringResource(Res.string.details_browse_rail_popular),
                        topRated = stringResource(Res.string.details_browse_rail_top_rated),
                        recent = stringResource(Res.string.details_browse_rail_recent),
                        winners = stringResource(Res.string.details_browse_rail_winners),
                        nominees = stringResource(Res.string.details_browse_rail_nominees),
                    )
                    val sections = remember(state.rails, labels, name) {
                        buildEntityCatalogSections(state.rails, name, labels)
                    }
                    HomeScreen(
                        contentMode = HomeContentMode.Catalogs(
                            key = key,
                            sections = sections,
                        ),
                        topChromePadding = 72.dp,
                        onBack = onBack,
                        onLoadMoreCatalog = { section ->
                            val current = (uiState as? RailBrowseUiState.Success)?.rails
                                ?: return@HomeScreen
                            val railIndex = current.indexOfFirst { entityRailKey(it) == section.key }
                            val rail = current.getOrNull(railIndex)
                                ?: return@HomeScreen
                            if (rail.isLoading || !rail.hasMore) return@HomeScreen

                            uiState = RailBrowseUiState.Success(
                                current.toMutableList().also { rails ->
                                    rails[railIndex] = rail.copy(isLoading = true)
                                },
                            )
                            coroutineScope.launch {
                                val page = loadNextPage(rail)
                                val latest = (uiState as? RailBrowseUiState.Success)?.rails
                                    ?: return@launch
                                val latestIndex = latest.indexOfFirst { entityRailKey(it) == section.key }
                                val latestRail = latest.getOrNull(latestIndex) ?: return@launch
                                val mergedItems = (latestRail.items + page.items)
                                    .distinctBy(MetaPreview::stableKey)
                                uiState = RailBrowseUiState.Success(
                                    latest.toMutableList().also { rails ->
                                        rails[latestIndex] = latestRail.copy(
                                            items = mergedItems,
                                            currentPage = rail.currentPage + 1,
                                            hasMore = page.hasMore,
                                            isLoading = false,
                                        )
                                    },
                                )
                            }
                        },
                        onPosterClick = onOpenMeta,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        NuvioBackButton(
            onClick = onBack,
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .align(Alignment.TopStart),
        )
    }
}

internal data class EntityCatalogLabels(
    val movies: String,
    val series: String,
    val popular: String,
    val topRated: String,
    val recent: String,
    val winners: String = "Winners",
    val nominees: String = "Nominees",
)

/**
 * Rails → Home catalog sections. Titles read `Movies • Popular • HBO`; a
 * [TmdbEntityRailType.FEATURED] rail has no rail label of its own (the name *is* the list), so it
 * reads `Movies • Trending`.
 */
internal fun buildEntityCatalogSections(
    rails: List<TmdbEntityRail>,
    name: String,
    labels: EntityCatalogLabels,
): List<HomeCatalogSection> = rails.map { rail ->
    val mediaLabel = when (rail.mediaType) {
        TmdbEntityMediaType.MOVIE -> labels.movies
        TmdbEntityMediaType.TV -> labels.series
    }
    val railLabel = when (rail.railType) {
        TmdbEntityRailType.POPULAR -> labels.popular
        TmdbEntityRailType.TOP_RATED -> labels.topRated
        TmdbEntityRailType.RECENT -> labels.recent
        TmdbEntityRailType.WINNERS -> labels.winners
        TmdbEntityRailType.NOMINEES -> labels.nominees
        TmdbEntityRailType.FEATURED -> null
    }
    HomeCatalogSection(
        key = entityRailKey(rail),
        title = listOfNotNull(mediaLabel, railLabel, name).joinToString(" • "),
        subtitle = "",
        addonName = name,
        items = rail.items,
        availableItemCount = rail.items.size + if (rail.hasMore) 1 else 0,
        hasMore = rail.hasMore,
        paginates = true,
        nextSkip = (rail.currentPage + 1).takeIf { rail.hasMore },
        isLoadingMore = rail.isLoading,
        inlineOnly = true,
    )
}

private fun entityRailKey(rail: TmdbEntityRail): String =
    "tmdb-entity:${rail.mediaType.value}:${rail.railType.value}"

@Composable
private fun EntityBrowseSkeleton() {
    val posterCardStyle = rememberPosterCardStyleUiState()
    val isLandscapeShelfMode = posterCardStyle.catalogLandscapeModeEnabled
    val skeletonPosterWidth = if (isLandscapeShelfMode) {
        landscapePosterWidth(posterCardStyle.widthDp)
    } else {
        posterCardStyle.widthDp.dp
    }
    val skeletonPosterHeight = if (isLandscapeShelfMode) {
        landscapePosterHeightForWidth(skeletonPosterWidth)
    } else {
        posterCardStyle.heightDp.dp
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 56.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .width(200.dp)
                    .height(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .width(140.dp)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        repeat(3) {
            Column(modifier = Modifier.padding(bottom = 20.dp)) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .width(160.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    repeat(4) {
                        Box(
                            modifier = Modifier
                                .width(skeletonPosterWidth)
                                .height(skeletonPosterHeight)
                                .clip(RoundedCornerShape(posterCardStyle.cornerRadiusDp.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EntityBrowseError(
    message: String,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Text(stringResource(Res.string.action_retry))
            }
        }
    }
}
