package com.nuvio.app

import com.nuvio.app.core.storage.DesktopStorage
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

private const val DESKTOP_LOG_MAX_BYTES = 2L * 1024L * 1024L
private const val DESKTOP_LOG_BACKUP_COUNT = 3

private val desktopFileLoggingConfigured = AtomicBoolean(false)

/** Captures console logging from packaged desktop builds in a small rolling log set. */
fun configureDesktopFileLogging() {
    if (!desktopFileLoggingConfigured.compareAndSet(false, true)) return

    val originalOut = System.out
    val originalErr = System.err
    runCatching {
        val logDirectory = desktopLogDirectory().apply { mkdirs() }
        val sink = RotatingLogSink(
            directory = logDirectory,
            fileName = "nuvio.log",
            maxBytes = DESKTOP_LOG_MAX_BYTES,
            backupCount = DESKTOP_LOG_BACKUP_COUNT,
        )
        System.setOut(PrintStream(TeeOutputStream(originalOut, sink), true, StandardCharsets.UTF_8))
        System.setErr(PrintStream(TeeOutputStream(originalErr, sink), true, StandardCharsets.UTF_8))

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            System.err.println("${Instant.now()} Uncaught exception on thread '${thread.name}'")
            error.printStackTrace(System.err)
            previousHandler?.uncaughtException(thread, error)
        }
        System.out.println("${Instant.now()} Nuvio desktop logging started: ${sink.activeFile.absolutePath}")
    }.onFailure { error ->
        originalErr.println("Unable to initialize Nuvio file logging: ${error.message}")
    }
}

private fun desktopLogDirectory(): File {
    return DesktopStorage.rootDir.resolve("logs").toFile()
}

private class TeeOutputStream(
    private val console: OutputStream,
    private val logSink: RotatingLogSink,
) : OutputStream() {
    override fun write(value: Int) {
        console.write(value)
        logSink.write(byteArrayOf(value.toByte()), 0, 1)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        console.write(bytes, offset, length)
        logSink.write(bytes, offset, length)
    }

    override fun flush() {
        console.flush()
        logSink.flush()
    }
}

private class RotatingLogSink(
    private val directory: File,
    private val fileName: String,
    private val maxBytes: Long,
    private val backupCount: Int,
) {
    val activeFile: File = File(directory, fileName)
    private var size = activeFile.takeIf(File::isFile)?.length() ?: 0L
    private var output = FileOutputStream(activeFile, true)

    @Synchronized
    fun write(bytes: ByteArray, offset: Int, length: Int) {
        runCatching {
            if (size > 0L && size + length > maxBytes) rotate()
            output.write(bytes, offset, length)
            size += length
        }
    }

    @Synchronized
    fun flush() {
        runCatching { output.flush() }
    }

    private fun rotate() {
        output.flush()
        output.close()
        for (index in backupCount downTo 1) {
            val source = if (index == 1) activeFile else File(directory, "$fileName.${index - 1}")
            val destination = File(directory, "$fileName.$index")
            if (source.exists()) {
                Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        output = FileOutputStream(activeFile, false)
        size = 0L
    }
}
