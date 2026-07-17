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
        relocateNativeCrashArtifacts(logDirectory)
    }.onFailure { error ->
        originalErr.println("Unable to initialize Nuvio file logging: ${error.message}")
    }
}

private fun desktopLogDirectory(): File {
    return DesktopStorage.rootDir.resolve("logs").toFile()
}

/**
 * Moves JVM native-crash artifacts into the normal log directory so they sit beside nuvio.log and
 * get picked up by the "Open logs folder" flow / user log bundles.
 *
 * These are the `hs_err_pid*.log` HotSpot crash report and the `.mdmp` minidump produced by the
 * `-XX:ErrorFile` / `-XX:+CreateCoredumpOnCrash` flags (see the jvmArgs block in
 * composeApp/build.gradle.kts). `-XX:ErrorFile` is a static startup flag that can't expand
 * `%LOCALAPPDATA%` into this user's per-profile log dir, so the JVM is pointed at a fixed,
 * always-writable drop location (C:\Users\Public) and we consolidate on the next launch. This is
 * inherently crash-then-relaunch: the crash kills the process, so the move can only happen after.
 * Windows-only, matching the flags; a no-op elsewhere.
 */
private fun relocateNativeCrashArtifacts(logDirectory: File) {
    val isWindows = System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)
    if (!isWindows) return

    // (directory, broad) — `broad` also sweeps default-named hs_err_pid*/*.mdmp files. Only enabled
    // for the Public drop dir that is ours; TEMP (a JVM fallback if Public was unwritable) is
    // restricted to our nuvio_ prefix so we never grab another JVM app's crash logs.
    val sources = buildList {
        add(File("C:\\Users\\Public") to true)
        System.getenv("PUBLIC")?.let { add(File(it) to true) }
        System.getenv("TEMP")?.let { add(File(it) to false) }
    }
    val seenDirs = mutableSetOf<String>()
    for ((dir, broad) in sources) {
        val key = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
        if (!seenDirs.add(key) || !dir.isDirectory) continue
        val crashFiles = dir.listFiles { file ->
            file.isFile && (
                file.name.startsWith("nuvio_hs_err_pid") ||
                    (broad && file.name.startsWith("hs_err_pid"))
                )
        } ?: continue
        for (source in crashFiles) {
            val destination = File(logDirectory, source.name)
            runCatching {
                Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }.recoverCatching {
                // Fall back to copy+delete if the move can't be done atomically.
                Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                source.delete()
            }.onSuccess {
                System.out.println("${Instant.now()} Relocated native crash artifact to ${destination.absolutePath}")
            }
        }
    }
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
