package com.nuvio.app.features.watchprogress

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContinueWatchingClickActionTest {
    @Test
    fun detailsActionOpensDetailsWhenTheItemSupportsDetails() {
        assertTrue(ContinueWatchingClickAction.DETAILS.opensDetails(canOpenDetails = true))
    }

    @Test
    fun detailsActionFallsBackToPlaybackWhenTheItemHasNoDetailsPage() {
        assertFalse(ContinueWatchingClickAction.DETAILS.opensDetails(canOpenDetails = false))
    }

    @Test
    fun playActionNeverOpensDetails() {
        assertFalse(ContinueWatchingClickAction.PLAY.opensDetails(canOpenDetails = true))
    }
}
