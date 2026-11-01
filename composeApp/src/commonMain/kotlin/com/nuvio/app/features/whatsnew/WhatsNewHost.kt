package com.nuvio.app.features.whatsnew

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioDialogSurface
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.StartupOverlayCoordinator
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.discover.DiscoverAiSettingsRepository
import com.nuvio.app.features.setup.WizardPrimaryButton
import com.nuvio.app.features.setup.WizardTextButton
import com.nuvio.app.isDesktop
import kotlinx.coroutines.delay

/**
 * Decides on launch whether the "What's new" cards are due, and shows them when the screen is free.
 *
 * It claims the last slot in [StartupOverlayCoordinator], so the setup wizard, the API-key prompt
 * and the updater all go first. [canShow] is false while anything other than the tabs is on screen
 * (a player, a details page): cards are not worth covering a film for, and nothing is recorded as
 * seen until they have actually been shown and closed, so they wait.
 *
 * [onOpenSettingsPage] takes a `SettingsPage` name and brings that page up, for a card whose button
 * is "Set it up". The cards close first.
 */
@Composable
fun WhatsNewHost(canShow: Boolean, onOpenSettingsPage: (String) -> Unit) {
    if (!isDesktop) return
    val state by WhatsNewController.uiState.collectAsStateWithLifecycle()
    val visibleOverlay by StartupOverlayCoordinator.visibleOverlay.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        WhatsNewController.evaluateOnLaunch()
    }

    val wants = state.visible && canShow
    LaunchedEffect(wants) {
        StartupOverlayCoordinator.setWantsToShow(StartupOverlayCoordinator.Overlay.WhatsNew, wants)
    }

    if (!wants) return
    if (visibleOverlay != StartupOverlayCoordinator.Overlay.WhatsNew) return

    WhatsNewDialog(state, onOpenSettingsPage)
}

/** Past this many cards the progress bars would be slivers, so only "N of M" is shown. */
private const val WHATS_NEW_MAX_PROGRESS_BARS = 12

/**
 * A dialog rather than a panel: the wizard sits in Home's corner so Home can preview live choices,
 * but these cards carry a picture and need room. Built like the updater's dialog, which is proven
 * on the HTPC: Escape and the gamepad's B close it, and clicking outside does not (a stray click
 * would otherwise mark every card as seen).
 *
 * Right and Left (the D-pad) step through. Focus sits on the button Select should press: the
 * card's action ("Turn it on" or "Set it up") while one is waiting, otherwise Next, which finishes
 * on the last card. After an action is taken, focus moves on to Next.
 */
@Composable
private fun WhatsNewDialog(state: WhatsNewUiState, onOpenSettingsPage: (String) -> Unit) {
    val tokens = MaterialTheme.nuvio
    val nextFocus = remember { FocusRequester() }
    val actionFocus = remember { FocusRequester() }
    val cardCount = state.cards.size

    // What the current card's button is. The consent test mirrors the Pre-show page: with an AI key
    // set and no agreement given, that page must ask first, so the card opens it instead of
    // flipping the switch behind its back.
    remember { DiscoverAiSettingsRepository.ensureLoaded() }
    val ai by DiscoverAiSettingsRepository.uiState.collectAsStateWithLifecycle()
    val action = state.current?.action ?: WhatsNewAction.None
    val toggle = (action as? WhatsNewAction.TurnOn)?.let { whatsNewToggleFor(it.featureKey) }
    val isOn = toggle?.isOn?.invoke() ?: false
    val button = whatsNewButtonFor(
        action = action,
        isOn = isOn,
        needsConsent = toggle?.needsAiConsent == true && ai.apiKey.isNotBlank() && !ai.consentGiven,
    )
    val actionPending = button == WhatsNewButton.TurnOn || button == WhatsNewButton.SetUp
    val advance: () -> Unit = { if (state.isLast) WhatsNewController.close() else WhatsNewController.next() }
    val nextLabel = if (state.isLast) "Done" else "Next"

    Dialog(
        onDismissRequest = WhatsNewController::close,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        // Focus the button Select should press, again whenever the card or its action changes.
        // Retried because the first frames may not have attached it yet, as in the setup wizard.
        LaunchedEffect(state.index, actionPending) {
            val target = if (actionPending) actionFocus else nextFocus
            repeat(12) { attempt ->
                delay(if (attempt == 0) 50 else 48)
                if (runCatching { target.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        }

        NuvioDialogSurface(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 24.dp)
                .widthIn(max = 640.dp)
                .fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    // After the buttons have had their turn (Select on the focused one acts first).
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (event.key) {
                            Key.DirectionRight -> {
                                WhatsNewController.next()
                                true
                            }
                            Key.DirectionLeft -> {
                                WhatsNewController.back()
                                true
                            }
                            else -> false
                        }
                    }
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "WHAT'S NEW",
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.colors.textMuted,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    if (cardCount > 1) {
                        Text(
                            text = "${state.index + 1} of $cardCount",
                            style = MaterialTheme.typography.labelMedium,
                            color = tokens.colors.textMuted,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                if (cardCount in 2..WHATS_NEW_MAX_PROGRESS_BARS) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
                    ) {
                        repeat(cardCount) { index ->
                            val color by animateColorAsState(
                                if (index <= state.index) tokens.colors.accent else Color.White.copy(alpha = 0.12f),
                                label = "whats_new_progress",
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

                AnimatedContent(
                    targetState = state.index,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "whats_new_card",
                ) { index ->
                    val entry = state.cards.getOrNull(index)
                    if (entry != null) {
                        val spotlight = index == 0
                        Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12)) {
                            CardArt(spotlight = spotlight)
                            Text(
                                text = entry.title,
                                style = if (spotlight) {
                                    MaterialTheme.typography.headlineSmall
                                } else {
                                    MaterialTheme.typography.titleLarge
                                },
                                color = tokens.colors.textPrimary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = entry.pitch,
                                style = MaterialTheme.typography.bodyLarge,
                                color = tokens.colors.textSecondary,
                            )
                        }
                    }
                }

                if (state.isLast && state.moreCount > 0) {
                    Text(
                        text = "${state.moreCount} more in Settings",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
                ) {
                    if (cardCount > 1) {
                        if (state.isFirst) {
                            WizardTextButton(text = "Skip", onClick = WhatsNewController::close)
                        } else {
                            WizardTextButton(
                                text = "Back",
                                onClick = WhatsNewController::back,
                                emphasised = true,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    when (button) {
                        WhatsNewButton.TurnOn, WhatsNewButton.SetUp -> {
                            // The action is the accent button; moving on drops to a quiet one.
                            WizardTextButton(
                                text = nextLabel,
                                onClick = advance,
                                modifier = Modifier.focusRequester(nextFocus),
                            )
                            WizardPrimaryButton(
                                text = if (button == WhatsNewButton.TurnOn) "Turn it on" else "Set it up",
                                modifier = Modifier.focusRequester(actionFocus),
                                onClick = {
                                    if (button == WhatsNewButton.TurnOn) {
                                        toggle?.turnOn?.invoke()
                                    } else {
                                        // Closing records every card as seen, then the page opens.
                                        // The cards come back when the person leaves that page.
                                        val page = whatsNewSettingsPage(action)
                                        if (page != null) {
                                            WhatsNewController.closeToSettingsPage(page)
                                            onOpenSettingsPage(page)
                                        } else {
                                            WhatsNewController.close()
                                        }
                                    }
                                },
                            )
                        }
                        WhatsNewButton.On -> {
                            Text(
                                text = "On",
                                style = MaterialTheme.typography.labelLarge,
                                color = tokens.colors.accent,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = NuvioTokens.Space.s8),
                            )
                            WizardPrimaryButton(
                                text = nextLabel,
                                modifier = Modifier.focusRequester(nextFocus),
                                onClick = advance,
                            )
                        }
                        null -> WizardPrimaryButton(
                            text = nextLabel,
                            modifier = Modifier.focusRequester(nextFocus),
                            onClick = advance,
                        )
                    }
                }

                if (cardCount > 1) {
                    Text(
                        text = "Left and Right to browse. Back closes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                    )
                }
            }
        }
    }
}

/**
 * The card's picture, drawn from the app's own accent so there is nothing to ship: a soft accent
 * glow with a large sparkle and two small ones. Static, so there is no motion to reduce.
 */
@Composable
private fun CardArt(spotlight: Boolean, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.nuvio.colors.accent
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(if (spotlight) 150.dp else 96.dp)
            .clip(RoundedCornerShape(NuvioTokens.Radius.md)),
    ) {
        drawRect(
            brush = Brush.verticalGradient(
                listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.10f)),
            ),
        )
        val centreX = size.width / 2f
        val centreY = size.height / 2f
        val radius = size.height * 0.34f
        drawSparkle(centreX, centreY, radius, Color.White.copy(alpha = 0.92f))
        drawSparkle(centreX + size.height * 0.62f, centreY - size.height * 0.18f, radius * 0.42f, Color.White.copy(alpha = 0.60f))
        drawSparkle(centreX - size.height * 0.70f, centreY + size.height * 0.20f, radius * 0.30f, Color.White.copy(alpha = 0.45f))
    }
}

/** A four-pointed star: straight edges, so it needs nothing beyond move, line and close. */
private fun DrawScope.drawSparkle(centreX: Float, centreY: Float, radius: Float, color: Color) {
    val waist = radius * 0.22f
    val path = Path().apply {
        moveTo(centreX, centreY - radius)
        lineTo(centreX + waist, centreY - waist)
        lineTo(centreX + radius, centreY)
        lineTo(centreX + waist, centreY + waist)
        lineTo(centreX, centreY + radius)
        lineTo(centreX - waist, centreY + waist)
        lineTo(centreX - radius, centreY)
        lineTo(centreX - waist, centreY - waist)
        close()
    }
    drawPath(path = path, color = color)
}
