package com.nuvio.app.core.ui

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import java.awt.EventQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UiFreezeWatchdogTest {
    @Test
    fun `a blocked UI thread is reported once with the blocking frame`() {
        val lines = CopyOnWriteArrayList<String>()
        val capture = object : LogWriter() {
            override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
                if (tag == "UiFreeze") lines += message
            }
        }
        Logger.addLogWriter(capture)
        try {
            UiFreezeWatchdog.startWatching()
            // Let the first ping capture the UI thread, then block it.
            Thread.sleep(400)
            // The reported time runs from the first ping posted into the block, so up to one ping
            // interval (250 ms) short of the block itself.
            EventQueue.invokeAndWait { blockUiThreadFor(2_000) }
            Thread.sleep(600)
            assertEquals(1, lines.size, "lines: $lines")
            assertTrue("blockUiThreadFor" in lines.single(), lines.single())
            Thread.sleep(800)
            assertEquals(1, lines.size, "no report once the UI thread answers again: $lines")
        } finally {
            Logger.setLogWriters(Logger.config.logWriterList - capture)
        }
    }

    private fun blockUiThreadFor(ms: Long) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) Thread.onSpinWait()
    }
}
