package com.nuvio.app.features.simkl

import com.nuvio.app.features.addons.RawHttpResponse
import kotlin.test.Test
import kotlin.test.assertFailsWith

class SimklPlaybackDeleteTest {
    @Test
    fun `successful delete response is accepted`() {
        requireSuccessfulSimklPlaybackDelete(response(status = 204), sessionId = 42)
    }

    @Test
    fun `failed delete response is surfaced for rollback`() {
        assertFailsWith<IllegalStateException> {
            requireSuccessfulSimklPlaybackDelete(response(status = 404), sessionId = 42)
        }
    }

    private fun response(status: Int) = RawHttpResponse(
        status = status,
        statusText = "",
        url = "https://api.simkl.com/sync/playback/42",
        body = "",
        headers = emptyMap(),
    )
}
