package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The setup questionnaire: how the 8 answers compose into a points map, and the invariant that makes
 * the whole design work — each question owns a **disjoint** slice, so answering one never disturbs
 * another's traits or a manual edit.
 */
class ScoreAnswersTest {

    private fun StreamScoreProfile.points(trait: StreamScoreTrait): Int = pointsFor(trait)

    // --- defaults ---

    @Test
    fun aFreshProfileMatchesTheDefaultAnswers() {
        assertEquals(ScoreAnswers().toPointsMap(), StreamScoreProfile().points)
        assertEquals(ScoreAnswers(), StreamScoreProfile().answers)
    }

    @Test
    fun defaultAudioIsBasicAndSitsBelowHdr() {
        val p = ScoreAnswers().toPointsMap()
        // The complaint that started this: on default gear, top audio must not outweigh top HDR.
        val topAudio = p[StreamScoreTrait.AUDIO_ATMOS.id] ?: 0
        val topHdr = p[StreamScoreTrait.HDR_10.id] ?: 0
        assertTrue(topAudio in 1 until topHdr, "expected Atmos ($topAudio) to be a positive tiebreak below HDR ($topHdr)")
    }

    @Test
    fun defaultThreeDIsBuried() {
        assertEquals(-4000, ScoreAnswers().toPointsMap()[StreamScoreTrait.THREE_D.id])
    }

    // --- disjointness: the core invariant ---

    @Test
    fun everyQuestionOwnsADisjointSliceAndNoneOverlapTheBase() {
        val slices: List<Set<String>> = listOf(
            AudioDeviceSupport.BASIC.points(),
            HdrPreference.MEDIUM.points(),
            ThreeDEquipment.NO.points(),
            SizeQualityPreference.BALANCED.points(),
            LanguageImportance.MEDIUM.points(),
            DebridCachedBoost.MEDIUM.points(),
            UnknownGroupTrust.MEDIUM.points(),
            LowQualityGroupPenalty.HIGH.points(),
        ).map { slice -> slice.keys.map { it.id }.toSet() }

        slices.forEachIndexed { i, a ->
            slices.forEachIndexed { j, b ->
                if (i < j) {
                    assertTrue((a intersect b).isEmpty(), "questions $i and $j share traits: ${a intersect b}")
                }
            }
        }
    }

    // --- patching only touches the answered question ---

    @Test
    fun changingAnAnswerLeavesOtherQuestionsAndManualEditsAlone() {
        val tuned = StreamScoreProfile()
            // A manual override on an audio trait (owned by the audio question, not the HDR one).
            .withPoints(StreamScoreTrait.AUDIO_ATMOS, 500)
            // A manual override on a base trait no question owns.
            .withPoints(StreamScoreTrait.REPACK_PROPER, 250)

        val afterHdr = tuned.withHdrPreference(HdrPreference.HIGH)

        // HDR changed…
        assertEquals(100, afterHdr.points(StreamScoreTrait.HDR_DOLBY_VISION))
        // …but the audio override and the base override are untouched.
        assertEquals(500, afterHdr.points(StreamScoreTrait.AUDIO_ATMOS))
        assertEquals(250, afterHdr.points(StreamScoreTrait.REPACK_PROPER))
    }

    @Test
    fun changingAnAnswerDoesOverwriteItsOwnTraits() {
        val overwritten = StreamScoreProfile()
            .withPoints(StreamScoreTrait.AUDIO_ATMOS, 500)
            .withAudioDevice(AudioDeviceSupport.ADVANCED)
        // The audio question owns Atmos, so answering it replaces the manual 500 with the option value.
        assertEquals(40, overwritten.points(StreamScoreTrait.AUDIO_ATMOS))
        assertEquals(AudioDeviceSupport.ADVANCED, overwritten.answers.audioDevice)
    }

    @Test
    fun preferSdrRemovesTheHdrRowsAndRewardsSdr() {
        val p = StreamScoreProfile().withHdrPreference(HdrPreference.PREFER_SDR)
        // Zeroed HDR rows are dropped from the map (0 = no opinion)…
        assertFalse(StreamScoreTrait.HDR_DOLBY_VISION.id in p.points)
        assertFalse(StreamScoreTrait.HDR_10.id in p.points)
        // …and SDR now scores positively.
        assertEquals(100, p.points(StreamScoreTrait.HDR_SDR))
    }

    @Test
    fun threeDEquipmentToggleClearsThePenalty() {
        val yes = StreamScoreProfile().withThreeDEquipment(ThreeDEquipment.YES)
        assertNull(yes.points[StreamScoreTrait.THREE_D.id])
        assertEquals(ThreeDEquipment.YES, yes.answers.threeD)
    }

    // --- reset to preset ---

    @Test
    fun resetRebuildsEveryScoreFromTheCurrentAnswers() {
        val tuned = StreamScoreProfile()
            .withAudioDevice(AudioDeviceSupport.ADVANCED)
            .withHdrPreference(HdrPreference.PREFER_SDR)
            // Hand-tuned values across a question-owned trait and a base trait.
            .withPoints(StreamScoreTrait.AUDIO_ATMOS, 500)
            .withPoints(StreamScoreTrait.HYBRID, 250)
            .withPoints(StreamScoreTrait.QUALITY_2160P_REMUX, 999)

        val reset = tuned.resetPointsToAnswers()

        // Question-owned traits go back to what the *current* answers imply, not to the app defaults:
        // audio was answered "advanced", so Atmos returns to 40 rather than the default 12.
        assertEquals(40, reset.points(StreamScoreTrait.AUDIO_ATMOS))
        assertEquals(100, reset.points(StreamScoreTrait.HDR_SDR))
        assertEquals(350, reset.points(StreamScoreTrait.QUALITY_2160P_REMUX))
        // Traits no question controls go back to their defaults.
        assertEquals(10, reset.points(StreamScoreTrait.HYBRID))
        // The answers themselves are untouched.
        assertEquals(tuned.answers, reset.answers)
        assertEquals(AudioDeviceSupport.ADVANCED, reset.answers.audioDevice)
    }

    @Test
    fun resetEqualsTheMapTheAnswersProduce() {
        val tuned = StreamScoreProfile()
            .withSizeQuality(SizeQualityPreference.SMALL)
            .withPoints(StreamScoreTrait.CODEC_HEVC, 777)
        val reset = tuned.resetPointsToAnswers()
        assertEquals(tuned.answers.toPointsMap(), reset.points)
    }

    @Test
    fun resetOnAnUntouchedProfileChangesNothing() {
        val fresh = StreamScoreProfile()
        assertEquals(fresh.points, fresh.resetPointsToAnswers().points)
    }

    @Test
    fun resetLeavesAUserEnabledSizeBandAlone() {
        val withBand = StreamScoreProfile()
            .copy(sizeBand = StreamSizeBand(enabled = true, movieMaxGb = 33.0))
            .withPoints(StreamScoreTrait.HYBRID, 250)
        val reset = withBand.resetPointsToAnswers()
        assertEquals(33.0, reset.sizeBand.movieMaxGb)
        assertTrue(reset.sizeBand.enabled)
        assertEquals(10, reset.points(StreamScoreTrait.HYBRID))
    }

    // --- size band coupling (Q4 only) ---

    @Test
    fun sizeQualitySetsBandBoundsUntilTheUserEnablesTheirOwn() {
        val quality = StreamScoreProfile().withSizeQuality(SizeQualityPreference.QUALITY)
        assertEquals(80.0, quality.sizeBand.movieMaxGb)

        // Once the user enables their own band, a later answer must not overwrite their bounds.
        val userBand = quality.copy(sizeBand = quality.sizeBand.copy(enabled = true, movieMaxGb = 33.0))
        val afterSmall = userBand.withSizeQuality(SizeQualityPreference.SMALL)
        assertEquals(33.0, afterSmall.sizeBand.movieMaxGb)
    }
}
