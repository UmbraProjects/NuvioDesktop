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

    @Test
    fun detailsForUpcomingOpensDetailsOnlyForAnUnairedEpisode() {
        val action = ContinueWatchingClickAction.DETAILS_FOR_UPCOMING
        assertTrue(action.opensDetails(canOpenDetails = true, isUpcoming = true))
        assertFalse(action.opensDetails(canOpenDetails = true, isUpcoming = false))
        assertFalse(action.opensDetails(canOpenDetails = false, isUpcoming = true))
    }
}
