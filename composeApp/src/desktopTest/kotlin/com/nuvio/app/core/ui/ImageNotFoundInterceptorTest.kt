package com.nuvio.app.core.ui

import coil3.network.HttpException
import coil3.network.NetworkResponse
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageNotFoundInterceptorTest {

    private fun http(code: Int) = HttpException(NetworkResponse(code = code))

    @Test
    fun permanentAnswersAreMissingButTransientOnesAreNot() {
        assertTrue(isMissingImageError(http(400)))
        assertTrue(isMissingImageError(http(404)))
        assertTrue(isMissingImageError(http(410)))
        assertFalse(isMissingImageError(http(429)))
        assertFalse(isMissingImageError(http(503)))
        assertFalse(isMissingImageError(IOException("reset")))
    }

    @Test
    fun onlyNetworkUrlsAreKeyed() {
        assertEquals("https://a.test/x.jpg", imageNotFoundKey("https://a.test/x.jpg"))
        assertNull(imageNotFoundKey("file:///C:/art.png"))
        assertNull(imageNotFoundKey(42))
        assertNull(imageNotFoundKey(null))
    }

    @Test
    fun rememberedFailureExpiresAfterItsLifetime() {
        var clock = 0L
        val memory = ImageNotFoundMemory(ttlMillis = 1_000L, now = { clock })
        memory.remember("https://a.test/x.jpg", http(404))
        assertNotNull(memory.recall("https://a.test/x.jpg"))
        clock = 999L
        assertNotNull(memory.recall("https://a.test/x.jpg"))
        clock = 1_000L
        assertNull(memory.recall("https://a.test/x.jpg"))
        assertEquals(0, memory.size)
    }

    @Test
    fun oldestEntriesGoFirstPastTheCap() {
        val memory = ImageNotFoundMemory(maxEntries = 2, now = { 0L })
        memory.remember("https://a.test/1", http(404))
        memory.remember("https://a.test/2", http(404))
        memory.remember("https://a.test/1", http(404)) // refreshed: now the newest
        memory.remember("https://a.test/3", http(404))
        assertEquals(2, memory.size)
        assertNull(memory.recall("https://a.test/2"))
        assertNotNull(memory.recall("https://a.test/1"))
        assertNotNull(memory.recall("https://a.test/3"))
    }
}
