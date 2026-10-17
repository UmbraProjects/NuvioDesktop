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
 * Class loading is not tied to any thread, so the fix is to have already done it. A learning run
 * records every class load through JFR (`jdk.ClassLoad`, no stack traces) until a few seconds
 * after Home is ready, then writes the names in load order to [listFile]. Every later launch
 * replays that list on [PreloadThreads] threads from the first milliseconds of `main`, while the
 * overlay is still waiting on the network. Lists are keyed on the exact jar set; a stale or
 * partial list only costs a few failed lookups.
 *
 * A list also ships inside the app image ([BundledListName] beside the jars, from
 * `composeApp/startup-classes.lst`); being packaged with those jars is its key. Learning used
 * to happen on the first launch after every update — no preload *and* a JFR recording, so that
 * launch was the slowest and jankiest one a user ever saw (1 s+ UI freezes). Now a launch learns
 * only when neither its own list nor the bundled one fits (a Gradle run), when the bundled list
 * has gone stale (see [preload]), or when asked to: the `learn-startup-classes` marker file in the
 * data directory (consumed by the run) or `NUVIO_LEARN_STARTUP_CLASSES`. Packaging copies this
 * machine's newest learned list into `composeApp/startup-classes.lst` before bundling it.
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

    private const val BundledListName = "startup-classes.lst"

    private const val LearnMarkerName = "learn-startup-classes"

    /** A bundled list missing more than this share of its names (or [StaleMinMissing]) is relearned. */
    private const val StaleMissingFraction = 0.02
    private const val StaleMinMissing = 150

    private val log = Logger.withTag("StartupClassPreloader")
    private val listFile: Path by lazy { DesktopStorage.rootDir.resolve("startup-classes.lst") }
    private val learnMarker: Path by lazy { DesktopStorage.rootDir.resolve(LearnMarkerName) }

    /** Non-blocking; call once, as early in `main` as possible. */
    fun start() {
        if (NativeImageSupport.inImage || NativeImageSupport.tracing) {
            log.i { "native image (or its tracing run); preload skipped" }
            return
        }
        val fingerprint = classPathFingerprint()
        val forced = !System.getenv("NUVIO_LEARN_STARTUP_CLASSES").isNullOrBlank() ||
            Files.exists(learnMarker)
        if (forced) {
            log.i { "learning requested; learning a class list this launch" }
            learn(fingerprint)
            return
        }
        // A mapped archive already hands these classes over pre-parsed; replaying the list only
        // repeats that (~120 ms of redundant work). A launch *building* the archive is different:
        // there, defining a class is slow, and the preloader keeps it off the UI thread (measured
        // without it: 24 s to Home and a 5 s freeze, against 10 s with it).
        if (AppClassArchive.managed && !AppClassArchive.building) {
            log.i { "app class archive in use; preload skipped" }
            return
        }
        val local = runCatching { readList(listFile, fingerprint) }.getOrNull()
        // No key check for the bundled list: it sits beside the jars it was packaged with, so its
        // location already ties it to this build.
        val bundled = if (local == null) bundledListFile()?.let { file ->
            runCatching { readList(file, fingerprint = null) }.getOrNull()
        } else null
        val names = local ?: bundled
        if (names != null && names.isNotEmpty()) {
            if (bundled != null) log.i { "using the class list bundled with this build" }
            preload(replayOrder(names), relearnWhenStale = bundled != null)
        } else {
            log.i { "no usable class list for this build; learning one this launch" }
            learn(fingerprint)
        }
    }

    /**
     * Loads and links the whole list (this machine's, else the bundled one) and returns once done,
     * on as many threads as the machine has to spare. For the archive-training launch
     * ([AppClassArchiveTraining]): every class loaded here goes into the archive it writes at exit.
     * Returns how many classes it loaded, or -1 when there was no list to load.
     */
    fun preloadAllBlocking(): Int {
        val fingerprint = classPathFingerprint()
        val names = runCatching { readList(listFile, fingerprint) }.getOrNull()
            ?: bundledListFile()?.let { runCatching { readList(it, fingerprint = null) }.getOrNull() }
            ?: return -1
        val loader = StartupClassPreloader::class.java.classLoader
        val loaded = AtomicInteger()
        val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 8)
        val workers = (0 until threads).map { index ->
            thread(isDaemon = true, name = "nuvio-archive-train-$index") {
                var i = index
                while (i < names.size) {
                    val name = names[i]
                    if (!name.startsWith('#')) {
                        runCatching { Class.forName(name, false, loader).declaredConstructors }
                            .onSuccess { loaded.incrementAndGet() }
                    }
                    i += threads
                }
            }
        }
        workers.forEach(Thread::join)
        return loaded.get()
    }

    /** Beside the app jars in a packaged build; absent in a Gradle run, whose classes are a folder. */
    private fun bundledListFile(): Path? = runCatching {
        val codeSource = File(StartupClassPreloader::class.java.protectionDomain.codeSource.location.toURI())
        codeSource.takeIf { it.isFile }?.parentFile?.resolve(BundledListName)?.toPath()
            ?.takeIf { Files.isRegularFile(it) }
    }.getOrNull()

    // ---- replay ---------------------------------------------------------------------------------

    /** Startup segment reversed, then the post-ready tail forwards; see the class comment. */
    private fun replayOrder(lines: List<String>): List<String> {
        val marker = lines.indexOf(HomeReadyMarker)
        if (marker < 0) return lines.asReversed()
        return lines.subList(0, marker).asReversed() + lines.subList(marker + 1, lines.size)
    }

    /**
     * [relearnWhenStale]: replaying the bundled list, and too many of its names no longer exist,
     * so it was learned against code that has since moved on (renamed classes and the numbered
     * lambda classes around any edit both show up here). One learning run is then scheduled for
     * the next launch — never this one, which is already preloading — and that machine's own list
     * takes over. On the dev machine the build then copies it into the shipped list, so keeping
     * that list fresh needs no step of its own.
     */
    private fun preload(names: List<String>, relearnWhenStale: Boolean = false) {
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
                    val staleLimit = maxOf(StaleMinMissing, (names.size * StaleMissingFraction).toInt())
                    if (relearnWhenStale && failed.get() > staleLimit) {
                        runCatching { Files.createDirectories(learnMarker.parent); Files.createFile(learnMarker) }
                        log.i { "bundled list is stale (${failed.get()} > $staleLimit missing); next launch will learn" }
                    }
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
            // One learning run per request; later launches go back to replaying.
            runCatching { Files.deleteIfExists(learnMarker) }
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

    /** [fingerprint] null accepts any list with a valid header. */
    private fun readList(file: Path, fingerprint: String?): List<String>? {
        if (!Files.isRegularFile(file)) return null
        val lines = Files.readAllLines(file, StandardCharsets.UTF_8)
        val header = lines.firstOrNull() ?: return null
        if (!header.startsWith(HeaderPrefix) || (fingerprint != null && header != HeaderPrefix + fingerprint)) {
            log.i { "class list ${file.fileName} is for a different build" }
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
    internal fun classPathFingerprint(): String {
        val entries = System.getProperty("java.class.path").orEmpty()
            .split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .map { path -> File(path).let { "${it.name}:${it.length()}" } }
            .sorted()
        val version = System.getProperty("jpackage.app-version") ?: "dev"
        return version + "-" + entries.joinToString("|").hashCode().toUInt().toString(16)
    }
}
