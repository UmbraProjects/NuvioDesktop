package com.nuvio.app.features.setup

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.StartupOverlayCoordinator
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioPanelBackdrop
import com.nuvio.app.features.home.HomeDisplayMode
import com.nuvio.app.isDesktop
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_back
import nuvio.composeapp.generated.resources.action_continue
import nuvio.composeapp.generated.resources.setup_wizard_finish
import nuvio.composeapp.generated.resources.setup_wizard_finish_later
import nuvio.composeapp.generated.resources.setup_wizard_skip_setup
import nuvio.composeapp.generated.resources.setup_wizard_step_of
import org.jetbrains.compose.resources.stringResource

/**
 * Width below which the mode cards stack 2×2 instead of sitting in one row of four. Chosen so a
 * 1280×720 window still gets four columns and only genuinely narrow windows fall back.
 */
private val CompactWizardWidth = 1180.dp

@Composable
fun FirstRunWizardHost(
    modifier: Modifier = Modifier,
    onOpenKeyboardShortcuts: () -> Unit = {},
) {
    if (!isDesktop) return

    val state by FirstRunWizardController.uiState.collectAsStateWithLifecycle()
    val visibleOverlay by StartupOverlayCoordinator.visibleOverlay.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        FirstRunWizardController.evaluateOnLaunch()
    }
    LaunchedEffect(state.visible) {
        StartupOverlayCoordinator.setWantsToShow(StartupOverlayCoordinator.Overlay.SetupWizard, state.visible)
    }

    if (!state.visible) return
    if (visibleOverlay != StartupOverlayCoordinator.Overlay.SetupWizard) return

    val tokens = MaterialTheme.nuvio
    val density = LocalDensity.current
    var isCompact by remember { mutableStateOf(false) }
    var expandedPreview by remember { mutableStateOf<HomeDisplayMode?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            // Swallows clicks on the scrim so the app underneath cannot be operated behind the
            // wizard, and gives the surface a focus owner for Escape.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    if (expandedPreview != null) expandedPreview = null else FirstRunWizardController.close()
                    true
                } else {
                    false
                }
            }
            .onGloballyPositioned { coordinates ->
                isCompact = with(density) { coordinates.size.width.toDp() } < CompactWizardWidth
            },
        contentAlignment = Alignment.Center,
    ) {
        val panelShape = RoundedCornerShape(NuvioTokens.Radius.lg)
        Surface(
            modifier = Modifier
                .widthIn(max = 1400.dp)
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.92f)
                .heightIn(max = 1000.dp)
                .nuvioPanelBackdrop(tokens.colors.surfaceDialog, panelShape),
            color = Color.Transparent,
            shape = panelShape,
            shadowElevation = NuvioTokens.Space.s8,
        ) {
            Column(modifier = Modifier.padding(NuvioTokens.Space.s32)) {
                StepIndicator(step = state.step)
                Spacer(modifier = Modifier.height(NuvioTokens.Space.s24))

                Box(modifier = Modifier.weight(1f)) {
                    Crossfade(targetState = state.step, label = "setup_wizard_step") { step ->
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            when (step) {
                                FirstRunWizardStep.Experience -> WizardExperienceStep(
                                    draft = state.draft,
                                    onDraftChange = FirstRunWizardController::updateDraft,
                                    compact = isCompact,
                                    onExpandPreview = { expandedPreview = it },
                                )

                                FirstRunWizardStep.Metadata -> WizardMetadataStep(
                                    draft = state.draft,
                                    onDraftChange = FirstRunWizardController::updateDraft,
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(NuvioTokens.Space.s20))
                WizardFooter(
                    state = state,
                    onFinish = {
                        FirstRunWizardController.finish()
                        // The wizard never taught the shortcuts itself; it hands the user to the
                        // page that owns them, which is also where the arrows/WASD choice lives.
                        onOpenKeyboardShortcuts()
                    },
                )
            }
        }

        expandedPreview?.let { mode ->
            ModePreviewLightbox(mode = mode, onDismiss = { expandedPreview = null })
        }
    }
}

@Composable
private fun StepIndicator(step: FirstRunWizardStep) {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
        Text(
            text = stringResource(
                Res.string.setup_wizard_step_of,
                step.index + 1,
                FirstRunWizardStep.count,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = tokens.colors.textMuted,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
        ) {
            FirstRunWizardStep.ordered.forEach { candidate ->
                val reached = candidate.index <= step.index
                val color by animateColorAsState(
                    if (reached) tokens.colors.accent else tokens.colors.surfaceElevated,
                    label = "setup_wizard_step_dot",
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
    }
}

@Composable
private fun WizardFooter(state: FirstRunWizardUiState, onFinish: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
    ) {
        // Two different exits on a first run: "Skip setup" is a decision and marks the wizard done,
        // while "Finish later" just closes, so an unfinished first run comes back next launch. A
        // rerun has nothing to decide — closing it is the only exit.
        if (!state.isRerun) {
            FooterLink(
                text = stringResource(Res.string.setup_wizard_skip_setup),
                onClick = FirstRunWizardController::skip,
            )
        }
        FooterLink(
            text = stringResource(Res.string.setup_wizard_finish_later),
            onClick = FirstRunWizardController::close,
        )

        Spacer(modifier = Modifier.weight(1f))

        if (!state.isFirstStep) {
            FooterLink(
                text = stringResource(Res.string.action_back),
                onClick = FirstRunWizardController::back,
                emphasised = true,
            )
        }

        WizardPrimaryButton(
            text = if (state.isLastStep) {
                stringResource(Res.string.setup_wizard_finish)
            } else {
                stringResource(Res.string.action_continue)
            },
            onClick = { if (state.isLastStep) onFinish() else FirstRunWizardController.next() },
        )
    }
}

@Composable
private fun FooterLink(text: String, onClick: () -> Unit, emphasised: Boolean = false) {
    val tokens = MaterialTheme.nuvio
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (emphasised) tokens.colors.textSecondary else tokens.colors.textMuted,
        fontWeight = if (emphasised) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = NuvioTokens.Space.s10, vertical = NuvioTokens.Space.s8),
    )
}
