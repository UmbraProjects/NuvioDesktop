package com.nuvio.app.features.music

import kotlin.test.Test
import kotlin.test.assertNull

class MusicStreamFallbacksTest {
    @Test
    fun aBuildWithoutTheFallbackHasNone() {
        assertNull(MusicStreamFallbacks.load("com.nuvio.app.features.music.NoSuchFallback"))
    }
}
