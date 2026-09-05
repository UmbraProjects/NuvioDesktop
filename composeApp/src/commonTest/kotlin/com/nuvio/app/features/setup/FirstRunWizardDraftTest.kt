package com.nuvio.app.features.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirstRunWizardDraftTest {

    @Test
    fun `steps are ordered and indexed consistently`() {
        assertEquals(FirstRunWizardStep.entries.size, FirstRunWizardStep.count)
        FirstRunWizardStep.ordered.forEachIndexed { index, step ->
            assertEquals(index, step.index)
        }
    }

    @Test
    fun `the experience step comes first and metadata finishes setup`() {
        assertEquals(FirstRunWizardStep.Experience, FirstRunWizardStep.ordered.first())
        assertEquals(FirstRunWizardStep.Metadata, FirstRunWizardStep.ordered.last())
    }

    @Test
    fun `first and last step flags match the ordering`() {
        val first = FirstRunWizardUiState(step = FirstRunWizardStep.ordered.first())
        val last = FirstRunWizardUiState(step = FirstRunWizardStep.ordered.last())
        assertTrue(first.isFirstStep)
        assertFalse(first.isLastStep)
        assertTrue(last.isLastStep)
        assertFalse(last.isFirstStep)
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
