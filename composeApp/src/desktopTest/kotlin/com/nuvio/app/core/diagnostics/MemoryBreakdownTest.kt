package com.nuvio.app.core.diagnostics

import com.nuvio.app.features.player.desktop.NativePlayerBridge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryBreakdownTest {
    private fun breakdown(otherPrivate: Long = 2_000L, heapCommitted: Long = 500L, mpvBuffer: Long = 300L) = MemoryBreakdown(
        privateCommit = 4_000, workingSet = 2_500, privateWorkingSet = 2_300, driverMapped = 1_000,
        otherPrivate = otherPrivate, images = 600, mappedFiles = 300, videoMemoryDedicated = 900, videoMemoryShared = 100,
        heapUsed = 350, heapCommitted = heapCommitted, heapMax = 4_000, nonHeapCommitted = 150, directBuffers = 50,
        artworkCache = 40, artworkCacheBudget = 64, scaledArtworkCache = 3, animationCache = 0,
        players = 1, mpvBuffer = mpvBuffer, mpvBufferAhead = 200,
    )

    @Test
    fun unattributedNativeIsPrivateMemoryLessWhatIsAccountedFor() {
        assertEquals(2_000L - 500 - 150 - 50 - 300, breakdown().unattributedNative)
        assertEquals(-1L, breakdown(otherPrivate = -1).unattributedNative)
        assertEquals(0L, breakdown(otherPrivate = 100).unattributedNative)
    }

    @Test
    fun unknownFiguresShowAsQuestionMarks() {
        val rows = MemoryBreakdownSampler.overlayRows(breakdown().copy(privateWorkingSet = -1)).toMap()
        assertEquals("?", rows.getValue("Memory (Task Manager)"))
    }

    @Test
    fun samplingNeverThrowsAndReadsTheJvm() {
        val sample = MemoryBreakdownSampler.sample()
        assertTrue(sample.heapCommitted > 0)
        assertTrue(sample.heapUsed > 0)
        val labels = MemoryBreakdownSampler.overlayRows(sample).map { it.first }
        assertTrue("Voice model" in labels && "Other native" in labels, "rows $labels")
    }

    @Test
    fun voiceModelCostComesOutOfOtherNativeAndIsShown() {
        val withModel = breakdown().copy(
            otherPrivate = 2_000L * 1024 * 1024,
            heapCommitted = 0, nonHeapCommitted = 0, directBuffers = 0, mpvBuffer = 0,
            voiceModel = NativeMemoryUsers.VoiceModel("ggml-small.en-q5_1.bin", 700L * 1024 * 1024, exact = true),
            wakeWordListening = true,
        )
        assertEquals(1_300L * 1024 * 1024, withModel.unattributedNative)
        val rows = MemoryBreakdownSampler.overlayRows(withModel).toMap()
        assertEquals("small.en-q5_1 · 700 MB allocated · wake word", rows.getValue("Voice model"))
        val estimated = withModel.copy(voiceModel = withModel.voiceModel!!.copy(exact = false), wakeWordListening = false)
        assertEquals("small.en-q5_1 · ~700 MB", MemoryBreakdownSampler.overlayRows(estimated).toMap().getValue("Voice model"))
    }

    @Test
    fun childProcessesAreGroupedAndAddedToTheTaskManagerTotal() {
        val groups = groupChildProcesses(
            listOf("msedgewebview2.exe" to 100L, "msedgewebview2.exe" to 50L, "python.exe" to 30L, "ffmpeg.exe" to -1L),
        )
        assertEquals(listOf("msedgewebview2.exe", "python.exe", "ffmpeg.exe"), groups.map { it.name })
        assertEquals(2, groups.first().count)
        assertEquals(150L, groups.first().privateWorkingSet)
        assertEquals(-1L, groups.last().privateWorkingSet)
        val withChildren = breakdown().copy(children = groups)
        assertEquals(2_300L + 180L, withChildren.withHelpers)
    }

    @Test
    fun enginesAreNamedFromTheModuleList() {
        assertEquals(
            listOf("mpv", "Whisper", "QuickJS", "Python"),
            notableEngines("ntdll.dll;libmpv-2.dll;Nuvio_Whisper.dll;libquickjs.dll;libpython3.14.dll;skiko-windows-x64.dll"),
        )
        assertEquals(emptyList(), notableEngines(""))
    }

    @Test
    fun nativeBreakdownReadsThisProcessWhenTheLibraryLoads() {
        val out = LongArray(9) { -2 }
        val read = runCatching { NativePlayerBridge.processMemoryBreakdown(out) }
            .onFailure { println("native breakdown not available in this test JVM: $it") }
            .getOrNull() ?: return
        assertTrue(read)
        println("native breakdown (MB): ${out.map { if (it < 0) it else it / (1024 * 1024) }}")
        println("sample: ${MemoryBreakdownSampler.logLine(MemoryBreakdownSampler.sample())}")
        assertTrue(out[0] > 0, "private commit ${out[0]}")
        assertTrue(out[1] > 0, "working set ${out[1]}")
        assertTrue(out[3] >= 0 && out[4] > 0 && out[5] > 0, "region walk ${out.toList()}")
        assertTrue(out.none { it == -2L }, "every slot written ${out.toList()}")
    }
}
