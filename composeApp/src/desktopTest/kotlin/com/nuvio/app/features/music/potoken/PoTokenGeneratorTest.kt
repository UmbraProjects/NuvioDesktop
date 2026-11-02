package com.nuvio.app.features.music.potoken

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PoTokenGeneratorTest {
    private class Fake(
        override var isExpired: Boolean = false,
        override var isDead: Boolean = false,
        var failMints: Int = 0,
        var failFirstMintOnly: Boolean = false,
    ) : PoTokenMinter {
        val minted = mutableListOf<String>()
        var closed = false
        override suspend fun mint(identifier: String): String {
            if (failMints > 0) { failMints--; throw PoTokenException("mint failed") }
            minted += identifier
            return "tok($identifier)"
        }
        override fun close() { closed = true }
    }

    private var clock = 0L
    private val made = mutableListOf<Fake>()
    private var creations = 0
    private var createFailure: Throwable? = null
    private var available = true
    private var prepare: (Fake) -> Unit = {}

    private val generator = PoTokenGenerator(
        createMinter = {
            creations++
            createFailure?.let { throw it }
            Fake().also { prepare(it); made += it }
        },
        available = { available },
        now = { clock },
        idleCloseMs = 10_000,
        idleCheckMs = 3_600_000,
    )

    @AfterTest
    fun tearDown() = runBlocking { generator.close() }

    @Test
    fun `one page serves a session, the session token is minted once`() = runBlocking {
        val first = generator.tokens("video-a", "visitor")!!
        val second = generator.tokens("video-b", "visitor")!!
        assertEquals(1, creations)
        assertEquals("tok(visitor)", first.sessionToken)
        assertEquals("tok(visitor)", second.sessionToken)
        assertEquals("tok(video-a)", first.videoToken)
        assertEquals("tok(video-b)", second.videoToken)
        assertEquals(listOf("visitor", "video-a", "video-b"), made.single().minted)
    }

    @Test
    fun `a new session replaces the page and closes the old one`() = runBlocking {
        generator.tokens("v", "visitor-1")
        generator.tokens("v", "visitor-2")
        assertEquals(2, creations)
        assertTrue(made[0].closed)
        assertEquals("tok(visitor-2)", generator.tokens("v", "visitor-2")!!.sessionToken)
    }

    @Test
    fun `an expired or dead page is replaced`() = runBlocking {
        generator.tokens("v", "visitor")
        made[0].isExpired = true
        generator.tokens("v", "visitor")
        assertEquals(2, creations)
        made[1].isDead = true
        generator.tokens("v", "visitor")
        assertEquals(3, creations)
    }

    @Test
    fun `a mint that fails on an old page is retried once on a fresh one`() = runBlocking {
        generator.tokens("v1", "visitor")
        made[0].failMints = 1
        val tokens = generator.tokens("v2", "visitor")
        assertNotNull(tokens)
        assertEquals(2, creations)
        assertTrue(made[0].closed)
        assertEquals("tok(v2)", tokens.videoToken)
    }

    @Test
    fun `a failure on a fresh page is not retried forever`() = runBlocking {
        prepare = { it.failMints = 5 }
        assertNull(generator.tokens("v", "visitor"))
        assertEquals(1, creations)
    }

    @Test
    fun `repeated start-up failures pause the attempts, then resume`() = runBlocking {
        createFailure = PoTokenException("attestation refused")
        assertNull(generator.tokens("v", "visitor"))
        assertNull(generator.tokens("v", "visitor"))
        assertEquals(2, creations)
        // Paused: no further launches.
        assertNull(generator.tokens("v", "visitor"))
        assertNull(generator.tokens("v", "visitor"))
        assertEquals(2, creations)
        clock += PoTokenGenerator.BASE_PAUSE_MS + 1
        createFailure = null
        assertNotNull(generator.tokens("v", "visitor"))
        assertEquals(3, creations)
    }

    @Test
    fun `no browser means no attempts at all`() = runBlocking {
        available = false
        assertNull(generator.tokens("v", "visitor"))
        generator.prewarm()
        assertEquals(0, creations)
    }

    @Test
    fun `prewarm loads the page and the first track reuses it`() = runBlocking {
        generator.prewarm()
        assertEquals(1, creations)
        assertNotNull(generator.tokens("v", "visitor"))
        assertEquals(1, creations)
    }

    @Test
    fun `an idle page is closed, a recently used one is not`() = runBlocking {
        generator.tokens("v", "visitor")
        clock += 9_000
        generator.closeIfIdle()
        assertTrue(!made[0].closed)
        clock += 2_000
        generator.closeIfIdle()
        assertTrue(made[0].closed)
        generator.tokens("v", "visitor")
        assertEquals(2, creations)
    }
}
