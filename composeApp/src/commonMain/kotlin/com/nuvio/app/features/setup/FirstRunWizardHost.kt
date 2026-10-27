package com.nuvio.app.features.setup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.LocalScreenCovered
import com.nuvio.app.core.ui.NuvioGlass
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.StartupOverlayCoordinator
import com.nuvio.app.core.ui.ditheredBackground
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.isDesktop
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_back
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.action_continue
import nuvio.composeapp.generated.resources.setup_wizard_finish
import nuvio.composeapp.generated.resources.setup_wizard_finish_later
import nuvio.composeapp.generated.resources.setup_wizard_skip_setup
import nuvio.composeapp.generated.resources.setup_wizard_step_of
import org.jetbrains.compose.resources.stringResource

/** Narrow enough to leave most of Home visible beside it, wide enough for a row and its subtext. */
val FirstRunWizardPanelWidth: Dp = 400.dp

/**
 * Nearly solid: the panel carries a lot of text, and over a busy Home the see-through glass made
 * it hard to read. The edge, the fill and a hint of what is behind still read as the app's glass.
 */
private const val PanelGlassOpacity = 0.95f

/**
 * Decides on launch whether setup runs, and claims the startup-overlay slot while it does so the
 * API-key prompt and updater wait their turn. Draws nothing: the panel itself is
 * [FirstRunWizardPanel], hosted over Home. [onShowHome] brings Home forward whenever setup opens —
 * from Settings it would otherwise be open behind the Settings tab, where nobody can see it.
 */
@Composable
fun FirstRunWizardHost(onShowHome: () -> Unit) {
    if (!isDesktop) return
    val state by FirstRunWizardController.uiState.collectAsStateWithLifecycle()
    val visible = state.visible

    LaunchedEffect(Unit) {
        FirstRunWizardController.evaluateOnLaunch()
    }
    LaunchedEffect(visible) {
        StartupOverlayCoordinator.setWantsToShow(StartupOverlayCoordinator.Overlay.SetupWizard, visible)
        if (visible) onShowHome()
    }
}

/**
 * The setup panel: glass in Home's top-right corner, the emptiest area in every display mode.
 *
 * Every choice is written as it is made, so Home behind the panel is the preview. The panel takes
 * the keyboard when it opens and moves focus to the top of each step it shows; arrows (and the
 * D-pad, which arrives as arrows) walk its rows. It is a sibling of Home rather than part of it, so
 * Home's own key handling never sees these keys, and Home holds back its focus requests while the
 * panel is open (it re-renders under every mode change, and would otherwise take focus back).
 * Clicking Home still moves focus there; nothing here traps it.
 */
@Composable
fun FirstRunWizardPanel(
    modifier: Modifier = Modifier,
    onOpenKeyboardShortcuts: () -> Unit = {},
) {
    if (!isDesktop) return

    val state by FirstRunWizardController.uiState.collectAsStateWithLifecycle()
    val visibleOverlay by StartupOverlayCoordinator.visibleOverlay.collectAsStateWithLifecycle()
    // Hidden, not closed, while another screen covers Home: setup resumes where it was on return.
    val covered = LocalScreenCovered.current
    if (!state.visible || covered) return
    if (visibleOverlay != StartupOverlayCoordinator.Overlay.SetupWizard) return

    val tokens = MaterialTheme.nuvio
    val focusManager = LocalFocusManager.current
    val stepFocus = remember { FocusRequester() }
    val primaryFocus = remember { FocusRequester() }
    val panelShape = NuvioGlass.Shape

    // Whenever a step opens (including the first), focus its first control. Retried because the
    // step's content arrives through an animation and the first frames may not have placed it.
    LaunchedEffect(state.step) {
        val target = if (state.step == FirstRunWizardStep.Metadata) primaryFocus else stepFocus
        repeat(12) { attempt ->
            delay(if (attempt == 0) 50 else 48)
            if (runCatching { target.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    Column(
        modifier = modifier
            .width(FirstRunWizardPanelWidth)
            .shadow(elevation = 24.dp, shape = panelShape, clip = false)
            .background(
                NuvioGlass.night(tokens.colors.background, tokens.colors.accent).copy(alpha = PanelGlassOpacity),
                panelShape,
            )
            .ditheredBackground(NuvioGlass.Fill, panelShape)
            .border(width = 1.dp, brush = NuvioGlass.Edge, shape = panelShape)
            .clip(panelShape)
            // Takes the pointer for the whole panel, so a click or wheel between rows does not
            // fall through to the poster underneath.
            .pointerInput(Unit) {}
            // After the rows have had their turn (a stepper keeps Left/Right for itself).
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                    Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                    Key.DirectionLeft -> focusManager.moveFocus(FocusDirection.Left)
                    Key.DirectionRight -> focusManager.moveFocus(FocusDirection.Right)
                    Key.Escape, Key.Back -> {
                        FirstRunWizardController.close()
                        true
                    }
                    else -> false
                }
            }
            .padding(NuvioTokens.Space.s16),
    ) {
        PanelHeader(state = state)
        Spacer(modifier = Modifier.height(NuvioTokens.Space.s12))

        Box(modifier = Modifier.weight(1f, fill = false)) {
            AnimatedContent(
                targetState = state.step,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "setup_panel_step",
            ) { step ->
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    WizardStepContent(
                        step = step,
                        draft = state.draft,
                        onDraftChange = FirstRunWizardController::updateDraft,
                        firstFocus = Modifier.focusRequester(stepFocus),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(NuvioTokens.Space.s12))
        PanelFooter(
            state = state,
            primaryFocus = primaryFocus,
            onFinish = {
                FirstRunWizardController.finish()
                // The panel never taught the shortcuts itself; it hands the user to the page that
                // owns them, which is also where the arrows/WASD choice lives.
                onOpenKeyboardShortcuts()
            },
        )
    }
}

@Composable
private fun PanelHeader(state: FirstRunWizardUiState) {
    val tokens = MaterialTheme.nuvio
    val (title, subtitle) = state.step.header()
    val steps = state.steps
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(Res.string.setup_wizard_step_of, state.stepIndex + 1, steps.size),
                style = MaterialTheme.typography.labelMedium,
                color = tokens.colors.textMuted,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            CloseButton()
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
        ) {
            steps.forEachIndexed { index, _ ->
                val color by animateColorAsState(
                    if (index <= state.stepIndex) tokens.colors.accent else Color.White.copy(alpha = 0.12f),
                    label = "setup_panel_step_dot",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(NuvioTokens.Radius.sm))
                        .background(color),
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textSecondary,
        )
    }
}

@Composable
private fun CloseButton() {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.06f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = FirstRunWizardController::close,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = stringResource(Res.string.action_close),
            tint = tokens.colors.textSecondary,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun PanelFooter(
    state: FirstRunWizardUiState,
    primaryFocus: FocusRequester,
    onFinish: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
    ) {
        // Two different exits on a first run: "Skip setup" is a decision and marks setup done,
        // while "Finish later" just closes, so an unfinished first run comes back next launch. A
        // rerun has nothing to decide — closing it is the only exit. Both put back what the
        // settings were when the panel opened.
        if (state.isFirstStep) {
            if (!state.isRerun) {
                WizardTextButton(
                    text = stringResource(Res.string.setup_wizard_skip_setup),
                    onClick = FirstRunWizardController::skip,
                )
            }
            WizardTextButton(
                text = stringResource(Res.string.setup_wizard_finish_later),
                onClick = FirstRunWizardController::close,
            )
        } else {
            WizardTextButton(
                text = stringResource(Res.string.action_back),
                onClick = FirstRunWizardController::back,
                emphasised = true,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        WizardPrimaryButton(
            text = if (state.isLastStep) {
                stringResource(Res.string.setup_wizard_finish)
            } else {
                stringResource(Res.string.action_continue)
            },
            modifier = Modifier.focusRequester(primaryFocus),
            onClick = { if (state.isLastStep) onFinish() else FirstRunWizardController.next() },
        )
    }
}
