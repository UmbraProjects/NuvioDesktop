package com.nuvio.app.features.music.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StageMotionTest {
    @Test
    fun calmDriftsSlowerThanSteadyThanDriven() {
        val calm = stageMotionSpec(MotionProfile.Calm, false)
        val steady = stageMotionSpec(MotionProfile.Steady, false)
        val driven = stageMotionSpec(MotionProfile.Driven, false)
        assertTrue(calm.driftMs > steady.driftMs && steady.driftMs > driven.driftMs)
        assertTrue(calm.pulseMs > steady.pulseMs && steady.pulseMs > driven.pulseMs)
        assertTrue(calm.pulseDepth < driven.pulseDepth)
        assertTrue(calm.discSecondsPerTurn > driven.discSecondsPerTurn)
        assertEquals(40_000, calm.driftMs)
        assertTrue(listOf(calm, steady, driven).all { it.animated && it.discSpins && it.scaleTo > it.scaleFrom })
    }

    @Test
    fun reducedMotionIsStillForEveryProfile() {
        MotionProfile.entries.forEach { profile ->
            val spec = stageMotionSpec(profile, reducedMotion = true)
            assertFalse(spec.animated)
            assertFalse(spec.discSpins)
            assertEquals(0f, spec.pulseDepth)
            assertEquals(spec.scaleFrom, spec.scaleTo)
            assertEquals(Long.MAX_VALUE, spec.holdMs)
        }
    }

    @Test
    fun captionsNameTheProfile() {
        assertTrue(stageMotionSpec(MotionProfile.Calm, false).caption.startsWith("Calm"))
        assertTrue(stageMotionSpec(MotionProfile.Driven, false).caption.startsWith("Driven"))
        assertTrue(stageMotionSpec(MotionProfile.Driven, true).caption.contains("Reduced"))
    }

    @Test
    fun captionUsesTheMoodWhenThereIsOne() {
        val calm = stageMotionSpec(MotionProfile.Calm, false)
        assertEquals("Motion profile: Passionate → slow drift, slow breath", stageMotionCaption("Passionate", calm))
        assertEquals("Motion profile: Calm · slow drift", stageMotionCaption("  ", calm))
        assertEquals("Reduced motion · still", stageMotionCaption("Passionate", stageMotionSpec(MotionProfile.Calm, true)))
    }
}
