package com.nuvio.app.features.details.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.AppIconResource
import com.nuvio.app.core.ui.appIconPainter
import com.nuvio.app.core.ui.NuvioPosterHoverTooltip
import com.nuvio.app.core.ui.secondaryClick
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_play
import nuvio.composeapp.generated.resources.details_episode_search_close
import nuvio.composeapp.generated.resources.details_episode_search_matches
import nuvio.composeapp.generated.resources.details_episode_search_placeholder
import org.jetbrains.compose.resources.stringResource
import androidx.compose.material.icons.rounded.Search
import com.nuvio.app.core.ui.trackTextInputFocus

data class DetailSecondaryAction(
    val label: String,
    val icon: ImageVector,
    val isActive: Boolean = false,
    val onClick: () -> Unit = {},
    val onLongClick: (() -> Unit)? = null,
)

/**
 * Episode search folded into the Play button. A floating overlay cannot be used on the desktop
 * hero: the trailer plays on a heavyweight native surface that paints over every Compose layer
 * regardless of z-order, so the panel was landing behind it. The action row is the one strip the
 * bounded hero trailer never covers, so Play morphs into the field the same way the floating top
 * bar's nav quadrants morph into the global search box, and morphs back on Escape / the X.
 *
 * [inputReady] keeps the shortcut's own key-down out of the query while focus is still moving into
 * the field.
 */
data class DetailEpisodeSearchState(
    val active: Boolean,
    val query: String,
    val resultCount: Int,
    val inputReady: Boolean,
    val focusRequester: FocusRequester,
    val onQueryChange: (String) -> Unit,
    val onClose: () -> Unit,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailActionButtons(
    modifier: Modifier = Modifier,
    playLabel: String = stringResource(Res.string.action_play),
    secondaryActions: List<DetailSecondaryAction> = emptyList(),
    actionsMenuLabel: String = "More actions",
    isTablet: Boolean = false,
    /**
     * Which button in the row holds keyboard focus: 0 is Play, 1+ index into [secondaryActions] in
     * render order. Null when the row is not focused at all. Previously only Play could be focused,
     * so left/right had nowhere to go from it.
     */
    focusedActionIndex: Int? = null,
    onPlayClick: () -> Unit = {},
    onPlayLongClick: (() -> Unit)? = null,
    episodeSearch: DetailEpisodeSearchState? = null,
) {
    val playPainter = appIconPainter(AppIconResource.PlayerPlay)
    val buttonHeight = if (isTablet) 56.dp else 52.dp
    val iconButtonSize = buttonHeight
    val playShape = RoundedCornerShape(12.dp)
    val hapticFeedback = LocalHapticFeedback.current
    val searchActive = episodeSearch != null && episodeSearch.active
    // The text editor owns the keyboard while searching, so no button in the row should still be
    // drawing a focus ring behind it.
    val activeFocusedActionIndex = focusedActionIndex.takeUnless { searchActive }
    // Kept small: this button is wide, so even a few percent of scale would push its edge into
    // the secondary buttons beside it.
    val playFocused = activeFocusedActionIndex == 0
    val playScale by animateFloatAsState(targetValue = if (playFocused) 1.02f else 1f)
    val focusRingAlpha by animateFloatAsState(targetValue = if (playFocused) 1f else 0f)
    val focusRingColor = MaterialTheme.colorScheme.primary
    val hasSecondaryActions = secondaryActions.isNotEmpty()

    Box(
        modifier = modifier
            .widthIn(max = if (isTablet) 620.dp else 420.dp)
            .fillMaxWidth()
            .height(buttonHeight),
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(buttonHeight)
                    .graphicsLayer {
                        scaleX = playScale
                        scaleY = playScale
                    }
                    // Focus ring: an accent-coloured outline drawn just inside the button edge
                    // (on top of the fill), so it doesn't overlap the buttons beside it while
                    // still clearly showing keyboard focus.
                    .drawWithContent {
                        drawContent()
                        if (focusRingAlpha <= 0f) return@drawWithContent
                        val stroke = 3.dp.toPx()
                        val inset = stroke / 2f
                        drawRoundRect(
                            color = focusRingColor.copy(alpha = focusRingColor.alpha * focusRingAlpha),
                            topLeft = Offset(inset, inset),
                            size = Size(size.width - inset * 2f, size.height - inset * 2f),
                            cornerRadius = CornerRadius((12.dp.toPx() - inset).coerceAtLeast(0f)),
                            style = Stroke(width = stroke),
                        )
                    },
                shape = playShape,
                color = MaterialTheme.colorScheme.onBackground,
                contentColor = MaterialTheme.colorScheme.background,
            ) {
                AnimatedContent(
                    targetState = searchActive,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
                    label = "detail_play_search",
                ) { showSearch ->
                    if (showSearch && episodeSearch != null) {
                        DetailEpisodeSearchField(
                            state = episodeSearch,
                            height = buttonHeight,
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        onPlayClick()
                                    },
                                    onLongClick = onPlayLongClick,
                                    role = Role.Button,
                                )
                                .secondaryClick(onPlayLongClick)
                                .height(buttonHeight),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = playPainter,
                                contentDescription = null,
                                modifier = Modifier.size(if (isTablet) 20.dp else 18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = playLabel,
                                style = if (isTablet) {
                                    MaterialTheme.typography.titleMedium
                                } else {
                                    MaterialTheme.typography.titleSmall
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            if (hasSecondaryActions) {
                Spacer(modifier = Modifier.width(12.dp))
                secondaryActions.forEachIndexed { index, action ->
                    NuvioPosterHoverTooltip(title = action.label) {
                        DetailIconAction(
                            label = action.label,
                            icon = action.icon,
                            active = action.isActive,
                            focused = activeFocusedActionIndex == index + 1,
                            size = iconButtonSize,
                            onClick = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                action.onClick()
                            },
                            onLongClick = action.onLongClick?.let { longClick ->
                                {
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                    longClick()
                                }
                            },
                        )
                    }

                    if (index != secondaryActions.lastIndex) {
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailEpisodeSearchField(
    state: DetailEpisodeSearchState,
    height: Dp,
) {
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(state.query, TextRange(state.query.length)))
    }
    // The query is owned by the details screen (clearing it on close, for instance), so follow it
    // whenever it diverges from what the editor holds.
    LaunchedEffect(state.query) {
        if (fieldValue.text != state.query) {
            fieldValue = fieldValue.copy(
                text = state.query,
                selection = TextRange(state.query.length),
            )
        }
    }
    LaunchedEffect(Unit) {
        // Let the field enter composition before handing it focus.
        delay(1)
        try { state.focusRequester.requestFocus() } catch (_: Exception) {}
    }

    val contentColor = LocalContentColor.current
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = contentColor.copy(alpha = 0.7f),
        )
        BasicTextField(
            value = fieldValue,
            onValueChange = { value ->
                if (!state.inputReady) return@BasicTextField
                fieldValue = value
                state.onQueryChange(value.text)
            },
            modifier = Modifier
                .weight(1f)
                .focusRequester(state.focusRequester)
                // Details shortcuts are handled at the window root; mark this as an active text
                // editor so typed letters stay query text instead of firing them again.
                .trackTextInputFocus(),
            singleLine = true,
            readOnly = !state.inputReady,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = contentColor),
            cursorBrush = SolidColor(contentColor),
            decorationBox = { inner ->
                if (fieldValue.text.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.details_episode_search_placeholder),
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor.copy(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                inner()
            },
        )
        if (state.query.isNotBlank()) {
            Text(
                text = stringResource(Res.string.details_episode_search_matches, state.resultCount),
                style = MaterialTheme.typography.labelMedium,
                color = contentColor.copy(alpha = 0.7f),
                maxLines = 1,
            )
        }
        IconButton(
            onClick = state.onClose,
            modifier = Modifier.size(height - 16.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(Res.string.details_episode_search_close),
                tint = contentColor.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DetailIconAction(
    label: String,
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp,
    focused: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    // Matches the Play button's ring so a focus move along the row reads as one continuous control.
    val focusRingAlpha by animateFloatAsState(targetValue = if (focused) 1f else 0f)
    val focusRingColor = MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier.drawWithContent {
            drawContent()
            if (focusRingAlpha <= 0f) return@drawWithContent
            val stroke = 3.dp.toPx()
            val inset = stroke / 2f
            drawRoundRect(
                color = focusRingColor.copy(alpha = focusRingColor.alpha * focusRingAlpha),
                topLeft = Offset(inset, inset),
                size = Size(this.size.width - inset * 2f, this.size.height - inset * 2f),
                cornerRadius = CornerRadius((12.dp.toPx() - inset).coerceAtLeast(0f)),
                style = Stroke(width = stroke),
            )
        },
        shape = RoundedCornerShape(12.dp),
        color = if (active) {
            MaterialTheme.colorScheme.onBackground
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.88f)
        },
        contentColor = if (active) {
            MaterialTheme.colorScheme.background
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                    role = Role.Button,
                )
                .secondaryClick(onLongClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(21.dp),
            )
        }
    }
}
