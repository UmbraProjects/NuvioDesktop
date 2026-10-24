package com.nuvio.app

import com.nuvio.app.core.storage.DesktopStorage
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

// 50 MB of history. At 2 MB x 4 an hour of browsing rotated away everything, including the
// minutes before a crash that the 15-35 minute CTD investigations needed.
private const val DESKTOP_LOG_MAX_BYTES = 10L * 1024L * 1024L
private const val DESKTOP_LOG_BACKUP_COUNT = 4

/**
 * Debug lines are written only when asked for: a file named `debug-log` in the Nuvio data
 * directory (survives any launch method), `NUVIO_DEBUG_LOG` set, or a dev run from Gradle.
 * Otherwise Info and above. Debug volume (per-image timings, per-fetch payload notes, the
 * NextUpDiag/HeroLogoRace investigation tags) wrapped the whole log in well under an hour.
 */
fun desktopDebugLoggingRequested(): Boolean {
    val marker = runCatching { DesktopStorage.rootDir.resolve("debug-log").toFile().exists() }.getOrDefault(false)
    val env = !System.getenv("NUVIO_DEBUG_LOG").isNullOrBlank()
    val devRun = System.getProperty("jpackage.app-path").isNullOrBlank()
    return marker || env || devRun
}

private val desktopFileLoggingConfigured = AtomicBoolean(false)

/**
 * Captures console logging from packaged desktop builds in a small rolling log set.
 *
 * Every line in the file is prefixed with a local wall-clock stamp (`yyyy-MM-dd HH:mm:ss.SSS`), the
 * same clock Explorer shows for the files next to it, so a timeline can be read straight off the
 * log and cross-referenced with cache files and crash dumps without converting from UTC. The
 * console keeps the bare lines: the IDE/Gradle consoles stamp them themselves.
 */
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
            System.err.println("Uncaught exception on thread '${thread.name}'")
            error.printStackTrace(System.err)
            previousHandler?.uncaughtException(thread, error)
        }
        val debugLogging = desktopDebugLoggingRequested()
        co.touchlab.kermit.Logger.setMinSeverity(
            if (debugLogging) co.touchlab.kermit.Severity.Debug else co.touchlab.kermit.Severity.Info,
        )
        System.out.println(
            "Nuvio desktop logging started: ${sink.activeFile.absolutePath} " +
                "(debug lines ${if (debugLogging) "on" else "off; create a file named debug-log in ${DesktopStorage.rootDir} to turn them on"}) " +
                "(line stamps are local time, ${ZoneId.systemDefault()} = UTC${localUtcOffset()}; " +
                "now ${Instant.now()})"
        )
        relocateJvmDiagnosticArtifacts(logDirectory)
        if (System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)) {
            enableSafepointLog(logDirectory)
        }
    }.onFailure { error ->
        originalErr.println("Unable to initialize Nuvio file logging: ${error.message}")
    }
}

private fun desktopLogDirectory(): File {
    return DesktopStorage.rootDir.resolve("logs").toFile()
}

private fun localUtcOffset(): String =
    ZoneId.systemDefault().rules.getOffset(Instant.now()).id.let { if (it == "Z") "+00:00" else it }

/**
 * Moves JVM diagnostic artifacts into the normal log directory so they sit beside nuvio.log and
 * get picked up by the "Open logs folder" flow / user log bundles, then prunes old ones.
 *
 * These are the `nuvio_hs_err_pid*.log` HotSpot crash report (`-XX:ErrorFile=$APPDIR/…`) and the
 * `hs_err_pid*.mdmp` minidump (`-XX:+CreateCoredumpOnCrash`, written to the working directory),
 * both static startup flags that cannot expand `%LOCALAPPDATA%`; see the jvmArgs block in
 * composeApp/build.gradle.kts. For crash artifacts the move is inherently crash-then-relaunch.
 *
 * They used to go to C:\Users\Public, which every local account can read (hs_err carries the
 * environment and command line) and pre-create. That folder is still swept so older artifacts
 * are collected, but nothing new is written there. Minidumps hold heap memory — tokens and keys —
 * and can run to gigabytes, so only the newest [KEEP_MINIDUMPS] are kept.
 */
private fun relocateJvmDiagnosticArtifacts(logDirectory: File) {
    val isWindows = System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)
    if (!isWindows) return

    val installRoot = System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }
        ?.let { File(it).parentFile }
    // (directory, broad) — `broad` also sweeps default-named hs_err_pid* files. Only for folders
    // that are ours; TEMP (the JVM's fallback) is restricted to our nuvio_ prefix so we never grab
    // another JVM app's crash logs.
    val sources = buildList {
        installRoot?.let { root ->
            add(root to true)
            add(File(root, "app") to true)
        }
        add(File("C:\\Users\\Public") to true)
        System.getenv("PUBLIC")?.let { add(File(it) to true) }
        System.getenv("TEMP")?.let { add(File(it) to false) }
    }
    // This run's own files are open (or not written yet); skip anything carrying our PID.
    val currentPidMarker = "pid${ProcessHandle.current().pid()}"
    val seenDirs = mutableSetOf<String>()
    for ((dir, broad) in sources) {
        val key = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
        if (!seenDirs.add(key) || !dir.isDirectory) continue
        val artifacts = dir.listFiles { file ->
            file.isFile && (
                file.name.startsWith("nuvio_hs_err_pid") ||
                    file.name.startsWith("nuvio_safepoint_pid") ||
                    (broad && file.name.startsWith("hs_err_pid"))
                ) && !file.name.contains(currentPidMarker)
        } ?: continue
        for (source in artifacts) {
            val destination = File(logDirectory, source.name)
            runCatching {
                Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }.recoverCatching {
                // Fall back to copy+delete if the move can't be done atomically.
                Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                source.delete()
            }.onSuccess {
                System.out.println("Relocated JVM diagnostic artifact to ${destination.absolutePath}")
            }
        }
    }
    pruneJvmDiagnosticArtifacts(logDirectory, currentPidMarker)
}

private const val KEEP_MINIDUMPS = 2
private const val SAFEPOINT_LOG_MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000

private fun pruneJvmDiagnosticArtifacts(logDirectory: File, currentPidMarker: String) {
    val files = logDirectory.listFiles(File::isFile).orEmpty()
    files.filter { it.name.endsWith(".mdmp", ignoreCase = true) }
        .sortedByDescending(File::lastModified)
        .drop(KEEP_MINIDUMPS)
        .forEach { runCatching { it.delete() } }
    // One set per session (with .0/.1/.2 rotations): 500+ files and 100 MB after a few weeks.
    val cutoff = System.currentTimeMillis() - SAFEPOINT_LOG_MAX_AGE_MS
    files.filter { file ->
        file.name.startsWith("nuvio_safepoint_pid") && !file.name.contains(currentPidMarker) &&
            file.lastModified() < cutoff
    }.forEach { runCatching { it.delete() } }
}

/**
 * Turns on the stop-the-world (safepoint + GC) log at runtime, straight into this user's log
 * directory. It exists for the "UI froze then recovered" hitch: a safepoint halts every Java
 * thread, so nuvio.log just stops mid-burst; this names each VM operation and splits reaching the
 * safepoint from time spent at it ("allocated too hard" vs "the OS was not scheduling us"). It was a `-Xlog:…:file=C:/Users/Public/…` startup flag because a startup flag cannot
 * expand `%LOCALAPPDATA%`; the `VM.log` diagnostic command can take any path, so the only thing
 * lost is the first few hundred milliseconds before main() gets here. One line per safepoint,
 * capped at 3 x 2 MB per session. Silently skipped if the management module is missing.
 */
private fun enableSafepointLog(logDirectory: File) {
    runCatching {
        val pid = ProcessHandle.current().pid()
        val path = File(logDirectory, "nuvio_safepoint_pid$pid.log").absolutePath.replace('\\', '/')
        java.lang.management.ManagementFactory.getPlatformMBeanServer().invoke(
            javax.management.ObjectName("com.sun.management:type=DiagnosticCommand"),
            "vmLog",
            arrayOf<Any>(
                arrayOf(
                    // Quoted, so a path with spaces stays one argument.
                    "output=\"file=$path\" what=safepoint,gc decorators=time,uptime,level,tags " +
                        "output_options=filesize=2m,filecount=3",
                ),
            ),
            arrayOf(Array<String>::class.java.name),
        )
    }.onFailure { error ->
        System.out.println("Safepoint log not enabled: ${error.message}")
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

private val LogLineStampFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS ")

/**
 * Append-only rolling sink that keeps writing no matter what happens to the file underneath it.
 *
 * The 2026-09-22 investigation found nuvio.log frozen just under the cap for hours: a second,
 * orphaned Nuvio process held the file open, the `Files.move` in the rotation failed on its handle,
 * and the previous implementation had already closed its stream — so every later write threw into a
 * `runCatching` and vanished. Two changes stop that recurring:
 *
 *  - The file is opened through NIO, which on Windows shares delete/rename with other handles
 *    (`FileOutputStream` does not). A concurrent process rotating the same file therefore succeeds
 *    instead of wedging.
 *  - Rotation degrades rather than dying. If the backups cannot be shuffled the active file is
 *    truncated in place; if even that fails the stream is simply reopened for append and the cap is
 *    exceeded. Silence is the one outcome that is never acceptable, because it looks exactly like
 *    "nothing happened".
 *
 * Every line is stamped with local wall-clock time at the point it starts (see [stamp]); chunks that
 * continue a line are passed through unchanged, so a `PrintStream` that writes text and newline
 * separately still yields one stamp per line.
 */
internal class RotatingLogSink(
    private val directory: File,
    private val fileName: String,
    private val maxBytes: Long,
    private val backupCount: Int,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    val activeFile: File = File(directory, fileName)
    private var size = activeFile.takeIf(File::isFile)?.length() ?: 0L
    private var output: OutputStream? = runCatching { openForAppend() }.getOrNull()
    private var atLineStart = true

    @Synchronized
    fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        val stamped = stamp(bytes, offset, length)
        runCatching {
            if (size > 0L && size + stamped.size > effectiveMaxBytes) rotate()
            val out = output ?: openForAppend().also { output = it }
            out.write(stamped)
            size += stamped.size
        }.onFailure {
            // A failed write leaves the stream in an unknown state; drop it so the next write
            // reopens rather than failing forever on a closed handle.
            runCatching { output?.close() }
            output = null
        }
    }

    @Synchronized
    fun flush() {
        runCatching { output?.flush() }
    }

    /** Prefixes each line start inside the chunk with the current local time. */
    private fun stamp(bytes: ByteArray, offset: Int, length: Int): ByteArray {
        val last = offset + length - 1
        var lineStarts = if (atLineStart) 1 else 0
        for (i in offset until last) if (bytes[i] == NEWLINE) lineStarts++
        if (lineStarts == 0) {
            atLineStart = bytes[last] == NEWLINE
            return bytes.copyOfRange(offset, offset + length)
        }
        val prefix = LogLineStampFormat.format(LocalDateTime.now(zone)).toByteArray(StandardCharsets.US_ASCII)
        val result = ByteArray(length + lineStarts * prefix.size)
        var out = 0
        var pendingStamp = atLineStart
        for (i in offset..last) {
            if (pendingStamp) {
                prefix.copyInto(result, out)
                out += prefix.size
                pendingStamp = false
            }
            val b = bytes[i]
            result[out++] = b
            if (b == NEWLINE && i != last) pendingStamp = true
        }
        atLineStart = bytes[last] == NEWLINE
        return result
    }

    private fun openForAppend(): OutputStream =
        Files.newOutputStream(activeFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.APPEND)

    private fun rotate() {
        runCatching { output?.flush(); output?.close() }
        output = null
        val shuffled = runCatching {
            for (index in backupCount downTo 1) {
                val source = if (index == 1) activeFile else File(directory, "$fileName.${index - 1}")
                val destination = File(directory, "$fileName.$index")
                if (source.exists()) {
                    Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        if (shuffled.isSuccess) {
            output = openForAppend()
            size = 0L
            maxBytesOverride = 0L
            atLineStart = true
            return
        }
        val reason = shuffled.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
        val truncated = runCatching {
            Files.newOutputStream(
                activeFile.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
        }
        if (truncated.isSuccess) {
            output = truncated.getOrThrow()
            size = 0L
            atLineStart = true
            writeMarker("rotation could not move $fileName aside ($reason); truncated in place instead")
        } else {
            // Last resort: keep appending past the cap. Retrying the rotation on every write would
            // fail the same way, so only try again once another cap's worth has been written.
            output = openForAppend()
            maxBytesOverride += maxBytes
            writeMarker("rotation failed ($reason) and the file could not be truncated; continuing past the cap")
        }
    }

    private var maxBytesOverride = 0L
    private val effectiveMaxBytes: Long get() = maxBytes + maxBytesOverride

    private fun writeMarker(message: String) {
        val raw = "[log] $message\n".toByteArray(StandardCharsets.UTF_8)
        val line = stamp(raw, 0, raw.size)
        runCatching { output?.write(line) }
        size += line.size
    }

    private companion object {
        const val NEWLINE: Byte = '\n'.code.toByte()
    }
}
