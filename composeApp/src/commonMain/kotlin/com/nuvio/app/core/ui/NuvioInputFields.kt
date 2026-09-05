package com.nuvio.app.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_hide_secret
import nuvio.composeapp.generated.resources.settings_show_secret
import org.jetbrains.compose.resources.stringResource

/*
 * The app's text and number inputs.
 *
 * Material's OutlinedTextField draws a notched border with a label floating through the gap, and
 * every screen that used one had to re-specify colours, shape and text style to stop it looking
 * like stock Material. That is why they had drifted apart. These two controls take their look from
 * the rest of the app instead - flat surfaceCard container, hairline border, accent for the active
 * state, the same language as NuvioSegmentedControl - and take no styling parameters at all, so a
 * call site cannot drift again.
 */

private val FieldShape = RoundedCornerShape(NuvioTokens.Radius.lg)
private val FieldMinHeight = 46.dp
private val FieldIconButtonSize = 32.dp
private val StepperHeight = 34.dp
private val StepperButtonWidth = 34.dp

private const val HoldRepeatDelayMillis = 400L
private const val HoldRepeatIntervalMillis = 110L
private const val HoldRepeatMinIntervalMillis = 30L
private const val HoldRepeatDecay = 0.88

/**
 * The app's text input.
 *
 * The label Material floated into the border becomes the [placeholder]: these sit under a row title
 * or a dialog heading that already names the field, so a persistent label only duplicated it.
 */
@Composable
fun NuvioTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    isError: Boolean = false,
    secret: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    supportingText: String? = null,
    textStyle: TextStyle? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction? = null,
    onImeAction: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    leadingContent: @Composable (RowScope.() -> Unit)? = null,
    trailingContent: @Composable (RowScope.() -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    var focused by remember { mutableStateOf(false) }
    var secretVisible by rememberSaveable { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focusManager = LocalFocusManager.current
    val resolvedTextStyle = (textStyle ?: MaterialTheme.typography.bodyMedium).copy(
        color = if (enabled) tokens.colors.textPrimary else tokens.colors.textDisabled,
    )
    val resolvedImeAction = imeAction ?: if (onImeAction != null) ImeAction.Done else ImeAction.Default

    val borderColor by animateColorAsState(
        targetValue = when {
            !enabled -> tokens.colors.borderSubtle
            isError -> tokens.colors.danger
            focused -> tokens.colors.accent
            hovered -> tokens.colors.borderStrong
            else -> tokens.colors.borderDefault
        },
        animationSpec = tween(tokens.motion.fastMillis, easing = tokens.motion.standard),
        label = "nuvioFieldBorder",
    )
    // Second stop of the same animation, so a gradient accent fades in with the first instead of
    // snapping on at the end of the transition. Null for flat themes, which stay a solid colour.
    val accentEnd = tokens.colors.accentGradientEnd
    val borderEndColor by animateColorAsState(
        targetValue = when {
            !enabled -> tokens.colors.borderSubtle
            isError -> tokens.colors.danger
            focused -> accentEnd ?: tokens.colors.accent
            hovered -> tokens.colors.borderStrong
            else -> tokens.colors.borderDefault
        },
        animationSpec = tween(tokens.motion.fastMillis, easing = tokens.motion.standard),
        label = "nuvioFieldBorderEnd",
    )
    val borderWidth by animateDpAsState(
        targetValue = if (focused || isError) tokens.borders.medium else tokens.borders.thin,
        animationSpec = tween(tokens.motion.fastMillis, easing = tokens.motion.standard),
        label = "nuvioFieldBorderWidth",
    )
    // A hint of accent behind a focused field, so the active one is obvious on a page full of them
    // without having to work out which border changed colour.
    val containerColor by animateColorAsState(
        targetValue = if (focused) {
            tokens.colors.accent.copy(alpha = tokens.opacity.subtle)
        } else {
            tokens.colors.surfaceCard
        },
        animationSpec = tween(tokens.motion.fastMillis, easing = tokens.motion.standard),
        label = "nuvioFieldContainer",
    )
    val containerEndColor by animateColorAsState(
        targetValue = if (focused) {
            (accentEnd ?: tokens.colors.accent).copy(alpha = tokens.opacity.subtle)
        } else {
            tokens.colors.surfaceCard
        },
        animationSpec = tween(tokens.motion.fastMillis, easing = tokens.motion.standard),
        label = "nuvioFieldContainerEnd",
    )
    val containerBrush = if (accentEnd != null) {
        Brush.horizontalGradient(listOf(containerColor, containerEndColor))
    } else {
        SolidColor(containerColor)
    }
    val borderBrush = if (accentEnd != null) {
        Brush.horizontalGradient(listOf(borderColor, borderEndColor))
    } else {
        SolidColor(borderColor)
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = FieldMinHeight)
                .clip(FieldShape)
                .background(containerBrush)
                .border(borderWidth, borderBrush, FieldShape)
                .hoverable(interactionSource, enabled)
                .alpha(if (enabled) NuvioTokens.Opacity.visible else tokens.opacity.medium)
                // An icon carries its own optical padding, so a leading one sits closer to the edge
                // than bare text would.
                .padding(
                    start = if (leadingContent == null) NuvioTokens.Space.s14 else NuvioTokens.Space.s8,
                    end = NuvioTokens.Space.s6,
                ),
            // Multi-line fields keep their trailing content and placeholder pinned to the first
            // line rather than drifting to the middle of a tall box.
            verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
        ) {
            leadingContent?.invoke(this)
            Box(
                modifier = Modifier.weight(1f).padding(vertical = NuvioTokens.Space.s12),
                contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
            ) {
                if (value.isEmpty() && !placeholder.isNullOrBlank()) {
                    Text(
                        text = placeholder,
                        style = resolvedTextStyle.copy(color = tokens.colors.textMuted),
                        maxLines = if (singleLine) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    readOnly = readOnly,
                    singleLine = singleLine,
                    minLines = minLines,
                    maxLines = maxLines,
                    textStyle = resolvedTextStyle,
                    cursorBrush = SolidColor(tokens.colors.accent),
                    visualTransformation = if (secret && !secretVisible) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (secret) KeyboardType.Password else keyboardType,
                        imeAction = resolvedImeAction,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = onImeAction?.let { action -> { action(); focusManager.clearFocus() } },
                        onNext = { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Next) },
                        onGo = onImeAction?.let { action -> { action() } },
                        onSearch = onImeAction?.let { action -> { action() } },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                        .onFocusChanged { focused = it.isFocused }
                        // Tracked here rather than at each call site: a value containing "h" would
                        // otherwise navigate to Home mid-typing. Every field gets it for free.
                        .trackTextInputFocus(),
                )
            }
            if (secret) {
                NuvioFieldIconButton(
                    icon = if (secretVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = stringResource(
                        if (secretVisible) Res.string.settings_hide_secret else Res.string.settings_show_secret,
                    ),
                    enabled = enabled,
                    onClick = { secretVisible = !secretVisible },
                )
            }
            trailingContent?.invoke(this)
        }
        if (!supportingText.isNullOrBlank()) {
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) tokens.colors.danger else tokens.colors.textMuted,
            )
        }
    }
}

/** Ghost icon button sized to sit inside [NuvioTextField] without inflating its height. */
@Composable
fun NuvioFieldIconButton(
    icon: ImageVector,
    contentDescription: String?,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .size(FieldIconButtonSize)
            .clip(RoundedCornerShape(NuvioTokens.Radius.md))
            .background(if (hovered && enabled) tokens.colors.overlayHover else Color.Transparent)
            .hoverable(interactionSource, enabled)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onTap = { onClick() })
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (hovered) tokens.colors.textPrimary else tokens.colors.textMuted,
            modifier = Modifier.size(NuvioTokens.Icon.md),
        )
    }
}

/**
 * The app's number input: `[ -  value  + ]` as one bordered unit rather than three loose parts.
 *
 * Holding either end auto-repeats and accelerates, so walking a value a long way no longer means
 * twenty clicks. Clicking the number turns it into an inline field, because stepping to 900 in
 * fives is not a real option; Enter or clicking away commits, Escape reverts.
 */
@Composable
fun NuvioNumberStepper(
    value: Double,
    step: Double,
    min: Double,
    max: Double,
    onChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    format: (Double) -> String = { it.toInt().toString() },
    editFormat: (Double) -> String = format,
    valueColor: Color? = null,
    fieldWidth: Dp = 62.dp,
) {
    val tokens = MaterialTheme.nuvio
    var editing by remember { mutableStateOf(false) }
    // TextFieldValue rather than String so the whole number starts out selected - typing then
    // replaces it, which is what you want when swapping 40 for 500.
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    // A newly composed text field reports "not focused" once, before requestFocus() has had a
    // chance to run. Without this latch that first report reads as "focus lost" and ends the edit
    // immediately - the field appears for a single frame and closes again.
    var hasGainedFocus by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val resolvedValueColor = valueColor
        ?: if (enabled) tokens.colors.textPrimary else tokens.colors.textDisabled

    fun commit() {
        draft.text.trim().replace(",", ".").toDoubleOrNull()
            ?.coerceIn(min, max)
            ?.let(onChange)
        editing = false
    }

    fun startEditing() {
        val text = editFormat(value)
        draft = TextFieldValue(text = text, selection = TextRange(0, text.length))
        hasGainedFocus = false
        editing = true
    }

    LaunchedEffect(editing) {
        if (editing) runCatching { focusRequester.requestFocus() }
    }

    val stepperAccentEnd = tokens.colors.accentGradientEnd
    val borderColor by animateColorAsState(
        targetValue = if (editing) tokens.colors.accent else tokens.colors.borderDefault,
        animationSpec = tween(tokens.motion.fastMillis, easing = tokens.motion.standard),
        label = "nuvioStepperBorder",
    )
    val borderEndColor by animateColorAsState(
        targetValue = if (editing) {
            stepperAccentEnd ?: tokens.colors.accent
        } else {
            tokens.colors.borderDefault
        },
        animationSpec = tween(tokens.motion.fastMillis, easing = tokens.motion.standard),
        label = "nuvioStepperBorderEnd",
    )
    val borderBrush = if (stepperAccentEnd != null) {
        Brush.horizontalGradient(listOf(borderColor, borderEndColor))
    } else {
        SolidColor(borderColor)
    }

    Row(
        modifier = modifier
            .height(StepperHeight)
            .clip(FieldShape)
            .background(tokens.colors.surfaceCard)
            .border(
                width = if (editing) tokens.borders.medium else tokens.borders.thin,
                brush = borderBrush,
                shape = FieldShape,
            )
            .alpha(if (enabled) NuvioTokens.Opacity.visible else tokens.opacity.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NuvioStepperButton(
            icon = Icons.Rounded.Remove,
            enabled = enabled && !editing && value > min,
            onStep = { onChange((value - step).coerceIn(min, max)) },
        )
        Box(
            modifier = Modifier.width(fieldWidth).fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            // Identical style whether static or editing, so the control does not jump when clicked.
            val valueStyle: TextStyle = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                color = resolvedValueColor,
            )
            if (editing) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    enabled = enabled,
                    singleLine = true,
                    textStyle = valueStyle,
                    cursorBrush = SolidColor(resolvedValueColor),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commit(); focusManager.clearFocus() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        // Clicking away commits rather than cancels - losing an edit because the
                        // pointer moved would be worse than accepting what was typed.
                        .onFocusChanged { state ->
                            if (state.isFocused) {
                                hasGainedFocus = true
                            } else if (hasGainedFocus && editing) {
                                commit()
                            }
                        }
                        .onPreviewKeyEvent { event ->
                            when {
                                event.type != KeyEventType.KeyDown -> false
                                event.key == Key.Escape -> {
                                    editing = false
                                    focusManager.clearFocus()
                                    true
                                }
                                event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                                    commit()
                                    focusManager.clearFocus()
                                    true
                                }
                                else -> false
                            }
                        }
                        // Keeps app-wide keyboard shortcuts from firing on the digits being typed.
                        .trackTextInputFocus(),
                )
            } else {
                NuvioStepperValue(
                    text = format(value),
                    style = valueStyle,
                    enabled = enabled,
                    onClick = { startEditing() },
                )
            }
        }
        NuvioStepperButton(
            icon = Icons.Rounded.Add,
            enabled = enabled && !editing && value < max,
            onStep = { onChange((value + step).coerceIn(min, max)) },
        )
    }
}

@Composable
private fun NuvioStepperValue(
    text: String,
    style: TextStyle,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .background(if (hovered && enabled) tokens.colors.overlayHover else Color.Transparent)
            .hoverable(interactionSource, enabled)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onTap = { onClick() })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** One end of [NuvioNumberStepper]: a single step on tap, accelerating repeat while held. */
@Composable
private fun NuvioStepperButton(
    icon: ImageVector,
    enabled: Boolean,
    onStep: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val scope = rememberCoroutineScope()
    // The hold loop outlives the composition that started it, so it has to read the *current* step
    // lambda - the captured one would keep re-applying the value the button had when first pressed.
    val currentStep by rememberUpdatedState(onStep)
    val currentEnabled by rememberUpdatedState(enabled)

    Box(
        modifier = Modifier
            .width(StepperButtonWidth)
            .fillMaxHeight()
            .background(if (hovered && enabled) tokens.colors.overlayHover else Color.Transparent)
            .hoverable(interactionSource, enabled)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        currentStep()
                        val repeat = scope.launch {
                            delay(HoldRepeatDelayMillis)
                            var interval = HoldRepeatIntervalMillis
                            while (isActive && currentEnabled) {
                                currentStep()
                                interval = (interval * HoldRepeatDecay).toLong()
                                    .coerceAtLeast(HoldRepeatMinIntervalMillis)
                                delay(interval)
                            }
                        }
                        tryAwaitRelease()
                        repeat.cancel()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = when {
                !enabled -> tokens.colors.textDisabled
                hovered -> tokens.colors.textPrimary
                else -> tokens.colors.textSecondary
            },
            modifier = Modifier.size(NuvioTokens.Icon.sm),
        )
    }
}
