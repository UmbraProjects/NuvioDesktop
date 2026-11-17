package com.nuvio.app.features.watchprogress

import kotlin.test.Test
import kotlin.test.assertEquals

/** Upstream 9da04b944: a dismissed Next Up show is keyed by its content id alone. */
class NextUpDismissKeyTest {
    @Test
    fun dismissKeyIgnoresTheSeedEpisode() {
        assertEquals(nextUpDismissKey("show", 1, 1), nextUpDismissKey("show", 1, 4))
        assertEquals("show", nextUpDismissKey(" show ", null, null))
    }
}
