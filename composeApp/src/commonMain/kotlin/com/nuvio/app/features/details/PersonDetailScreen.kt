package com.nuvio.app.features.details

import com.nuvio.app.core.ui.requestFocusLogged
import com.nuvio.app.features.posterservice.CustomPosterScreen
import com.nuvio.app.features.posterservice.rememberCustomPosters
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.nuvio.app.core.ui.navigationKey
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.border
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioAsyncImage as AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.nuvio.app.core.i18n.localizedShortMonthName
import com.nuvio.app.core.ui.landscapePosterHeightForWidth
import com.nuvio.app.core.ui.landscapePosterWidth
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.features.details.components.DetailPosterRailSection
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.tmdb.TmdbMetadataService
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

private sealed interface PersonDetailUiState {
    data object Loading : PersonDetailUiState
    data class Success(val personDetail: PersonDetail) : PersonDetailUiState
    data class Error(val message: String) : PersonDetailUiState
}

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
fun PersonDetailScreen(
    personId: Int,
    personName: String,
    initialProfilePhoto: String? = null,
    avatarTransitionKey: String? = null,
    preferCrew: Boolean = false,
    onBack: () -> Unit,
    onOpenMeta: (MetaPreview) -> Unit,
    onPosterLongClick: ((MetaPreview) -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    var uiState by remember(personId) { mutableStateOf<PersonDetailUiState>(PersonDetailUiState.Loading) }
    var loadAttempt by remember(personId) { mutableStateOf(0) }
    val watchedUiState by remember {
        WatchedRepository.ensureLoaded()
        WatchedRepository.uiState
    }.collectAsStateWithLifecycle()
    val resolvedAvatarTransitionKey = avatarTransitionKey ?: castAvatarSharedTransitionKey(personId)
    // Esc / Backspace / gamepad B / a rebound Back key: every other screen maps them itself, and this
    // one never did, so only the on-screen arrow could leave it. Key events only reach a focused
    // subtree, so the page takes focus when it opens.
    val rootFocusRequester = remember { FocusRequester() }
    var contentKeyHandler by remember { mutableStateOf<((Key) -> Boolean)?>(null) }
    LaunchedEffect(Unit) { rootFocusRequester.requestFocusLogged() }

    LaunchedEffect(personId, loadAttempt) {
        uiState = PersonDetailUiState.Loading
        val detail = TmdbMetadataService.fetchPersonDetail(
            personId = personId,
            preferCrewCredits = preferCrew,
        )
        uiState = if (detail != null) {
            PersonDetailUiState.Success(detail)
        } else {
            PersonDetailUiState.Error(getString(Res.string.person_load_failed, personName))
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.navigationKey()) {
                    Key.Escape, Key.Backspace -> {
                        onBack()
                        true
                    }
                    else -> contentKeyHandler?.invoke(event.navigationKey()) ?: false
                }
            }
            .focusRequester(rootFocusRequester)
            .focusable(),
    ) {
        when (val state = uiState) {
            is PersonDetailUiState.Loading -> PersonDetailSkeleton(
                personId = personId,
                personName = personName,
                profilePhoto = initialProfilePhoto,
                avatarTransitionKey = resolvedAvatarTransitionKey,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
            )
            is PersonDetailUiState.Error -> PersonDetailError(
                message = state.message,
                onRetry = {
                    uiState = PersonDetailUiState.Loading
                    // Resetting the state alone never re-ran the load; the effect is keyed on this.
                    loadAttempt++
                },
            )
            is PersonDetailUiState.Success -> PersonDetailContent(
                person = state.personDetail,
                watchedKeys = watchedUiState.watchedKeys,
                onOpenMeta = onOpenMeta,
                onPosterLongClick = onPosterLongClick,
                initialProfilePhoto = initialProfilePhoto,
                avatarTransitionKey = resolvedAvatarTransitionKey,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                onKeyHandler = { contentKeyHandler = it },
            )
            }

        // Back button overlaid on top
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 4.dp, top = 4.dp)
                .align(Alignment.TopStart),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(Res.string.action_back),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
private fun PersonDetailContent(
    person: PersonDetail,
    watchedKeys: Set<String>,
    onOpenMeta: (MetaPreview) -> Unit,
    onPosterLongClick: ((MetaPreview) -> Unit)? = null,
    initialProfilePhoto: String? = null,
    avatarTransitionKey: String,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    onKeyHandler: ((((Key) -> Boolean))?) -> Unit = {},
) {
    val rawCredits = remember(person.movieCredits, person.tvCredits) {
        (person.movieCredits + person.tvCredits)
            .distinctBy { it.id }
    }
    val allCredits = rememberCustomPosters(rawCredits, CustomPosterScreen.Details)

    val todayDate = remember { CurrentDateProvider.todayIsoDate() }

    val popularCredits = remember(allCredits) {
        allCredits
            .sortedByDescending { it.popularity ?: 0.0 }
    }

    val latestCredits = remember(allCredits, todayDate) {
        allCredits
            .filter { credit ->
                val date = credit.rawReleaseDate
                date != null && date <= todayDate
            }
            .sortedByDescending { it.rawReleaseDate ?: "" }
    }

    val upcomingCredits = remember(allCredits, todayDate) {
        allCredits
            .filter { credit ->
                val date = credit.rawReleaseDate
                date != null && date > todayDate
            }
            .sortedBy { it.rawReleaseDate ?: "" }
    }

    val scrollState = rememberScrollState()
    val haptic = LocalHapticFeedback.current

    // Keyboard / pad: the rails draw a cursor at focusedItemIndex and the page moves it, as on the
    // details page. null row = the hero (nothing highlighted); Down from there enters the first row.
    val railTitles = listOf(
        stringResource(Res.string.person_popular),
        stringResource(Res.string.person_latest),
        stringResource(Res.string.person_upcoming),
    )
    val rails = remember(popularCredits, latestCredits, upcomingCredits) {
        listOf(0 to popularCredits, 1 to latestCredits, 2 to upcomingCredits).filter { it.second.isNotEmpty() }
    }
    // Opens on the first poster, as the other pages do, without scrolling: the hero stays in view.
    var focusedRow by remember(person.tmdbId) { mutableStateOf<Int?>(if (rails.isNotEmpty()) 0 else null) }
    val focusedColumns = remember(person.tmdbId) { mutableStateMapOf<Int, Int>() }
    val railRequesters = remember(rails.size) { List(rails.size) { BringIntoViewRequester() } }
    val coroutineScope = rememberCoroutineScope()
    fun focusRow(row: Int?) {
        focusedRow = row
        coroutineScope.launch {
            if (row == null) scrollState.animateScrollTo(0) else railRequesters.getOrNull(row)?.bringIntoView()
        }
    }
    val handleKey: (Key) -> Boolean = handleKey@{ key ->
        if (rails.isEmpty()) return@handleKey false
        val row = focusedRow
        when (key) {
            Key.DirectionDown -> {
                focusRow(if (row == null) 0 else (row + 1).coerceAtMost(rails.lastIndex))
                true
            }
            Key.DirectionUp -> {
                if (row != null) focusRow(if (row == 0) null else row - 1)
                true
            }
            Key.DirectionLeft, Key.DirectionRight -> {
                if (row == null) return@handleKey false
                val size = rails[row].second.size
                val column = focusedColumns[row] ?: 0
                focusedColumns[row] = (column + if (key == Key.DirectionRight) 1 else -1).coerceIn(0, size - 1)
                true
            }
            Key.Enter -> {
                if (row == null) return@handleKey false
                rails[row].second.getOrNull(focusedColumns[row] ?: 0)?.let(onOpenMeta)
                true
            }
            else -> false
        }
    }
    DisposableEffect(handleKey) {
        onKeyHandler(handleKey)
        onDispose { onKeyHandler(null) }
    }

    val shouldTriggerCatalogHaptic by remember {
        derivedStateOf { scrollState.value >= HAPTIC_TRIGGER_SCROLL_THRESHOLD_PX }
    }
    var didTriggerCatalogHaptic by remember(person.tmdbId) { mutableStateOf(false) }
    LaunchedEffect(shouldTriggerCatalogHaptic, didTriggerCatalogHaptic) {
        if (shouldTriggerCatalogHaptic && !didTriggerCatalogHaptic) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            didTriggerCatalogHaptic = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        PersonBackdrop(url = person.heroBackdrop, scrollOffset = { scrollState.value })
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
            ) {
                HeroSection(
                    person = person,
                    fallbackProfilePhoto = initialProfilePhoto,
                    avatarTransitionKey = avatarTransitionKey,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                )

                rails.forEachIndexed { row, (titleIndex, items) ->
                    Spacer(modifier = Modifier.height(24.dp))
                    DetailPosterRailSection(
                        title = railTitles[titleIndex],
                        items = items,
                        watchedKeys = watchedKeys,
                        headerHorizontalPadding = 20.dp,
                        focusedItemIndex = if (focusedRow == row) focusedColumns[row] ?: 0 else null,
                        onPosterClick = onOpenMeta,
                        onPosterLongClick = onPosterLongClick,
                        // The mouse moves the same highlight the keys do.
                        onHoverItem = { column ->
                            focusedRow = row
                            focusedColumns[row] = column
                        },
                        modifier = Modifier.bringIntoViewRequester(railRequesters[row]),
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

private const val HAPTIC_TRIGGER_SCROLL_THRESHOLD_PX = 56

@Composable
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalComposeUiApi::class)
private fun HeroSection(
    person: PersonDetail,
    fallbackProfilePhoto: String? = null,
    avatarTransitionKey: String,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    val infoItems = personInfoLines(person)
    PersonHeroFrame(
        portraitUrl = person.profilePhoto?.takeIf { it.isNotBlank() } ?: fallbackProfilePhoto,
        name = person.name,
        avatarTransitionKey = avatarTransitionKey,
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
        underName = {
            infoItems.forEach { info ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = info,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    ) {
        person.biography?.let { bio ->
            // Clamped to seven lines, but scrollable with the wheel instead of cut off, like the
            // details page's synopsis. Deliberately not a focus stop: arrows and the pad move
            // straight on to the rows.
            val bioScroll = rememberScrollState()
            Text(
                text = bio,
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                modifier = Modifier
                    .heightIn(max = PERSON_BIO_MAX_HEIGHT)
                    // A wheel turn over a bio that scrolls stays in it, also at its ends. The
                    // page can get it two ways, so both are closed: a notch that arrives with the
                    // bio already at its end bubbles up as an unconsumed pointer event (consumed
                    // here once the bio's scroller has passed on it), and the rest of a notch's
                    // glide that runs past the end is handed up through nested scrolling
                    // (ContainedScroll swallows it).
                    .onPointerEvent(PointerEventType.Scroll) { event ->
                        if (bioScroll.maxValue > 0) event.changes.forEach { it.consume() }
                    }
                    .nestedScroll(remember(bioScroll) { ContainedScroll(bioScroll) })
                    .verticalScroll(bioScroll),
            )
        }
        person.heroBackdropTitle?.let { title ->
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = stringResource(Res.string.person_backdrop_from, title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The lines under the name: "Born Sep 12, 1986 · age 40" (with "Died …" on its own line for
 * someone who has died), then the birthplace. The department ("Known for: Acting") is left out:
 * the rows already show it and the biography says it.
 */
@Composable
private fun personInfoLines(person: PersonDetail): List<String> {
    val lines = mutableListOf<String>()
    person.birthday?.let { bday ->
        val age = calculateAge(bday, person.deathday)
        val ageText = age?.let { stringResource(Res.string.person_age_plain, it) }
        val bdayDisplay = formatDateForDisplay(bday) ?: bday
        val deathDisplay = person.deathday?.let { formatDateForDisplay(it) ?: it }
        val born = stringResource(Res.string.person_born, bdayDisplay, "")
        if (deathDisplay != null) {
            lines += born
            lines += listOfNotNull(stringResource(Res.string.person_died, deathDisplay), ageText).joinToString(" · ")
        } else {
            lines += listOfNotNull(born, ageText).joinToString(" · ")
        }
    }
    person.placeOfBirth?.let { lines += it }
    return lines
}

/**
 * The backdrop of their best-known title, at its own 16:9 shape across the top of the page (not
 * cropped to the hero's strip, which on a wide window kept only a thin band and usually cut the
 * faces off). It darkens towards the left, where the portrait and text are, and fades into the
 * page under the rows. Scrolls away at half the page's speed.
 */
@Composable
private fun PersonBackdrop(url: String?, scrollOffset: () -> Int) {
    if (url.isNullOrBlank()) return
    val background = MaterialTheme.colorScheme.background
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .graphicsLayer { translationY = -scrollOffset() * 0.5f },
    ) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.horizontalGradient(
                        0f to background.copy(alpha = 0.92f),
                        0.45f to background.copy(alpha = 0.72f),
                        0.75f to background.copy(alpha = 0.22f),
                        1f to background.copy(alpha = 0.08f),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to background.copy(alpha = 0.35f),
                        0.15f to Color.Transparent,
                        0.45f to Color.Transparent,
                        0.85f to background,
                        1f to background,
                    ),
                ),
        )
    }
}

/**
 * The hero (drawn over [PersonBackdrop]). Their portrait is a large circle in the top left with the name, birth date and birthplace under
 * it, and the biography sits beside it. The frame takes the height of its content (with a floor), so a short
 * biography does not leave an empty band above the text. Without a backdrop the page's own
 * background shows through.
 */
@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
private fun PersonHeroFrame(
    portraitUrl: String?,
    name: String,
    avatarTransitionKey: String,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    underName: @Composable ColumnScope.() -> Unit = {},
    details: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val wide = maxWidth >= PERSON_HERO_WIDE_MIN_WIDTH
        val portraitSize = if (wide) 220.dp else 150.dp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (wide) PERSON_HERO_MIN_HEIGHT else 0.dp),
        ) {
            val portrait: @Composable () -> Unit = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(portraitSize + 60.dp),
                ) {
                    PersonPortraitCircle(
                        url = portraitUrl,
                        name = name,
                        size = portraitSize,
                        avatarTransitionKey = avatarTransitionKey,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = name,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    underName()
                }
            }
            val heroPadding = Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 20.dp, end = 20.dp, top = 56.dp, bottom = 32.dp)
            if (wide) {
                Row(
                    modifier = heroPadding,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    portrait()
                    Spacer(modifier = Modifier.width(40.dp))
                    Column(modifier = Modifier.widthIn(max = PERSON_HERO_TEXT_MAX_WIDTH)) {
                        details()
                    }
                }
            } else {
                Column(modifier = heroPadding) {
                    portrait()
                    Spacer(modifier = Modifier.height(20.dp))
                    details()
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
private fun PersonPortraitCircle(
    url: String?,
    name: String,
    size: Dp,
    avatarTransitionKey: String,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
) {
    val platformContext = LocalPlatformContext.current
    val sharedModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedElement(
                sharedContentState = rememberSharedContentState(key = avatarTransitionKey),
                animatedVisibilityScope = animatedVisibilityScope,
            )
        }
    } else {
        Modifier
    }
    Box(
        modifier = Modifier
            .then(sharedModifier)
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(3.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (!url.isNullOrBlank()) {
            // Same cache key as the cast circle it grows from, so the photo is there at once.
            val request = remember(platformContext, url, avatarTransitionKey) {
                ImageRequest.Builder(platformContext)
                    .data(url)
                    .memoryCacheKey(avatarTransitionKey)
                    .placeholderMemoryCacheKey(avatarTransitionKey)
                    .diskCacheKey(url)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Text(
                text = name.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val PERSON_HERO_MIN_HEIGHT = 380.dp
private val PERSON_HERO_TEXT_MAX_WIDTH = 820.dp
private val PERSON_HERO_WIDE_MIN_WIDTH = 760.dp

// ─── Loading / Error States ───

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
private fun PersonDetailSkeleton(
    personId: Int,
    personName: String,
    profilePhoto: String? = null,
    avatarTransitionKey: String,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
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
            .verticalScroll(rememberScrollState()),
    ) {
        PersonHeroFrame(
            portraitUrl = profilePhoto,
            name = personName,
            avatarTransitionKey = avatarTransitionKey,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
        ) {
            Column(modifier = Modifier.widthIn(max = 560.dp)) {
                SkeletonLine(widthFraction = 0.58f, height = 14.dp)
                Spacer(modifier = Modifier.height(6.dp))
                SkeletonLine(widthFraction = 0.42f, height = 14.dp)
                Spacer(modifier = Modifier.height(16.dp))
                listOf(1.0f, 0.96f, 0.92f, 0.98f, 0.74f).forEach { widthFraction ->
                    SkeletonLine(widthFraction = widthFraction, height = 16.dp)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Box(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .width(120.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            repeat(6) {
                Box(
                    modifier = Modifier
                        .width(skeletonPosterWidth)
                        .height(skeletonPosterHeight)
                        .clip(RoundedCornerShape(posterCardStyle.cornerRadiusDp.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun SkeletonLine(
    widthFraction: Float,
    height: androidx.compose.ui.unit.Dp,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
private fun PersonDetailError(
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
                text = stringResource(Res.string.person_something_wrong),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(stringResource(Res.string.action_retry))
            }
        }
    }
}

// ─── Utility ───

private fun calculateAge(birthday: String, deathday: String?): Int? {
    val birthParts = birthday.split("-").mapNotNull { it.toIntOrNull() }
    if (birthParts.size < 3) return null
    val birthYear = birthParts[0]
    val birthMonth = birthParts[1]
    val birthDay = birthParts[2]

    val endParts = deathday?.split("-")?.mapNotNull { it.toIntOrNull() }
    val endYear: Int
    val endMonth: Int
    val endDay: Int
    if (endParts != null && endParts.size >= 3) {
        endYear = endParts[0]
        endMonth = endParts[1]
        endDay = endParts[2]
    } else {
        val today = CurrentDateProvider.todayIsoDate().split("-").mapNotNull { it.toIntOrNull() }
        if (today.size < 3) return null
        endYear = today[0]
        endMonth = today[1]
        endDay = today[2]
    }

    var age = endYear - birthYear
    if (endMonth < birthMonth || (endMonth == birthMonth && endDay < birthDay)) {
        age--
    }
    return age.takeIf { it >= 0 }
}

private fun formatDateForDisplay(date: String): String? {
    val parts = date.split("-").mapNotNull { it.toIntOrNull() }
    if (parts.size < 3) return null
    val month = parts[1]
    val day = parts[2]
    val year = parts[0]
    return if (month in 1..12) {
        "${localizedShortMonthName(month)} $day, $year"
    } else {
        null
    }
}

/** Twelve lines of the biography before it scrolls. */
private val PERSON_BIO_MAX_HEIGHT = 288.dp

/** The leftover of a scroll inside an overflowing bio, kept from the page (see the bio's wheel note). */
private class ContainedScroll(private val inner: ScrollState) : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (inner.maxValue > 0) available.copy(x = 0f) else Offset.Zero

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        if (inner.maxValue > 0) available.copy(x = 0f) else Velocity.Zero
}
