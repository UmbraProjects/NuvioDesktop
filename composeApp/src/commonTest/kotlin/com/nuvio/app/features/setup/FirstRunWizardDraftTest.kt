package com.nuvio.app.features.setup

import com.nuvio.app.core.ui.CompactPosterCardWidthDp
import com.nuvio.app.core.ui.ExtraLargePosterCardWidthDp
import com.nuvio.app.core.ui.LargePosterCardWidthDp
import com.nuvio.app.features.home.HomeDisplayMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirstRunWizardDraftTest {

    @Test
    fun `layout comes first and metadata finishes setup`() {
        val steps = FirstRunWizardStep.visibleSteps(FirstRunWizardDraft())
        assertEquals(FirstRunWizardStep.Layout, steps.first())
        assertEquals(FirstRunWizardStep.Metadata, steps.last())
    }

    @Test
    fun `basic mode has no trailer step`() {
        val basic = FirstRunWizardDraft(displayMode = HomeDisplayMode.Basic)
        assertFalse(FirstRunWizardStep.Trailers in FirstRunWizardStep.visibleSteps(basic))
        HomeDisplayMode.entries.filter { it != HomeDisplayMode.Basic }.forEach { mode ->
            val draft = FirstRunWizardDraft(displayMode = mode)
            assertTrue(FirstRunWizardStep.Trailers in FirstRunWizardStep.visibleSteps(draft), mode.name)
        }
    }

    @Test
    fun `step position counts only the steps shown`() {
        val draft = FirstRunWizardDraft(displayMode = HomeDisplayMode.Basic)
        val metadata = FirstRunWizardUiState(step = FirstRunWizardStep.Metadata, draft = draft)
        assertEquals(FirstRunWizardStep.entries.size - 1, metadata.steps.size)
        assertEquals(metadata.steps.lastIndex, metadata.stepIndex)
        assertTrue(metadata.isLastStep)
        assertFalse(metadata.isFirstStep)
        val first = FirstRunWizardUiState(step = FirstRunWizardStep.Layout, draft = draft)
        assertTrue(first.isFirstStep)
        assertFalse(first.isLastStep)
    }

    @Test
    fun `hero steppers stay on their grid and inside their range`() {
        assertEquals(1.05f, stepHeight(1.0f, 1), 0.0001f)
        assertEquals(1.75f, stepHeight(1.75f, 1), 0.0001f)
        assertEquals(0.75f, stepHeight(0.75f, -1), 0.0001f)
        // An off-grid value from Settings snaps to the grid on its first step.
        assertEquals(1.1f, stepHeight(1.07f, 1), 0.0001f)
        assertEquals(-0.25f, stepBias(0f, -1), 0.0001f)
        assertEquals(1f, stepBias(1f, 1), 0.0001f)
        assertEquals(-1f, stepBias(-1f, -1), 0.0001f)
    }

    @Test
    fun `poster width steps from the nearest preset`() {
        assertEquals(0, nearestPosterPresetIndex(CompactPosterCardWidthDp))
        assertEquals(6, nearestPosterPresetIndex(ExtraLargePosterCardWidthDp))
        assertEquals(5, nearestPosterPresetIndex(LargePosterCardWidthDp + 3))
    }

    @Test
    fun `an empty draft never asks for a trailer nobody chose`() {
        // The draft's own fallbacks only apply where nothing has been seeded; a fresh install is
        // seeded from NewInstallDefaults instead, and a rerun from the user's own settings.
        val draft = FirstRunWizardDraft()
        assertEquals(0, draft.trailerDelaySeconds)
        assertFalse(draft.trailerSoundEnabled)
        assertFalse(draft.trailerFullscreen)
    }
}
