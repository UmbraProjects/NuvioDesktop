package com.nuvio.app.features.details.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer
import com.nuvio.app.core.ui.NuvioDesktopImageScaling
import com.nuvio.app.core.ui.NuvioAsyncImage as AsyncImage
import com.nuvio.app.features.details.MetaCompany
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaHeroTrailerBackgroundMode
import com.nuvio.app.features.details.MetaHeroTrailerPlaybackMode
import com.nuvio.app.features.details.MetaPerson
import com.nuvio.app.features.details.formatMetaReleaseLineForDetails
import com.nuvio.app.features.details.formatRuntimeForDisplay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

object DetailHeroPeoplePanelToggleTrigger {
    private val _tokens = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val tokens: SharedFlow<Int> = _tokens.asSharedFlow()
    private var nextToken = 0

    fun trigger() {
        nextToken += 1
        _tokens.tryEmit(nextToken)
    }
}

@Composable
fun DetailHero(
    meta: MetaDetails,
    isTablet: Boolean = false,
    scrollOffset: Int = 0,
    contentMaxWidth: Dp = 560.dp,
    viewportHeight: Dp = 0.dp,
    onHeightChanged: (Int) -> Unit = {},
    heroTrailerSourceUrl: String? = null,
    heroTrailerSourceAudioUrl: String? = null,
    heroTrailerReady: Boolean = false,
    heroTrailerPlayWhenReady: Boolean = false,
    heroTrailerMuted: Boolean = true,
    heroTrailerVolume: Int = 0,
    heroTrailerKeyboardNavigation: Boolean = false,
    heroTrailerPlaybackMode: MetaHeroTrailerPlaybackMode = MetaHeroTrailerPlaybackMode.Hero,
    heroTrailerBackgroundMode: MetaHeroTrailerBackgroundMode = MetaHeroTrailerBackgroundMode.Black,
    desktopOverlay: Boolean = false,
    playButtonLabel: String = stringResource(Res.string.action_play),
    isSaved: Boolean = false,
    isWatched: Boolean = false,
    showActions: Boolean = false,
    showOverview: Boolean = false,
    showCast: Boolean = false,
    showProduction: Boolean = false,
    showDetails: Boolean = false,
    showManualPlayOption: Boolean = false,
    actionsFocused: Boolean = false,
    onPrimaryPlayClick: () -> Unit = {},
    onPrimaryPlayLongClick: (() -> Unit)? = null,
    onRandomEpisodeClick: (() -> Unit)? = null,
    onSaveClick: () -> Unit = {},
    onSaveLongClick: (() -> Unit)? = null,
    onWatchedClick: () -> Unit = {},
    onCastClick: ((MetaPerson, String?) -> Unit)? = null,
    onCompanyClick: ((MetaCompany, String) -> Unit)? = null,
    onHeroTrailerMuteToggle: () -> Unit = {},
    onHeroTrailerVolumeChange: (Int) -> Unit = {},
    // Invoked when the passive trailer surface's WebView2 transiently grabs OS focus (chrome
    // click), so the caller can hand keyboard focus back to the details screen.
    onHeroTrailerReclaimFocus: () -> Unit = {},
    onHeroTrailerDismiss: () -> Unit = {},
    onHeroTrailerReady: () -> Unit = {},
    onHeroTrailerEnded: () -> Unit = {},
    onHeroTrailerError: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
    ) {
        val heroHeight = detailHeroHeight(maxWidth, viewportHeight, isTablet, desktopOverlay)
        val trailerAlpha by animateFloatAsState(
            targetValue = if (heroTrailerReady) 1f else 0f,
            animationSpec = tween(durationMillis = 300),
            label = "detail_hero_trailer_alpha",
        )
        var logoLoadError by remember(meta.id, meta.logo) {
            mutableStateOf(false)
        }
        val logoUrl = meta.logo?.takeIf { it.isNotBlank() }
        val boundedHeroTrailer = desktopOverlay &&
            heroTrailerPlaybackMode == MetaHeroTrailerPlaybackMode.Hero
        val fullHeroTrailer = heroTrailerSourceUrl != null && !boundedHeroTrailer
        // Hide the backdrop only once the trailer is actually on screen (ready), not the
        // instant playback is requested — so the buffering gap of a freshly-attached clicked
        // trailer stays covered by the backdrop instead of flashing black.
        val boundedHeroTrailerActive = heroTrailerSourceUrl != null &&
            boundedHeroTrailer &&
            heroTrailerReady
        val heroTrailerArtworkStart = minOf(
            64.dp + contentMaxWidth.coerceAtMost(620.dp) + 64.dp,
            maxWidth * 0.44f,
        )
        val heroTrailerArtworkBottomPadding = 358.dp
        // The desktop hero draws its content over scrim gradients that normally fade to the theme
        // background. While a bounded trailer plays, the trailer-background setting recolours those
        // scrims (and the flat fill) so the whole surround honours the choice — the flat background
        // Box alone is invisible here because these gradients paint over it. "Black" (lights out)
        // and "Backdrop" (a dark wash over the artwork) both scrim to black; "Theme" and the
        // no-trailer state keep the theme background.
        val heroTrailerScrimColor = when {
            !boundedHeroTrailerActive -> MaterialTheme.colorScheme.background
            heroTrailerBackgroundMode == MetaHeroTrailerBackgroundMode.Theme -> MaterialTheme.colorScheme.background
            else -> Color.Black
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(heroHeight)
                .onSizeChanged { onHeightChanged(it.height) }
                .graphicsLayer {
                    clip = true
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                val imageUrl = meta.background ?: meta.poster
                val backdropScale = when {
                    desktopOverlay -> 1.04f
                    isTablet -> 1f
                    else -> 1.08f
                }
                // While a bounded trailer is on screen the area around it shows a configurable
                // background: "Backdrop" keeps the artwork dimmed to a wash, "Black" (default,
                // lights-out) and "Theme" replace it with a flat colour. Reverting is automatic —
                // once the trailer ends boundedHeroTrailerActive flips false and the normal
                // backdrop image returns.
                val backdropWashActive = boundedHeroTrailerActive &&
                    heroTrailerBackgroundMode == MetaHeroTrailerBackgroundMode.Backdrop &&
                    imageUrl != null
                val showBackdropImage = imageUrl != null && (!boundedHeroTrailerActive || backdropWashActive)
                if (showBackdropImage) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = meta.name,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationY = scrollOffset * 0.5f
                                scaleX = backdropScale
                                scaleY = backdropScale
                            },
                        alignment = when {
                            desktopOverlay -> Alignment.CenterEnd
                            isTablet -> Alignment.TopCenter
                            else -> Alignment.Center
                        },
                        contentScale = ContentScale.Crop,
                        desktopImageScaling = NuvioDesktopImageScaling.Disabled,
                    )
                    if (backdropWashActive) {
                        // Dim the backdrop so the small trailer stays the focal point. The scrim
                        // gradients below add further darkening on the content side.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.4f)),
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                if (boundedHeroTrailerActive) {
                                    heroTrailerScrimColor
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                            ),
                    )
                }
                if (fullHeroTrailer) {
                    HeroTrailerPlayerSurface(
                        sourceUrl = heroTrailerSourceUrl,
                        sourceAudioUrl = heroTrailerSourceAudioUrl,
                        playWhenReady = heroTrailerPlayWhenReady,
                        muted = heroTrailerMuted,
                        volume = heroTrailerVolume,
                        keyboardNavigationEnabled = heroTrailerKeyboardNavigation,
                        // Plain fillMaxSize, matching HomeHeroTrailerSurface's usage — no
                        // graphicsLayer transform here. The scale/translation/alpha above are
                        // meant for the backdrop *image* (parallax zoom to avoid revealing bare
                        // edges while scrolling); applying that same 1.04x scale to this native
                        // video surface pushed it past the screen edge in fullscreen mode,
                        // shifting the webview's own corner chrome (close/mute/volume) out along
                        // with it so its CSS inset looked like it had no padding at all.
                        modifier = Modifier.fillMaxSize(),
                        onReady = onHeroTrailerReady,
                        onEnded = onHeroTrailerEnded,
                        onError = onHeroTrailerError,
                        onMuteToggle = onHeroTrailerMuteToggle,
                        onVolumeChange = onHeroTrailerVolumeChange,
                        onReclaimFocus = onHeroTrailerReclaimFocus,
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (desktopOverlay) heroHeight else 260.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            if (desktopOverlay) {
                                Brush.horizontalGradient(
                                    colorStops = arrayOf(
                                        0f to heroTrailerScrimColor,
                                        0.34f to heroTrailerScrimColor.copy(alpha = 0.9f),
                                        0.6f to heroTrailerScrimColor.copy(alpha = 0.42f),
                                        0.82f to heroTrailerScrimColor.copy(alpha = 0.12f),
                                        1f to Color.Transparent,
                                    ),
                                )
                            } else {
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        MaterialTheme.colorScheme.background.copy(alpha = 0.7f),
                                        MaterialTheme.colorScheme.background,
                                    ),
                                )
                            },
                        ),
                )

                if (desktopOverlay) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0f to Color.Transparent,
                                        0.38f to Color.Transparent,
                                        0.68f to heroTrailerScrimColor.copy(alpha = 0.72f),
                                        1f to heroTrailerScrimColor,
                                    ),
                                ),
                            ),
                    )
                    DetailDesktopHeroOverlay(
                        meta = meta,
                        logoUrl = logoUrl,
                        logoLoadError = logoLoadError,
                        onLogoLoadError = { logoLoadError = true },
                        contentMaxWidth = contentMaxWidth,
                        playButtonLabel = playButtonLabel,
                        isSaved = isSaved,
                        isWatched = isWatched,
                        showActions = showActions,
                        showOverview = showOverview,
                        showCast = showCast,
                        showProduction = showProduction,
                        showDetails = showDetails,
                        showManualPlayOption = showManualPlayOption,
                        actionsFocused = actionsFocused,
                        onPrimaryPlayClick = onPrimaryPlayClick,
                        onPrimaryPlayLongClick = onPrimaryPlayLongClick,
                        onRandomEpisodeClick = onRandomEpisodeClick,
                        onSaveClick = onSaveClick,
                        onSaveLongClick = onSaveLongClick,
                        onWatchedClick = onWatchedClick,
                        onCastClick = onCastClick,
                        onCompanyClick = onCompanyClick,
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = if (isTablet) 32.dp else 18.dp)
                            .padding(bottom = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        DetailHeroLogoOrTitle(
                            meta = meta,
                            logoUrl = logoUrl,
                            logoLoadError = logoLoadError,
                            onLogoLoadError = { logoLoadError = true },
                            modifier = Modifier
                                .fillMaxWidth(if (isTablet) 0.56f else 0.6f)
                                .widthIn(max = contentMaxWidth)
                                .height(if (isTablet) 72.dp else 80.dp),
                            titleTextAlign = TextAlign.Center,
                            logoAlignment = Alignment.Center,
                            isTablet = isTablet,
                        )

                        if (meta.genres.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = meta.genres.take(3).joinToString(" \u2022 "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }

                if (heroTrailerSourceUrl != null && boundedHeroTrailer) {
                    HeroTrailerPlayerSurface(
                        sourceUrl = heroTrailerSourceUrl,
                        sourceAudioUrl = heroTrailerSourceAudioUrl,
                        playWhenReady = heroTrailerPlayWhenReady,
                        muted = heroTrailerMuted,
                        volume = heroTrailerVolume,
                        keyboardNavigationEnabled = heroTrailerKeyboardNavigation,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                start = heroTrailerArtworkStart,
                                bottom = heroTrailerArtworkBottomPadding,
                            )
                            .graphicsLayer {
                                alpha = trailerAlpha
                            },
                        onReady = onHeroTrailerReady,
                        onEnded = onHeroTrailerEnded,
                        onError = onHeroTrailerError,
                        onMuteToggle = onHeroTrailerMuteToggle,
                        onVolumeChange = onHeroTrailerVolumeChange,
                        onReclaimFocus = onHeroTrailerReclaimFocus,
                    )
                }

            }
        }
    }
}

@Composable
private fun DetailDesktopHeroOverlay(
    meta: MetaDetails,
    logoUrl: String?,
    logoLoadError: Boolean,
    onLogoLoadError: () -> Unit,
    contentMaxWidth: Dp,
    playButtonLabel: String,
    isSaved: Boolean,
    isWatched: Boolean,
    showActions: Boolean,
    showOverview: Boolean,
    showCast: Boolean,
    showProduction: Boolean,
    showDetails: Boolean,
    showManualPlayOption: Boolean,
    actionsFocused: Boolean,
    onPrimaryPlayClick: () -> Unit,
    onPrimaryPlayLongClick: (() -> Unit)?,
    onRandomEpisodeClick: (() -> Unit)?,
    onSaveClick: () -> Unit,
    onSaveLongClick: (() -> Unit)?,
    onWatchedClick: () -> Unit,
    onCastClick: ((MetaPerson, String?) -> Unit)?,
    onCompanyClick: ((MetaCompany, String) -> Unit)?,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        val safeTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val laneStart = 64.dp
        val laneWidth = contentMaxWidth.coerceAtMost(760.dp)
        val headingWidth = laneWidth.coerceAtMost(620.dp)
        val productionPeople = remember(meta) { meta.detailHeroProductionPeople() }
        val starringPeople = remember(meta.cast) {
            meta.cast.filterNot { person -> person.role?.isDetailHeroCrewRole() == true }
        }
        val canShowCast = showCast && starringPeople.isNotEmpty()
        val canShowProduction = showProduction && productionPeople.isNotEmpty()
        var peopleTab by remember(meta.id, canShowCast, canShowProduction) {
            mutableStateOf(
                if (canShowCast) DetailHeroPeopleTab.Starring else DetailHeroPeopleTab.Production,
            )
        }
        val activePeopleTab = when {
            peopleTab == DetailHeroPeopleTab.Production && canShowProduction -> DetailHeroPeopleTab.Production
            canShowCast -> DetailHeroPeopleTab.Starring
            canShowProduction -> DetailHeroPeopleTab.Production
            else -> DetailHeroPeopleTab.Starring
        }
        val peoplePanelToggleToken by DetailHeroPeoplePanelToggleTrigger.tokens.collectAsState(initial = 0)
        LaunchedEffect(peoplePanelToggleToken) {
            if (peoplePanelToggleToken == 0) return@LaunchedEffect
            if (!canShowCast || !canShowProduction) return@LaunchedEffect
            peopleTab = if (activePeopleTab == DetailHeroPeopleTab.Starring) {
                DetailHeroPeopleTab.Production
            } else {
                DetailHeroPeopleTab.Starring
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = laneStart, top = safeTop),
        ) {
            DetailHeroLogoOrTitle(
                meta = meta,
                logoUrl = logoUrl,
                logoLoadError = logoLoadError,
                onLogoLoadError = onLogoLoadError,
                modifier = Modifier
                    .padding(top = 70.dp)
                    .width(headingWidth)
                    .height(150.dp),
                titleTextAlign = TextAlign.Center,
                logoAlignment = Alignment.Center,
                isTablet = true,
            )

            if (showOverview) {
                Column(
                    modifier = Modifier
                        .padding(top = 238.dp)
                        .width(headingWidth),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    DetailHeroMetadataRow(
                        meta = meta,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (meta.externalRatings.isNotEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            RatingsRow(
                                ratings = meta.externalRatings,
                                horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
                            )
                        }
                    }
                }
            }

            if (showActions) {
                DetailActionButtons(
                    modifier = Modifier
                        .padding(top = 328.dp)
                        .width(headingWidth),
                    playLabel = playButtonLabel,
                    secondaryActions = listOfNotNull(
                        onRandomEpisodeClick?.let { playRandom ->
                            DetailSecondaryAction(
                                label = stringResource(Res.string.action_random_episode),
                                icon = Icons.Default.PlayArrow,
                                onClick = playRandom,
                            )
                        },
                        DetailSecondaryAction(
                            label = if (isWatched) {
                                stringResource(Res.string.hero_mark_unwatched)
                            } else {
                                stringResource(Res.string.hero_mark_watched)
                            },
                            icon = if (isWatched) {
                                Icons.Default.CheckCircle
                            } else {
                                Icons.Default.CheckCircleOutline
                            },
                            isActive = isWatched,
                            onClick = onWatchedClick,
                        ),
                        DetailSecondaryAction(
                            label = if (isSaved) {
                                stringResource(Res.string.hero_remove_from_library)
                            } else {
                                stringResource(Res.string.hero_add_to_library)
                            },
                            icon = if (isSaved) {
                                Icons.Default.Check
                            } else {
                                Icons.Default.Add
                            },
                            isActive = isSaved,
                            onClick = onSaveClick,
                            onLongClick = onSaveLongClick,
                        ),
                    ),
                    isTablet = true,
                    focused = actionsFocused,
                    onPlayClick = onPrimaryPlayClick,
                    onPlayLongClick = if (showManualPlayOption) onPrimaryPlayLongClick else null,
                )
            }

            if (showOverview && !meta.description.isNullOrBlank()) {
                // Same footprint as a 4-line clamp (4 * 25.sp line height), but scrollable
                // instead of ellipsized so a long synopsis can still be read in full.
                Text(
                    text = meta.description,
                    modifier = Modifier
                        .padding(top = 418.dp)
                        .width(headingWidth)
                        .heightIn(max = 100.dp)
                        .verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 25.sp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
                )
            }

            if (canShowCast || canShowProduction) {
                Column(
                    modifier = Modifier
                        .padding(top = 552.dp)
                        .width(headingWidth),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    DetailHeroPeopleHeader(
                        activeTab = activePeopleTab,
                        showCast = canShowCast,
                        showProduction = canShowProduction,
                        onTabSelected = { peopleTab = it },
                    )
                    DetailCastSection(
                        cast = if (activePeopleTab == DetailHeroPeopleTab.Production) {
                            productionPeople
                        } else {
                            starringPeople
                        },
                        showHeader = false,
                        visibleItemCount = 6,
                        onCastClick = if (activePeopleTab == DetailHeroPeopleTab.Starring) onCastClick else null,
                        compactDesktopLayout = true,
                    )
                }
            }
        }

        DetailHeroProductionBadges(
            meta = meta,
            onCompanyClick = onCompanyClick,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 16.dp,
                    end = 24.dp,
                ),
        )
    }
}

@Composable
private fun DetailHeroProductionBadges(
    meta: MetaDetails,
    onCompanyClick: ((MetaCompany, String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val isSeriesLike = meta.type == "series" || meta.videos.any { it.season != null || it.episode != null }
    val sourceItems = if (isSeriesLike) {
        meta.networks.ifEmpty { meta.productionCompanies }
    } else {
        meta.productionCompanies.ifEmpty { meta.networks }
    }
    if (sourceItems.isEmpty()) return

    val entityKind = if (isSeriesLike && meta.networks.isNotEmpty()) "network" else "company"
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        sourceItems.take(4).forEach { item ->
            val clickAction = if (onCompanyClick != null && item.tmdbId != null) {
                { onCompanyClick(item, entityKind) }
            } else {
                null
            }
            Box(
                modifier = Modifier
                    .width(76.dp)
                    .height(42.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color(0xDCF5F5F5))
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.36f),
                        shape = RoundedCornerShape(7.dp),
                    )
                    .then(if (clickAction != null) Modifier.clickable(onClick = clickAction) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                if (!item.logo.isNullOrBlank()) {
                    AsyncImage(
                        model = item.logo,
                        contentDescription = item.name,
                        modifier = Modifier
                            .width(52.dp)
                            .height(24.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text(
                        text = item.name,
                        modifier = Modifier.padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = Color(0xFF333333),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private enum class DetailHeroPeopleTab {
    Starring,
    Production,
}

@Composable
private fun DetailHeroPeopleHeader(
    activeTab: DetailHeroPeopleTab,
    showCast: Boolean,
    showProduction: Boolean,
    onTabSelected: (DetailHeroPeopleTab) -> Unit,
) {
    Row(
        modifier = Modifier.widthIn(max = 620.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (showCast) {
            DetailHeroPeopleTabLabel(
                text = "Starring",
                selected = activeTab == DetailHeroPeopleTab.Starring,
                onClick = { onTabSelected(DetailHeroPeopleTab.Starring) },
            )
        }
        if (showCast && showProduction) {
            Text(
                text = "|",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
            )
        }
        if (showProduction) {
            DetailHeroPeopleTabLabel(
                text = stringResource(Res.string.meta_section_production_title),
                selected = activeTab == DetailHeroPeopleTab.Production,
                onClick = { onTabSelected(DetailHeroPeopleTab.Production) },
            )
        }
    }
}

@Composable
private fun DetailHeroPeopleTabLabel(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        modifier = Modifier.clickable(onClick = onClick),
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        color = if (selected) {
            MaterialTheme.colorScheme.onBackground
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
        },
    )
}

private fun MetaDetails.detailHeroProductionPeople(): List<MetaPerson> {
    val people = mutableListOf<MetaPerson>()
    fun addNames(names: List<String>, role: String) {
        names.map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(2)
            .forEach { name ->
                people += MetaPerson(
                    name = name,
                    role = role,
                    photo = detailHeroCrewImage(name, role),
                )
            }
    }

    addNames(creator, "Creator")
    addNames(director, "Director")
    addNames(writer, "Writer")
    addNames(producer, "Producer")

    return people.distinctBy { it.name.lowercase() to it.role.orEmpty().lowercase() }
}

private fun MetaDetails.detailHeroCrewImage(name: String, roleNeedle: String): String? =
    cast.firstOrNull { person ->
        person.name.equals(name, ignoreCase = true) &&
            person.role.orEmpty().contains(roleNeedle, ignoreCase = true) &&
            !person.photo.isNullOrBlank()
    }?.photo ?: cast.firstOrNull { person ->
        person.name.equals(name, ignoreCase = true) && !person.photo.isNullOrBlank()
    }?.photo

private fun String.isDetailHeroCrewRole(): Boolean {
    val roleParts = split(Regex("""[,/;|â€¢Â·]+"""))
        .map { it.trim().lowercase() }
        .filter(String::isNotBlank)
    if (roleParts.isEmpty()) return false
    return roleParts.all { part ->
        detailHeroCrewRoleMarkers.any(part::contains)
    }
}

private val detailHeroCrewRoleMarkers = listOf(
    "director",
    "writer",
    "creator",
    "created by",
    "screenplay",
    "showrunner",
    "producer",
)

@Composable
private fun DetailHeroLogoOrTitle(
    meta: MetaDetails,
    logoUrl: String?,
    logoLoadError: Boolean,
    onLogoLoadError: () -> Unit,
    modifier: Modifier,
    titleTextAlign: TextAlign,
    logoAlignment: Alignment,
    isTablet: Boolean,
) {
    if (logoUrl != null && !logoLoadError) {
        AsyncImage(
            model = logoUrl,
            contentDescription = stringResource(Res.string.detail_logo_content_description, meta.name),
            modifier = modifier,
            alignment = logoAlignment,
            contentScale = ContentScale.Fit,
            onError = { onLogoLoadError() },
        )
    } else {
        Text(
            text = meta.name,
            modifier = modifier,
            style = if (isTablet) {
                MaterialTheme.typography.displaySmall
            } else {
                MaterialTheme.typography.displayLarge
            },
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = titleTextAlign,
        )
    }
}

@Composable
private fun DetailHeroMetadataRow(
    meta: MetaDetails,
    modifier: Modifier = Modifier,
) {
    val releaseLine = formatMetaReleaseLineForDetails(meta)
    val runtimeText = formatRuntimeForDisplay(meta.runtime)
    val ageBadge = meta.ageRating?.trim()?.takeIf { it.isNotBlank() }
    val languageBadge = meta.language
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.uppercase()
    val countryBadge = meta.country
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    val items = buildList {
        releaseLine?.let { add(it) }
        if (meta.genres.isNotEmpty()) add(meta.genres.take(3).joinToString(" \u2022 "))
        runtimeText?.let { add(it) }
        ageBadge?.let { add(it) }
        languageBadge?.let { add(it) }
        countryBadge?.let { add(it) }
    }
        // Drop any blank entry so the bullet separators (drawn between items) never end up
        // orphaned next to an empty value.
        .filter { it.isNotBlank() }
    if (items.isEmpty()) return

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        items.forEachIndexed { index, item ->
            if (index > 0) {
                Text(
                    text = "\u2022",
                    modifier = Modifier.padding(horizontal = 10.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = item,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun detailHeroHeight(
    maxWidth: Dp,
    viewportHeight: Dp,
    isTablet: Boolean,
    desktopOverlay: Boolean,
): Dp =
    if (!isTablet) {
        (maxWidth * 1.33f).coerceIn(420.dp, 760.dp)
    } else if (desktopOverlay) {
        viewportHeight
            .takeIf { it > 0.dp }
            ?: minOf(maxWidth * 9f / 16f, 940.dp).coerceAtLeast(620.dp)
    } else {
        val viewportLimit = viewportHeight
            .takeIf { it > 0.dp }
            ?.let { it * 0.72f }
            ?: 1080.dp
        minOf(maxWidth * 9f / 16f, viewportLimit).coerceIn(420.dp, 1080.dp)
    }
