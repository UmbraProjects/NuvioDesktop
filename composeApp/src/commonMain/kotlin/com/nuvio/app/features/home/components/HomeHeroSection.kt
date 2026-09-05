package com.nuvio.app.features.home.components

import co.touchlab.kermit.Logger
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.settings.DesktopNavigationLayout
import com.nuvio.app.features.settings.ThemeSettingsRepository
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.nuvio.app.isDesktop
import com.nuvio.app.core.ui.NuvioDesktopImageScaling
import com.nuvio.app.core.ui.secondaryClick
import com.nuvio.app.core.ui.NuvioAsyncImage as AsyncImage
import com.nuvio.app.core.format.formatReleaseDateForDisplay
import com.nuvio.app.features.details.HeroTrailerAudioState
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaCompany
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaExternalRating
import com.nuvio.app.features.details.components.RatingsRow
import com.nuvio.app.features.details.formatRuntimeForDisplay
import com.nuvio.app.features.qualicache.QualiCacheQualityService
import com.nuvio.app.features.qualicache.QualityHighlight
import com.nuvio.app.features.qualicache.QualityInlineBadges
import com.nuvio.app.features.qualicache.rememberQualityBadgesEnabled
import com.nuvio.app.features.qualicache.rememberQualityHighlights
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.randomPlayCategoryOrNull
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

/**
 * How long focus must rest on an item before its trailer is extracted from YouTube. Long enough
 * that browsing a row never triggers one; comfortably shorter than the shortest auto-play delay,
 * so the stream is still warm by the time the dwell timer fires.
 */
private const val HERO_TRAILER_PREFETCH_DWELL_MS = 1_200L
private val IMMERSIVE_HERO_CONTENT_MIN_HEIGHT = 300.dp
private val IMMERSIVE_HERO_CONTENT_MAX_HEIGHT = 420.dp
private val IMMERSIVE_HERO_CONTENT_BOTTOM_PADDING = 44.dp
private val IMMERSIVE_HERO_CONTENT_MIN_OFFSET_Y = 16.dp
private val IMMERSIVE_HERO_CONTENT_MAX_OFFSET_Y = 42.dp
private val IMMERSIVE_HERO_LANDSCAPE_SHELF_OVERLAP = 72.dp
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
    // How much the hero was scaled past its natural height by the adaptive Hero-height slider
    // (1f = natural). Drives logo size and content placement so they grow/shrink with the hero
    // instead of staying pinned to the un-scaled base metrics.
    val heroHeightScale: Float = 1f,
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
    /**
     * TV Mode only: run the backdrop to the bottom of the screen and let the shelf float over it,
     * instead of confining it above the shelf and fading to black. Ignored outside [immersiveMode].
     */
    immersiveFullBackdrop: Boolean = false,
    adaptiveHeroMode: Boolean = false,
    heroEnabled: Boolean = true,
    heroInfoLines: Int = 2,
    heroInfoPriority: String = "wins,gg_wins,festival,pic_noms,gg_noms,emmy_noms,studio,director,trending,cult,foreign,new_release,metacritic,true_story,stinger,short_film,mini_series,binge_ready,release_status",
    heroBadgePlacement: HeroBadgePlacement = HeroBadgePlacement.BottomBackdrop,
    heroReleaseStatusUnavailableOnly: Boolean = true,
    trailersEnabledInCurrentMode: Boolean = true,
    immersiveContentBottomPadding: Dp = IMMERSIVE_HERO_CONTENT_BOTTOM_PADDING,
    resumePromptItemKey: String? = null,
    resumePromptLabel: String = "",
    onResumePromptAction: (() -> Unit)? = null,
    onResumePromptDismiss: (() -> Unit)? = null,
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
        val layout = heightOverride?.let { override ->
            // The override carries the adaptive Hero-height slider result; recover the multiplier
            // relative to the natural base height so the logo and content placement scale with it.
            // Only the Adaptive (non-immersive) hero should scale — TV Mode / full-screen trailer
            // overrides blow the hero up to the whole viewport and must not drag the logo along.
            val scale = if (adaptiveHeroMode && !immersiveMode && baseLayout.heroHeight > 0.dp) {
                (override / baseLayout.heroHeight).coerceIn(0.5f, 2.5f)
            } else {
                baseLayout.heroHeightScale
            }
            baseLayout.copy(heroHeight = override, heroHeightScale = scale)
        } ?: baseLayout
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

        val focusedIndex = focusedItem?.let { focused ->
            items.indexOfFirst { it.id == focused.id && it.type == focused.type }
        }?.takeIf { it >= 0 }
        val displayItems = when {
            focusedItem == null -> items
            // The carousel seed holds its *own* copy of an item — artwork stripped while its hero
            // pass is pending, then refilled from whatever that pass found. The focused row item is
            // the one the caller means, so it has to replace the seed copy rather than merely
            // select it: otherwise the backdrop is read off the seed copy while the title, logo and
            // cast come from the focused one, and the two disagree (typically a poster-shaped
            // fallback sitting under the right title).
            focusedIndex != null -> items.toMutableList().apply { set(focusedIndex, focusedItem) }
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
        val discoveryPriority = remember(heroInfoPriority) {
            HeroDiscoveryMetadataService.normalizePriority(heroInfoPriority)
        }
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
                // Every lookup below goes through the item's metadata identity rather than its own
                // id: a cloud-library row is listed under a provider download key that resolves to
                // nothing anywhere, so without this it has no cast, no ratings and no badges.
                if (!castCache.containsKey(key) && target.type != "collection") {
                    launch {
                        prefetchSlots.withPermit {
                            castCache[key] = HeroCastMetadataService.fetch(
                                type = target.metadataType,
                                id = target.metadataId,
                            )
                        }
                    }
                }
                if (!discoveryCache.containsKey(discoveryKey) && target.type != "collection") {
                    launch {
                        prefetchSlots.withPermit {
                            discoveryCache[discoveryKey] = HeroDiscoveryMetadataService.fetch(
                                type = target.metadataType,
                                id = target.metadataId,
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
                                type = target.metadataType,
                                id = target.metadataId,
                            )
                        }
                    }
                }
                if (ratingsCache.containsKey(key)) continue
                if (target.type == "collection") {
                    ratingsCache[key] = emptyList()
                    continue
                }
                val baseMeta = MetaDetails(
                    id = target.metadataId,
                    type = target.metadataType,
                    name = target.name,
                )
                if (!MdbListMetadataService.shouldFetchForMeta(baseMeta, target.metadataId, settings)) {
                    ratingsCache[key] = emptyList()
                    continue
                }
                launch {
                    prefetchSlots.withPermit {
                        val ratings = MdbListMetadataService.enrichMeta(
                            meta = baseMeta,
                            fallbackItemId = target.metadataId,
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
                    immersiveFullBackdrop = immersiveFullBackdrop,
                    adaptiveHeroMode = adaptiveHeroMode,
                    heroEnabled = heroEnabled,
                    heroInfoLines = heroInfoLines,
                    heroInfoPriority = heroInfoPriority,
            heroBadgePlacement = heroBadgePlacement,
            heroReleaseStatusUnavailableOnly = heroReleaseStatusUnavailableOnly,
            trailersEnabledInCurrentMode = trailersEnabledInCurrentMode,
                    immersiveContentBottomPadding = immersiveContentBottomPadding,
                    resumePromptItemKey = resumePromptItemKey,
                    resumePromptLabel = resumePromptLabel,
                    onResumePromptAction = onResumePromptAction,
                    onResumePromptDismiss = onResumePromptDismiss,
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
    immersiveFullBackdrop: Boolean = false,
    adaptiveHeroMode: Boolean = false,
    heroEnabled: Boolean,
    heroInfoLines: Int,
    heroInfoPriority: String,
    heroBadgePlacement: HeroBadgePlacement,
    heroReleaseStatusUnavailableOnly: Boolean,
    trailersEnabledInCurrentMode: Boolean,
    immersiveContentBottomPadding: Dp,
    resumePromptItemKey: String?,
    resumePromptLabel: String,
    onResumePromptAction: (() -> Unit)?,
    onResumePromptDismiss: (() -> Unit)?,
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
    val heroTrailerRequest by HomeHeroTrailerManualTrigger.requests.collectAsState(initial = null)
    val heroTrailerFocusKey = "${currentItem.type}:${currentItem.id}"
    // Resets the dwell timer on every focus move; false whenever home isn't the active screen.
    val heroTrailerFocusNonce by HomeHeroTrailerGate.focusNonce.collectAsState()
    val heroTrailerHomeActive by HomeHeroTrailerGate.homeActive.collectAsState()
    var heroTrailerSource by remember { mutableStateOf<TrailerPlaybackSource?>(null) }
    var heroTrailerSurfaceReady by remember { mutableStateOf(false) }
    var heroTrailerPlaybackRequested by remember { mutableStateOf(false) }
    var heroTrailerFinished by remember { mutableStateOf(false) }
    // Bumped by the surface's onError; the effect below turns one failure into a single fresh
    // re-extraction rather than letting a rejected media URL silence the item.
    var heroTrailerErrorToken by remember { mutableIntStateOf(0) }
    var heroTrailerRetriedKey by remember { mutableStateOf<String?>(null) }
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
        // Clearing the token first matters: the retry effect keys on it, and a leftover non-zero
        // token plus a cleared marker would read as "this newly focused item just failed".
        heroTrailerErrorToken = 0
        heroTrailerRetriedKey = null
        heroTrailerLog.i {
            "gate autoplay=$heroTrailerAutoplayEnabled homeActive=$heroTrailerHomeActive " +
                "adaptiveHeroMode=$adaptiveHeroMode immersive=$immersiveMode " +
                "settingEnabled=${playerSettings.heroTvTrailerEnabled} key=$heroTrailerFocusKey " +
                "delay=${playerSettings.heroTvTrailerDelaySeconds}s"
        }
    }
    // Resolve the trailer stream once focus settles, even when autoplay is disabled, so a later
    // `T` press can start without YouTube extraction. Do not mount the native player here: even a
    // hidden WebView can briefly steal OS focus while it initializes.
    //
    // The wait is long enough to sit out normal browsing. Each resolve is a full YouTube extraction
    // (watch page + one player call per client), so at the old 250ms scrolling a catalog fired one
    // per poster passed over — dozens of extractions a minute, which is both wasted work and the
    // surest way to get this client's media URLs refused.
    LaunchedEffect(heroTrailerFocusKey, tvHeroActive, heroTrailerHomeActive) {
        if (!tvHeroActive || !heroTrailerHomeActive || currentItem.type == "collection") {
            return@LaunchedEffect
        }
        delay(HERO_TRAILER_PREFETCH_DWELL_MS)
        heroTrailerLog.i { "caching trailer stream for $heroTrailerFocusKey" }
        HeroTrailerMetadataService.resolve(currentItem.type, currentItem.id)
    }
    // A rejected media URL (YouTube 403s them often enough) is worth exactly one fresh extraction:
    // the cached resolution is dropped and re-resolved, which is what the details screen effectively
    // does every time and why a trailer could play there while the home hero stayed silent. One
    // retry per focused item — a second failure means the item really has nothing playable.
    LaunchedEffect(heroTrailerErrorToken, heroTrailerFocusKey) {
        if (heroTrailerErrorToken == 0 || heroTrailerRetriedKey == heroTrailerFocusKey) {
            return@LaunchedEffect
        }
        heroTrailerRetriedKey = heroTrailerFocusKey
        heroTrailerLog.i { "retrying trailer after playback error for $heroTrailerFocusKey" }
        HeroTrailerMetadataService.invalidate(currentItem.type, currentItem.id)
        val resolved = HeroTrailerMetadataService.resolve(currentItem.type, currentItem.id)
        if (resolved == null) return@LaunchedEffect
        heroTrailerSource = resolved
        heroTrailerFinished = false
        heroTrailerPlaybackRequested = true
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
    // Manual `T` shortcut / hover-preview button: play a trailer immediately, even with auto-play
    // off. The request names its own subject when the caller has one in mind.
    LaunchedEffect(heroTrailerRequest?.token) {
        val request = heroTrailerRequest
        // Logged before the guards so a swallowed request names which guard swallowed it.
        heroTrailerLog.i {
            "manual token=${request?.token} tvHeroActive=$tvHeroActive " +
                "homeActive=$heroTrailerHomeActive key=$heroTrailerFocusKey"
        }
        if (request == null || !tvHeroActive || !heroTrailerHomeActive ||
            currentItem.type == "collection"
        ) {
            return@LaunchedEffect
        }
        // Toggle: if a trailer is already showing, the same input dismisses it (an explicit way
        // out in addition to simply moving focus to another item).
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
            heroTrailerLog.i { "no trailer available for $heroTrailerFocusKey" }
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
    // The heavyweight native trailer surface paints over the Compose navbar. When the trailer is
    // full-bleed in the hero (not fullscreen — nobody navigates during a fullscreen trailer), tell
    // the overlay which edge the navbar sits on so moving the pointer there dismisses the trailer
    // and uncovers it. TV/immersive and adaptive-hero both cover the top/left chrome.
    val desktopNavLayout by ThemeSettingsRepository.desktopNavigationLayout.collectAsState()
    val heroTrailerNavDismissEdge = when {
        heroTrailerFullscreen -> "none"
        desktopNavLayout == DesktopNavigationLayout.Sidebar -> "left"
        else -> "top"
    }
    // The overlay surface tracks the hero, so the dismiss band is a fraction of that surface. TV
    // mode's hero fills the viewport (a modest fraction is a small top slice); the adaptive hero is
    // a compact, resizable strip where the same fraction would swallow it, so keep it much tighter.
    val heroTrailerNavDismissBandFraction = if (immersiveMode) 0.22f else 0.12f
    // Expose visibility so the home key handler can map Escape to "dismiss trailer".
    // Only claim the shared "a trailer is showing" flag while this hero is actually hosting
    // trailers. Basic's overlay owns the flag there, and a hero scrolling in and out of the rows
    // list re-runs this effect on every remount — which would clear the overlay's claim.
    LaunchedEffect(heroTrailerVisible, tvHeroActive) {
        if (tvHeroActive) HomeHeroTrailerManualTrigger.setActive(heroTrailerVisible)
    }
    DisposableEffect(tvHeroActive) {
        onDispose { if (tvHeroActive) HomeHeroTrailerManualTrigger.setActive(false) }
    }
    // Full backdrop runs the artwork the whole way down and the whole way across; the default
    // stops it above the shelf and confines it to the right-hand region, which is what makes the
    // shelf read as a black band rather than as something floating over the picture.
    val immersiveFullBackdropActive = immersiveMode && immersiveFullBackdrop
    // Where the shelf begins. Everything that has to stay *out* of the shelf keeps measuring
    // against this in both layouts: the discovery badges, which would otherwise be buried under
    // the rail, and the trailer surface, which is a heavyweight native window Compose cannot draw
    // over — running that to the bottom edge would hide the rows outright rather than sit behind
    // them.
    val immersiveBackdropHeight = immersiveHeroBackdropHeight(
        heroHeight = layout.heroHeight,
        immersiveContentBottomPadding = immersiveContentBottomPadding,
    )
    // The still artwork, and only it, is what full backdrop extends.
    val heroArtworkHeight =
        if (immersiveFullBackdropActive) layout.heroHeight else immersiveBackdropHeight
    val heroBackdropWidthFraction =
        if (immersiveFullBackdropActive) 1f else HERO_BACKDROP_WIDTH_FRACTION

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
                        Modifier.height(heroArtworkHeight)
                    } else {
                        Modifier.fillMaxHeight()
                    },
                )
                .fillMaxWidth(heroBackdropWidthFraction)
                // Clip the backdrop region: during a transition the layers are parallax-shifted
                // and scaled, and without this they paint a cropped sliver outside the box (over
                // the content panel, where the fade mask doesn't reach) — a stray vertical band.
                .clipToBounds()
                // The left-hand fade stays in both layouts: it is what keeps the logo and metadata
                // legible over the artwork. The immersive extra mask does not — its vertical half
                // fades the picture out to solid black before the bottom of its own box, which is
                // precisely the band full backdrop exists to remove.
                .heroBackdropFadeMask(backgroundColor, immersiveFullBackdropActive)
                .then(
                    if (immersiveMode && !immersiveFullBackdropActive) {
                        Modifier.immersiveHeroExtraMask(backgroundColor)
                    } else {
                        Modifier
                    },
                )
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
                            .height(immersiveBackdropHeight)
                            .fillMaxWidth()
                        else -> Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .fillMaxWidth()
                    },
                    navDismissEdge = heroTrailerNavDismissEdge,
                    navDismissBandFraction = heroTrailerNavDismissBandFraction,
                    onReady = { heroTrailerSurfaceReady = true },
                    onEnded = {
                        heroTrailerSurfaceReady = false
                        heroTrailerPlaybackRequested = false
                        heroTrailerFinished = true
                    },
                    // Pointer reached the navbar edge: stop the trailer so the navbar is usable.
                    // Stays stopped (heroTrailerFinished) until focus moves to another hero item.
                    onNavChromeDismiss = {
                        heroTrailerSurfaceReady = false
                        heroTrailerPlaybackRequested = false
                        heroTrailerFinished = true
                    },
                    onError = {
                        heroTrailerSurfaceReady = false
                        heroTrailerPlaybackRequested = false
                        heroTrailerFinished = true
                        // Hands the item to the retry effect above, which re-extracts once before
                        // accepting that it has no playable trailer.
                        heroTrailerErrorToken += 1
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
                            immersiveHeroBottomFadeStops(
                                backgroundColor = backgroundColor,
                                fullBackdrop = immersiveFullBackdropActive,
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
                            resumePromptLabel = if ("${items[layer.page].type}:${items[layer.page].id}" == resumePromptItemKey) {
                                resumePromptLabel
                            } else {
                                null
                            },
                            onResumePromptAction = onResumePromptAction,
                            onResumePromptDismiss = onResumePromptDismiss,
                            synopsisAutoScroll = layer.visibility >= HeroSettledVisibility &&
                                items[layer.page].let {
                                    it.id == currentItem.id && it.type == currentItem.type
                                },
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
                    immersiveBackdropHeight = immersiveBackdropHeight,
                ),
                contentAlignment = heroBadgePlacement.heroDiscoveryMedalAlignment(),
            ) {
                visiblePages.forEach { layer ->
                    val itemKey = "${items[layer.page].type}:${items[layer.page].id}"
                    val discoveryKey = "$itemKey:heroDiscoveryV${HeroDiscoveryMetadataService.CACHE_VERSION}"
                    val facts = discoveryCache[discoveryKey].orEmpty()
                    if (facts.isNotEmpty()) {
                        HeroDiscoveryBadgeStrip(
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

        // Page dots belong to the Basic hero only. Adaptive/Ambient hide them; TV mode (immersive)
        // is full-height, so a BottomStart indicator would land at the screen bottom behind the
        // content rows — exclude it there too.
        if (!adaptiveHeroMode && !immersiveMode) {
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

/**
 * Fades the backdrop's left edge into the background so the logo and metadata column stay legible.
 *
 * [fullWidth] is the full-backdrop TV layout, where the box spans the whole window rather than the
 * right-hand [HERO_BACKDROP_WIDTH_FRACTION]. That case needs a different ramp entirely, not a
 * rescaled one: this fade exists to blend the backdrop's own left edge into background the text is
 * already sitting on, whereas there the text sits on artwork and the ramp is the only thing making
 * it readable. See [immersiveHeroSideScrimStops].
 */
private fun Modifier.heroBackdropFadeMask(
    backgroundColor: Color,
    fullWidth: Boolean = false,
): Modifier =
    drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.horizontalGradient(
                colorStops = if (fullWidth) {
                    immersiveHeroSideScrimStops(backgroundColor)
                } else {
                    arrayOf(
                        0f to backgroundColor,
                        HERO_BACKDROP_FADE_FRACTION to Color.Transparent,
                        1f to Color.Transparent,
                    )
                },
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

internal fun immersiveHeroBackdropHeight(
    heroHeight: Dp,
    immersiveContentBottomPadding: Dp,
): Dp {
    val portraitMinimum = heroHeight * 0.64f
    val shelfBoundary = heroHeight - immersiveContentBottomPadding
    return if (shelfBoundary > portraitMinimum) {
        shelfBoundary + IMMERSIVE_HERO_LANDSCAPE_SHELF_OVERLAP
    } else {
        portraitMinimum
    }
}

// Fixed top offset for the adaptive hero's metadata column so it sits in a consistent spot
// without drifting as content height changes between items. Kept fairly high since this mode's
// hero is short and the content otherwise leaves a lot of empty space below.
private fun adaptiveHeroContentTopBaseline(heroHeight: Dp): Dp =
    // heroHeight already carries the Hero-height slider multiplier, so this baseline naturally
    // drifts down as the hero grows — keeping the content block visually placed within the taller
    // backdrop rather than clustered at the top. Raised cap lets that continue at large sizes.
    (heroHeight * 0.12f).coerceIn(36.dp, 140.dp)

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
    resumePromptLabel: String? = null,
    onResumePromptAction: (() -> Unit)? = null,
    onResumePromptDismiss: (() -> Unit)? = null,
    /**
     * True only for the settled, front-most hero item. Layers behind a page transition render the
     * same block, and letting those run the timer would leave a half-scrolled synopsis waiting on
     * the page the reader is heading back to.
     */
    synopsisAutoScroll: Boolean = false,
    onItemClick: ((MetaPreview) -> Unit)?,
) {
    val interactionSource = remember { MutableInteractionSource() }
    var logoLoadError by remember(item.type, item.id, item.logo) { mutableStateOf(false) }
    val logoUrl = item.logo?.takeIf { it.isNotBlank() && !logoLoadError }
    // TEMPORARY (hero race diagnosis) — remove with logHeroPick. Reports what the slot actually
    // paints, not merely the logo value: the first version of this probe printed TITLE-TEXT
    // whenever logo was null, which is also true while the slot is deliberately held blank, so it
    // could not see whether the hold was working. Text completeness rides along so a
    // partially-enriched frame (ratings present, genres/synopsis missing) is visible too.
    LaunchedEffect(
        item.type,
        item.id,
        item.logo,
        item.heroMetadataPending,
        item.genres.size,
        item.description,
    ) {
        co.touchlab.kermit.Logger.withTag("HeroLogoRace").i {
            val slot = when {
                !item.logo.isNullOrBlank() -> "logo:" + item.logo!!.takeLast(26)
                item.heroMetadataPending -> "BLANK-HELD"
                else -> "TITLE-TEXT"
            }
            "t=${com.nuvio.app.features.home.heroProbeMs()} RENDER ${item.type}:${item.id} slot=$slot " +
                "genres=${item.genres.size} hasDescr=${!item.description.isNullOrBlank()} " +
                "year=${item.releaseInfo ?: "-"} runtime=${item.runtime ?: "-"}"
        }
    }
    // A resume prompt puts its two actions on the hero itself rather than on buttons: left click
    // resumes, right click dismisses. The buttons they replace sat at the bottom of the metadata
    // column, so they inherited its layout — they appeared before the metadata they were positioned
    // against and were liable to being clipped by a short hero. Nothing about the prompt needs its
    // own hit target, and this way the actions are available from the first frame regardless of
    // what the column is doing. Keyboard already had the same pair (Select resumes, Back dismisses).
    val resumePromptActive = !resumePromptLabel.isNullOrBlank() && onResumePromptAction != null
    val primaryClick: (() -> Unit)? = when {
        resumePromptActive -> onResumePromptAction
        interactive && onItemClick != null -> ({ onItemClick(item) })
        else -> null
    }
    val secondaryClickAction: (() -> Unit)? = onResumePromptDismiss.takeIf { resumePromptActive }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (primaryClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = primaryClick,
                    )
                } else {
                    Modifier
                },
            )
            .secondaryClick(secondaryClickAction),
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
                // Third state: the item is a pre-enrichment copy whose logo has not been decided
                // yet, so the title would only be painted for the moment it takes the logo to
                // arrive and then replaced. Hold the slot empty instead. The slot keeps its height
                // either way, so resolving into a logo or into the title causes no layout shift.
                if (!item.heroMetadataPending) {
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
        }

        // The metadata column below is withheld in one piece while the item is a pre-enrichment
        // placeholder — see [MetaPreview.heroMetadataPending]. Revealing these rows as each field
        // arrives is what read as a flash: the row would paint with a bare episode label and no
        // genres, ratings or synopsis, then repaint complete a few frames later. The backdrop, the
        // fixed-height logo slot and the action buttons are outside the gate and always render, so
        // the hero is never empty and never changes height.
        if (!item.heroMetadataPending &&
            !showExtendedMetadata &&
            (cast.isNotEmpty() || production.isNotEmpty())
        ) {
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

        // Looked up once: a second call site would issue its own poll while the server warms a
        // title up.
        val qualityBadgesEnabled = rememberQualityBadgesEnabled()
        val qualityHighlights = rememberQualityHighlights(
            type = item.metadataType,
            id = item.metadataId,
            releaseDate = item.rawReleaseDate,
        )
        // With the quality badges on, the meta line splits in two the way the streaming apps lay it
        // out: genres and the age rating lead above the synopsis, while year, runtime and the
        // quality sit underneath it as a footer. Keeping all of that on one row was tried and
        // overran the line on titles with three genres, a long release string and a quality set
        // beside them. With the badges off there is nothing to make room for, so the year and
        // runtime stay on the genre line and the footer disappears entirely.
        val ageRating = item.ageRating?.trim()?.takeIf { it.isNotBlank() }
        val genreText = desktopHeroGenreText(
            item = item,
            showExtendedMetadata = showExtendedMetadata,
            showReleaseMetadata = showReleaseMetadata && !qualityBadgesEnabled,
            includeAgeRating = false,
        )
        if (!item.heroMetadataPending && (genreText.isNotBlank() || ageRating != null)) {
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (genreText.isNotBlank()) {
                    Text(
                        text = genreText,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.76f),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                ageRating?.let { rating -> HeroAgeRatingBadge(text = rating) }
            }
        }

        if (!item.heroMetadataPending) {
            HomeHeroRatingsRow(item = item, ratingsCache = ratingsCache)

            item.description
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { description ->
                    Spacer(modifier = Modifier.height(16.dp))
                    HeroSynopsis(
                        text = description,
                        resetKey = "${item.type}:${item.id}",
                        autoScroll = synopsisAutoScroll,
                    )
                }

            HeroReleaseFooter(
                item = item,
                highlights = qualityHighlights,
                // Nothing to put down here when the badges are off: the year and runtime went back
                // onto the genre line above.
                showReleaseMetadata = showReleaseMetadata && qualityBadgesEnabled,
            )
        }

    }
}

/** Lines of synopsis the hero shows before the rest has to be scrolled into view. */
private const val HeroSynopsisMaxLines = 5

/** Layer visibility above which a hero page counts as settled rather than mid-transition. */
private const val HeroSettledVisibility = 0.99f

/**
 * How long an item has to hold the hero before its synopsis starts scrolling. Long enough that
 * flicking through the carousel never sets anything in motion, and that the reader gets the visible
 * lines on their own terms first.
 */
private const val HeroSynopsisScrollDelayMs = 4_000L

/**
 * Teleprompter speed. Deliberately expressed as a rate rather than a fixed duration so a
 * twelve-line synopsis scrolls at the same readable pace as a six-line one — a fixed duration would
 * make the long ones fly. At the hero's ~24dp line height this is roughly two seconds per line.
 */
internal const val HeroSynopsisScrollDpPerSecond = 12f

/**
 * Travel time for [overflowDp] of synopsis at the teleprompter's constant speed. Never zero: a
 * synopsis that overflows by a single pixel still animates rather than jumping.
 */
internal fun heroSynopsisScrollDurationMs(overflowDp: Float): Int =
    ((overflowDp / HeroSynopsisScrollDpPerSecond) * 1000f).roundToInt().coerceAtLeast(1)

/** Depth of the soft edge that replaces a hard cut where the text enters and leaves the window. */
private val HeroSynopsisFadeHeight = 16.dp

/**
 * The hero synopsis. When the text overflows [maxLines] and the item holds the hero for
 * [HeroSynopsisScrollDelayMs], it scrolls up through a [maxLines]-tall window at a constant speed
 * and stops the moment its last line is in view, the way a teleprompter would.
 *
 * The distance comes from the scroll state of the text that is actually on screen, never from a
 * separate pre-measure. A pre-measured copy can disagree with the rendered one about where the
 * lines break, and when it does the scroll runs past the end of the synopsis and parks the reader
 * in front of blank space. Letting the layout system own both the window and the travel makes that
 * disagreement impossible: [ScrollState.maxValue] is by definition "content minus window".
 *
 * The window is a cap, not a fixed height — a synopsis shorter than [maxLines] keeps its own
 * height, scrolls nowhere, and pays for no offscreen layer.
 */
@Composable
private fun HeroSynopsis(
    text: String,
    resetKey: String,
    autoScroll: Boolean,
    modifier: Modifier = Modifier,
    maxLines: Int = HeroSynopsisMaxLines,
) {
    val style = MaterialTheme.typography.bodyLarge
    val color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f)
    val density = LocalDensity.current
    val scrollState = remember(resetKey) { ScrollState(0) }

    // The window opens at maxLines of the style's nominal line height — close enough that the first
    // frame is never wrong by more than a pixel or two — and the first layout replaces it with
    // either the real line boundary or no cap at all. Keeping the estimate as a standing fallback
    // was worse than useless: where a font's real line box runs slightly taller than its nominal
    // line height, an exactly-maxLines synopsis would report a few pixels of overflow and creep.
    val estimatedWindowHeight = with(density) {
        val lineHeight = if (style.lineHeight.isSpecified) {
            style.lineHeight.toDp()
        } else {
            style.fontSize.toDp() * 1.5f
        }
        lineHeight * maxLines
    }
    var windowHeight by remember(resetKey) { mutableStateOf(estimatedWindowHeight) }

    val overflowPx = scrollState.maxValue.takeIf { it != Int.MAX_VALUE } ?: 0
    val hasOverflow = overflowPx > 0

    LaunchedEffect(resetKey, autoScroll, overflowPx) {
        scrollState.scrollTo(0)
        if (!autoScroll || !hasOverflow) return@LaunchedEffect
        delay(HeroSynopsisScrollDelayMs)
        scrollState.animateScrollTo(
            value = overflowPx,
            animationSpec = tween(
                durationMillis = heroSynopsisScrollDurationMs(
                    overflowDp = with(density) { overflowPx.toDp().value },
                ),
                easing = LinearEasing,
            ),
        )
    }

    val fadeHeightPx = with(density) { HeroSynopsisFadeHeight.toPx() }
    Text(
        text = text,
        style = style,
        color = color,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = windowHeight)
            .then(
                if (hasOverflow) {
                    // The soft edges are punched out of the text with DstIn, which needs its own
                    // layer — without one the blend would erase the backdrop behind the synopsis
                    // instead of the synopsis itself.
                    Modifier
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            val fade = fadeHeightPx.coerceAtMost(size.height / 3f)
                            val scrolled = scrollState.value.toFloat()
                            val enteringFromTop = (scrolled / fade).coerceIn(0f, 1f)
                            val remainingBelow = ((overflowPx - scrolled) / fade).coerceIn(0f, 1f)
                            if (enteringFromTop > 0f) {
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        colorStops = arrayOf(
                                            0f to Color.Black.copy(alpha = 1f - enteringFromTop),
                                            fade / size.height to Color.Black,
                                        ),
                                    ),
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                            if (remainingBelow > 0f) {
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        colorStops = arrayOf(
                                            1f - fade / size.height to Color.Black,
                                            1f to Color.Black.copy(alpha = 1f - remainingBelow),
                                        ),
                                    ),
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                        }
                } else {
                    Modifier
                },
            )
            .verticalScroll(scrollState, enabled = false),
        // The text is measured unbounded inside the scroll, so this always sees the whole synopsis
        // rather than the capped view of it. Writing back an unchanged value is inert, so this
        // settles on the first layout instead of looping.
        onTextLayout = { layout ->
            windowHeight = if (layout.lineCount > maxLines) {
                with(density) { layout.getLineBottom(maxLines - 1).toDp() }
            } else {
                // Nothing to cap. A shorter synopsis keeps its own height rather than reserving
                // five lines of empty space above the release footer.
                Dp.Unspecified
            }
        },
    )
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
            text = stringResource(Res.string.meta_starring),
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
                text = stringResource(Res.string.meta_section_production_title),
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
    // Summary rather than the details-screen record: a director and a writer do not need every
    // episode of every season fetched to find them.
    val meta = MetaDetailsRepository.peek(type = type, id = id)
        ?: MetaDetailsRepository.fetchHeroSummary(type = type, id = id)
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

/** Outlined age rating beside the genres, matching the badge the details header already uses. */
@Composable
private fun HeroAgeRatingBadge(text: String) {
    val color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.76f)
    Box(
        modifier = Modifier
            .border(BorderStroke(1.dp, color.copy(alpha = 0.55f)), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/**
 * Year, runtime and the quality, set under the synopsis.
 *
 * Its own line rather than part of the genre line above so the quality has somewhere to sit that
 * is not competing with the genres for a single row's width. Renders nothing when there is neither
 * release metadata nor a trusted release, so it costs no space on items with neither — which is the
 * whole line with the quality badges switched off, since the caller then keeps the year and runtime
 * on the genre line instead.
 */
@Composable
private fun HeroReleaseFooter(
    item: MetaPreview,
    highlights: List<QualityHighlight>,
    showReleaseMetadata: Boolean,
) {
    val releaseText = if (showReleaseMetadata) {
        listOfNotNull(
            item.releaseInfo?.takeIf(String::isNotBlank)?.let(::formatReleaseDateForDisplay),
            formatRuntimeForDisplay(item.runtime),
        ).filter { it.isNotBlank() }.joinToString(" • ")
    } else {
        ""
    }
    if (releaseText.isBlank() && highlights.isEmpty()) return

    Spacer(modifier = Modifier.height(14.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (releaseText.isNotBlank()) {
            Text(
                text = releaseText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.76f),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        QualityInlineBadges(highlights = highlights)
    }
}

@Composable
private fun HomeHeroRatingsRow(item: MetaPreview, ratingsCache: Map<String, List<MetaExternalRating>>) {
    val ratings = ratingsCache["${item.type}:${item.id}"].orEmpty()

    if (ratings.isNotEmpty()) {
        Spacer(modifier = Modifier.height(14.dp))
        RatingsRow(ratings = ratings)
    }
}

private fun desktopHeroLogoWidthFraction(layout: HomeHeroLayout): Float {
    val base = when {
        layout.contentMaxWidth >= 640.dp -> 0.74f
        layout.contentMaxWidth >= 520.dp -> 0.74f
        else -> 0.8f
    }
    // Widen the logo only partially with the hero-height scale — the slot height (below) carries
    // most of the growth, and a full-rate width bump would run a wide logo off the content column.
    val scaled = base * (1f + (layout.heroHeightScale - 1f) * 0.6f)
    return scaled.coerceIn(0.4f, 0.95f)
}

private fun desktopHeroLogoSlotHeight(layout: HomeHeroLayout): Dp {
    val base = when {
        layout.contentMaxWidth >= 640.dp -> 156.dp
        layout.contentMaxWidth >= 520.dp -> 136.dp
        else -> 104.dp
    }
    return (base * layout.heroHeightScale).coerceIn(88.dp, 240.dp)
}

private fun compactHeroMetaParts(item: MetaPreview): List<String> =
    buildList {
        if (item.type != "collection" && item.randomPlayCategoryOrNull() == null) {
            // metadataType, not type: a cloud-library row's own type is the addon's `library`, which
            // would otherwise read as the literal word "Library" where the genres belong.
            add(item.metadataType.replaceFirstChar(Char::uppercase))
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
    /** False when the caller draws the rating as its own badge rather than as text in this line. */
    includeAgeRating: Boolean = true,
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
            if (includeAgeRating) {
                item.ageRating
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let(::add)
            }
        }
    }
    if (values.isEmpty() && (item.type == "collection" || item.randomPlayCategoryOrNull() != null)) return ""
    // metadataType, not type: see [compactHeroMetaParts].
    return values.joinToString(" • ").ifBlank { item.metadataType.replaceFirstChar(Char::uppercase) }
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
            heroHeightScale = heightMultiplier,
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
            heroHeightScale = heightMultiplier,
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
            heroHeightScale = heightMultiplier,
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
            heroHeightScale = heightMultiplier,
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
            heroHeightScale = heightMultiplier,
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
    immersiveBackdropHeight: Dp,
): Modifier =
    when (placement) {
        HeroBadgePlacement.BottomBackdrop -> if (immersiveMode) {
            Modifier
                .align(Alignment.TopEnd)
                .height(immersiveBackdropHeight)
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
            .height(if (immersiveMode) immersiveBackdropHeight else heroHeight)
            .fillMaxWidth(HERO_BACKDROP_WIDTH_FRACTION)
            .padding(
                top = HERO_DISCOVERY_MEDAL_TOP_PADDING,
                end = HERO_DISCOVERY_MEDAL_EDGE_PADDING,
            )
    }

/**
 * Where the backdrop region's centre line falls across the hero, as a fraction of its width. The
 * "Bottom of backdrop" discovery badges are centred on it, so content elsewhere on screen can line
 * up with them horizontally — see TV Mode's row-jump dots.
 */
internal const val HeroBackdropCentreFraction = 1f - HERO_BACKDROP_WIDTH_FRACTION / 2f

private fun HeroBadgePlacement.heroDiscoveryMedalAlignment(): Alignment =
    when (this) {
        HeroBadgePlacement.BottomBackdrop -> Alignment.BottomCenter
        HeroBadgePlacement.TopRightHorizontal,
        HeroBadgePlacement.TopRightVertical -> Alignment.TopEnd
    }

@Composable
internal fun HeroDiscoveryBadgeStrip(
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
                badgeFileName?.let { heroBundledBadgeModel(it) }
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
        // festival, foreign:<lang>, director, studio, release_status, stinger carry a dynamic label.
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
        "stinger" ->
            when {
                label.startsWith("Mid &") ->
                    "This film has an extra scene partway through the credits and another after they finish, so it is worth staying to the end."
                label.startsWith("Mid-") ->
                    "This film has an extra scene partway through the credits, so it is worth staying past the first block of names."
                else ->
                    "This film has an extra scene once the credits have finished, so it is worth staying to the very end."
            }
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
            "stinger" -> Res.drawable.hero_badge_stinger
            "new_release", "digital_release" -> Res.drawable.hero_badge_new_release
            "release_status", "alert" -> if (label.isUnavailableReleaseStatusLabel()) {
                Res.drawable.hero_badge_release_status
            } else {
                Res.drawable.hero_badge_release_status_available
            }
            "studio", "prestige" -> Res.drawable.hero_badge_director
            "director" -> Res.drawable.hero_badge_studio
            "trending" -> Res.drawable.hero_badge_trending
            "short_film" -> Res.drawable.hero_badge_short_film
            "mini_series" -> Res.drawable.hero_badge_mini_series
            "binge_ready" -> Res.drawable.hero_badge_binge_ready
            "foreign", "info" -> Res.drawable.hero_badge_foreign
            else -> Res.drawable.hero_badge_win
        }
    }

/**
 * The bundled badge bitmap to load through the image pipeline, or null when the badge is vector art.
 *
 * Most badges are SVG now and are drawn straight from [painterResource], which lets Skia rasterise
 * them at draw size — sharp at any medal size or DPI. Routing those through the bitmap loader would
 * only add a temp-file extraction and a downscale, so they return null and take the painter branch.
 * What is left is the per-language flags and the generic win/nom marks, which are still PNG and do
 * want the loader's high-quality downscale.
 *
 * Unknown categories return null and fall through to [heroDiscoveryBadgeResource]'s own fallback.
 */
private fun heroDiscoveryBadgeFileName(category: String, label: String): String? =
    if (category.startsWith("foreign:")) {
        "hero_badge_foreign.png"
    } else {
        when (category) {
            "wins", "gg_wins", "win" -> "hero_badge_win.png"
            "pic_noms", "gg_noms", "emmy_noms", "noms", "nom" -> "hero_badge_nom.png"
            "award:people_choice" -> "hero_badge_people_choice.png"
            // Only the "available" half is still a bitmap; Cinema/Production is vector.
            "release_status", "alert" -> if (label.isUnavailableReleaseStatusLabel()) {
                null
            } else {
                "hero_badge_release_status_available.png"
            }
            "foreign", "info" -> "hero_badge_foreign.png"
            else -> null
        }
    }

private fun String.isUnavailableReleaseStatusLabel(): Boolean =
    trim().let { status ->
        status.equals("Cinema", ignoreCase = true) || status.equals("Production", ignoreCase = true)
    }
