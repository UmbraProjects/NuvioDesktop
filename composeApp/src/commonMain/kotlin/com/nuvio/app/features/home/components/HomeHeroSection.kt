package com.nuvio.app.features.home.components

import co.touchlab.kermit.Logger
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.nuvio.app.isDesktop
import com.nuvio.app.core.ui.NuvioDesktopImageScaling
import com.nuvio.app.core.ui.NuvioAsyncImage as AsyncImage
import com.nuvio.app.core.format.formatReleaseDateForDisplay
import com.nuvio.app.features.details.HeroTrailerAudioState
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaCompany
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaExternalRating
import com.nuvio.app.features.details.components.RatingsRow
import com.nuvio.app.features.details.formatRuntimeForDisplay
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.HeroCastMember
import com.nuvio.app.features.home.HeroBadgePlacement
import com.nuvio.app.features.home.HeroDiscoveryFact
import com.nuvio.app.features.home.HeroDiscoveryMetadataService
import com.nuvio.app.features.home.heroBundledBadgeModel
import com.nuvio.app.features.home.heroCustomBadgeModel
import com.nuvio.app.features.mdblist.HeroCastMetadataService
import com.nuvio.app.features.mdblist.MdbListMetadataService
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.trailer.HeroTrailerMetadataService
import com.nuvio.app.features.trailer.TrailerPlaybackSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt

private const val HERO_BACKGROUND_PARALLAX = 0.055f
private const val HERO_CONTENT_PARALLAX = 0.18f
private const val HERO_SCROLL_PARALLAX = 0.3f
private const val HERO_SCROLL_DOWN_SCALE_MULTIPLIER = 0.0001f
private const val HERO_SCROLL_UP_SCALE_MULTIPLIER = 0.002f
private const val HERO_SCROLL_MAX_SCALE = 1.3f
private const val HERO_SWIPE_THRESHOLD_FRACTION = 0.16f
private const val HERO_SWIPE_VELOCITY_THRESHOLD = 300f
private const val MOBILE_HERO_VIEWPORT_RATIO = 0.82f
private const val MOBILE_HERO_MIN_HEIGHT_DP = 360f
private const val MOBILE_HERO_MAX_HEIGHT_DP = 760f
private val DesktopHeroBackdropAlignment = BiasAlignment(
    horizontalBias = 0f,
    verticalBias = -0.65f,
)
private val ImmersiveHeroBackdropAlignment = BiasAlignment(
    horizontalBias = 1f,
    verticalBias = -0.6f,
)

object HomeHeroPeoplePanelToggleTrigger {
    private val _tokens = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val tokens: SharedFlow<Int> = _tokens.asSharedFlow()
    private var nextToken = 0

    fun trigger() {
        nextToken += 1
        _tokens.tryEmit(nextToken)
    }
}

private const val HERO_BACKDROP_WIDTH_FRACTION = 0.85f
private const val HERO_BACKDROP_FADE_FRACTION = 0.35f
private const val HERO_METADATA_PREFETCH_CONCURRENCY = 4
private val heroImageLog = Logger.withTag("HomeHeroImages")
private val heroTrailerLog = Logger.withTag("HomeHeroTrailer")
private val IMMERSIVE_HERO_CONTENT_MIN_HEIGHT = 300.dp
private val IMMERSIVE_HERO_CONTENT_MAX_HEIGHT = 420.dp
private val IMMERSIVE_HERO_CONTENT_BOTTOM_PADDING = 44.dp
private val IMMERSIVE_HERO_CONTENT_MIN_OFFSET_Y = 16.dp
private val IMMERSIVE_HERO_CONTENT_MAX_OFFSET_Y = 42.dp
private val HERO_PEOPLE_TAB_HEIGHT = 40.dp
private val HERO_DISCOVERY_PANEL_PADDING_HORIZONTAL = 20.dp
private val HERO_DISCOVERY_BADGE_SIZE = 58.dp
private val HERO_DISCOVERY_BADGE_CELL_SIZE = 58.dp
private val HERO_DISCOVERY_BADGE_GAP = 16.dp
private const val HERO_DISCOVERY_BADGE_COLUMNS = 3
private val HERO_DISCOVERY_PANEL_WIDTH =
    HERO_DISCOVERY_PANEL_PADDING_HORIZONTAL * 2 +
        HERO_DISCOVERY_BADGE_CELL_SIZE * HERO_DISCOVERY_BADGE_COLUMNS +
        HERO_DISCOVERY_BADGE_GAP * (HERO_DISCOVERY_BADGE_COLUMNS - 1)
private val HERO_DISCOVERY_ICON_SIZE = 46.dp
private val HERO_DISCOVERY_GRID_COLUMN_GAP = 22.dp
private val HERO_DISCOVERY_GRID_ROW_GAP = 18.dp
private val HERO_DISCOVERY_PILL_ICON_SIZE = 38.dp
private val HERO_DISCOVERY_PILL_GAP = 10.dp
private val HERO_DISCOVERY_MEDAL_SIZE = 46.dp
private val HERO_DISCOVERY_MEDAL_GAP = 14.dp
private val HERO_DISCOVERY_MEDAL_EDGE_PADDING = 42.dp
private val HERO_DISCOVERY_MEDAL_TOP_PADDING = 34.dp
private const val HERO_DISCOVERY_MAX_VISIBLE_BADGES = 6
private val HERO_DISCOVERY_TOOLTIP_OFFSET = 16.dp

internal data class HomeHeroLayout(
    val isTablet: Boolean,
    val heroHeight: Dp,
    val contentMaxWidth: Dp,
    val contentWidthFraction: Float,
    val contentHorizontalPadding: Dp,
    val contentVerticalPadding: Dp,
    val bottomFadeHeight: Dp,
    val logoWidthFraction: Float,
)

@Composable
fun HomeHeroSection(
    items: List<MetaPreview>,
    modifier: Modifier = Modifier,
    viewportHeight: Dp? = null,
    mobileBelowSectionHeightHint: Dp? = null,
    sectionPadding: Dp? = null,
    listState: LazyListState? = null,
    focusedItem: MetaPreview? = null,
    metadataPrefetchItems: List<MetaPreview> = emptyList(),
    heightOverride: Dp? = null,
    roundedBottomCorners: Boolean = true,
    immersiveMode: Boolean = false,
    adaptiveHeroMode: Boolean = false,
    heroEnabled: Boolean = true,
    heroInfoLines: Int = 2,
    heroInfoPriority: String = "wins,gg_wins,festival,pic_noms,gg_noms,emmy_noms,studio,director,trending,cult,foreign,new_release,metacritic,true_story,short_film,mini_series,binge_ready,release_status",
    heroBadgePlacement: HeroBadgePlacement = HeroBadgePlacement.BottomBackdrop,
    heroReleaseStatusUnavailableOnly: Boolean = true,
    trailersEnabledInCurrentMode: Boolean = true,
    immersiveContentBottomPadding: Dp = IMMERSIVE_HERO_CONTENT_BOTTOM_PADDING,
    onActiveItemChanged: ((MetaPreview) -> Unit)? = null,
    onCastClick: ((HeroCastMember) -> Unit)? = null,
    onItemClick: ((MetaPreview) -> Unit)? = null,
    // See HomeHeroTrailerSurface's onSurfaceDisposed doc: reclaim keyboard focus for the caller
    // when the native trailer surface disposes, so scrolling away from the hero mid-playback
    // (in the default, non-adaptive layout) can't leave the app's keyboard input stuck.
    onHeroTrailerSurfaceDisposed: () -> Unit = {},
) {
    if (items.isEmpty()) return

    val pagerState = rememberPagerState(pageCount = { items.size })
    val coroutineScope = rememberCoroutineScope()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .homeHeroPagerGesture(
                pagerState = pagerState,
                itemCount = items.size,
                coroutineScope = coroutineScope,
            )
            .then(
                if (roundedBottomCorners) {
                    Modifier.clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                } else {
                    Modifier
                },
            ),
    ) {
        val baseLayout = homeHeroLayout(
            maxWidthDp = maxWidth.value,
            viewportHeightDp = viewportHeight?.value,
            mobileBelowSectionHeightHintDp = mobileBelowSectionHeightHint?.value,
            preferDesktopLayout = isDesktop,
        )
        val layout = heightOverride?.let { baseLayout.copy(heroHeight = it) } ?: baseLayout
        val heroWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val heroHeightPx = with(LocalDensity.current) { layout.heroHeight.toPx() }
        val scrollOffsetPx by remember(listState, heroHeightPx) {
            derivedStateOf {
                when {
                    listState == null -> 0f
                    listState.firstVisibleItemIndex > 0 -> heroHeightPx
                    else -> listState.firstVisibleItemScrollOffset.toFloat()
                }
            }
        }
        val heroScrollScale = heroBackgroundScrollScale(scrollOffsetPx)
        val heroScrollTranslationY = heroBackgroundScrollTranslationY(scrollOffsetPx)
        val currentPage = pagerState.currentPage.coerceIn(items.indices)
        val visiblePages = listOf(
            currentPage,
            (currentPage - 1).coerceIn(items.indices),
            (currentPage + 1).coerceIn(items.indices),
        ).distinct()
            .mapNotNull { index ->
                val pageOffset = heroPageOffset(pagerState, index)
                val visibility = (1f - abs(pageOffset)).coerceIn(0f, 1f)
                if (visibility <= 0f) {
                    null
                } else {
                    HeroPageLayer(
                        page = index,
                        visibility = visibility,
                        offset = pageOffset,
                    )
                }
            }
            .sortedBy(HeroPageLayer::visibility)
        val currentItem = visiblePages
            .lastOrNull()
            ?.page
            ?.let(items::get)
            ?: items[currentPage]

        val focusedIndex = focusedItem?.let { focused -> items.indexOfFirst { it.id == focused.id } }
            ?.takeIf { it >= 0 }
        val displayItems = when {
            focusedItem == null -> items
            focusedIndex != null -> items
            else -> items + focusedItem
        }
        val displayVisiblePages = if (focusedItem != null) {
            listOf(HeroPageLayer(page = focusedIndex ?: (displayItems.size - 1), visibility = 1f, offset = 0f))
        } else {
            visiblePages
        }
        val displayCurrentItem = focusedItem ?: currentItem
        val castCache = remember { mutableStateMapOf<String, List<HeroCastMember>>() }
        val displayItemsWithCast = displayItems.map { item ->
            val cachedCast = castCache["${item.type}:${item.id}"]
                ?: HeroCastMetadataService.peek(type = item.type, id = item.id)
            item.copy(cast = cachedCast?.takeIf { it.isNotEmpty() } ?: item.cast)
        }
        val displayCurrentItemWithCast = displayItemsWithCast.firstOrNull { item ->
            item.type == displayCurrentItem.type && item.id == displayCurrentItem.id
        } ?: displayCurrentItem

        LaunchedEffect(displayCurrentItem.type, displayCurrentItem.id) {
            onActiveItemChanged?.invoke(displayCurrentItem)
        }

        val ratingsCache = remember { mutableStateMapOf<String, List<MetaExternalRating>>() }
        val discoveryCache = remember { mutableStateMapOf<String, List<HeroDiscoveryFact>>() }
        val productionCache = remember { mutableStateMapOf<String, List<HeroProductionCredit>>() }
        val discoveryPriority = remember(heroInfoPriority) { normalizeHeroDiscoveryPriority(heroInfoPriority) }
        // Keep warming all nearby metadata, but put what the user can see first and avoid
        // flooding the add-on/TMDB/image hosts with dozens of simultaneous cold requests.
        val metadataTargets = (listOf(displayCurrentItemWithCast) + metadataPrefetchItems + displayItemsWithCast)
            .distinctBy { item ->
                "${item.type}:${item.id}"
            }
        LaunchedEffect(metadataTargets, HeroDiscoveryMetadataService.CACHE_VERSION) {
            val settings = MdbListSettingsRepository.snapshot()
            val prefetchSlots = Semaphore(HERO_METADATA_PREFETCH_CONCURRENCY)
            for (target in metadataTargets) {
                val key = "${target.type}:${target.id}"
                val discoveryKey = "$key:heroDiscoveryV${HeroDiscoveryMetadataService.CACHE_VERSION}"
                if (!castCache.containsKey(key) && target.type != "collection") {
                    launch {
                        prefetchSlots.withPermit {
                            castCache[key] = HeroCastMetadataService.fetch(
                                type = target.type,
                                id = target.id,
                            )
                        }
                    }
                }
                if (!discoveryCache.containsKey(discoveryKey) && target.type != "collection") {
                    launch {
                        prefetchSlots.withPermit {
                            discoveryCache[discoveryKey] = HeroDiscoveryMetadataService.fetch(
                                type = target.type,
                                id = target.id,
                                priority = discoveryPriority,
                                releaseStatusUnavailableOnly = heroReleaseStatusUnavailableOnly,
                            )
                        }
                    }
                }
                if (!productionCache.containsKey(key) && target.type != "collection") {
                    launch {
                        prefetchSlots.withPermit {
                            productionCache[key] = fetchHeroProductionCredits(
                                type = target.type,
                                id = target.id,
                            )
                        }
                    }
                }
                if (ratingsCache.containsKey(key)) continue
                if (target.type == "collection") {
                    ratingsCache[key] = emptyList()
                    continue
                }
                val baseMeta = MetaDetails(id = target.id, type = target.type, name = target.name)
                if (!MdbListMetadataService.shouldFetchForMeta(baseMeta, target.id, settings)) {
                    ratingsCache[key] = emptyList()
                    continue
                }
                launch {
                    prefetchSlots.withPermit {
                        val ratings = MdbListMetadataService.enrichMeta(
                            meta = baseMeta,
                            fallbackItemId = target.id,
                            settings = settings,
                        ).externalRatings
                        ratingsCache[key] = ratings
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(layout.heroHeight),
        ) {
            HeroCastPortraitPreloader(
                cast = (castCache.values.flatten() + displayItemsWithCast.flatMap(MetaPreview::cast))
                    .filter { person -> !person.photo.isNullOrBlank() },
            )

            HorizontalPager(
                state = pagerState,
                userScrollEnabled = false,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.01f },
            ) {
                Box(modifier = Modifier.fillMaxSize())
            }

            if (isDesktop) {
                DesktopHomeHeroFrame(
                    items = displayItemsWithCast,
                    visiblePages = displayVisiblePages,
                    currentItem = displayCurrentItemWithCast,
                    layout = layout,
                    heroWidthPx = heroWidthPx,
                    heroScrollScale = heroScrollScale,
                    heroScrollTranslationY = heroScrollTranslationY,
                    contentHorizontalPadding = sectionPadding ?: layout.contentHorizontalPadding,
                    pagerState = pagerState,
                    pageIndicatorCount = items.size,
                    coroutineScope = coroutineScope,
                    immersiveMode = immersiveMode,
                    adaptiveHeroMode = adaptiveHeroMode,
                    heroEnabled = heroEnabled,
                    heroInfoLines = heroInfoLines,
                    heroInfoPriority = heroInfoPriority,
            heroBadgePlacement = heroBadgePlacement,
            heroReleaseStatusUnavailableOnly = heroReleaseStatusUnavailableOnly,
            trailersEnabledInCurrentMode = trailersEnabledInCurrentMode,
            immersiveContentBottomPadding = immersiveContentBottomPadding,
                    ratingsCache = ratingsCache,
                    discoveryCache = discoveryCache,
                    productionCache = productionCache,
                    onCastClick = onCastClick,
                    onItemClick = onItemClick,
                    onHeroTrailerSurfaceDisposed = onHeroTrailerSurfaceDisposed,
                )
            } else {
                DefaultHomeHeroFrame(
                    items = displayItemsWithCast,
                    visiblePages = displayVisiblePages,
                    currentItem = displayCurrentItemWithCast,
                    layout = layout,
                    heroWidthPx = heroWidthPx,
                    heroScrollScale = heroScrollScale,
                    heroScrollTranslationY = heroScrollTranslationY,
                    pagerState = pagerState,
                    pageIndicatorCount = items.size,
                    coroutineScope = coroutineScope,
                    onItemClick = onItemClick,
                )
            }
        }
    }
}

private data class HeroPageLayer(
    val page: Int,
    val visibility: Float,
    val offset: Float,
)

@Composable
private fun DefaultHomeHeroFrame(
    items: List<MetaPreview>,
    visiblePages: List<HeroPageLayer>,
    currentItem: MetaPreview,
    layout: HomeHeroLayout,
    heroWidthPx: Float,
    heroScrollScale: Float,
    heroScrollTranslationY: Float,
    pagerState: PagerState,
    pageIndicatorCount: Int = items.size,
    coroutineScope: CoroutineScope,
    onItemClick: ((MetaPreview) -> Unit)?,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        visiblePages.forEach { layer ->
            AsyncImage(
                model = items[layer.page].banner ?: items[layer.page].poster,
                contentDescription = items[layer.page].name,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = layer.visibility
                        translationX = -layer.offset * heroWidthPx * HERO_BACKGROUND_PARALLAX
                        translationY = heroScrollTranslationY
                        scaleX = heroScrollScale
                        scaleY = heroScrollScale
                        if (layout.isTablet) {
                            transformOrigin = TransformOrigin(0.5f, 0f)
                        }
                    },
                alignment = if (layout.isTablet) Alignment.TopCenter else Alignment.Center,
                contentScale = ContentScale.Crop,
                desktopImageScaling = NuvioDesktopImageScaling.Disabled,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background.copy(alpha = 0.02f),
                            MaterialTheme.colorScheme.background.copy(alpha = 0.12f),
                            MaterialTheme.colorScheme.background.copy(alpha = 0.34f),
                            MaterialTheme.colorScheme.background.copy(alpha = 0.78f),
                        ),
                    ),
                ),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(layout.bottomFadeHeight)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background.copy(alpha = 0f),
                            MaterialTheme.colorScheme.background,
                        ),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(
                    horizontal = layout.contentHorizontalPadding,
                    vertical = layout.contentVerticalPadding,
                ),
            horizontalAlignment = if (layout.isTablet) Alignment.Start else Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(layout.contentWidthFraction)
                    .widthIn(max = layout.contentMaxWidth),
                contentAlignment = if (layout.isTablet) Alignment.CenterStart else Alignment.Center,
            ) {
                visiblePages.forEach { layer ->
                    Box(
                        modifier = Modifier.graphicsLayer {
                            alpha = layer.visibility
                            translationX = -layer.offset * heroWidthPx * HERO_CONTENT_PARALLAX
                        },
                    ) {
                        HeroContentBlock(
                            item = items[layer.page],
                            layout = layout,
                            onItemClick = onItemClick?.let { handler ->
                                { _ -> handler(currentItem) }
                            },
                        )
                    }
                }
            }

            if (!layout.isTablet) {
                Spacer(modifier = Modifier.height(14.dp))
                Surface(
                    modifier = Modifier
                        .clickable(enabled = onItemClick != null) {
                            onItemClick?.invoke(currentItem)
                        },
                    color = MaterialTheme.colorScheme.onBackground,
                    contentColor = MaterialTheme.colorScheme.background,
                    shape = RoundedCornerShape(40.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.home_view_details),
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            HeroPageIndicatorRow(
                itemCount = pageIndicatorCount,
                pagerState = pagerState,
                coroutineScope = coroutineScope,
                modifier = Modifier.padding(top = if (layout.isTablet) 14.dp else 12.dp),
            )
        }
    }
}

@Composable
private fun HeroBackdropImage(
    item: MetaPreview,
    contentDescription: String?,
    modifier: Modifier,
    alignment: Alignment,
    contentScale: ContentScale,
    onImageLoaded: ((coil3.Image) -> Unit)? = null,
) {
    // Addons that don't supply a backdrop (only a poster) would otherwise render a blank/black
    // hero — fall back to the poster like the rest of the hero pipeline (e.g. the ambient
    // background wash) already does.
    val model = item.banner?.takeIf(String::isNotBlank)
        ?: item.poster?.takeIf(String::isNotBlank)

    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        modifier = modifier,
        alignment = alignment,
        contentScale = contentScale,
        desktopImageScaling = NuvioDesktopImageScaling.Disabled,
        onError = {
            if (model != null) {
                heroImageLog.w { "Hero artwork failed: ${model.safeImageUrlForLog()}" }
            }
        },
        onSuccess = { state -> onImageLoaded?.invoke(state.result.image) },
    )
}

@Composable
private fun DesktopHomeHeroFrame(
    items: List<MetaPreview>,
    visiblePages: List<HeroPageLayer>,
    currentItem: MetaPreview,
    layout: HomeHeroLayout,
    heroWidthPx: Float,
    heroScrollScale: Float,
    heroScrollTranslationY: Float,
    contentHorizontalPadding: Dp,
    pagerState: PagerState,
    pageIndicatorCount: Int = items.size,
    coroutineScope: CoroutineScope,
    immersiveMode: Boolean,
    adaptiveHeroMode: Boolean = false,
    heroEnabled: Boolean,
    heroInfoLines: Int,
    heroInfoPriority: String,
    heroBadgePlacement: HeroBadgePlacement,
    heroReleaseStatusUnavailableOnly: Boolean,
    trailersEnabledInCurrentMode: Boolean,
    immersiveContentBottomPadding: Dp,
    ratingsCache: Map<String, List<MetaExternalRating>>,
    discoveryCache: Map<String, List<HeroDiscoveryFact>>,
    productionCache: Map<String, List<HeroProductionCredit>>,
    onCastClick: ((HeroCastMember) -> Unit)?,
    onItemClick: ((MetaPreview) -> Unit)?,
    onHeroTrailerSurfaceDisposed: () -> Unit = {},
) {
    val backgroundColor = if (immersiveMode) Color.Black else MaterialTheme.colorScheme.background

    val playerSettings by PlayerSettingsRepository.uiState.collectAsState()
    // TV-mode hero trailer (desktop only; mirrors Nuvio TV). The feature applies to the
    // TV-style heroes; auto-play after a delay is opt-in, while the `T` shortcut plays it
    // on demand regardless of the auto-play setting.
    val tvHeroActive = trailersEnabledInCurrentMode && (adaptiveHeroMode || immersiveMode)
    val heroTrailerAutoplayEnabled = tvHeroActive && playerSettings.heroTvTrailerEnabled
    val heroTrailerFocusKey = "${currentItem.type}:${currentItem.id}"
    // Resets the dwell timer on every focus move; false whenever home isn't the active screen.
    val heroTrailerFocusNonce by HomeHeroTrailerGate.focusNonce.collectAsState()
    val heroTrailerHomeActive by HomeHeroTrailerGate.homeActive.collectAsState()
    val heroTrailerManualToken by HomeHeroTrailerManualTrigger.tokens.collectAsState(initial = 0)
    var heroTrailerSource by remember { mutableStateOf<TrailerPlaybackSource?>(null) }
    var heroTrailerSurfaceReady by remember { mutableStateOf(false) }
    var heroTrailerPlaybackRequested by remember { mutableStateOf(false) }
    var heroTrailerFinished by remember { mutableStateOf(false) }
    var peoplePanelTab by remember { mutableStateOf(HeroPeoplePanelTab.Starring) }
    // Manual override: automatic saliency-based crop detection was removed (unreliable across a
    // wide enough variety of backdrops that it wasn't worth the complexity) in favor of a single
    // user-tunable vertical bias applied to every adaptive-hero backdrop. See Homescreen settings.
    val homeCatalogSettings by HomeCatalogSettingsRepository.uiState.collectAsState()
    val adaptiveHeroVerticalBias = homeCatalogSettings.adaptiveHeroVerticalBias
    val peoplePanelToggleToken by HomeHeroPeoplePanelToggleTrigger.tokens.collectAsState(initial = 0)
    LaunchedEffect(peoplePanelToggleToken) {
        if (peoplePanelToggleToken == 0) return@LaunchedEffect
        val key = "${currentItem.type}:${currentItem.id}"
        if (productionCache[key].orEmpty().isEmpty()) return@LaunchedEffect
        peoplePanelTab = if (peoplePanelTab == HeroPeoplePanelTab.Starring) {
            HeroPeoplePanelTab.Production
        } else {
            HeroPeoplePanelTab.Starring
        }
    }
    // Reset all trailer state when focus moves, the feature gate changes, or home is left.
    LaunchedEffect(
        heroTrailerFocusKey,
        heroTrailerAutoplayEnabled,
        heroTrailerFocusNonce,
        heroTrailerHomeActive,
    ) {
        heroTrailerSource = null
        heroTrailerSurfaceReady = false
        heroTrailerPlaybackRequested = false
        heroTrailerFinished = false
        heroTrailerLog.i {
            "gate autoplay=$heroTrailerAutoplayEnabled homeActive=$heroTrailerHomeActive " +
                "adaptiveHeroMode=$adaptiveHeroMode immersive=$immersiveMode " +
                "settingEnabled=${playerSettings.heroTvTrailerEnabled} key=$heroTrailerFocusKey " +
                "delay=${playerSettings.heroTvTrailerDelaySeconds}s"
        }
    }
    // Resolve the trailer stream shortly after focus settles, even when autoplay is disabled,
    // so a later `T` press can start without YouTube extraction. Do not mount the native player
    // here: even a hidden WebView can briefly steal OS focus while it initializes.
    LaunchedEffect(heroTrailerFocusKey, tvHeroActive) {
        if (!tvHeroActive || currentItem.type == "collection") return@LaunchedEffect
        delay(250L)
        heroTrailerLog.i { "caching trailer stream for $heroTrailerFocusKey" }
        HeroTrailerMetadataService.resolve(currentItem.type, currentItem.id)
    }
    // At the configured delay, mount and play using the cached stream. The desktop surface stays
    // at 1px until its first frame, so the artwork remains visible instead of flashing black.
    LaunchedEffect(
        heroTrailerFocusKey,
        heroTrailerAutoplayEnabled,
        playerSettings.heroTvTrailerDelaySeconds,
        heroTrailerFocusNonce,
        heroTrailerHomeActive,
    ) {
        if (!heroTrailerAutoplayEnabled || !heroTrailerHomeActive || currentItem.type == "collection") {
            return@LaunchedEffect
        }
        // Don't count dwell time during the app's startup grace window, while continue-watching
        // and other home assets are still loading and the hero is focused by default.
        HomeHeroTrailerGate.startupGraceRemainingMillis().let { grace -> if (grace > 0L) delay(grace) }
        delay(playerSettings.heroTvTrailerDelaySeconds.coerceAtLeast(0) * 1000L)
        if (heroTrailerFinished) return@LaunchedEffect
        if (heroTrailerSource == null) {
            heroTrailerSource = HeroTrailerMetadataService.resolve(currentItem.type, currentItem.id)
        }
        if (heroTrailerSource == null) {
            heroTrailerFinished = true
        } else {
            heroTrailerPlaybackRequested = true
        }
    }
    // Manual `T` shortcut: play the focused item's trailer immediately, even with auto-play off.
    LaunchedEffect(heroTrailerManualToken) {
        if (heroTrailerManualToken == 0 || !tvHeroActive || !heroTrailerHomeActive ||
            currentItem.type == "collection"
        ) {
            return@LaunchedEffect
        }
        // Toggle: if a trailer is already showing, `T` dismisses it (an explicit way out
        // in addition to simply moving focus to another item).
        if (heroTrailerPlaybackRequested && heroTrailerSource != null && !heroTrailerFinished) {
            heroTrailerSurfaceReady = false
            heroTrailerPlaybackRequested = false
            heroTrailerFinished = true
            heroTrailerSource = null
            return@LaunchedEffect
        }
        heroTrailerFinished = false
        heroTrailerSurfaceReady = false
        heroTrailerLog.i { "resolving (manual) trailer for $heroTrailerFocusKey" }
        val resolved = HeroTrailerMetadataService.resolve(currentItem.type, currentItem.id)
        if (resolved == null) {
            heroTrailerFinished = true
        } else {
            heroTrailerSource = resolved
            heroTrailerPlaybackRequested = true
        }
    }
    val heroTrailerAudioMuted by HeroTrailerAudioState.muted.collectAsState()
    LaunchedEffect(heroTrailerFocusKey, playerSettings.heroTvTrailerSoundEnabled) {
        HeroTrailerAudioState.setMuted(!playerSettings.heroTvTrailerSoundEnabled)
    }
    val heroTrailerMuted = heroTrailerAudioMuted
    val heroTrailerVolume by HeroTrailerAudioState.volume.collectAsState()
    val heroTrailerMounted = tvHeroActive &&
        heroTrailerSource != null &&
        !heroTrailerFinished
    val heroTrailerVisible = heroTrailerMounted && heroTrailerPlaybackRequested
    val heroTrailerReady = heroTrailerVisible && heroTrailerSurfaceReady
    // Hero text rendered over the full-bleed trailer by the web overlay.
    val heroTrailerMetaLine = remember(currentItem) {
        buildList {
            currentItem.genres.firstOrNull()?.takeIf(String::isNotBlank)?.let(::add)
            currentItem.releaseInfo?.takeIf(String::isNotBlank)
                ?.let(::formatReleaseDateForDisplay)?.takeIf(String::isNotBlank)?.let(::add)
            formatRuntimeForDisplay(currentItem.runtime)?.takeIf(String::isNotBlank)?.let(::add)
        }.joinToString("   •   ")
    }
    val heroTrailerDescription = remember(currentItem) {
        currentItem.description?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
    }
    val heroTrailerFullscreen = playerSettings.heroTvTrailerFullscreen
    // Expose visibility so the home key handler can map Escape to "dismiss trailer".
    LaunchedEffect(heroTrailerVisible) {
        HomeHeroTrailerManualTrigger.setActive(heroTrailerVisible)
    }
    DisposableEffect(Unit) {
        onDispose { HomeHeroTrailerManualTrigger.setActive(false) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor),
    ) {
        Box(
            modifier = Modifier
                .align(if (immersiveMode) Alignment.TopEnd else Alignment.CenterEnd)
                .then(
                    if (immersiveMode) {
                        Modifier.height(layout.heroHeight * 0.64f)
                    } else {
                        Modifier.fillMaxHeight()
                    },
                )
                .fillMaxWidth(HERO_BACKDROP_WIDTH_FRACTION)
                // Clip the backdrop region: during a transition the layers are parallax-shifted
                // and scaled, and without this they paint a cropped sliver outside the box (over
                // the content panel, where the fade mask doesn't reach) — a stray vertical band.
                .clipToBounds()
                .heroBackdropFadeMask(backgroundColor)
                .then(if (immersiveMode) Modifier.immersiveHeroExtraMask(backgroundColor) else Modifier)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = onItemClick != null,
                ) {
                    onItemClick?.invoke(currentItem)
                },
        ) {
            visiblePages.forEach { layer ->
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            // Hide the static backdrop once the trailer has a frame so the
                            // video blends into the background instead of seaming against it.
                            alpha = if (heroTrailerReady) 0f else layer.visibility
                            translationX = -layer.offset * heroWidthPx * HERO_BACKGROUND_PARALLAX
                            translationY = heroScrollTranslationY
                            scaleX = heroScrollScale
                            scaleY = heroScrollScale
                            transformOrigin = TransformOrigin(0.5f, 0f)
                        },
                ) {
                    HeroBackdropImage(
                        item = items[layer.page],
                        contentDescription = items[layer.page].name,
                        modifier = Modifier.fillMaxSize(),
                        alignment = when {
                            immersiveMode -> ImmersiveHeroBackdropAlignment
                            adaptiveHeroMode -> BiasAlignment(horizontalBias = 0f, verticalBias = adaptiveHeroVerticalBias)
                            else -> DesktopHeroBackdropAlignment
                        },
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }

        if (heroTrailerMounted) {
            val source = heroTrailerSource
            if (source != null) {
                // Full-bleed trailer: the hero logo/title/metadata are rendered into the
                // player's web overlay (Compose can't draw over the heavyweight video), so
                // the trailer can fill the whole hero like the source app.
                HomeHeroTrailerSurface(
                    sourceUrl = source.videoUrl,
                    sourceAudioUrl = source.audioUrl,
                    playWhenReady = heroTrailerPlaybackRequested,
                    muted = heroTrailerMuted,
                    volume = heroTrailerVolume,
                    backgroundColor = backgroundColor,
                    logoUrl = currentItem.logo,
                    title = currentItem.name,
                    meta = heroTrailerMetaLine,
                    description = heroTrailerDescription,
                    // Full screen fills the whole hero slot (the full viewport in immersive
                    // mode); otherwise full-bleed width but height stays within the hero
                    // backdrop region so the rows below remain visible and navigable.
                    modifier = when {
                        heroTrailerFullscreen -> Modifier.fillMaxSize()
                        immersiveMode -> Modifier
                            .align(Alignment.TopEnd)
                            .height(layout.heroHeight * 0.64f)
                            .fillMaxWidth()
                        else -> Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .fillMaxWidth()
                    },
                    onReady = { heroTrailerSurfaceReady = true },
                    onEnded = {
                        heroTrailerSurfaceReady = false
                        heroTrailerPlaybackRequested = false
                        heroTrailerFinished = true
                    },
                    onError = {
                        heroTrailerSurfaceReady = false
                        heroTrailerPlaybackRequested = false
                        heroTrailerFinished = true
                    },
                    onVolumeChange = { newVolume -> HeroTrailerAudioState.setVolume(newVolume) },
                    onSurfaceDisposed = onHeroTrailerSurfaceDisposed,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (immersiveMode) layout.heroHeight * 0.52f else layout.bottomFadeHeight)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colorStops = if (immersiveMode) {
                            arrayOf(
                                0f to backgroundColor.copy(alpha = 0f),
                                0.38f to backgroundColor.copy(alpha = 0.18f),
                                0.68f to backgroundColor.copy(alpha = 0.72f),
                                1f to backgroundColor,
                            )
                        } else {
                            arrayOf(
                                0f to backgroundColor.copy(alpha = 0f),
                                1f to backgroundColor,
                            )
                        },
                    ),
                ),
        )

        if (immersiveMode && !heroTrailerReady) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = contentHorizontalPadding, top = 30.dp, end = contentHorizontalPadding)
                    .fillMaxWidth(0.32f)
                    .widthIn(max = 600.dp),
                contentAlignment = Alignment.TopStart,
            ) {
                visiblePages.forEach { layer ->
                    val item = items[layer.page]
                    val cast = heroDisplayCast(item, maxCount = 4)
                    val production = productionCache["${item.type}:${item.id}"].orEmpty()
                    if (cast.isNotEmpty() || production.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    alpha = layer.visibility
                                    translationX = -layer.offset * heroWidthPx * HERO_CONTENT_PARALLAX
                                },
                        ) {
                            HeroPeopleBlock(
                                cast = cast,
                                production = production,
                                activeTab = peoplePanelTab,
                                onTabChange = { peoplePanelTab = it },
                                onCastClick = onCastClick,
                            )
                        }
                    }
                }
            }
        }

        if (!heroTrailerReady) {
            Box(
                modifier = Modifier
                    .align(if (immersiveMode) Alignment.BottomStart else Alignment.TopStart)
                    .padding(start = contentHorizontalPadding, end = contentHorizontalPadding)
                    .then(
                        if (immersiveMode) {
                            Modifier
                                .padding(bottom = immersiveContentBottomPadding)
                                .height(immersiveHeroContentHeight(layout.heroHeight))
                                .offset(y = immersiveHeroContentOffsetY(layout.heroHeight))
                        } else {
                            // Anchor to a fixed top baseline so cast/genre/ratings/synopsis stay
                            // put between items instead of drifting as content height changes
                            // (vertical centering moved the whole block).
                            Modifier.padding(top = adaptiveHeroContentTopBaseline(layout.heroHeight))
                        },
                    )
                    .fillMaxWidth(
                        when {
                            immersiveMode -> 0.32f
                            adaptiveHeroMode -> 0.38f
                            else -> layout.contentWidthFraction
                        },
                    )
                    .widthIn(
                        max = when {
                            immersiveMode -> 600.dp
                            adaptiveHeroMode -> 480.dp
                            else -> layout.contentMaxWidth
                        },
                    ),
                contentAlignment = Alignment.TopStart,
            ) {
                visiblePages.forEach { layer ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                alpha = layer.visibility
                                translationX = -layer.offset * heroWidthPx * HERO_CONTENT_PARALLAX
                            },
                    ) {
                        DesktopHeroContentBlock(
                            item = items[layer.page],
                            layout = layout,
                            interactive = true,
                            showExtendedMetadata = immersiveMode,
                            showReleaseMetadata = immersiveMode || adaptiveHeroMode,
                            ratingsCache = ratingsCache,
                            onCastClick = onCastClick,
                            production = if (adaptiveHeroMode) productionCache["${items[layer.page].type}:${items[layer.page].id}"].orEmpty() else emptyList(),
                            peoplePanelTab = peoplePanelTab,
                            allowProductionHotkeySwap = adaptiveHeroMode,
                            onItemClick = onItemClick?.let { handler ->
                                { _ -> handler(currentItem) }
                            },
                        )
                    }
                }
            }
        }

        if (discoveryCache.isNotEmpty()) {
            Box(
                modifier = heroDiscoveryMedalOverlayModifier(
                    placement = heroBadgePlacement,
                    immersiveMode = immersiveMode,
                    heroHeight = layout.heroHeight,
                ),
                contentAlignment = heroBadgePlacement.heroDiscoveryMedalAlignment(),
            ) {
                visiblePages.forEach { layer ->
                    val itemKey = "${items[layer.page].type}:${items[layer.page].id}"
                    val discoveryKey = "$itemKey:heroDiscoveryV${HeroDiscoveryMetadataService.CACHE_VERSION}"
                    val facts = discoveryCache[discoveryKey].orEmpty()
                    if (facts.isNotEmpty()) {
                        HeroDiscoveryMedalStrip(
                            facts = facts,
                            maxCount = heroInfoLines,
                            placement = heroBadgePlacement,
                            modifier = Modifier
                                .graphicsLayer {
                                    alpha = layer.visibility
                                    translationX = -layer.offset * heroWidthPx * HERO_CONTENT_PARALLAX
                                }
                        )
                    }
                }
            }
        }

        if (!adaptiveHeroMode) {
            HeroPageIndicatorRow(
                itemCount = pageIndicatorCount,
                pagerState = pagerState,
                coroutineScope = coroutineScope,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        start = contentHorizontalPadding,
                        bottom = layout.contentVerticalPadding,
                    ),
            )
        }
    }
}

private fun Modifier.heroBackdropFadeMask(backgroundColor: Color): Modifier =
    drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.horizontalGradient(
                colorStops = arrayOf(
                    0f to backgroundColor,
                    HERO_BACKDROP_FADE_FRACTION to Color.Transparent,
                    1f to Color.Transparent,
                ),
            ),
        )
    }

private fun Modifier.immersiveHeroExtraMask(backgroundColor: Color): Modifier =
    drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    0.82f to Color.Transparent,
                    1f to backgroundColor,
                ),
            ),
        )
        drawRect(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    0.68f to Color.Transparent,
                    1f to backgroundColor,
                ),
                center = center.copy(x = size.width * 0.78f, y = size.height * 0.35f),
                radius = size.maxDimension * 0.82f,
            ),
        )
    }

private fun immersiveHeroContentHeight(heroHeight: Dp): Dp =
    (heroHeight * 0.38f).coerceIn(
        IMMERSIVE_HERO_CONTENT_MIN_HEIGHT,
        IMMERSIVE_HERO_CONTENT_MAX_HEIGHT,
    )

private fun immersiveHeroContentOffsetY(heroHeight: Dp): Dp =
    (heroHeight * 0.04f).coerceIn(
        IMMERSIVE_HERO_CONTENT_MIN_OFFSET_Y,
        IMMERSIVE_HERO_CONTENT_MAX_OFFSET_Y,
    )

// Fixed top offset for the adaptive hero's metadata column so it sits in a consistent spot
// without drifting as content height changes between items. Kept fairly high since this mode's
// hero is short and the content otherwise leaves a lot of empty space below.
private fun adaptiveHeroContentTopBaseline(heroHeight: Dp): Dp =
    (heroHeight * 0.12f).coerceIn(36.dp, 96.dp)

@Composable
private fun HeroPageIndicatorRow(
    itemCount: Int,
    pagerState: PagerState,
    coroutineScope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    if (itemCount <= 1) return

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(itemCount) { index ->
            val activeFraction = heroPageVisibility(pagerState, index)
            Box(
                modifier = Modifier
                    .clickable {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(index)
                        }
                    }
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onBackground)
                    .graphicsLayer {
                        alpha = 0.35f + (0.57f * activeFraction)
                    }
                    .width(8.dp + (24.dp * activeFraction))
                    .height(8.dp),
            )
        }
    }
}

private fun heroPageOffset(
    pagerState: PagerState,
    page: Int,
): Float = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction

private fun heroPageVisibility(
    pagerState: PagerState,
    page: Int,
): Float {
    return (1f - abs(heroPageOffset(pagerState, page))).coerceIn(0f, 1f)
}

@Composable
fun HomeHeroReservedSpace(
    modifier: Modifier = Modifier,
    viewportHeight: Dp? = null,
    mobileBelowSectionHeightHint: Dp? = null,
    heightOverride: Dp? = null,
    roundedBottomCorners: Boolean = true,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (roundedBottomCorners) {
                    Modifier.clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                } else {
                    Modifier
                },
            ),
    ) {
        val baseLayout = homeHeroLayout(
            maxWidthDp = maxWidth.value,
            viewportHeightDp = viewportHeight?.value,
            mobileBelowSectionHeightHintDp = mobileBelowSectionHeightHint?.value,
            preferDesktopLayout = isDesktop,
        )
        val layout = heightOverride?.let { baseLayout.copy(heroHeight = it) } ?: baseLayout

        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(layout.heroHeight),
        )
    }
}

@Composable
private fun HeroContentBlock(
    item: MetaPreview,
    layout: HomeHeroLayout,
    onItemClick: ((MetaPreview) -> Unit)?,
) {
    var logoLoadError by remember(item.type, item.id, item.logo) {
        mutableStateOf(false)
    }
    val logoUrl = item.logo?.takeIf { it.isNotBlank() }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (layout.isTablet) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        if (logoUrl != null && !logoLoadError) {
            AsyncImage(
                model = logoUrl,
                contentDescription = item.name,
                modifier = Modifier
                    .fillMaxWidth(layout.logoWidthFraction)
                    .aspectRatio(2.6f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = onItemClick != null,
                    ) {
                        onItemClick?.invoke(item)
                    },
                alignment = if (layout.isTablet) Alignment.CenterStart else Alignment.Center,
                contentScale = ContentScale.Fit,
                onError = { logoLoadError = true },
            )
        } else {
            Text(
                text = item.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = onItemClick != null,
                    ) {
                        onItemClick?.invoke(item)
                    },
                style = if (layout.isTablet) {
                    MaterialTheme.typography.displaySmall
                } else {
                    MaterialTheme.typography.displaySmall
                },
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Black,
                textAlign = if (layout.isTablet) TextAlign.Start else TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        val metaParts = compactHeroMetaParts(item)
        if (metaParts.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (layout.isTablet) {
                    Arrangement.spacedBy(8.dp, Alignment.Start)
                } else {
                    Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                metaParts.forEachIndexed { index, text ->
                    if (index > 0) HeroMetaDot()
                    HeroMetaText(text = text)
                }
            }
        }
    }
}

@Composable
private fun DesktopHeroContentBlock(
    item: MetaPreview,
    layout: HomeHeroLayout,
    interactive: Boolean,
    showExtendedMetadata: Boolean,
    showReleaseMetadata: Boolean = showExtendedMetadata,
    ratingsCache: Map<String, List<MetaExternalRating>>,
    onCastClick: ((HeroCastMember) -> Unit)?,
    production: List<HeroProductionCredit> = emptyList(),
    peoplePanelTab: HeroPeoplePanelTab = HeroPeoplePanelTab.Starring,
    allowProductionHotkeySwap: Boolean = false,
    onItemClick: ((MetaPreview) -> Unit)?,
) {
    val interactionSource = remember { MutableInteractionSource() }
    var logoLoadError by remember(item.type, item.id, item.logo) { mutableStateOf(false) }
    val logoUrl = item.logo?.takeIf { it.isNotBlank() && !logoLoadError }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (interactive && onItemClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                    ) { onItemClick(item) }
                } else {
                    Modifier
                },
            ),
        horizontalAlignment = Alignment.Start,
    ) {
        val cast = heroDisplayCast(
            item = item,
            maxCount = 3,
        )

        if (logoUrl != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(desktopHeroLogoSlotHeight(layout)),
                contentAlignment = if (showExtendedMetadata) Alignment.BottomStart else Alignment.CenterStart,
            ) {
                AsyncImage(
                    model = logoUrl,
                    contentDescription = item.name,
                    modifier = Modifier
                        .fillMaxWidth(desktopHeroLogoWidthFraction(layout))
                        .fillMaxHeight(),
                    alignment = if (showExtendedMetadata) Alignment.BottomStart else Alignment.CenterStart,
                    contentScale = ContentScale.Fit,
                    clipToBounds = false,
                    onError = { state ->
                        heroImageLog.w(state.result.throwable) {
                            "Hero logo failed; showing title: ${logoUrl.safeImageUrlForLog()}"
                        }
                        logoLoadError = true
                    },
                )
            }
        } else {
            // Same fixed-height slot and alignment as the logo branch above (including the
            // showExtendedMetadata-based alignment switch) — otherwise the title's vertical
            // position shifts depending on whether this item has a logo, dragging everything
            // below it (cast, genre line, description) along with it.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(desktopHeroLogoSlotHeight(layout)),
                contentAlignment = if (showExtendedMetadata) Alignment.BottomStart else Alignment.CenterStart,
            ) {
                Text(
                    text = item.name,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Start,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (!showExtendedMetadata && (cast.isNotEmpty() || production.isNotEmpty())) {
            Spacer(modifier = Modifier.height(14.dp))
            if (
                allowProductionHotkeySwap &&
                peoplePanelTab == HeroPeoplePanelTab.Production &&
                production.isNotEmpty()
            ) {
                HeroProductionRow(production)
            } else {
                HeroCastRow(cast, onCastClick = onCastClick)
            }
            Spacer(modifier = Modifier.height(2.dp))
        }

        val genreText = desktopHeroGenreText(item, showExtendedMetadata, showReleaseMetadata)
        if (genreText.isNotBlank()) {
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = genreText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.76f),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        HomeHeroRatingsRow(item = item, ratingsCache = ratingsCache)

        item.description
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { description ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f),
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun String.safeImageUrlForLog(): String = substringBefore('?').take(500)

private enum class HeroPeoplePanelTab {
    Starring,
    Production,
}

private data class HeroProductionCredit(
    val label: String,
    val role: String,
    val imageUrl: String? = null,
)

@Composable
private fun HeroPeopleBlock(
    cast: List<HeroCastMember>,
    production: List<HeroProductionCredit>,
    activeTab: HeroPeoplePanelTab,
    onTabChange: (HeroPeoplePanelTab) -> Unit,
    onCastClick: ((HeroCastMember) -> Unit)?,
) {
    val effectiveTab = when {
        activeTab == HeroPeoplePanelTab.Production && production.isNotEmpty() -> HeroPeoplePanelTab.Production
        cast.isNotEmpty() -> HeroPeoplePanelTab.Starring
        production.isNotEmpty() -> HeroPeoplePanelTab.Production
        else -> HeroPeoplePanelTab.Starring
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        HeroPeopleTabs(
            activeTab = effectiveTab,
            showProduction = production.isNotEmpty(),
            onTabChange = onTabChange,
        )
        Spacer(modifier = Modifier.height(18.dp))
        when (effectiveTab) {
            HeroPeoplePanelTab.Starring -> HeroCastGrid(cast, onCastClick = onCastClick)
            HeroPeoplePanelTab.Production -> HeroProductionGrid(production)
        }
    }
}

@Composable
private fun HeroPeopleTabs(
    activeTab: HeroPeoplePanelTab,
    showProduction: Boolean,
    onTabChange: (HeroPeoplePanelTab) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeroPeopleTabLabel(
            text = "Starring",
            selected = activeTab == HeroPeoplePanelTab.Starring,
            onClick = { onTabChange(HeroPeoplePanelTab.Starring) },
        )
        if (showProduction) {
            Text(
                text = "|",
                modifier = Modifier.height(HERO_PEOPLE_TAB_HEIGHT),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.34f),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            HeroPeopleTabLabel(
                text = "Production",
                selected = activeTab == HeroPeoplePanelTab.Production,
                onClick = { onTabChange(HeroPeoplePanelTab.Production) },
            )
        }
    }
}

@Composable
private fun HeroPeopleTabLabel(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(HERO_PEOPLE_TAB_HEIGHT)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = if (selected) 0.92f else 0.52f),
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun HeroProductionGrid(production: List<HeroProductionCredit>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        production.take(4).chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                row.forEach { credit ->
                    HeroProductionChip(
                        credit = credit,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// Compact single-row production layout that mirrors HeroCastRow (same 34dp icon size and
// spacing) so the adaptive hero's Production view matches its Starring view. Capped to 3
// (TV keeps 4) since the adaptive hero has less room; company/studio credits are dropped
// first when trimming so director/producer/writer are kept.
@Composable
private fun HeroProductionRow(production: List<HeroProductionCredit>) {
    val visible = production
        .sortedBy { it.role.isHeroCompanyRole() }
        .take(3)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        visible.forEach { credit ->
            HeroProductionChip(
                credit = credit,
                modifier = Modifier.weight(1f, fill = false),
                imageSize = 34.dp,
                textFillsWidth = false,
            )
        }
    }
}

@Composable
private fun HeroProductionChip(
    credit: HeroProductionCredit,
    modifier: Modifier = Modifier,
    imageSize: Dp = 52.dp,
    textFillsWidth: Boolean = true,
) {
    val isCompanyLogo = credit.role.isHeroCompanyRole()
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(imageSize)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            if (!credit.imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = credit.imageUrl,
                    contentDescription = credit.label,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(if (isCompanyLogo) imageSize * 0.15f else 0.dp),
                    contentScale = if (isCompanyLogo) ContentScale.Fit else ContentScale.Crop,
                )
            } else {
                Text(
                    text = credit.role.heroProductionInitials(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f),
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                )
            }
        }
        Column(
            modifier = if (textFillsWidth) Modifier.weight(1f) else Modifier,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = credit.label,
                style = if (imageSize >= 48.dp) {
                    MaterialTheme.typography.bodyLarge
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = credit.role,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.52f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HeroCastGrid(
    cast: List<HeroCastMember>,
    onCastClick: ((HeroCastMember) -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        cast.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                row.forEach { person ->
                    HeroCastChip(
                        person = person,
                        modifier = Modifier.weight(1f),
                        imageSize = 52.dp,
                        onCastClick = onCastClick,
                    )
                }
                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HeroCastPortraitPreloader(cast: List<HeroCastMember>) {
    val photoUrls = cast
        .mapNotNull { person -> person.photo?.takeIf(String::isNotBlank) }
        .distinct()
        .take(24)
    if (photoUrls.isEmpty()) return

    Row(
        modifier = Modifier
            .size(1.dp)
            .graphicsLayer { alpha = 0f },
    ) {
        photoUrls.forEach { photoUrl ->
            AsyncImage(
                model = photoUrl,
                contentDescription = null,
                modifier = Modifier.size(1.dp),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun HeroCastRow(
    cast: List<HeroCastMember>,
    onCastClick: ((HeroCastMember) -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cast.forEach { person ->
            HeroCastChip(
                person = person,
                modifier = Modifier.weight(1f, fill = false),
                imageSize = 34.dp,
                onCastClick = onCastClick,
            )
        }
    }
}

@Composable
private fun HeroCastChip(
    person: HeroCastMember,
    modifier: Modifier = Modifier,
    imageSize: Dp,
    onCastClick: ((HeroCastMember) -> Unit)?,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val portraitHighlightAlpha by animateFloatAsState(
        targetValue = if (isHovered && onCastClick != null && person.tmdbId != null && person.tmdbId > 0) 0.12f else 0f,
        label = "hero_cast_portrait_highlight",
    )
    val clickModifier = onCastClick
        ?.takeIf { person.tmdbId != null && person.tmdbId > 0 }
        ?.let { handler ->
            Modifier.clickable(
                interactionSource = interactionSource,
                indication = null,
            ) {
                handler(person)
            }
        }
        ?: Modifier
    Row(
        modifier = modifier.then(clickModifier),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(imageSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (!person.photo.isNullOrBlank()) {
                AsyncImage(
                    model = person.photo,
                    contentDescription = person.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White.copy(alpha = portraitHighlightAlpha)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White.copy(alpha = portraitHighlightAlpha)),
                )
                Text(
                    text = person.name.heroInitials(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Text(
            text = person.name,
            style = if (imageSize >= 48.dp) {
                MaterialTheme.typography.bodyLarge
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.78f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun heroDisplayCast(item: MetaPreview, maxCount: Int): List<HeroCastMember> =
    item.cast
        .asSequence()
        .filter { person -> person.name.isNotBlank() }
        .filterNot { person -> person.role?.isHeroCrewRole() == true }
        .distinctBy { person -> person.name }
        .take(maxCount)
        .toList()

private suspend fun fetchHeroProductionCredits(
    type: String,
    id: String,
): List<HeroProductionCredit> {
    val meta = MetaDetailsRepository.peek(type = type, id = id)
        ?: MetaDetailsRepository.fetch(type = type, id = id)
        ?: return emptyList()
    return heroProductionCredits(meta)
}

private fun heroProductionCredits(meta: MetaDetails): List<HeroProductionCredit> =
    listOfNotNull(
        meta.director.firstHeroName()
            ?.let { name -> HeroProductionCredit(label = name, role = "Director", imageUrl = meta.heroCrewImage(name, "director")) }
            ?: meta.creator.firstHeroName()
                ?.let { name -> HeroProductionCredit(label = name, role = "Creator", imageUrl = meta.heroCrewImage(name, "creator")) },
        meta.producer.firstHeroName()
            ?.let { name -> HeroProductionCredit(label = name, role = "Producer", imageUrl = meta.heroCrewImage(name, "producer")) },
        meta.writer.firstHeroName()
            ?.let { name -> HeroProductionCredit(label = name, role = "Writer", imageUrl = meta.heroCrewImage(name, "writer")) },
        meta.productionCompanies.firstHeroCompany()
            ?.let { company -> HeroProductionCredit(label = company.name, role = "Studio", imageUrl = company.logo) }
            ?: meta.networks.firstHeroCompany()
                ?.let { network -> HeroProductionCredit(label = network.name, role = "Network", imageUrl = network.logo) },
    )
        .distinctBy { credit -> credit.label.trim().lowercase() }

private fun List<String>.firstHeroName(): String? =
    firstOrNull { name -> name.isNotBlank() }?.trim()

private fun List<MetaCompany>.firstHeroCompany(): MetaCompany? =
    firstOrNull { company -> company.name.isNotBlank() }

private fun MetaDetails.heroCrewImage(name: String, roleNeedle: String): String? =
    cast.firstOrNull { person ->
        person.name.equals(name, ignoreCase = true) &&
            person.role.orEmpty().contains(roleNeedle, ignoreCase = true) &&
            !person.photo.isNullOrBlank()
    }?.photo ?: cast.firstOrNull { person ->
        person.name.equals(name, ignoreCase = true) && !person.photo.isNullOrBlank()
    }?.photo

private fun String.heroProductionInitials(): String =
    when (lowercase()) {
        "director" -> "DIR"
        "creator" -> "CRT"
        "producer" -> "PRD"
        "writer" -> "WRT"
        "studio" -> "ST"
        "network" -> "NET"
        else -> take(3).uppercase()
    }

private fun String.isHeroCompanyRole(): Boolean =
    equals("Studio", ignoreCase = true) || equals("Network", ignoreCase = true)

private fun String.heroInitials(): String =
    trim()
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)
        .take(2)
        .mapNotNull { part -> part.firstOrNull()?.uppercaseChar() }
        .joinToString("")

internal fun String.isHeroCrewRole(): Boolean {
    val roleParts = split(Regex("""[,/;|•·]+"""))
        .map { it.trim().lowercase() }
        .filter(String::isNotBlank)
    if (roleParts.isEmpty()) return false
    return roleParts.all { part ->
        heroCrewRoleMarkers.any(part::contains)
    }
}

private val heroCrewRoleMarkers = listOf(
        "director",
        "writer",
        "creator",
        "created by",
        "screenplay",
        "showrunner",
        "producer",
)

@Composable
private fun HomeHeroRatingsRow(item: MetaPreview, ratingsCache: Map<String, List<MetaExternalRating>>) {
    val ratings = ratingsCache["${item.type}:${item.id}"].orEmpty()

    if (ratings.isNotEmpty()) {
        Spacer(modifier = Modifier.height(14.dp))
        RatingsRow(ratings = ratings)
    }
}

private fun desktopHeroLogoWidthFraction(layout: HomeHeroLayout): Float =
    when {
        layout.contentMaxWidth >= 640.dp -> 0.74f
        layout.contentMaxWidth >= 520.dp -> 0.74f
        else -> 0.8f
    }

private fun desktopHeroLogoSlotHeight(layout: HomeHeroLayout): Dp =
    when {
        layout.contentMaxWidth >= 640.dp -> 156.dp
        layout.contentMaxWidth >= 520.dp -> 136.dp
        else -> 104.dp
    }

private fun compactHeroMetaParts(item: MetaPreview): List<String> =
    buildList {
        if (item.type != "collection") {
            add(item.type.replaceFirstChar(Char::uppercase))
        }
        item.genres.firstOrNull()
            ?.takeIf(String::isNotBlank)
            ?.let(::add)
        item.releaseInfo
            ?.takeIf(String::isNotBlank)
            ?.let(::formatReleaseDateForDisplay)
            ?.takeIf(String::isNotBlank)
            ?.let(::add)
    }

private fun desktopHeroGenreText(
    item: MetaPreview,
    showExtendedMetadata: Boolean,
    showReleaseMetadata: Boolean = showExtendedMetadata,
): String {
    val values = buildList {
        addAll(item.genres.take(3))
        if (showReleaseMetadata) {
            item.releaseInfo
                ?.takeIf(String::isNotBlank)
                ?.let(::formatReleaseDateForDisplay)
                ?.takeIf(String::isNotBlank)
                ?.let(::add)
            formatRuntimeForDisplay(item.runtime)
                ?.takeIf(String::isNotBlank)
                ?.let(::add)
            item.ageRating
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.let(::add)
        }
    }
    if (values.isEmpty() && item.type == "collection") return ""
    return values.joinToString(" • ").ifBlank { item.type.replaceFirstChar(Char::uppercase) }
}

@Composable
private fun HeroMetaText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onBackground,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

internal fun homeHeroLayout(
    maxWidthDp: Float,
    viewportHeightDp: Float? = null,
    mobileBelowSectionHeightHintDp: Float? = null,
    preferDesktopLayout: Boolean = false,
    heightMultiplier: Float = 1f,
): HomeHeroLayout =
    when {
        maxWidthDp >= 1200f -> HomeHeroLayout(
            isTablet = true,
            heroHeight = ((maxWidthDp * 0.42f).coerceIn(360f, 440f) * heightMultiplier).dp,
            contentMaxWidth = 640.dp,
            contentWidthFraction = 0.56f,
            contentHorizontalPadding = 56.dp,
            contentVerticalPadding = 22.dp,
            bottomFadeHeight = 190.dp,
            logoWidthFraction = 0.58f,
        )
        maxWidthDp >= 840f -> HomeHeroLayout(
            isTablet = true,
            heroHeight = ((maxWidthDp * 0.46f).coerceIn(340f, 420f) * heightMultiplier).dp,
            contentMaxWidth = 560.dp,
            contentWidthFraction = 0.62f,
            contentHorizontalPadding = 40.dp,
            contentVerticalPadding = 20.dp,
            bottomFadeHeight = 180.dp,
            logoWidthFraction = 0.56f,
        )
        maxWidthDp >= 600f -> HomeHeroLayout(
            isTablet = true,
            heroHeight = ((maxWidthDp * 0.58f).coerceIn(320f, 380f) * heightMultiplier).dp,
            contentMaxWidth = 520.dp,
            contentWidthFraction = 0.72f,
            contentHorizontalPadding = 32.dp,
            contentVerticalPadding = 18.dp,
            bottomFadeHeight = 170.dp,
            logoWidthFraction = 0.54f,
        )
        preferDesktopLayout -> HomeHeroLayout(
            isTablet = true,
            heroHeight = ((maxWidthDp * 0.68f).coerceIn(300f, 360f) * heightMultiplier).dp,
            contentMaxWidth = 360.dp,
            contentWidthFraction = 0.56f,
            contentHorizontalPadding = 16.dp,
            contentVerticalPadding = 18.dp,
            bottomFadeHeight = 150.dp,
            logoWidthFraction = 0.64f,
        )
        else -> HomeHeroLayout(
            isTablet = false,
            heroHeight = mobileHeroHeight(
                maxWidthDp = maxWidthDp,
                viewportHeightDp = viewportHeightDp,
                mobileBelowSectionHeightHintDp = mobileBelowSectionHeightHintDp,
            ) * heightMultiplier,
            contentMaxWidth = 480.dp,
            contentWidthFraction = 1f,
            contentHorizontalPadding = 24.dp,
            contentVerticalPadding = 16.dp,
            bottomFadeHeight = 220.dp,
            logoWidthFraction = 0.62f,
        )
    }

private fun mobileHeroHeight(
    maxWidthDp: Float,
    viewportHeightDp: Float?,
    mobileBelowSectionHeightHintDp: Float?,
): Dp {
    val viewportDrivenHeight = viewportHeightDp?.let { (it * MOBILE_HERO_VIEWPORT_RATIO).dp }
    val widthFallbackHeight = (maxWidthDp * 1.16f).dp
    val baseHeight = viewportDrivenHeight ?: widthFallbackHeight

    val cappedHeight = if (viewportHeightDp != null && mobileBelowSectionHeightHintDp != null) {
        val maxAllowedFromViewport = (viewportHeightDp - mobileBelowSectionHeightHintDp).dp
        baseHeight.coerceAtMost(maxAllowedFromViewport)
    } else {
        baseHeight
    }

    return cappedHeight.coerceIn(MOBILE_HERO_MIN_HEIGHT_DP.dp, MOBILE_HERO_MAX_HEIGHT_DP.dp)
}

@Composable
private fun HeroMetaDot() {
    Box(
        modifier = Modifier
            .size(4.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)),
    )
}

private fun heroBackgroundScrollScale(scrollOffsetPx: Float): Float {
    val scaleIncrease = if (scrollOffsetPx < 0f) {
        abs(scrollOffsetPx) * HERO_SCROLL_UP_SCALE_MULTIPLIER
    } else {
        scrollOffsetPx * HERO_SCROLL_DOWN_SCALE_MULTIPLIER
    }
    return (1f + scaleIncrease).coerceAtMost(HERO_SCROLL_MAX_SCALE)
}

private fun heroBackgroundScrollTranslationY(scrollOffsetPx: Float): Float {
    return scrollOffsetPx * HERO_SCROLL_PARALLAX
}

private fun Modifier.homeHeroPagerGesture(
    pagerState: PagerState,
    itemCount: Int,
    coroutineScope: CoroutineScope,
): Modifier {
    if (itemCount <= 1) return this

    return pointerInput(pagerState, itemCount) {
        awaitEachGesture {
            val down = awaitFirstDown(pass = PointerEventPass.Initial)
            val widthPx = size.width.toFloat().takeIf { it > 0f } ?: return@awaitEachGesture
            val velocityTracker = VelocityTracker().apply {
                addPosition(down.uptimeMillis, down.position)
            }
            val startPage = pagerState.currentPage
            var totalDx = 0f
            var totalDy = 0f
            var dragging = false

            while (true) {
                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                velocityTracker.addPosition(change.uptimeMillis, change.position)

                if (!change.pressed) {
                    if (dragging) {
                        val targetPage = resolveHeroTargetPage(
                            startPage = startPage,
                            itemCount = itemCount,
                            totalDx = totalDx,
                            velocityX = velocityTracker.calculateVelocity().x,
                            widthPx = widthPx,
                        )
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(targetPage)
                        }
                    }
                    break
                }

                val delta = change.position - change.previousPosition
                totalDx += delta.x
                totalDy += delta.y

                if (!dragging) {
                    val horizontalDrag =
                        abs(totalDx) > viewConfiguration.touchSlop && abs(totalDx) > abs(totalDy)
                    val verticalDrag =
                        abs(totalDy) > viewConfiguration.touchSlop && abs(totalDy) > abs(totalDx)

                    when {
                        verticalDrag -> break
                        horizontalDrag -> dragging = true
                        else -> continue
                    }
                }

                pagerState.dispatchRawDelta(-delta.x)
                change.consume()
            }
        }
    }
}

private fun resolveHeroTargetPage(
    startPage: Int,
    itemCount: Int,
    totalDx: Float,
    velocityX: Float,
    widthPx: Float,
): Int {
    val thresholdPassed = abs(totalDx) > widthPx * HERO_SWIPE_THRESHOLD_FRACTION ||
        abs(velocityX) > HERO_SWIPE_VELOCITY_THRESHOLD
    if (!thresholdPassed) return startPage

    val currentPage = startPage.coerceIn(0, itemCount - 1)
    return when {
        totalDx > 0f -> if (currentPage == 0) itemCount - 1 else currentPage - 1
        totalDx < 0f -> if (currentPage == itemCount - 1) 0 else currentPage + 1
        else -> currentPage
    }
}

private fun BoxScope.heroDiscoveryMedalOverlayModifier(
    placement: HeroBadgePlacement,
    immersiveMode: Boolean,
    heroHeight: Dp,
): Modifier =
    when (placement) {
        HeroBadgePlacement.BottomBackdrop -> if (immersiveMode) {
            Modifier
                .align(Alignment.TopEnd)
                .height(heroHeight * 0.64f)
                .fillMaxWidth(HERO_BACKDROP_WIDTH_FRACTION)
                .padding(bottom = HERO_DISCOVERY_MEDAL_EDGE_PADDING)
        } else {
            // Constrain to the same right-side backdrop region as immersive mode so the
            // BottomCenter content alignment centers the badges under the backdrop rather
            // than across the full hero width (which pulls them too far left).
            Modifier
                .align(Alignment.BottomEnd)
                .fillMaxWidth(HERO_BACKDROP_WIDTH_FRACTION)
                .padding(bottom = HERO_DISCOVERY_MEDAL_EDGE_PADDING)
        }

        HeroBadgePlacement.TopRightHorizontal,
        HeroBadgePlacement.TopRightVertical -> Modifier
            .align(Alignment.TopEnd)
            .height(if (immersiveMode) heroHeight * 0.64f else heroHeight)
            .fillMaxWidth(HERO_BACKDROP_WIDTH_FRACTION)
            .padding(
                top = HERO_DISCOVERY_MEDAL_TOP_PADDING,
                end = HERO_DISCOVERY_MEDAL_EDGE_PADDING,
            )
    }

private fun HeroBadgePlacement.heroDiscoveryMedalAlignment(): Alignment =
    when (this) {
        HeroBadgePlacement.BottomBackdrop -> Alignment.BottomCenter
        HeroBadgePlacement.TopRightHorizontal,
        HeroBadgePlacement.TopRightVertical -> Alignment.TopEnd
    }

private fun normalizeHeroDiscoveryPriority(priority: String): List<String> {
    val slots = priority
        .split(',')
        .map(String::trim)
        .filter(String::isNotBlank)
        .toMutableList()
    if ("emmy_noms" !in slots) {
        val insertIndex = slots.indexOf("gg_noms").takeIf { it >= 0 }
            ?.let { it + 1 }
            ?: slots.indexOf("pic_noms").takeIf { it >= 0 }?.let { it + 1 }
            ?: slots.size
        slots.add(insertIndex, "emmy_noms")
    }
    // Migration: the single "structural" slot was split into three distinct badges.
    val structuralIndex = slots.indexOf("structural")
    if (structuralIndex >= 0) {
        slots.removeAt(structuralIndex)
        slots.addAll(structuralIndex, listOf("short_film", "mini_series", "binge_ready"))
    }
    return slots
}

@Composable
private fun HeroDiscoveryMedalStrip(
    facts: List<HeroDiscoveryFact>,
    maxCount: Int,
    placement: HeroBadgePlacement,
    modifier: Modifier = Modifier,
) {
    val visibleFacts = facts.heroVisibleAwardFacts(maxCount)
    if (visibleFacts.isEmpty()) return

    // Badge size scaler (for TV viewing from a distance) — see Homescreen settings.
    val badgeScale = HomeCatalogSettingsRepository.uiState.collectAsState().value.heroBadgeScale
    val medalGap = HERO_DISCOVERY_MEDAL_GAP * badgeScale

    when (placement) {
        HeroBadgePlacement.TopRightVertical -> Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(medalGap),
        ) {
            visibleFacts.forEach { fact ->
                HeroDiscoveryAwardMedal(fact = fact, scale = badgeScale)
            }
        }

        HeroBadgePlacement.BottomBackdrop,
        HeroBadgePlacement.TopRightHorizontal -> Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(medalGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            visibleFacts.forEach { fact ->
                HeroDiscoveryAwardMedal(fact = fact, scale = badgeScale)
            }
        }
    }
}

@Composable
private fun HeroDiscoveryAwardMedal(
    fact: HeroDiscoveryFact,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
) {
    val awardLabel = fact.heroDiscoveryAwardLabel()
    var hoverPosition by remember { mutableStateOf<Offset?>(null) }
    val customBadgeModel = remember(awardLabel, fact.category) {
        heroCustomBadgeModel(label = awardLabel, category = fact.category)
            ?: heroCustomBadgeModel(label = fact.label, category = fact.category)
    }
    val tooltipOffsetPx = with(LocalDensity.current) { HERO_DISCOVERY_TOOLTIP_OFFSET.toPx().roundToInt() }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(HERO_DISCOVERY_MEDAL_SIZE * scale)
                .onPointerEvent(PointerEventType.Enter) { event ->
                    hoverPosition = event.changes.firstOrNull()?.position
                }
                .onPointerEvent(PointerEventType.Move) { event ->
                    hoverPosition = event.changes.firstOrNull()?.position
                }
                .onPointerEvent(PointerEventType.Exit) {
                    hoverPosition = null
                }
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.78f))
                .border(1.dp, Color.White.copy(alpha = 0.20f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            HeroDiscoveryAwardIcon(
                category = fact.category,
                label = awardLabel,
                customBadgeModel = customBadgeModel,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp * scale),
            )
        }

        hoverPosition?.let { position ->
            Popup(
                popupPositionProvider = remember(position, tooltipOffsetPx) {
                    HeroDiscoveryTooltipPositionProvider(
                        IntOffset(
                            x = position.x.roundToInt() + tooltipOffsetPx,
                            y = position.y.roundToInt() + tooltipOffsetPx,
                        ),
                    )
                },
                properties = PopupProperties(focusable = false),
            ) {
                HeroDiscoveryAwardTooltip(
                    title = awardLabel,
                    description = fact.heroDiscoveryTooltipDescription(),
                )
            }
        }
    }
}

private class HeroDiscoveryTooltipPositionProvider(
    private val cursorOffset: IntOffset,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val preferredX = anchorBounds.left + cursorOffset.x
        val preferredY = anchorBounds.top + cursorOffset.y
        return IntOffset(
            x = preferredX.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y = preferredY.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}

@Composable
private fun HeroDiscoveryAwardTooltip(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.widthIn(min = 260.dp, max = 340.dp),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xF21A1F21),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HeroDiscoveryAwardIcon(
    category: String,
    label: String,
    customBadgeModel: Any?,
    modifier: Modifier = Modifier,
) {
    when {
        // 1. A user-supplied custom image in the Badges folder always wins.
        customBadgeModel != null -> AsyncImage(
            model = customBadgeModel,
            contentDescription = label,
            modifier = modifier,
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.High,
        )
        // 2. Foreign uses a per-language flag, which reads better than a generic icon.
        category.startsWith("foreign:") -> HeroDiscoveryFlagIcon(
            languageCode = category.substringAfter(':'),
            modifier = modifier,
        )
        else -> {
            val badgeFileName = heroDiscoveryBadgeFileName(category, label)
            val bundledBadgeModel = remember(badgeFileName) {
                heroBundledBadgeModel(badgeFileName)
            }
            val fallbackPainter = painterResource(heroDiscoveryBadgeResource(category, label))
            if (bundledBadgeModel != null) {
                AsyncImage(
                    model = bundledBadgeModel,
                    contentDescription = label,
                    modifier = modifier,
                    error = fallbackPainter,
                    fallback = fallbackPainter,
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.High,
                )
            } else {
                Image(
                    painter = fallbackPainter,
                    contentDescription = label,
                    modifier = modifier,
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

@Composable
private fun HeroDiscoveryFlagIcon(
    languageCode: String,
    modifier: Modifier = Modifier,
) {
    val palette = languageCode.heroDiscoveryFlagPalette()
    if (palette == null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "\uD83C\uDF10",
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp, lineHeight = 20.sp),
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
        return
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val flagWidth = size.width * 0.78f
        val flagHeight = size.height * 0.56f
        val left = (size.width - flagWidth) / 2f
        val top = (size.height - flagHeight) / 2f
        if (palette.vertical) {
            val stripeWidth = flagWidth / palette.colors.size
            palette.colors.forEachIndexed { index, color ->
                drawRect(
                    color = color,
                    topLeft = Offset(left + stripeWidth * index, top),
                    size = Size(stripeWidth + 0.5f, flagHeight),
                )
            }
        } else {
            val stripeHeight = flagHeight / palette.colors.size
            palette.colors.forEachIndexed { index, color ->
                drawRect(
                    color = color,
                    topLeft = Offset(left, top + stripeHeight * index),
                    size = Size(flagWidth, stripeHeight + 0.5f),
                )
            }
        }
        drawRect(
            color = Color.White.copy(alpha = 0.28f),
            topLeft = Offset(left, top),
            size = Size(flagWidth, 1.2f),
        )
    }
}

private data class HeroDiscoveryFlagPalette(
    val colors: List<Color>,
    val vertical: Boolean = false,
)

private fun String.heroDiscoveryFlagPalette(): HeroDiscoveryFlagPalette? =
    when (trim().lowercase()) {
        "es", "spa" -> HeroDiscoveryFlagPalette(listOf(Color(0xFFC60B1E), Color(0xFFFFC400), Color(0xFFC60B1E)))
        "fr", "fre", "fra" -> HeroDiscoveryFlagPalette(listOf(Color(0xFF0055A4), Color.White, Color(0xFFEF4135)), vertical = true)
        "de", "ger", "deu" -> HeroDiscoveryFlagPalette(listOf(Color.Black, Color(0xFFDD0000), Color(0xFFFFCE00)))
        "it", "ita" -> HeroDiscoveryFlagPalette(listOf(Color(0xFF009246), Color.White, Color(0xFFCE2B37)), vertical = true)
        "pt", "por" -> HeroDiscoveryFlagPalette(listOf(Color(0xFF006600), Color(0xFFFF0000)), vertical = true)
        "ja", "jpn" -> HeroDiscoveryFlagPalette(listOf(Color.White, Color(0xFFBC002D), Color.White), vertical = true)
        "ko", "kor" -> HeroDiscoveryFlagPalette(listOf(Color.White, Color(0xFFC60C30), Color(0xFF003478)), vertical = true)
        "zh", "zho", "chi" -> HeroDiscoveryFlagPalette(listOf(Color(0xFFDE2910), Color(0xFFFFDE00), Color(0xFFDE2910)), vertical = true)
        "da", "dan" -> HeroDiscoveryFlagPalette(listOf(Color(0xFFC60C30), Color.White, Color(0xFFC60C30)), vertical = true)
        "sv", "swe" -> HeroDiscoveryFlagPalette(listOf(Color(0xFF006AA7), Color(0xFFFECC00), Color(0xFF006AA7)), vertical = true)
        "no", "nor" -> HeroDiscoveryFlagPalette(listOf(Color(0xFFBA0C2F), Color.White, Color(0xFF00205B), Color.White, Color(0xFFBA0C2F)), vertical = true)
        "fi", "fin" -> HeroDiscoveryFlagPalette(listOf(Color.White, Color(0xFF002F6C), Color.White), vertical = true)
        "nl", "dut", "nld" -> HeroDiscoveryFlagPalette(listOf(Color(0xFFAE1C28), Color.White, Color(0xFF21468B)))
        "pl", "pol" -> HeroDiscoveryFlagPalette(listOf(Color.White, Color(0xFFDC143C)))
        "ru", "rus" -> HeroDiscoveryFlagPalette(listOf(Color.White, Color(0xFF0039A6), Color(0xFFD52B1E)))
        "tr", "tur" -> HeroDiscoveryFlagPalette(listOf(Color(0xFFE30A17), Color.White, Color(0xFFE30A17)), vertical = true)
        "ar", "ara" -> HeroDiscoveryFlagPalette(listOf(Color(0xFF006C35), Color.White, Color(0xFF006C35)), vertical = true)
        "hi", "hin" -> HeroDiscoveryFlagPalette(listOf(Color(0xFFFF9933), Color.White, Color(0xFF138808)))
        "fa", "per", "fas" -> HeroDiscoveryFlagPalette(listOf(Color(0xFF239F40), Color.White, Color(0xFFDA0000)))
        "ro", "rum", "ron" -> HeroDiscoveryFlagPalette(listOf(Color(0xFF002B7F), Color(0xFFFCD116), Color(0xFFCE1126)), vertical = true)
        "hu", "hun" -> HeroDiscoveryFlagPalette(listOf(Color(0xFFCE2939), Color.White, Color(0xFF477050)))
        "cs", "cze", "ces" -> HeroDiscoveryFlagPalette(listOf(Color.White, Color(0xFFD7141A), Color(0xFF11457E)), vertical = true)
        "he", "heb" -> HeroDiscoveryFlagPalette(listOf(Color.White, Color(0xFF0038B8), Color.White))
        "el", "gre", "ell" -> HeroDiscoveryFlagPalette(listOf(Color(0xFF0D5EAF), Color.White, Color(0xFF0D5EAF)))
        else -> null
    }

private fun List<HeroDiscoveryFact>.heroVisibleAwardFacts(): List<HeroDiscoveryFact> =
    heroVisibleAwardFacts(HERO_DISCOVERY_MAX_VISIBLE_BADGES)

private fun List<HeroDiscoveryFact>.heroVisibleAwardFacts(maxCount: Int): List<HeroDiscoveryFact> {
    val safeMaxCount = maxCount.coerceIn(0, HERO_DISCOVERY_MAX_VISIBLE_BADGES)
    if (safeMaxCount == 0) return emptyList()
    // Facts already arrive in configured priority order (awards first, context badges after).
    // Show them together up to the badge limit rather than hiding context badges whenever an
    // award is present. A time-sensitive release-status alert stays pinned to the front.
    val releaseStatus = firstOrNull { it.category == "release_status" || it.category == "alert" }
    return if (releaseStatus != null) {
        listOf(releaseStatus) + filterNot { it === releaseStatus }.take(safeMaxCount - 1)
    } else {
        take(safeMaxCount)
    }
}

private fun HeroDiscoveryFact.heroDiscoveryAwardLabel(): String =
    when (category) {
        "award:best_picture" -> "Best Picture"
        "award:best_picture_nom" -> "Best Picture Nominee"
        "award:globe_win" -> "Golden Globe"
        "award:globe_nom" -> "Globe Nominee"
        "award:emmy_win" -> "Emmy Winner"
        "award:emmy_nom" -> "Emmy Nominee"
        "award:palme" -> "Palme d'Or"
        "award:golden_lion" -> "Golden Lion"
        "award:golden_bear" -> "Golden Bear"
        "award:people_choice" -> "People's Choice"
        "metacritic" -> "Must-See"
        "cult" -> "Cult Classic"
        "trending" -> "Trending"
        "short_film" -> "Short Film"
        "mini_series" -> "Mini Series"
        "binge_ready" -> "Binge Ready"
        "new_release", "digital_release" -> "New Release"
        // festival, foreign:<lang>, director, studio, release_status carry a dynamic label.
        else -> label
    }

private fun HeroDiscoveryFact.heroDiscoveryTooltipDescription(): String =
    // Foreign carries a `foreign:<lang>` category, so match the prefix before the exact keys.
    if (category.startsWith("foreign:")) {
        "The title's original language is not English, shown as a quick language context badge."
    } else when (category) {
        "award:best_picture" ->
            "The Academy Award for Best Picture, the top film award presented annually by the Academy of Motion Picture Arts and Sciences."
        "award:best_picture_nom" ->
            "Nominated for the Academy Award for Best Picture, the Academy's top annual film category."
        "award:globe_win" ->
            "Won a major Golden Globe, presented by the Hollywood Foreign Press Association for film and television."
        "award:globe_nom" ->
            "Nominated for a major Golden Globe, highlighting higher-profile film or television categories."
        "award:emmy_win" ->
            "Won a major Emmy Award, one of television's most recognized industry honors."
        "award:emmy_nom" ->
            "Nominated for a major Emmy Award, filtered to highlight higher-profile television recognition."
        "award:palme" ->
            "The most prestigious award from the Cannes Film Festival held in France, awarded to one film per year since 1975."
        "award:golden_lion" ->
            "The top prize from the Venice Film Festival, awarded to the best film in the main competition."
        "award:golden_bear" ->
            "The top prize from the Berlin International Film Festival, awarded to the best film in competition."
        "award:people_choice" ->
            "Toronto International Film Festival's audience-voted top prize, often a strong signal for broad festival appeal."
        "metacritic" ->
            "Metacritic's Must-See designation, reserved for titles with especially strong critic consensus."
        "cult" ->
            "Flagged by cult-film keywords or curated metadata as a title with lasting niche, midnight-movie, or fan-driven appeal."
        "release_status" ->
            when {
                label.equals("Cinema", ignoreCase = true) ->
                    "This title is still marked as cinema-only, so it may not be available to stream or watch yet."
                label.equals("Production", ignoreCase = true) ->
                    "This title is still marked as in production, so it may not be available to stream or watch yet."
                label.equals("Streaming", ignoreCase = true) ->
                    "This title is marked as available to stream or watch at home."
                label.equals("Physical", ignoreCase = true) ->
                    "This title has a physical home release, such as Blu-ray or DVD."
                else ->
                    "Release status: $label."
            }
        "true_story" ->
            "Detected from true-story metadata and keywords, meaning the title is based on real people, events, or reported history."
        "studio", "prestige" ->
            "Highlights a favored or notable production company attached to the title."
        "director" ->
            "Highlights a favored or notable director attached to the title."
        "foreign", "info" ->
            "The title's original language is not English, shown as a quick language context badge."
        "award:festival", "festival" ->
            when (label) {
                "Sundance Grand Jury" ->
                    "The Grand Jury Prize from the Sundance Film Festival, the top honor at the leading festival for American independent film."
                "New Currents" ->
                    "The New Currents award from the Busan International Film Festival, recognizing first or second features by Asian directors."
                "Golden Leopard" ->
                    "The top prize from the Locarno Film Festival in Switzerland, one of the longest-running festivals for auteur and independent cinema."
                "Tiger Award" ->
                    "The top prize from the International Film Festival Rotterdam, spotlighting bold work from first- or second-time filmmakers."
                "SXSW Jury" ->
                    "The jury-selected top prize at South by Southwest (SXSW), a major festival for independent film."
                "Tribeca Audience Award" ->
                    "The audience-voted top prize at the Tribeca Festival in New York."
                else ->
                    "Recognized at a major film festival, such as Cannes, Venice, Berlin, Sundance, or Toronto."
            }
        "trending" ->
            "Currently trending, among the most popular titles being watched right now."
        "short_film" ->
            "A short film, with a runtime under 40 minutes."
        "mini_series" ->
            "A limited or mini series, a self-contained story told over a single short season."
        "binge_ready" ->
            "A finished series with a small enough episode count to watch from start to end in a few sittings."
        "new_release", "digital_release" ->
            "Recently released or newly available to stream, based on the title's release date."
        else ->
            "Shown because this title matched one of your configured hero discovery signals."
    }

private fun heroDiscoveryBadgeResource(category: String, label: String): DrawableResource =
    if (category.startsWith("foreign:")) {
        Res.drawable.hero_badge_foreign
    } else {
        when (category) {
            // Oscar wins are surfaced as Best Picture only (not "any Oscar").
            "award:best_picture" -> Res.drawable.hero_badge_best_picture
            "award:best_picture_nom" -> Res.drawable.hero_badge_oscar_nom
            "award:globe_win" -> Res.drawable.hero_badge_globe_win
            "award:globe_nom" -> Res.drawable.hero_badge_globe_nom
            "award:emmy_win" -> Res.drawable.hero_badge_emmy_win
            "award:emmy_nom" -> Res.drawable.hero_badge_emmy_nom
            "award:palme" -> Res.drawable.hero_badge_palme
            "award:golden_lion" -> Res.drawable.hero_badge_golden_lion
            "award:golden_bear" -> Res.drawable.hero_badge_golden_bear
            "award:festival", "festival" -> Res.drawable.hero_badge_festival
            "award:people_choice" -> Res.drawable.hero_badge_people_choice
            "wins", "gg_wins", "win" -> Res.drawable.hero_badge_win
            "pic_noms", "gg_noms", "emmy_noms", "noms", "nom" -> Res.drawable.hero_badge_nom
            "metacritic" -> Res.drawable.hero_badge_metacritic
            "cult" -> Res.drawable.hero_badge_cult
            "true_story" -> Res.drawable.hero_badge_true_story
            "new_release", "digital_release" -> Res.drawable.hero_badge_new_release
            "release_status", "alert" -> if (label.isUnavailableReleaseStatusLabel()) {
                Res.drawable.hero_badge_release_status
            } else {
                Res.drawable.hero_badge_release_status_available
            }
            "studio", "prestige" -> Res.drawable.hero_badge_studio
            "director" -> Res.drawable.hero_badge_director
            "trending" -> Res.drawable.hero_badge_trending
            "short_film" -> Res.drawable.hero_badge_short_film
            "mini_series" -> Res.drawable.hero_badge_mini_series
            "binge_ready" -> Res.drawable.hero_badge_binge_ready
            "foreign", "info" -> Res.drawable.hero_badge_foreign
            else -> Res.drawable.hero_badge_win
        }
    }

private fun heroDiscoveryBadgeFileName(category: String, label: String): String =
    if (category.startsWith("foreign:")) {
        "hero_badge_foreign.png"
    } else {
        when (category) {
            "award:best_picture" -> "hero_badge_best_picture.png"
            "award:best_picture_nom" -> "hero_badge_oscar_nom.png"
            "award:globe_win" -> "hero_badge_globe_win.png"
            "award:globe_nom" -> "hero_badge_globe_nom.png"
            "award:emmy_win" -> "hero_badge_emmy_win.png"
            "award:emmy_nom" -> "hero_badge_emmy_nom.png"
            "award:palme" -> "hero_badge_palme.png"
            "award:golden_lion" -> "hero_badge_golden_lion.png"
            "award:golden_bear" -> "hero_badge_golden_bear.png"
            "award:festival", "festival" -> "hero_badge_festival.png"
            "award:people_choice" -> "hero_badge_people_choice.png"
            "wins", "gg_wins", "win" -> "hero_badge_win.png"
            "pic_noms", "gg_noms", "emmy_noms", "noms", "nom" -> "hero_badge_nom.png"
            "metacritic" -> "hero_badge_metacritic.png"
            "cult" -> "hero_badge_cult.png"
            "true_story" -> "hero_badge_true_story.png"
            "new_release", "digital_release" -> "hero_badge_new_release.png"
            "release_status", "alert" -> if (label.isUnavailableReleaseStatusLabel()) {
                "hero_badge_release_status.png"
            } else {
                "hero_badge_release_status_available.png"
            }
            "studio", "prestige" -> "hero_badge_studio.png"
            "director" -> "hero_badge_director.png"
            "trending" -> "hero_badge_trending.png"
            "short_film" -> "hero_badge_short_film.png"
            "mini_series" -> "hero_badge_mini_series.png"
            "binge_ready" -> "hero_badge_binge_ready.png"
            "foreign", "info" -> "hero_badge_foreign.png"
            else -> "hero_badge_win.png"
        }
    }

private fun String.isUnavailableReleaseStatusLabel(): Boolean =
    trim().let { status ->
        status.equals("Cinema", ignoreCase = true) || status.equals("Production", ignoreCase = true)
    }
