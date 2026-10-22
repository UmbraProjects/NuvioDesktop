package com.nuvio.app.features.player.audiosync

import kotlin.math.abs

/**
 * Desktop-only addition to [AudioSyncTracker]. The tracker withdraws an early estimate that is
 * still unconfirmed after ~35 dialogue lines, assuming (as on Android) that live audio would have
 * locked a real match by then. Desktop only has sampled spots, which arrive in rounds, so a real
 * match can pass that point a few seconds before it locks, and the subtitles would jump back to
 * their own timing and then forward again. When the estimate the tracker withdrew still agrees with
 * the applied one, it stays on screen; it goes once newer audio keeps contradicting it.
 */
internal class EstimateHold {
    /** The estimate kept on screen past the tracker's withdrawal, or null. */
    var held: SubtitleSyncModel? = null
        private set

    private var disagreeingRuns = 0

    /** The tracker withdrew [applied] because of [estimate]. True: keep showing it. */
    fun onWithdrawn(applied: SubtitleSyncModel, estimate: SubtitleAudioAligner.Estimate): Boolean {
        disagreeingRuns = 0
        held = applied.takeIf { agrees(it, estimate, AGREEMENT_MS) }
        return held != null
    }

    /** Another unconfirmed run with [estimate]. True: the held estimate must go now. */
    fun onSearching(estimate: SubtitleAudioAligner.Estimate?): Boolean {
        val model = held ?: return false
        if (estimate == null) return false
        disagreeingRuns = if (agrees(model, estimate, DISAGREEMENT_MS)) 0 else disagreeingRuns + 1
        if (disagreeingRuns < DISAGREEING_RUNS) return false
        clear()
        return true
    }

    fun clear() {
        held = null
        disagreeingRuns = 0
    }

    private fun agrees(model: SubtitleSyncModel, estimate: SubtitleAudioAligner.Estimate, toleranceMs: Double): Boolean {
        val segment = model.segments.first()
        return segment.scale == estimate.scale && abs(segment.shiftMs - estimate.shiftMs) <= toleranceMs
    }

    private companion object {
        // The tracker's own agreement window for early estimates (ProvisionalPolicy.agreementMs).
        const val AGREEMENT_MS = 300.0

        // The tracker's own retraction rule (RETRACT_DISAGREEMENT_MS / RETRACT_DISAGREEING_RUNS).
        const val DISAGREEMENT_MS = 1_000.0
        const val DISAGREEING_RUNS = 3
    }
}
