package com.nuvio.app.features.home.components

import co.touchlab.kermit.Logger
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.isDesktop
import com.nuvio.app.core.ui.NuvioDesktopImageScaling
import com.nuvio.app.core.ui.NuvioAsyncImage as AsyncImage
import com.nuvio.app.core.format.formatReleaseDateForDisplay
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaExternalRating
import com.nuvio.app.features.details.components.RatingsRow
import com.nuvio.app.features.details.formatRuntimeForDisplay
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.HeroCastMember
import com.nuvio.app.features.mdblist.HeroCastMetadataService
import com.nuvio.app.features.mdblist.MdbListMetadataService
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

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
private const val HERO_BACKDROP_WIDTH_FRACTION = 0.85f
private const val HERO_BACKDROP_FADE_FRACTION = 0.35f
private const val HERO_METADATA_PREFETCH_CONCURRENCY = 4
private val heroImageLog = Logger.withTag("HomeHeroImages")
private val IMMERSIVE_HERO_CONTENT_MIN_HEIGHT = 300.dp
private val IMMERSIVE_HERO_CONTENT_MAX_HEIGHT = 420.dp
private val IMMERSIVE_HERO_CONTENT_BOTTOM_PADDING = 44.dp
private val IMMERSIVE_HERO_CONTENT_MIN_OFFSET_Y = 16.dp
private val IMMERSIVE_HERO_CONTENT_MAX_OFFSET_Y = 42.dp

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
    tvMode: Boolean = false,
    immersiveContentBottomPadding: Dp = IMMERSIVE_HERO_CONTENT_BOTTOM_PADDING,
    onActiveItemChanged: ((MetaPreview) -> Unit)? = null,
    onCastClick: ((HeroCastMember) -> Unit)? = null,
    onItemClick: ((MetaPreview) -> Unit)? = null,
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
        // Keep warming all nearby metadata, but put what the user can see first and avoid
        // flooding the add-on/TMDB/image hosts with dozens of simultaneous cold requests.
        val metadataTargets = (listOf(displayCurrentItemWithCast) + metadataPrefetchItems + displayItemsWithCast)
            .distinctBy { item ->
                "${item.type}:${item.id}"
            }
        LaunchedEffect(metadataTargets) {
            val settings = MdbListSettingsRepository.snapshot()
            val prefetchSlots = Semaphore(HERO_METADATA_PREFETCH_CONCURRENCY)
            for (target in metadataTargets) {
                val key = "${target.type}:${target.id}"
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
                    tvMode = tvMode,
                    immersiveContentBottomPadding = immersiveContentBottomPadding,
                    ratingsCache = ratingsCache,
                    onCastClick = onCastClick,
                    onItemClick = onItemClick,
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
) {
    val banner = item.banner?.takeIf(String::isNotBlank)
    val poster = item.poster?.takeIf(String::isNotBlank)
    var bannerLoadFailed by remember(item.type, item.id, banner, poster) { mutableStateOf(false) }
    val model = if (bannerLoadFailed) poster else banner ?: poster

    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        modifier = modifier,
        alignment = alignment,
        contentScale = contentScale,
        desktopImageScaling = NuvioDesktopImageScaling.Disabled,
        onError = {
            if (!bannerLoadFailed && banner != null && poster != null && banner != poster) {
                heroImageLog.w { "Hero banner failed; trying poster: ${banner.safeImageUrlForLog()}" }
                bannerLoadFailed = true
            } else if (model != null) {
                heroImageLog.w { "Hero artwork failed: ${model.safeImageUrlForLog()}" }
            }
        },
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
    tvMode: Boolean = false,
    immersiveContentBottomPadding: Dp,
    ratingsCache: Map<String, List<MetaExternalRating>>,
    onCastClick: ((HeroCastMember) -> Unit)?,
    onItemClick: ((MetaPreview) -> Unit)?,
) {
    val backgroundColor = if (immersiveMode) Color.Black else MaterialTheme.colorScheme.background

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
                .heroBackdropFadeMask(backgroundColor)
                .then(if (immersiveMode) Modifier.immersiveHeroExtraMask(backgroundColor) else Modifier)
                .clickable(enabled = onItemClick != null) {
                    onItemClick?.invoke(currentItem)
                },
        ) {
            visiblePages.forEach { layer ->
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            alpha = layer.visibility
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
                        alignment = if (immersiveMode) {
                            ImmersiveHeroBackdropAlignment
                        } else {
                            DesktopHeroBackdropAlignment
                        },
                        contentScale = ContentScale.Crop,
                    )
                }
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

        if (immersiveMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = contentHorizontalPadding, top = 30.dp, end = contentHorizontalPadding)
                    .fillMaxWidth(0.32f)
                    .widthIn(max = 600.dp),
                contentAlignment = Alignment.TopStart,
            ) {
                visiblePages.forEach { layer ->
                    val cast = heroDisplayCast(items[layer.page], maxCount = 4)
                    if (cast.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    alpha = layer.visibility
                                    translationX = -layer.offset * heroWidthPx * HERO_CONTENT_PARALLAX
                                },
                        ) {
                            HeroStarringBlock(cast, onCastClick = onCastClick)
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .align(if (immersiveMode) Alignment.BottomStart else Alignment.CenterStart)
                .padding(start = contentHorizontalPadding, end = contentHorizontalPadding)
                .then(
                    if (immersiveMode) {
                        Modifier
                            .padding(bottom = immersiveContentBottomPadding)
                            .height(immersiveHeroContentHeight(layout.heroHeight))
                            .offset(y = immersiveHeroContentOffsetY(layout.heroHeight))
                    } else {
                        Modifier
                    },
                )
                .fillMaxWidth(
                    when {
                        immersiveMode -> 0.32f
                        tvMode -> 0.38f
                        else -> layout.contentWidthFraction
                    },
                )
                .widthIn(
                    max = when {
                        immersiveMode -> 600.dp
                        tvMode -> 480.dp
                        else -> layout.contentMaxWidth
                    },
                ),
            contentAlignment = if (immersiveMode) Alignment.TopStart else Alignment.CenterStart,
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
                        showReleaseMetadata = immersiveMode || tvMode,
                        ratingsCache = ratingsCache,
                        onCastClick = onCastClick,
                        onItemClick = onItemClick?.let { handler ->
                            { _ -> handler(currentItem) }
                        },
                    )
                }
            }
        }

        if (!tvMode) {
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
                    .clickable(enabled = onItemClick != null) {
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
                    .clickable(enabled = onItemClick != null) {
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
            HeroMetaText(text = item.type.replaceFirstChar(Char::uppercase))
            item.genres.firstOrNull()?.let { genre ->
                HeroMetaDot()
                HeroMetaText(text = genre)
            }
            item.releaseInfo?.takeIf { it.isNotBlank() }?.let { info ->
                HeroMetaDot()
                HeroMetaText(text = formatReleaseDateForDisplay(info))
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
        } else if (showExtendedMetadata) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(desktopHeroLogoSlotHeight(layout)),
                contentAlignment = Alignment.CenterStart,
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
        } else {
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

        if (!showExtendedMetadata && cast.isNotEmpty()) {
            Spacer(modifier = Modifier.height(14.dp))
            HeroCastRow(cast, onCastClick = onCastClick)
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

@Composable
private fun HeroStarringBlock(
    cast: List<HeroCastMember>,
    onCastClick: ((HeroCastMember) -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = "Starring",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f),
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .width(92.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(modifier = Modifier.height(18.dp))
        HeroCastGrid(cast, onCastClick = onCastClick)
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

private fun heroDisplayCast(item: MetaPreview, maxCount: Int): List<HeroCastMember> =
    item.cast
        .asSequence()
        .filter { person -> person.name.isNotBlank() }
        .filterNot { person -> person.role?.isHeroCrewRole() == true }
        .distinctBy { person -> person.name }
        .take(maxCount)
        .toList()

private fun String.heroInitials(): String =
    trim()
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)
        .take(2)
        .mapNotNull { part -> part.firstOrNull()?.uppercaseChar() }
        .joinToString("")

private fun String.isHeroCrewRole(): Boolean {
    val normalized = lowercase()
    return listOf(
        "director",
        "writer",
        "creator",
        "created by",
        "screenplay",
        "showrunner",
        "producer",
    ).any(normalized::contains)
}

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
