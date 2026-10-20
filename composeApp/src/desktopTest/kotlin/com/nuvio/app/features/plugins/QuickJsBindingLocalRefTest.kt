package com.nuvio.app.features.plugins

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuickJsBindingLocalRefTest {
    /**
     * quickjs-kt's own DLL leaked each binding call's argument array (and return value) as JNI
     * local references that lived until the outermost native call returned: a whole pending job.
     * A scraper parsing a page through the cheerio bindings kept every page and string it passed
     * across alive for that long. The patched DLL (src/desktopMain/native/quickjs-kt) gives each
     * call its own local frame. This passes a 1 MB string to a binding 100 times in one evaluation
     * and measures the heap from inside the last call, while any leaked references are still live.
     */
    @Test
    fun `binding calls release their arguments before the evaluation ends`() = runBlocking {
        if (System.getProperty(QuickJsNativeLibrary.LIBRARY_PATH_PROPERTY).isNullOrBlank()) {
            println("Skipped: patched libquickjs.dll not built (buildWindowsQuickJs needs MSYS2 UCRT64 gcc)")
            return@runBlocking
        }
        QuickJsNativeLibrary.select()
        val usedBytes = mutableListOf<Long>()
        var received = 0
        // Deliberately never closed: quickjs-kt's process-wide instance count must not reach zero
        // while PluginRuntime's runtimes share this JVM (see PluginRuntime.runtimeLifecycleLock).
        val runtime = QuickJs.create(jobDispatcher = Dispatchers.IO)
        runtime.function("sink") { args ->
            received += (args[0] as String).length
            null
        }
        runtime.function("measure") { _ ->
            repeat(2) { System.gc() }
            val heap = Runtime.getRuntime()
            usedBytes += heap.totalMemory() - heap.freeMemory()
            null
        }
        runtime.evaluate<Any?>(
            """
                var big = 'x'.repeat(1024 * 1024);
                measure();
                for (var i = 0; i < 100; i++) sink(big);
                measure();
                void 0;
            """.trimIndent(),
        )

        assertEquals(100 * 1024 * 1024, received)
        val retainedMb = (usedBytes[1] - usedBytes[0]) / (1024 * 1024)
        assertTrue(retainedMb < 40, "binding arguments still reachable inside the job: ~$retainedMb MB retained")
    }
}
