package com.nuvio.app.features.music.identity

/**
 * How the Now Playing stage moves, from the artist's [MotionProfile] and the reduced-motion switch.
 * Pure data so the numbers can be tested; the stage composables only read it.
 *
 * Calm is a slow drift and a slow breath, Driven a quicker push and a tight pulse, Steady sits
 * between. With [animated] false (reduced motion) nothing drifts, pulses, cycles or spins: the
 * backdrop is one still picture at [scaleFrom], the glow is steady and the disc rests out of its sleeve.
 */
data class StageMotionSpec(
    val animated: Boolean,
    /** How long one backdrop push-in lasts. */
    val driftMs: Int,
    val scaleFrom: Float,
    val scaleTo: Float,
    /** How long each backdrop picture is held before the crossfade to the next. Irrelevant when not [animated]. */
    val holdMs: Long,
    /** One breath of the glow behind the stage. */
    val pulseMs: Int,
    /** How far the glow's opacity swings (0 = steady). */
    val pulseDepth: Float,
    val discSecondsPerTurn: Float,
    /** Whether the disc turns while playing. */
    val discSpins: Boolean,
    val caption: String,
)

fun stageMotionSpec(profile: MotionProfile, reducedMotion: Boolean): StageMotionSpec {
    if (reducedMotion) {
        return StageMotionSpec(
            animated = false,
            driftMs = 0,
            scaleFrom = 1.06f,
            scaleTo = 1.06f,
            holdMs = Long.MAX_VALUE,
            pulseMs = 0,
            pulseDepth = 0f,
            discSecondsPerTurn = 2.4f,
            discSpins = false,
            caption = "Reduced motion · still",
        )
    }
    return when (profile) {
        MotionProfile.Calm -> StageMotionSpec(
            animated = true, driftMs = 40_000, scaleFrom = 1.06f, scaleTo = 1.20f, holdMs = 20_000,
            pulseMs = 6_000, pulseDepth = 0.08f, discSecondsPerTurn = 3.4f, discSpins = true,
            caption = "Calm · slow drift",
        )
        MotionProfile.Steady -> StageMotionSpec(
            animated = true, driftMs = 28_000, scaleFrom = 1.06f, scaleTo = 1.16f, holdMs = 14_000,
            pulseMs = 4_000, pulseDepth = 0.14f, discSecondsPerTurn = 2.4f, discSpins = true,
            caption = "Steady · even drift",
        )
        MotionProfile.Driven -> StageMotionSpec(
            animated = true, driftMs = 16_000, scaleFrom = 1.08f, scaleTo = 1.15f, holdMs = 9_000,
            pulseMs = 1_800, pulseDepth = 0.24f, discSecondsPerTurn = 1.8f, discSpins = true,
            caption = "Driven · tight pulse",
        )
    }
}
