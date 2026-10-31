package com.nuvio.app.features.music

import com.nuvio.app.features.trailer.TrailerPlaybackSource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * fork:music-resolver
 *
 * The probe rules ported from BitChord's StreamResolver (see StreamVerification.kt): what counts as
 * a refusal, where the range starts, and how a source and its fallbacks are judged.
 */
class StreamVerificationTest {
    private val goodMedia = ProbeResponse(206, "audio/mp4", PROBE_READ_BYTES)

    @Test
    fun rangeIsOpenEndedAndSkipsPastTheAuthBoundaryForLongFiles() {
        val longFile = "https://rr1.googlevideo.com/videoplayback?itag=140&clen=5000000&c=IOS"
        assertEquals("bytes=$AUTH_BOUNDARY_BYTES-", probeRange(longFile))
        // Too short to have a megabyte left to read, or no length at all: start at the top.
        assertEquals("bytes=0-", probeRange("https://rr1.googlevideo.com/videoplayback?clen=${AUTH_BOUNDARY_BYTES}"))
        assertEquals("bytes=0-", probeRange("https://rr1.googlevideo.com/videoplayback?itag=18"))
    }

    @Test
    fun onlyDirectGooglevideoMediaIsProbed() {
        assertTrue(isProbeable("https://rr3---sn-abc.googlevideo.com/videoplayback?itag=140"))
        assertFalse(isProbeable("https://manifest.googlevideo.com/api/manifest/hls_variant/id/x/file/index.m3u8"))
        assertFalse(isProbeable("https://rr1.googlevideo.com/api/manifest/hls_playlist/x"))
        assertFalse(isProbeable("https://example.com/audio.mp4"))
        assertFalse(isProbeable("not a url"))
    }

    @Test
    fun verdictBlamesTheUrlOnlyForADefiniteRefusal() {
        assertEquals(ProbeVerdict.Ok, probeVerdict(goodMedia))
        for (status in listOf(403, 404, 410)) {
            assertEquals(ProbeVerdict.Refused, probeVerdict(ProbeResponse(status, "text/html", 0)), "HTTP $status")
        }
        // The server did not rule on the URL: never blame it.
        for (status in listOf(416, 429, 500, 503)) {
            assertEquals(ProbeVerdict.Unreachable, probeVerdict(ProbeResponse(status, null, 0)), "HTTP $status")
        }
        assertEquals(ProbeVerdict.Unreachable, probeVerdict(null))
    }

    @Test
    fun aRefusalDressedAsSuccessIsRefusedAndAStalledBodyIsNot() {
        assertEquals(ProbeVerdict.Refused, probeVerdict(ProbeResponse(200, "text/html; charset=utf-8", PROBE_READ_BYTES)))
        assertEquals(ProbeVerdict.Refused, probeVerdict(ProbeResponse(200, "application/json", PROBE_READ_BYTES)))
        // Headers arrived, the body did not: no verdict on the URL itself.
        assertEquals(ProbeVerdict.Unreachable, probeVerdict(ProbeResponse(206, "audio/mp4", 10)))
        // Video and octet-stream are media too.
        assertEquals(ProbeVerdict.Ok, probeVerdict(ProbeResponse(206, "video/mp4", PROBE_READ_BYTES)))
        assertEquals(ProbeVerdict.Ok, probeVerdict(ProbeResponse(200, "application/octet-stream", PROBE_READ_BYTES)))
    }

    @Test
    fun probeSendsTheOpenEndedRangeAndTheGivenUserAgent(): Unit = runBlocking {
        var seen: Triple<String, String, String>? = null
        val transport = object : ProbeTransport {
            override suspend fun fetch(url: String, rangeHeader: String, userAgent: String, maxBytes: Int): ProbeResponse {
                seen = Triple(url, rangeHeader, userAgent)
                return goodMedia
            }
        }
        val url = "https://rr1.googlevideo.com/videoplayback?itag=140&clen=9000000"

        assertEquals(ProbeVerdict.Ok, StreamProbe.probe(url, transport, userAgent = "UA/1"))
        assertEquals(Triple(url, "bytes=$AUTH_BOUNDARY_BYTES-", "UA/1"), seen)

        // Not probeable: no request is made, and nobody is blamed.
        seen = null
        assertEquals(ProbeVerdict.Unreachable, StreamProbe.probe("https://example.com/a.mp4", transport))
        assertNull(seen)
    }

    @Test
    fun audioOnlyChecksJustTheAudioUrlAndVideoChecksBoth() {
        val split = TrailerPlaybackSource(videoUrl = "https://v", audioUrl = "https://a")
        assertEquals(listOf("https://a"), urlsToVerify(split, needsVideo = false))
        assertEquals(listOf("https://v", "https://a"), urlsToVerify(split, needsVideo = true))
        val muxed = TrailerPlaybackSource(videoUrl = "https://muxed")
        assertEquals(listOf("https://muxed"), urlsToVerify(muxed, needsVideo = false))
        assertEquals(listOf("https://muxed"), urlsToVerify(muxed, needsVideo = true))
    }

    private fun source(url: String, reliable: Boolean = true, fallback: TrailerPlaybackSource? = null) =
        TrailerPlaybackSource(videoUrl = url, reliable = reliable, fallback = fallback)

    private fun probing(vararg verdicts: Pair<String, ProbeVerdict>): suspend (String) -> ProbeVerdict {
        val table = verdicts.toMap()
        return { url -> table.getValue(url) }
    }

    @Test
    fun aTrustedSourceThatProbesOkIsKeptAsIs(): Unit = runBlocking {
        val s = source("https://a", fallback = source("https://b"))
        assertEquals(s, verifiedSource(s, needsVideo = false, probe = probing("https://a" to ProbeVerdict.Ok)))
    }

    @Test
    fun aRefusedSourceMovesOnToItsFallback(): Unit = runBlocking {
        val s = source("https://a", fallback = source("https://b"))
        val chosen = verifiedSource(
            s, needsVideo = false,
            probe = probing("https://a" to ProbeVerdict.Refused, "https://b" to ProbeVerdict.Ok),
        )
        assertEquals("https://b", chosen?.videoUrl)
    }

    @Test
    fun everyLinkRefusedMeansNull(): Unit = runBlocking {
        val s = source("https://a", fallback = source("https://b"))
        val chosen = verifiedSource(
            s, needsVideo = false,
            probe = probing("https://a" to ProbeVerdict.Refused, "https://b" to ProbeVerdict.Refused),
        )
        assertNull(chosen)
    }

    @Test
    fun aTrustedSourceIsKeptWhenTheProbeCouldNotAnswer(): Unit = runBlocking {
        val s = source("https://a")
        assertEquals(s, verifiedSource(s, needsVideo = false, probe = probing("https://a" to ProbeVerdict.Unreachable)))
    }

    @Test
    fun anUntrustedSourceIsAcceptedOnlyWhenTheProbeProvesItPlays(): Unit = runBlocking {
        val cutOffClient = source("https://a", reliable = false)

        val proven = verifiedSource(cutOffClient, needsVideo = false, probe = probing("https://a" to ProbeVerdict.Ok))
        assertEquals("https://a", proven?.videoUrl)
        assertTrue(proven!!.reliable, "a source the probe proved is marked reliable")
        assertFalse(proven.openableMidway, "but its URLs still refuse a range from the middle")

        // Its fallback, from the same lookup but a single stream, opens anywhere.
        val withFallback = source("https://a", reliable = false, fallback = source("https://b"))
        val fellBack = verifiedSource(withFallback, needsVideo = false, probe = probing("https://a" to ProbeVerdict.Refused, "https://b" to ProbeVerdict.Ok))
        assertEquals("https://b", fellBack?.videoUrl)
        assertTrue(fellBack!!.openableMidway)

        // No answer is not proof: an untrusted source is dropped, as it was before the probe existed.
        assertNull(verifiedSource(cutOffClient, needsVideo = false, probe = probing("https://a" to ProbeVerdict.Unreachable)))
    }

    @Test
    fun withVideoShownOneBadUrlRefusesTheWholeSource(): Unit = runBlocking {
        val split = TrailerPlaybackSource(
            videoUrl = "https://v",
            audioUrl = "https://a",
            fallback = source("https://fallback"),
        )
        val chosen = verifiedSource(
            split, needsVideo = true,
            probe = probing(
                "https://v" to ProbeVerdict.Refused,
                "https://a" to ProbeVerdict.Ok,
                "https://fallback" to ProbeVerdict.Ok,
            ),
        )
        assertEquals("https://fallback", chosen?.videoUrl)

        // The same source is fine when only its audio is played.
        val audioOnly = verifiedSource(
            split, needsVideo = false,
            probe = probing("https://a" to ProbeVerdict.Ok),
        )
        assertEquals("https://v", audioOnly?.videoUrl)
    }
}
