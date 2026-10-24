package com.nuvio.app.core.build

import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.DesktopStorage
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Loads the classes Home's first composition is going to need on background threads, before it
 * needs them.
 *
 * Measured 2026-09-14 with the `(LaunchOverlay)` probe line and a JFR recording: the launch overlay
 * runs at 60fps for ~1.3s and then stops dead for 1.2–8s. Not GC, not a safepoint, not a lock —
 * JFR's `ClassLoadingStatistics` showed the JVM loading ~4,200 classes in that second, on the UI
 * thread, out of compressed jars: the moment the first catalog batch lands, the row/card/painter
 * recomposition touches thousands of classes nobody had loaded yet, and a composition pass cannot
 * yield halfway. Cold OS file cache makes it 8s.
 *
 * Class loading is not tied to any thread, so the fix is to have already done it. The list of
 * what to load is learned on this machine rather than shipped: a launch with no usable list
 * records every class load through JFR (`jdk.ClassLoad`, no stack traces) until a few seconds
 * after Home is ready, then writes the names in load order to [listFile]. Every later launch
 * replays that list on [PreloadThreads] threads from the first milliseconds of `main`, while the
 * overlay is still waiting on the network. The list is keyed on the exact jar set, so an update
 * re-learns; a stale or partial list only costs a few failed lookups.
 *
 * Replay order matters. The UI thread loads the head of the list itself, in the same order, from
 * the same moment — replaying forwards just races it over classes it was about to load anyway,
 * and the first measurement showed the preloader still 700ms from the end when Home stalled. So
 * the list is split at the point Home became ready (a `# home-ready` line) and the startup segment
 * is replayed from its far end backwards: the two threads meet in the middle, and the classes the
 * stall actually needs — the last ones before ready — are done first. The tail after the marker
 * (Home's own first frames) follows, forwards.
 *
 * Load *and link*: `Class.forName(name, false, …)` parses and defines but leaves verification to
 * first use, which would put the expensive half back on the UI thread. `getDeclaredConstructors`
 * forces linking without running static initialisers — those still run on first use, where they
 * belong.
 *
 * Why not AppCDS: the bundled runtime is JDK 17 with no base CDS archive, dynamic archives there
 * need a training run per build, and CDS validates jar timestamps, so an archive made before
 * zipping is rejected after extraction. This needs none of that.
 */
internal object StartupClassPreloader {

    private const val PreloadThreads = 4

    private const val HomeReadyMarker = "# home-ready"

    /** How long after Home is ready the learning run keeps recording: Home's own first frames. */
    private const val LearnTailMs = 4_000L

    /** A launch that never reaches Home (sign-in, a crash loop) still stops recording. */
    private const val LearnCapMs = 90_000L

    private const val HeaderPrefix = "# nuvio-startup-classes "

    private val log = Logger.withTag("StartupClassPreloader")
    private val listFile: Path by lazy { DesktopStorage.rootDir.resolve("startup-classes.lst") }

    /** Non-blocking; call once, as early in `main` as possible. */
    fun start() {
        val fingerprint = classPathFingerprint()
        val names = runCatching { readList(fingerprint) }.getOrNull()
        if (names != null && names.isNotEmpty()) {
            preload(replayOrder(names))
        } else {
            log.i { "no usable class list for this build; learning one this launch" }
            learn(fingerprint)
        }
    }

    // ---- replay ---------------------------------------------------------------------------------

    /** Startup segment reversed, then the post-ready tail forwards; see the class comment. */
    private fun replayOrder(lines: List<String>): List<String> {
        val marker = lines.indexOf(HomeReadyMarker)
        if (marker < 0) return lines.asReversed()
        return lines.subList(0, marker).asReversed() + lines.subList(marker + 1, lines.size)
    }

    private fun preload(names: List<String>) {
        val loader = StartupClassPreloader::class.java.classLoader
        val loaded = AtomicInteger()
        val failed = AtomicInteger()
        val startedAt = System.nanoTime()
        val remaining = AtomicInteger(PreloadThreads)
        repeat(PreloadThreads) { index ->
            thread(isDaemon = true, name = "nuvio-class-preload-$index", priority = Thread.NORM_PRIORITY - 1) {
                var i = index
                while (i < names.size) {
                    try {
                        if (names[i].startsWith('#')) { i += PreloadThreads; continue }
                        val cls = Class.forName(names[i], false, loader)
                        cls.declaredConstructors
                        loaded.incrementAndGet()
                    } catch (_: Throwable) {
                        failed.incrementAndGet()
                    }
                    i += PreloadThreads
                }
                if (remaining.decrementAndGet() == 0) {
                    val ms = (System.nanoTime() - startedAt) / 1_000_000
                    log.i { "preloaded ${loaded.get()} classes (${failed.get()} not found) in ${ms}ms on $PreloadThreads threads" }
                }
            }
        }
    }

    // ---- learn ----------------------------------------------------------------------------------

    private fun learn(fingerprint: String) {
        // JFR's first use costs a couple of hundred milliseconds; off the main thread, and the
        // handful of bootstrap classes loaded meanwhile are ones the JVM would load anyway.
        thread(isDaemon = true, name = "nuvio-class-learn") {
            val recording = runCatching {
                jdk.jfr.Recording().apply {
                    name = "nuvio-startup-classes"
                    enable("jdk.ClassLoad").withoutThreshold().withoutStackTrace()
                    isToDisk = true
                    start()
                }
            }.getOrElse { error ->
                log.i { "learning unavailable: ${error.message}" }
                return@thread
            }
            val stop = Object()
            var ready = false
            var readyAtEpochMs = Long.MAX_VALUE
            StartupReadySignal.onHomeReady {
                synchronized(stop) {
                    ready = true
                    readyAtEpochMs = System.currentTimeMillis()
                    stop.notifyAll()
                }
            }
            synchronized(stop) {
                val deadline = System.currentTimeMillis() + LearnCapMs
                while (!ready && System.currentTimeMillis() < deadline) {
                    stop.wait(deadline - System.currentTimeMillis())
                }
            }
            if (ready) Thread.sleep(LearnTailMs)
            runCatching { finishLearning(recording, fingerprint, readyAtEpochMs) }
                .onFailure { error -> log.i { "learning failed: ${error.message}" } }
        }
    }

    private fun finishLearning(recording: jdk.jfr.Recording, fingerprint: String, readyAtEpochMs: Long) {
        val dump = Files.createTempFile("nuvio-startup-classes-", ".jfr")
        try {
            recording.stop()
            recording.dump(dump)
            recording.close()
            val startup = LinkedHashSet<String>()
            val tail = LinkedHashSet<String>()
            jdk.jfr.consumer.RecordingFile(dump).use { file ->
                while (file.hasMoreEvents()) {
                    val event = file.readEvent()
                    if (event.eventType.name != "jdk.ClassLoad") continue
                    val name = event.getClass("loadedClass")?.name ?: continue
                    if (!isLoadableByName(name)) continue
                    if (event.startTime.toEpochMilli() < readyAtEpochMs) startup += name else tail += name
                }
            }
            tail.removeAll(startup)
            writeList(fingerprint, startup + HomeReadyMarker + tail)
            log.i { "learned ${startup.size} startup classes + ${tail.size} after Home ready -> $listFile" }
        } finally {
            runCatching { Files.deleteIfExists(dump) }
        }
    }

    /**
     * Hidden classes, lambda proxies and arrays cannot be looked up by name; skip them. JFR
     * writes a hidden class as `Outer$$Lambda$12+0x…` (a `+` where the JVM prints `/`).
     */
    private fun isLoadableByName(name: String): Boolean =
        !name.startsWith("[") && '/' !in name && "+0x" !in name && "\$\$Lambda" !in name

    // ---- list file ------------------------------------------------------------------------------

    private fun readList(fingerprint: String): List<String>? {
        if (!Files.isRegularFile(listFile)) return null
        val lines = Files.readAllLines(listFile, StandardCharsets.UTF_8)
        val header = lines.firstOrNull() ?: return null
        if (header != HeaderPrefix + fingerprint) {
            log.i { "class list is for a different build; will re-learn" }
            return null
        }
        return lines.drop(1).filter { it.isNotBlank() && (it == HomeReadyMarker || !it.startsWith('#')) }
    }

    private fun writeList(fingerprint: String, names: Collection<String>) {
        Files.createDirectories(listFile.parent)
        val tmp = listFile.resolveSibling("${listFile.fileName}.tmp")
        Files.newBufferedWriter(tmp, StandardCharsets.UTF_8).use { out ->
            out.write(HeaderPrefix + fingerprint)
            out.newLine()
            for (name in names) {
                out.write(name)
                out.newLine()
            }
        }
        runCatching { Files.move(tmp, listFile, StandardCopyOption.ATOMIC_MOVE) }
            .recoverCatching { Files.move(tmp, listFile, StandardCopyOption.REPLACE_EXISTING) }
            .getOrThrow()
    }

    /**
     * The jar set this list was learned against. jpackage names every jar after its content hash,
     * so file names alone change whenever the code does; sizes cover a dev run's plain names.
     */
    private fun classPathFingerprint(): String {
        val entries = System.getProperty("java.class.path").orEmpty()
            .split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .map { path -> File(path).let { "${it.name}:${it.length()}" } }
            .sorted()
        val version = System.getProperty("jpackage.app-version") ?: "dev"
        return version + "-" + entries.joinToString("|").hashCode().toUInt().toString(16)
    }
}
