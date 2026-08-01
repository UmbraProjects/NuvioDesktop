package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.mdblist.WatchProgressSourceMdbList
import com.nuvio.app.features.simkl.WatchProgressSourceSimkl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContinueWatchingRemovalRoutingTest {
    @Test
    fun `each progress source routes only to its owner`() {
        assertEquals(
            ContinueWatchingRemovalTarget.LOCAL,
            continueWatchingRemovalTarget(WatchProgressSourceLocal),
        )
        assertEquals(
            ContinueWatchingRemovalTarget.TRAKT,
            continueWatchingRemovalTarget(WatchProgressSourceTraktPlayback),
        )
        assertEquals(
            ContinueWatchingRemovalTarget.TRAKT,
            continueWatchingRemovalTarget(WatchProgressSourceTraktHistory),
        )
        assertEquals(
            ContinueWatchingRemovalTarget.TRAKT,
            continueWatchingRemovalTarget(WatchProgressSourceTraktShowProgress),
        )
        assertEquals(
            ContinueWatchingRemovalTarget.SIMKL,
            continueWatchingRemovalTarget(WatchProgressSourceSimkl),
        )
        assertEquals(
            ContinueWatchingRemovalTarget.MDBLIST,
            continueWatchingRemovalTarget(WatchProgressSourceMdbList),
        )
    }

    @Test
    fun `unknown source cannot fall back to local or another provider`() {
        assertNull(continueWatchingRemovalTarget("unknown"))
    }
}
