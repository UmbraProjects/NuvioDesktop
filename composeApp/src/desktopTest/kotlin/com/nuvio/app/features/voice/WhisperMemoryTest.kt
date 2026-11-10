package com.nuvio.app.features.voice

import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What a speech model costs, three ways: what whisper.cpp says it allocated, and this process's
 * private working set (Task Manager's figure) after the load and after a first decode. Needs
 * nuvio_whisper.dll and `-Pnuvio.whisperTestModel=<ggml .bin>`; skipped otherwise.
 */
class WhisperMemoryTest {
    private val model = System.getProperty("nuvio.whisperTestModel").orEmpty().takeIf { it.isNotBlank() }?.let(::File)

    /** Private working set of this test JVM in MB, read the way Task Manager does. */
    private fun privateWorkingSetMb(): Long {
        val pid = ProcessHandle.current().pid()
        val process = ProcessBuilder(
            "powershell", "-NoProfile", "-Command",
            "[int]((Get-CimInstance Win32_PerfFormattedData_PerfProc_Process -Filter 'IDProcess=$pid').WorkingSetPrivate/1MB)",
        ).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText().trim()
        process.waitFor(30, TimeUnit.SECONDS)
        return text.lines().last().trim().toLongOrNull() ?: -1
    }

    @Test
    fun reportsWhatTheModelAllocates() {
        assumeTrue("no -Pnuvio.whisperTestModel", model?.isFile == true)
        assumeTrue("nuvio_whisper.dll not built", WhisperLibrary.resolve() != null)

        System.gc()
        val before = privateWorkingSetMb()
        WhisperTranscriber.open(model!!).getOrThrow().use { engine ->
            val reported = WhisperNative.nativeLastLoadBytes()
            val afterLoad = privateWorkingSetMb()
            // A first decode touches the compute buffers, which only then come into RAM.
            engine.transcribe(FloatArray(VoiceAudio.SAMPLE_RATE * 2) { i -> (Math.sin(i / 8.0) * 0.1).toFloat() })
            val afterDecode = privateWorkingSetMb()
            println(
                "${model.name}: whisper.cpp allocated ${reported / 1_000_000} MB; private working set " +
                    "$before MB -> $afterLoad MB after load (+${afterLoad - before}) -> $afterDecode MB after a decode " +
                    "(+${afterDecode - before})",
            )
            assertTrue(reported > model.length(), "reported $reported bytes for a ${model.length()} byte model")
        }
    }
}
