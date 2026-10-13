package com.nuvio.app.features.profiles

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioAsyncImage as AsyncImage
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.ui.navigationKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun ProfileSelectionScreen(
    onProfileSelected: (NuvioProfile) -> Unit,
    onEditProfile: (NuvioProfile) -> Unit,
    onAddProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val authState by AuthRepository.state.collectAsStateWithLifecycle()
    val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pinDialogProfile by remember { mutableStateOf<NuvioProfile?>(null) }
    var isEditMode by remember { mutableStateOf(false) }

    val titleAlpha = remember { Animatable(0f) }
    val titleOffset = remember { Animatable(20f) }
    val manageAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        AvatarRepository.fetchAvatars()
        AvatarRepository.refreshAvatars()
    }

    LaunchedEffect(authState) {
        if (authState is AuthState.Authenticated) {
            ProfileRepository.pullProfiles()
        }
    }

    LaunchedEffect(Unit) {
        launch { titleAlpha.animateTo(1f, tween(600, easing = FastOutSlowInEasing)) }
        launch { titleOffset.animateTo(0f, tween(600, easing = FastOutSlowInEasing)) }
        delay(300)
        manageAlpha.animateTo(1f, tween(500))
    }

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    // Keyboard / controller navigation. Targets are the profile cards, the add card, then the
    // Manage button last. Nothing here is a lazy list, so a requester per target is cheap.
    // The focus ring only shows once a navigation key has been pressed, so a mouse user never
    // sees one parked on the first card.
    val profiles = profileState.profiles
    val cardCount = profiles.size + if (profiles.size < 4) 1 else 0
    val manageIndex = cardCount
    val focusRequesters = remember(cardCount) { List(cardCount + 1) { FocusRequester() } }
    var focusedIndex by remember { mutableStateOf(-1) }
    var keyboardActive by remember { mutableStateOf(false) }
    var gridColumns by remember { mutableStateOf(cardCount.coerceAtLeast(1)) }

    fun activateCard(index: Int) {
        val profile = profiles.getOrNull(index)
        when {
            profile == null -> onAddProfile()
            isEditMode -> onEditProfile(profile)
            profile.pinEnabled -> pinDialogProfile = profile
            else -> onProfileSelected(profile)
        }
    }

    fun focusTarget(index: Int) {
        runCatching { focusRequesters.getOrNull(index)?.requestFocus() }
    }

    // Park focus on the last-used profile (else the first card) so the first key press has
    // somewhere to land: with no focused node, Compose delivers no key events to this screen,
    // which is why arrows, Enter and Tab all used to do nothing here.
    val initialFocusIndex = profiles.indexOfFirst {
        it.profileIndex == profileState.activeProfile?.profileIndex
    }.coerceAtLeast(0)
    LaunchedEffect(cardCount, pinDialogProfile) {
        if (pinDialogProfile != null) return@LaunchedEffect
        withFrameNanos { }
        focusTarget(focusedIndex.takeIf { it in focusRequesters.indices } ?: initialFocusIndex)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || pinDialogProfile != null) {
                    return@onPreviewKeyEvent false
                }
                val navKey = event.navigationKey()
                val isBack = navKey == Key.Escape || navKey == Key.Backspace
                val isNavigation = isBack || navKey in setOf(
                    Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown,
                    Key.Tab, Key.Enter,
                )
                if (!isNavigation) return@onPreviewKeyEvent false
                if (isBack) {
                    // Leave Manage mode first; otherwise the gate's own back handler decides
                    // (a no-op at startup, where there is nowhere to go back to).
                    if (!isEditMode) return@onPreviewKeyEvent false
                    isEditMode = false
                    return@onPreviewKeyEvent true
                }
                val current = focusedIndex.takeIf { it in focusRequesters.indices }
                // The first direction press only reveals where focus is, rather than moving it
                // off a card the user has not seen highlighted yet.
                val revealOnly = !keyboardActive && navKey != Key.Enter
                keyboardActive = true
                if (current == null) {
                    focusTarget(initialFocusIndex)
                    return@onPreviewKeyEvent true
                }
                if (revealOnly) return@onPreviewKeyEvent true
                val columns = gridColumns.coerceAtLeast(1)
                val lastCard = cardCount - 1
                val next: Int? = when (navKey) {
                    Key.DirectionLeft ->
                        (current - 1).takeIf { current != manageIndex && current % columns != 0 }
                    Key.DirectionRight -> (current + 1).takeIf {
                        current != manageIndex && it <= lastCard && current % columns != columns - 1
                    }
                    Key.DirectionUp -> if (current == manageIndex) {
                        // Back onto the bottom row, under where Manage sits (centred).
                        val bottomRowStart = (lastCard / columns) * columns
                        bottomRowStart + (lastCard - bottomRowStart) / 2
                    } else {
                        (current - columns).takeIf { it >= 0 }
                    }
                    Key.DirectionDown -> when {
                        current == manageIndex -> null
                        current + columns <= lastCard -> current + columns
                        else -> manageIndex
                    }
                    Key.Tab -> if (event.isShiftPressed) {
                        if (current == 0) manageIndex else current - 1
                    } else {
                        if (current == manageIndex) 0 else current + 1
                    }
                    else -> {
                        if (current == manageIndex) isEditMode = !isEditMode else activateCard(current)
                        null
                    }
                }
                next?.let(::focusTarget)
                true
            }
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f),
                    ),
                ),
            )
            .padding(top = statusBarTop),
    ) {
        val isTabletLayout = maxWidth >= 768.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isTabletLayout) {
                        Modifier
                    } else {
                        Modifier.verticalScroll(rememberScrollState())
                    },
                )
                .padding(horizontal = 24.dp),
            verticalArrangement = if (isTabletLayout) Arrangement.Center else Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(if (isTabletLayout) 0.dp else 60.dp))

            Text(
                text = stringResource(Res.string.profile_who_is_watching),
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = 30.sp,
                    letterSpacing = (-0.5).sp,
                ),
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.graphicsLayer {
                    alpha = titleAlpha.value
                    translationY = titleOffset.value
                },
            )

            Spacer(modifier = Modifier.height(if (isTabletLayout) 28.dp else 48.dp))

            val items = cardCount
            gridColumns = if (isTabletLayout) items.coerceAtLeast(1) else 2

            if (isTabletLayout) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        for (currentIndex in 0 until items) {
                            if (currentIndex < profiles.size) {
                                val profile = profiles[currentIndex]
                                ProfileAvatarCard(
                                    profile = profile,
                                    isEditMode = isEditMode,
                                    animDelay = currentIndex * 80,
                                    focused = keyboardActive && focusedIndex == currentIndex,
                                    modifier = Modifier
                                        .focusRequester(focusRequesters[currentIndex])
                                        .onFocusChanged { if (it.isFocused) focusedIndex = currentIndex },
                                    onClick = { activateCard(currentIndex) },
                                )
                            } else {
                                AddProfileCard(
                                    animDelay = currentIndex * 80,
                                    focused = keyboardActive && focusedIndex == currentIndex,
                                    modifier = Modifier
                                        .focusRequester(focusRequesters[currentIndex])
                                        .onFocusChanged { if (it.isFocused) focusedIndex = currentIndex },
                                    onClick = onAddProfile,
                                )
                            }
                        }
                    }
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    var index = 0
                    while (index < items) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            for (col in 0..1) {
                                if (index < items) {
                                    val currentIndex = index
                                    if (currentIndex < profiles.size) {
                                        val profile = profiles[currentIndex]
                                        ProfileAvatarCard(
                                            profile = profile,
                                            isEditMode = isEditMode,
                                            animDelay = currentIndex * 80,
                                            focused = keyboardActive && focusedIndex == currentIndex,
                                            modifier = Modifier
                                                .focusRequester(focusRequesters[currentIndex])
                                                .onFocusChanged { if (it.isFocused) focusedIndex = currentIndex },
                                            onClick = { activateCard(currentIndex) },
                                        )
                                    } else {
                                        AddProfileCard(
                                            animDelay = currentIndex * 80,
                                            focused = keyboardActive && focusedIndex == currentIndex,
                                            modifier = Modifier
                                                .focusRequester(focusRequesters[currentIndex])
                                                .onFocusChanged { if (it.isFocused) focusedIndex = currentIndex },
                                            onClick = onAddProfile,
                                        )
                                    }
                                    index++
                                } else {
                                    if (profiles.isNotEmpty()) {
                                        Spacer(modifier = Modifier.width(150.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(if (isTabletLayout) 28.dp else 48.dp))

            val manageFocused = keyboardActive && focusedIndex == manageIndex
            Box(
                modifier = Modifier
                    .graphicsLayer { alpha = manageAlpha.value }
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        if (isEditMode) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else Color.Transparent,
                    )
                    .border(
                        width = if (manageFocused) 2.dp else 1.dp,
                        color = when {
                            manageFocused -> MaterialTheme.colorScheme.primary
                            isEditMode -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        },
                        shape = RoundedCornerShape(24.dp),
                    )
                    .focusRequester(focusRequesters[manageIndex])
                    .onFocusChanged { if (it.isFocused) focusedIndex = manageIndex }
                    .clickable { isEditMode = !isEditMode }
                    .padding(horizontal = 24.dp, vertical = 10.dp),
            ) {
                Text(
                    text = if (isEditMode) {
                        stringResource(Res.string.action_done)
                    } else {
                        stringResource(Res.string.profile_manage_profiles)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isEditMode) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(modifier = Modifier.height(if (isTabletLayout) 0.dp else 32.dp))
        }
    }

    pinDialogProfile?.let { profile ->
        PinEntryDialog(
            profileName = profile.name,
            onVerify = { pin -> ProfileRepository.verifyPin(profile.profileIndex, pin) },
            onVerified = {
                pinDialogProfile = null
                onProfileSelected(profile)
            },
            onDismiss = { pinDialogProfile = null },
        )
    }
}

@Composable
private fun ProfileAvatarCard(
    profile: NuvioProfile,
    isEditMode: Boolean,
    animDelay: Int,
    focused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val avatarColor = remember(profile.avatarColorHex) {
        parseHexColor(profile.avatarColorHex)
    }
    val avatars by AvatarRepository.avatars.collectAsStateWithLifecycle()
    val avatarItem = remember(profile.avatarId, avatars) {
        profile.avatarId?.let { id -> avatars.find { it.id == id } }
    }
    val avatarImageUrl = remember(profile.avatarUrl, avatarItem) {
        profileAvatarImageUrl(profile, avatarItem)
    }

    val animAlpha = remember { Animatable(0f) }
    val animScale = remember { Animatable(0.85f) }
    val animOffset = remember { Animatable(30f) }

    LaunchedEffect(Unit) {
        delay(animDelay.toLong() + 150)
        launch { animAlpha.animateTo(1f, tween(450, easing = FastOutSlowInEasing)) }
        launch { animScale.animateTo(1f, tween(500, easing = FastOutSlowInEasing)) }
        launch { animOffset.animateTo(0f, tween(500, easing = FastOutSlowInEasing)) }
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale = when {
        isPressed -> 0.95f
        focused -> 1.06f
        else -> 1f
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(150.dp)
            .graphicsLayer {
                alpha = animAlpha.value
                scaleX = animScale.value * pressScale
                scaleY = animScale.value * pressScale
                translationY = animOffset.value
            }
            .clip(RoundedCornerShape(20.dp))
            .border(
                width = 2.dp,
                color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(20.dp),
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(8.dp),
    ) {
        Box(
            modifier = Modifier.size(110.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (avatarImageUrl != null) {
                val bgColor = avatarItem?.bgColor?.let { parseHexColor(it) } ?: avatarColor
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .clip(CircleShape)
                        .background(bgColor.copy(alpha = 0.2f)),
                )
            }

            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(
                        if (avatarItem != null) {
                            avatarItem.bgColor?.let { parseHexColor(it) } ?: avatarColor
                        } else {
                            avatarColor.copy(alpha = 0.15f)
                        },
                    )
                    .then(
                        if (avatarImageUrl == null) Modifier.border(2.dp, avatarColor.copy(alpha = 0.4f), CircleShape)
                        else Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (avatarImageUrl != null) {
                    AsyncImage(
                        model = avatarImageUrl,
                        contentDescription = avatarItem?.displayName ?: profile.name,
                        modifier = Modifier.size(100.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop,
                    )
                } else if (profile.name.isNotBlank()) {
                    Text(
                        text = profile.name.take(1).uppercase(),
                        style = MaterialTheme.typography.headlineLarge.copy(fontSize = 38.sp),
                        color = avatarColor,
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = null,
                        tint = avatarColor,
                        modifier = Modifier.size(46.dp),
                    )
                }
            }

            if (isEditMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .border(2.dp, MaterialTheme.colorScheme.background, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            if (profile.pinEnabled && !isEditMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(2.dp, MaterialTheme.colorScheme.background, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = profile.name.ifBlank {
                stringResource(Res.string.profile_label_number, profile.profileIndex)
            },
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AddProfileCard(
    animDelay: Int,
    focused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val animAlpha = remember { Animatable(0f) }
    val animScale = remember { Animatable(0.85f) }
    val animOffset = remember { Animatable(30f) }

    LaunchedEffect(Unit) {
        delay(animDelay.toLong() + 150)
        launch { animAlpha.animateTo(1f, tween(450, easing = FastOutSlowInEasing)) }
        launch { animScale.animateTo(1f, tween(500, easing = FastOutSlowInEasing)) }
        launch { animOffset.animateTo(0f, tween(500, easing = FastOutSlowInEasing)) }
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale = when {
        isPressed -> 0.95f
        focused -> 1.06f
        else -> 1f
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(150.dp)
            .graphicsLayer {
                alpha = animAlpha.value
                scaleX = animScale.value * pressScale
                scaleY = animScale.value * pressScale
                translationY = animOffset.value
            }
            .clip(RoundedCornerShape(20.dp))
            .border(
                width = 2.dp,
                color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(20.dp),
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(8.dp),
    ) {
        Box(
            modifier = Modifier.size(110.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .border(
                        2.dp,
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(40.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(Res.string.compose_profile_add_profile),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}
