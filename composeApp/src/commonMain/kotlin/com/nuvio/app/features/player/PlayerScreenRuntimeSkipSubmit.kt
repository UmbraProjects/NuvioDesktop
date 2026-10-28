package com.nuvio.app.features.player

import com.nuvio.app.features.player.skip.CHAPTER_SKIP_PROVIDER
import com.nuvio.app.features.player.skip.SKIP_CAPTURE_MIN_SPAN_SEC
import com.nuvio.app.features.player.skip.SkipCaptureSession
import com.nuvio.app.features.player.skip.SkipInterval
import com.nuvio.app.features.player.skip.SkipIntroRepository
import com.nuvio.app.features.player.skip.SkipKeyActions
import com.nuvio.app.features.player.skip.SkipSubmitOffer
import com.nuvio.app.features.player.skip.SkipSubmitToastPhase
import com.nuvio.app.features.player.skip.identityKey
import com.nuvio.app.features.player.skip.inferCapturedSegmentType
import com.nuvio.app.features.player.skip.isOfferableSkipKind
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import com.nuvio.app.core.i18n.localizedString

/**
 * One-press SkipDB contribution: the post-skip offer and capture mode. Both front the same HUD
 * toast and the same key as the skip prompt, so the whole thing is a small state machine over
 * [PlayerScreenRuntime.skipSubmitOffer] and [PlayerScreenRuntime.skipCaptureSession], with
 * [resolveSkipKeyAction] deciding what a press of that key means right now.
 *
 * Neither is a new opt-in: setting up a SkipDB key is the opt-in, and without one nothing here
 * ever shows. The Submit Timestamps panel stays as the manual route for everything this cannot
 * express (recaps, previews, a kind other than the inferred one).
 */

/** Whether a segment can be sent at all: submitting is on, a SkipDB key is set, and the title has
 *  an IMDb id to file it under. IntroDB-only keys do not count — that is not the database this
 *  contributes to, and [SkipIntroRepository.submitSegment] would send nothing to SkipDB. */
internal fun PlayerScreenRuntime.canContributeSkipSegments(): Boolean =
    playerSettingsUiState.introSubmitEnabled &&
        playerSettingsUiState.skipDbApiKey.isNotBlank() &&
        !isProviderDiagnosticVideoPlayback &&
        !activeSubmitIntroImdbId().isNullOrBlank()

/**
 * Called after a skip lands. A chapter-sourced intro/recap becomes an offer; anything else does
 * not. A running capture is abandoned either way: the prompt covered the segment being marked.
 */
internal fun PlayerScreenRuntime.offerSkipSubmissionAfterSkip(interval: SkipInterval) {
    skipCaptureSession = null
    if (interval.provider != CHAPTER_SKIP_PROVIDER) return
    if (!interval.type.isOfferableSkipKind()) return
    if (!canContributeSkipSegments()) return
    if (interval.identityKey() in submittedSkipSegments) return
    skipSubmitOffer = SkipSubmitOffer(interval)
}

internal fun PlayerScreenRuntime.submitSkipOffer() {
    val offer = skipSubmitOffer ?: return
    if (offer.phase != SkipSubmitToastPhase.OFFER) return
    val interval = offer.interval
    skipSubmitOffer = offer.copy(phase = SkipSubmitToastPhase.SUBMITTING)
    submitSkipSegment(interval.startTime, interval.endTime, interval.type) { accepted, message ->
        if (accepted) submittedSkipSegments += interval.identityKey()
        // Only if this is still the offer being submitted — an episode change clears it meanwhile.
        val current = skipSubmitOffer
        if (current?.interval == interval) {
            skipSubmitOffer = current.copy(
                phase = SkipSubmitToastPhase.RESULT,
                resultMessage = message,
                resultAccepted = accepted,
            )
        }
    }
}

internal fun PlayerScreenRuntime.startSkipCapture() {
    if (!canContributeSkipSegments()) return
    skipSubmitOffer = null
    skipCaptureSession = SkipCaptureSession(startSec = playbackSnapshot.positionMs / 1000.0)
}

/** Second press: closes the span. A press too close to the start is ignored rather than
 *  producing a segment SkipDB would reject anyway; the session keeps waiting. */
internal fun PlayerScreenRuntime.markSkipCaptureEnd() {
    val session = skipCaptureSession ?: return
    if (session.phase != SkipSubmitToastPhase.CAPTURING) return
    val endSec = playbackSnapshot.positionMs / 1000.0
    if (endSec < session.startSec + SKIP_CAPTURE_MIN_SPAN_SEC) return
    val durationSec = playbackSnapshot.durationMs.takeIf { it > 0L }?.let { it / 1000.0 }
    skipCaptureSession = session.copy(
        endSec = endSec,
        phase = SkipSubmitToastPhase.CAPTURED,
        segmentType = inferCapturedSegmentType(session.startSec, durationSec),
    )
}

internal fun PlayerScreenRuntime.submitSkipCapture() {
    val session = skipCaptureSession ?: return
    if (session.phase != SkipSubmitToastPhase.CAPTURED) return
    val endSec = session.endSec ?: return
    val type = session.segmentType ?: return
    skipCaptureSession = session.copy(phase = SkipSubmitToastPhase.SUBMITTING)
    submitSkipSegment(session.startSec, endSec, type) { accepted, message ->
        if (accepted) {
            submittedSkipSegments += SkipInterval(session.startSec, endSec, type, CHAPTER_SKIP_PROVIDER).identityKey()
        }
        if (skipCaptureSession?.startSec == session.startSec) {
            skipCaptureSession = session.copy(
                phase = SkipSubmitToastPhase.RESULT,
                resultMessage = message,
                resultAccepted = accepted,
            )
        }
    }
}

/** Escape / right-click: drops whatever the toast is asking. A result is left to time out. */
internal fun PlayerScreenRuntime.dismissSkipSubmitToast() {
    if (skipSubmitOffer?.phase == SkipSubmitToastPhase.OFFER) skipSubmitOffer = null
    when (skipCaptureSession?.phase) {
        SkipSubmitToastPhase.CAPTURING, SkipSubmitToastPhase.CAPTURED -> skipCaptureSession = null
        else -> Unit
    }
}

/** True while Escape should dismiss the toast instead of leaving the player. */
internal fun PlayerScreenRuntime.isSkipSubmitToastDismissible(): Boolean =
    skipSubmitOffer?.phase == SkipSubmitToastPhase.OFFER ||
        skipCaptureSession?.phase == SkipSubmitToastPhase.CAPTURING ||
        skipCaptureSession?.phase == SkipSubmitToastPhase.CAPTURED

/**
 * What the skip key does right now, in priority order. The skip prompt wins over a running
 * capture on purpose: a reflexive early press as an intro begins starts a capture, and the press
 * that follows once the prompt appears must still skip (and the pointless capture is dropped by
 * [offerSkipSubmissionAfterSkip]). Capture beats the next-episode card because that card is on
 * screen exactly when an outro's end mark is being placed. Starting a capture is last: only a
 * press with nothing else to act on begins one.
 */
internal fun PlayerScreenRuntime.resolveSkipKeyAction(
    skipPromptActionable: Boolean,
    nextEpisodeActionable: Boolean,
): String {
    if (skipPromptActionable) return SkipKeyActions.SKIP_INTERVAL
    when (skipCaptureSession?.phase) {
        SkipSubmitToastPhase.CAPTURING -> return SkipKeyActions.CAPTURE_MARK_END
        SkipSubmitToastPhase.CAPTURED -> return SkipKeyActions.CAPTURE_SUBMIT
        SkipSubmitToastPhase.SUBMITTING -> return ""
        else -> Unit
    }
    when (skipSubmitOffer?.phase) {
        SkipSubmitToastPhase.OFFER -> return SkipKeyActions.SUBMIT_OFFER
        SkipSubmitToastPhase.SUBMITTING -> return ""
        else -> Unit
    }
    if (nextEpisodeActionable) return SkipKeyActions.PLAY_NEXT_EPISODE
    // A result still on screen is not a reason to start marking; the press that would has to
    // wait the few seconds until it clears.
    if (skipSubmitOffer != null || skipCaptureSession != null) return ""
    if (canContributeSkipSegments() && initialLoadCompleted && !playerControlsLocked) {
        return SkipKeyActions.CAPTURE_START
    }
    return ""
}

/** Routes the key actions above (and the toast's own clicks) back into the state machine. */
internal fun PlayerScreenRuntime.handleSkipSubmitEvent(type: String): Boolean {
    when (type) {
        SkipKeyActions.SUBMIT_OFFER -> submitSkipOffer()
        SkipKeyActions.CAPTURE_START -> startSkipCapture()
        SkipKeyActions.CAPTURE_MARK_END -> markSkipCaptureEnd()
        SkipKeyActions.CAPTURE_SUBMIT -> submitSkipCapture()
        "skipSubmitDismiss" -> dismissSkipSubmitToast()
        else -> return false
    }
    return true
}

/** The HUD toast's copy. `{key}` is the skip key's label, filled in by the HUD, which is the only
 *  side that knows the binding. */
data class SkipSubmitToastCopy(
    val visible: Boolean = false,
    val phase: String = "",
    val title: String = "",
    val detail: String = "",
    val hint: String = "",
    val accepted: Boolean = false,
    /** Changes whenever a different offer/session/phase is shown, so the HUD restarts its timer. */
    val key: String = "",
)

internal fun PlayerScreenRuntime.skipSubmitToastCopy(): SkipSubmitToastCopy {
    val offer = skipSubmitOffer
    val session = skipCaptureSession
    val range = { start: Double, end: Double -> "${formatPlayerControlsSeconds(start)} – ${formatPlayerControlsSeconds(end)}" }
    val kindLabel = { type: String -> type.replaceFirstChar { it.uppercaseChar() } }
    if (offer != null) {
        val interval = offer.interval
        val kind = kindLabel(interval.type)
        return when (offer.phase) {
            SkipSubmitToastPhase.OFFER -> SkipSubmitToastCopy(
                visible = true,
                phase = "offer",
                title = localizedString(Res.string.skip_submit_skipped_from_chapters, kind),
                detail = range(interval.startTime, interval.endTime),
                hint = localizedString(Res.string.skip_submit_offer_hint),
                key = "offer:${interval.identityKey()}",
            )
            SkipSubmitToastPhase.SUBMITTING -> SkipSubmitToastCopy(
                visible = true,
                phase = "submitting",
                title = localizedString(Res.string.skip_submit_submitting_to_skipdb),
                detail = "$kind ${range(interval.startTime, interval.endTime)}",
                key = "offer-submitting:${interval.identityKey()}",
            )
            else -> resultCopy(offer.resultAccepted, offer.resultMessage, "offer-result:${interval.identityKey()}")
        }
    }
    if (session != null) {
        val start = session.startSec
        return when (session.phase) {
            SkipSubmitToastPhase.CAPTURING -> SkipSubmitToastCopy(
                visible = true,
                phase = "capturing",
                title = localizedString(Res.string.skip_submit_capturing_segment),
                detail = localizedString(Res.string.skip_submit_capture_from, formatPlayerControlsSeconds(start)),
                hint = localizedString(Res.string.skip_submit_capture_hint),
                key = "capture:$start",
            )
            SkipSubmitToastPhase.CAPTURED -> SkipSubmitToastCopy(
                visible = true,
                phase = "captured",
                title = localizedString(Res.string.skip_submit_confirm_title, kindLabel(session.segmentType.orEmpty())),
                detail = range(start, session.endSec ?: start),
                hint = localizedString(Res.string.skip_submit_confirm_hint),
                key = "captured:$start",
            )
            SkipSubmitToastPhase.SUBMITTING -> SkipSubmitToastCopy(
                visible = true,
                phase = "submitting",
                title = localizedString(Res.string.skip_submit_submitting_to_skipdb),
                detail = "${kindLabel(session.segmentType.orEmpty())} ${range(start, session.endSec ?: start)}",
                key = "capture-submitting:$start",
            )
            else -> resultCopy(session.resultAccepted, session.resultMessage, "capture-result:$start")
        }
    }
    return playerNoticeToast ?: SkipSubmitToastCopy()
}

private fun resultCopy(accepted: Boolean, message: String, key: String) = SkipSubmitToastCopy(
    visible = true,
    phase = "result",
    title = if (accepted) localizedString(Res.string.skip_submit_sent_to_skipdb) else localizedString(Res.string.skip_submit_not_submitted),
    // SkipDB explains itself — an overlap, a failed validation, a rate limit — and that is far
    // more actionable than a generic failure line.
    detail = message,
    accepted = accepted,
    key = key,
)

/**
 * The one submission path behind the offer, capture mode and the Submit Timestamps panel. The
 * runtime goes with it exactly as with a lookup: it is what lets SkipDB match these timings to the
 * right cut later.
 */
internal fun PlayerScreenRuntime.submitSkipSegment(
    startSec: Double,
    endSec: Double,
    segmentType: String,
    onOutcome: (accepted: Boolean, message: String) -> Unit,
) {
    val imdbId = activeSubmitIntroImdbId()
    if (imdbId.isNullOrBlank() || endSec <= startSec) {
        onOutcome(false, localizedString(Res.string.skip_submit_check_the_start_and_end_times))
        return
    }
    val season = activeSeasonNumber
    val episode = activeEpisodeNumber
    val durationSeconds = playbackSnapshot.durationMs.takeIf { it > 0L }?.let { it / 1000L }
    scope.launch {
        val outcome = SkipIntroRepository.submitSegment(
            imdbId = imdbId,
            season = season,
            episode = episode,
            startSec = startSec,
            endSec = endSec,
            segmentType = segmentType,
            durationSeconds = durationSeconds,
        )
        onOutcome(outcome.accepted, outcome.message)
    }
}
