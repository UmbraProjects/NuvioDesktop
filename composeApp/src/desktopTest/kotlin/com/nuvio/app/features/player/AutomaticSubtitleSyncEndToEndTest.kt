package com.nuvio.app.features.player

import com.nuvio.app.features.player.autosync.EmbeddedSubtitleTimelineLoader
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Whole automatic-sync pipeline against a real Matroska file: ffmpeg muxes a reference subtitle
 * track into an MKV, MockWebServer serves it with byte ranges like a debrid host, and deliberately
 * mistimed addon subtitles must come back corrected. Skipped when ffmpeg isn't on PATH.
 *
 * Also a manual harness: set AUTOSYNC_VIDEO_URL + AUTOSYNC_SUBTITLE_URL to try a real stream and
 * subtitle without launching the app; the result and log land in the test output.
 */
class AutomaticSubtitleSyncEndToEndTest {
    private data class Cue(val startMs: Long, val endMs: Long, val text: String)

    // Irregular, dialogue-like timing over ~25 minutes; the matcher keys on the gap rhythm.
    private val referenceCues: List<Cue> = run {
        val random = Random(42)
        var at = 4_000L
        List(260) { index ->
            val duration = 900L + random.nextLong(3_200)
            val cue = Cue(at, at + duration, "Reference line ${index + 1}")
            at += duration + 150L + random.nextLong(if (random.nextInt(6) == 0) 14_000 else 2_400)
            cue
        }
    }

    @Test
    fun constantOffsetIsCorrected() = runSynthetic(transform = { it + 2_500 }) { cues ->
        assertAllWithin(cues, toleranceMs = 120)
    }

    @Test
    fun frameRateDriftIsCorrected() = runSynthetic(
        // A 23.976 fps subtitle played against a 25 fps (PAL) release drifts ~4% plus an offset.
        transform = { (it * (25.0 / 23.976)).toLong() - 1_200 },
    ) { cues ->
        assertAllWithin(cues, toleranceMs = 150)
    }

    @Test
    fun alreadySyncedSubtitleStaysPut() = runSynthetic(transform = { it }) { cues ->
        assertAllWithin(cues, toleranceMs = 60)
    }

    @Test
    fun wrongEpisodeSubtitleIsNotSynced() {
        // Unrelated timing (a different episode): applying any alignment would be a bad guess.
        val random = Random(7)
        var at = 2_000L
        val unrelated = List(240) {
            val duration = 800L + random.nextLong(3_000)
            val cue = Cue(at, at + duration, "Other episode")
            at += duration + 200L + random.nextLong(4_000)
            cue
        }
        runSynthetic(addonCues = unrelated, expectSynced = false) {}
    }

    /** ffmpeg's default MP4 layout: moov after mdat, so the reader hops over mdat by range. */
    @Test
    fun mp4WithIndexAtEndIsSynced() = runSynthetic(container = Container.MP4, transform = { it + 2_500 }) { cues ->
        assertAllWithin(cues, toleranceMs = 120)
    }

    @Test
    fun faststartMp4FrameRateDriftIsSynced() = runSynthetic(
        container = Container.MP4_FASTSTART,
        transform = { (it * (25.0 / 23.976)).toLong() - 1_200 },
    ) { cues ->
        assertAllWithin(cues, toleranceMs = 150)
    }

    /** The MP4 sample-table reader recovers every embedded line's timing, at both moov positions. */
    @Test
    fun mp4SampleTablesMatchTheEmbeddedTrack() {
        val ffmpeg = findFfmpeg()
        assumeTrue("ffmpeg not on PATH", ffmpeg != null)
        for (container in listOf(Container.MP4, Container.MP4_FASTSTART)) {
            val dir = Files.createTempDirectory("autosync-mp4").toFile()
            val referenceSrt = File(dir, "reference.srt").apply { writeText(srt(referenceCues)) }
            val mp4 = File(dir, "movie.mp4")
            run(
                ffmpeg!!, "-y", "-f", "lavfi", "-i", "color=black:s=64x36:r=1", "-i", referenceSrt.path,
                "-t", (referenceCues.last().endMs / 1000 + 10).toString(), "-map", "0:v", "-map", "1:s",
                "-c:v", "libx264", "-metadata:s:s:0", "language=eng",
                *container.ffmpegArgs.toTypedArray(), mp4.path,
            )
            serve(mp4) { url ->
                val timeline = runBlocking { EmbeddedSubtitleTimelineLoader.load(url) }
                assertEquals("mp4-sample-table", timeline?.source, container.name)
                val track = timeline!!.tracks.single()
                assertEquals("eng", track.language)
                assertEquals(referenceCues.size, track.cues.size, container.name)
                track.cues.zip(referenceCues).forEach { (got, want) ->
                    assertTrue(abs(got.startTimeMs - want.startMs) <= 1, "${container.name} start ${got.startTimeMs} vs ${want.startMs}")
                    assertTrue(abs(got.endTimeMs - want.endMs) <= 1, "${container.name} end ${got.endTimeMs} vs ${want.endMs}")
                }
            }
        }
    }

    /** A local library file: the same reads, from disk instead of by range request. */
    @Test
    fun localMkvIsSynced() = runSynthetic(transform = { it + 2_500 }, localFile = true) { cues ->
        assertAllWithin(cues, toleranceMs = 120)
    }

    @Test
    fun localMp4WithIndexAtEndIsSynced() = runSynthetic(
        container = Container.MP4,
        transform = { it + 2_500 },
        localFile = true,
    ) { cues ->
        assertAllWithin(cues, toleranceMs = 120)
    }

    /** Fragmented MP4 keeps timing in moof boxes spread through the file; it isn't indexed. */
    @Test
    fun fragmentedMp4ReportsNoReference() = runSynthetic(
        container = Container.MP4_FRAGMENTED,
        transform = { it + 2_500 },
        expectedFailure = AutomaticSubtitleSyncResult.NoReference,
    ) {}

    /**
     * A torrent engine answers a range only once peers deliver it. Each response here takes 6 s:
     * past the network pace's 5 s per-request limit, inside the torrent pace's.
     */
    @Test
    fun slowTorrentStreamIsSyncedWithTorrentPace() = runSynthetic(
        transform = { it + 2_500 },
        responseDelayMs = 6_000,
        isTorrent = true,
    ) { cues ->
        assertAllWithin(cues, toleranceMs = 120)
    }

    @Test
    fun slowStreamGivesUpWithNetworkPace() = runSynthetic(
        transform = { it + 2_500 },
        responseDelayMs = 6_000,
        expectedFailure = AutomaticSubtitleSyncResult.NoReference,
    ) {}

    @Test
    fun streamWithoutEmbeddedSubtitlesReportsNoReference() {
        val ffmpeg = findFfmpeg()
        assumeTrue("ffmpeg not on PATH", ffmpeg != null)
        val dir = Files.createTempDirectory("autosync-e2e").toFile()
        val mkv = File(dir, "nosubs.mkv")
        run(ffmpeg!!, "-y", "-f", "lavfi", "-i", "color=black:s=64x36:r=1", "-t", "120", "-c:v", "libx264", mkv.path)
        val subtitle = File(dir, "addon.srt").apply { writeText(srt(referenceCues.take(40))) }
        serve(mkv) { videoUrl ->
            serve(subtitle) { subtitleUrl ->
                val result = runBlocking { runAutomaticSubtitleSync(request(videoUrl, subtitleUrl)) }
                assertEquals(AutomaticSubtitleSyncResult.NoReference, result)
            }
        }
    }

    @Test
    fun manualHarness() {
        val video = System.getenv("AUTOSYNC_VIDEO_URL")
        val subtitle = System.getenv("AUTOSYNC_SUBTITLE_URL")
        assumeTrue("AUTOSYNC_VIDEO_URL / AUTOSYNC_SUBTITLE_URL not set", video != null && subtitle != null)
        val result = runBlocking { runAutomaticSubtitleSync(request(video!!, subtitle!!)) }
        println("AutoSync manual harness result: $result".take(400))
        (result as? AutomaticSubtitleSyncResult.Synced)?.let { println("Retimed file: ${it.localFilePath}") }
    }

    private fun runSynthetic(
        transform: (Long) -> Long = { it },
        addonCues: List<Cue>? = null,
        expectSynced: Boolean = true,
        expectedFailure: AutomaticSubtitleSyncResult? = null,
        container: Container = Container.MKV,
        responseDelayMs: Long = 0,
        isTorrent: Boolean = false,
        localFile: Boolean = false,
        check: (List<Pair<Long, Long>>) -> Unit,
    ) {
        val ffmpeg = findFfmpeg()
        assumeTrue("ffmpeg not on PATH", ffmpeg != null)
        val dir = Files.createTempDirectory("autosync-e2e").toFile()
        val referenceSrt = File(dir, "reference.srt").apply { writeText(srt(referenceCues)) }
        val mkv = File(dir, "movie.${container.extension}")
        val lengthSeconds = (referenceCues.last().endMs / 1000 + 10).toString()
        run(
            ffmpeg!!, "-y",
            "-f", "lavfi", "-i", "color=black:s=64x36:r=1", "-i", referenceSrt.path,
            "-t", lengthSeconds, "-map", "0:v", "-map", "1:s",
            "-c:v", "libx264", "-metadata:s:s:0", "language=eng",
            *container.ffmpegArgs.toTypedArray(),
            mkv.path,
        )
        // Different text than the embedded track: matching is on timing, not words.
        val broken = addonCues ?: referenceCues.map { cue ->
            Cue(transform(cue.startMs), transform(cue.endMs), "Addon line for ${cue.text.substringAfterLast(' ')}")
        }
        val subtitleServer = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(srt(broken))
            }
            start()
        }
        try {
            val withSource: ((String) -> Unit) -> Unit =
                if (localFile) { block -> block(mkv.path) } else { block -> serve(mkv, responseDelayMs, block) }
            withSource { url ->
                val result = runBlocking {
                    runAutomaticSubtitleSync(
                        request(url, subtitleServer.url("/addon.srt").toString()).copy(isTorrent = isTorrent),
                    )
                }
                if (expectedFailure != null || !expectSynced) {
                    assertEquals(expectedFailure ?: AutomaticSubtitleSyncResult.NotConfident, result)
                    return@withSource
                }
                val synced = assertIs<AutomaticSubtitleSyncResult.Synced>(result)
                val file = File(synced.localFilePath)
                assertTrue(file.isFile)
                val timings = Regex("""(\d+):(\d\d):(\d\d),(\d{3}) --> (\d+):(\d\d):(\d\d),(\d{3})""")
                    .findAll(file.readText())
                    .map { m ->
                        val v = m.groupValues.drop(1).map(String::toLong)
                        ((v[0] * 60 + v[1]) * 60 + v[2]) * 1000 + v[3] to ((v[4] * 60 + v[5]) * 60 + v[6]) * 1000 + v[7]
                    }
                    .toList()
                assertEquals(referenceCues.size, timings.size)
                check(timings)
            }
        } finally {
            subtitleServer.shutdown()
        }
    }

    private fun assertAllWithin(retimed: List<Pair<Long, Long>>, toleranceMs: Long) {
        val errors = retimed.zip(referenceCues).map { (got, want) -> abs(got.first - want.startMs) }
        val worst = errors.max()
        println("AutoSync e2e: median start error ${errors.sorted()[errors.size / 2]}ms, worst ${worst}ms")
        assertTrue(worst <= toleranceMs, "worst start error ${worst}ms > ${toleranceMs}ms")
    }

    private fun request(videoUrl: String, subtitleUrl: String) = AutomaticSubtitleSyncRequest(
        sourceUrl = videoUrl,
        sourceHeaders = emptyMap(),
        subtitle = AddonSubtitle(id = subtitleUrl, url = subtitleUrl, language = "eng", display = "Addon"),
        alternatives = emptyList(),
    )

    /** Serves [file] with HTTP Range support, as debrid/CDN hosts do. */
    private enum class Container(val extension: String, val ffmpegArgs: List<String>) {
        MKV("mkv", listOf("-c:s", "srt")),
        MP4("mp4", listOf("-c:s", "mov_text")),
        MP4_FASTSTART("mp4", listOf("-c:s", "mov_text", "-movflags", "+faststart")),
        MP4_FRAGMENTED("mp4", listOf("-c:s", "mov_text", "-movflags", "frag_keyframe+empty_moov")),
    }

    private fun serve(file: File, responseDelayMs: Long = 0, block: (String) -> Unit) {
        val bytes = file.readBytes()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val range = request.getHeader("Range")?.let { Regex("""bytes=(\d+)-(\d*)""").find(it) }
                        ?: return MockResponse().setBody(Buffer().write(bytes))
                    val start = range.groupValues[1].toInt()
                    val end = (range.groupValues[2].toIntOrNull() ?: bytes.lastIndex).coerceAtMost(bytes.lastIndex)
                    if (start > end) return MockResponse().setResponseCode(416)
                    return MockResponse()
                        .setHeadersDelay(responseDelayMs, TimeUnit.MILLISECONDS)
                        .setResponseCode(206)
                        .setHeader("Content-Range", "bytes $start-$end/${bytes.size}")
                        .setBody(Buffer().write(bytes, start, end - start + 1))
                }
            }
            start()
        }
        try {
            block(server.url("/${file.name}").toString())
        } finally {
            server.shutdown()
        }
    }

    private fun srt(cues: List<Cue>): String = buildString {
        cues.forEachIndexed { index, cue ->
            append(index + 1).append('\n')
            append(time(cue.startMs)).append(" --> ").append(time(cue.endMs)).append('\n')
            append(cue.text).append("\n\n")
        }
    }

    private fun time(ms: Long) = String.format(
        Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000,
    )

    private fun findFfmpeg(): String? = runCatching {
        val process = ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start()
        process.inputStream.readAllBytes()
        if (process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0) "ffmpeg" else null
    }.getOrNull()

    private fun run(vararg command: String) {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().decodeToString()
        check(process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0) { output.takeLast(2000) }
    }
}
