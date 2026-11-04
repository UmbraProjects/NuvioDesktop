package com.nuvio.app.features.voice

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceModelDownloaderTest {
    private val payload = Random(7).nextBytes(1_300_000)
    private val sha256 = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private val ranges = mutableListOf<String?>()
    private var honourRange = true

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("voice-download").toFile()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                synchronized(ranges) { ranges += range }
                val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull()
                return if (from != null && honourRange) {
                    MockResponse().setResponseCode(206)
                        .setBody(Buffer().write(payload, from, payload.size - from))
                } else {
                    MockResponse().setResponseCode(200).setBody(Buffer().write(payload))
                }
            }
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun download(target: File, sha: String = sha256, progress: MutableList<Long> = mutableListOf()) = runBlocking {
        VoiceModelDownloader().download(
            url = server.url("/model.bin").toString(),
            target = target,
            expectedSize = payload.size.toLong(),
            expectedSha256 = sha,
            onProgress = { bytes, _ -> progress += bytes },
        )
    }

    @Test
    fun aVerifiedDownloadEndsUpAtTheTargetWithNoPartLeft() {
        val target = File(dir, "model.bin")
        val progress = mutableListOf<Long>()
        download(target, progress = progress)
        assertContentEquals(payload, target.readBytes())
        assertFalse(VoiceModelDownloader.partFile(target).exists())
        assertEquals(payload.size.toLong(), progress.last())
        assertEquals(listOf<String?>(null), ranges)
    }

    @Test
    fun aChecksumMismatchKeepsNothing() {
        val target = File(dir, "model.bin")
        assertFailsWith<VoiceModelDownloader.VerificationFailed> { download(target, sha = "0".repeat(64)) }
        assertFalse(target.exists())
        assertFalse(VoiceModelDownloader.partFile(target).exists())
    }

    @Test
    fun aPartLeftByAFailedDownloadIsResumed() {
        val target = File(dir, "model.bin")
        VoiceModelDownloader.partFile(target).writeBytes(payload.copyOfRange(0, 500_000))
        download(target)
        assertContentEquals(payload, target.readBytes())
        assertEquals(listOf<String?>("bytes=500000-"), ranges)
    }

    @Test
    fun aServerThatIgnoresTheRangeStartsOver() {
        honourRange = false
        val target = File(dir, "model.bin")
        VoiceModelDownloader.partFile(target).writeBytes(payload.copyOfRange(0, 500_000))
        download(target)
        assertContentEquals(payload, target.readBytes())
    }

    @Test
    fun aCorruptPartIsCaughtByTheChecksum() {
        val target = File(dir, "model.bin")
        VoiceModelDownloader.partFile(target).writeBytes(ByteArray(500_000))
        assertFailsWith<VoiceModelDownloader.VerificationFailed> { download(target) }
        assertFalse(target.exists())
        assertTrue(ranges.single() == "bytes=500000-")
    }
}
