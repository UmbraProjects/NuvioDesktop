package com.nuvio.app.features.addons

import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddonPlatformDesktopTest {
    @Test
    fun `ordinary raw responses retain the desktop size cap`() {
        val payload = "x".repeat(TEST_BODY_BYTES)

        val result = readRawResponseBody(
            body = payload.toResponseBody(),
            allowLargeResponse = false,
        )

        assertTrue(result.endsWith(RAW_HTTP_TRUNCATION_MARKER))
        assertTrue(result.length < payload.length)
    }

    @Test
    fun `trusted bulk responses can bypass the desktop size cap`() {
        val payload = "x".repeat(TEST_BODY_BYTES)

        val result = readRawResponseBody(
            body = payload.toResponseBody(),
            allowLargeResponse = true,
        )

        assertEquals(payload, result)
        assertFalse(result.endsWith(RAW_HTTP_TRUNCATION_MARKER))
    }

    private companion object {
        const val TEST_BODY_BYTES = 1024 * 1024 + 32
    }
}
