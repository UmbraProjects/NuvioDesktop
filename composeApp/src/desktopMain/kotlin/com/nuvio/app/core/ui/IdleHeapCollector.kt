package com.nuvio.app.core.ui

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.NativeImageSupport
import com.nuvio.app.core.perf.PerfTelemetry
import com.nuvio.app.core.storage.CachePersistGate
import com.nuvio.app.core.storage.DesktopStorage
import com.sun.management.HotSpotDiagnosticMXBean
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.lang.management.GarbageCollectorMXBean
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** When the user last touched the app: keyboard, mouse, wheel, or gamepad (posted as key events). */
internal object UserInputActivity {
    @Volatile
    var lastInputAtMs: Long = System.currentTimeMillis()
        private set

    private val installed = AtomicBoolean(false)

    fun install() {
        if (!installed.compareAndSet(false, true)) return
        val mask = AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK or
            AWTEvent.MOUSE_WHEEL_EVENT_MASK or AWTEvent.KEY_EVENT_MASK
        runCatching {
            Toolkit.getDefaultToolkit().addAWTEventListener(
                AWTEventListener { event ->
                    val now = System.currentTimeMillis()
                    lastInputAtMs = now
                    if (event.id == java.awt.event.MouseEvent.MOUSE_MOVED ||
                        event.id == java.awt.event.MouseEvent.MOUSE_DRAGGED
                    ) {
                        PointerMotionClock.lastRealMoveAtMs = now
                    }
                },
                mask,
            )
        }
    }
}

/**
 * One `(FullGc)` line per full collection, labelled by what started it: `idle` ([IdleHeapCollector]),
 * `explicit` (the playback / hidden-window trims), or `natural` (the collector's own decision, the
 * one that lands mid-scroll). Polled from [IdleHeapCollector]'s thread, so it costs nothing extra.
 */
internal object FullGcLog {
    private val log = Logger.withTag("FullGc")

    @Volatile
    private var explicitReason: String? = null

    private val fullCollectors: List<GarbageCollectorMXBean> = runCatching {
        ManagementFactory.getGarbageCollectorMXBeans().filter { bean ->
            val name = bean.name.lowercase()
            // Serial GC on the native image: "complete scavenger". HotSpot: "G1 Old Generation",
            // and the other collectors' old-generation names.
            "complete" in name || "old" in name || "marksweep" in name
        }
    }.getOrDefault(emptyList())

    private var lastCount = -1L
    private var lastTimeMs = 0L

    /** Heap in use right after the most recent full collection, whoever started it. */
    @Volatile
    var usedAfterLastFullBytes: Long = usedHeapBytes()
        private set

    /** When the most recent full collection was seen, whoever started it. */
    @Volatile
    var lastFullAtMs: Long = System.currentTimeMillis()
        private set

    fun usedHeapBytes(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    /** Runs [action] (a `System.gc()`) with the collection it causes attributed to [reason]. */
    fun explicit(reason: String, action: () -> Unit) {
        explicitReason = reason
        try {
            action()
        } finally {
            poll()
            explicitReason = null
        }
    }

    /** Logs any full collection since the last poll. */
    @Synchronized
    fun poll() {
        if (fullCollectors.isEmpty()) return
        val count = fullCollectors.sumOf { it.collectionCount.coerceAtLeast(0) }
        val timeMs = fullCollectors.sumOf { it.collectionTime.coerceAtLeast(0) }
        if (lastCount < 0) {
            lastCount = count
            lastTimeMs = timeMs
            return
        }
        if (count <= lastCount) return
        val used = usedHeapBytes()
        usedAfterLastFullBytes = used
        lastFullAtMs = System.currentTimeMillis()
        val trigger = explicitReason ?: "natural"
        log.i {
            "$trigger: ${count - lastCount}x ${timeMs - lastTimeMs}ms, heap now ${used / (1024 * 1024)}MB"
        }
        lastCount = count
        lastTimeMs = timeMs
        if (trigger == "natural") HeapDumpOnHighLiveSet.check(used)
    }
}

/**
 * Perf logging only: one live-heap dump per run, the first time a natural full collection leaves
 * [ThresholdBytes] or more in use. Long-series details visits pushed the live set from ~200 to
 * 450 MB for 15-30 s (2026-10-19 04:58 and 04:59), and no log says what held it. The dump takes a
 * few seconds and holds session tokens, so it goes to the logs folder and should be deleted once read.
 * The native build needs `--enable-monitoring=heapdump` and the VMRuntime reflection entry in
 * native-image/management for this to work.
 */
internal object HeapDumpOnHighLiveSet {
    // 380 MB never fired in the 07:11 session (natural full GCs peaked at 374 MB live while the
    // post-GC heap crept 195 -> 374 MB), so one notch lower.
    private const val ThresholdBytes = 320L * 1024 * 1024
    private val log = Logger.withTag("HeapDump")
    private val done = AtomicBoolean(false)

    // Never during playback or just after it. A dump stops every thread for ~2.7 s, and the one
    // that landed as the player was closing (2026-10-19 07:49:28) was followed a second later by a
    // native abort (0xc0000409 in ucrtbase) with mpv mid-teardown.
    private const val AfterPlaybackQuietMs = 30_000L

    @Volatile
    private var lastPlaybackSeenAtMs = 0L

    /** From the idle collector's 250 ms tick. */
    fun notePlayback(active: Boolean) {
        if (active) lastPlaybackSeenAtMs = System.currentTimeMillis()
    }

    // Off: two dumps (07:54 browsing, 08:26 just after launch) have been read, and each one freezes
    // the app for ~2.7 s. Flip to true for a run that needs another.
    private const val Enabled = false

    fun check(usedBytes: Long) {
        if (!Enabled || usedBytes < ThresholdBytes || !PerfTelemetry.enabled) return
        if (DesktopArtworkCaches.isPlaybackActive ||
            System.currentTimeMillis() - lastPlaybackSeenAtMs < AfterPlaybackQuietMs
        ) {
            return
        }
        if (!done.compareAndSet(false, true)) return
        runCatching {
            val logs = DesktopStorage.rootDir.resolve("logs")
            Files.createDirectories(logs)
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val file = logs.resolve("nuvio-heap-$stamp.hprof")
            val startedAt = System.nanoTime()
            val path = file.toAbsolutePath().toString()
            if (NativeImageSupport.inImage) {
                // No HotSpotDiagnosticMXBean in the native image (2026-10-19: "not a platform
                // management interface"); its own API, by reflection (native-image/management).
                val vmRuntime = Class.forName("org.graalvm.nativeimage.VMRuntime")
                vmRuntime.getMethod("dumpHeap", String::class.java, Boolean::class.javaPrimitiveType).invoke(null, path, true)
            } else {
                ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean::class.java).dumpHeap(path, true)
            }
            log.i {
                "live heap ${usedBytes / (1024 * 1024)}MB after a natural full GC: dumped to $file in " +
                    "${(System.nanoTime() - startedAt) / 1_000_000}ms"
            }
        }.onFailure { error -> log.w(error) { "heap dump failed" } }
    }
}

/**
 * Runs the native build's full garbage collection at moments nobody can see it, the way a game
 * collects on a loading screen.
 *
 * The native image has only the Serial collector, and its full collection stops every thread for as
 * long as it takes to copy the live heap: ~200 ms for ~190 MB. It runs when the old generation fills
 * with objects that outlived a young collection, so hard scrolling hit one every ~10 s, mid-motion,
 * as a visible hitch (FrameStall/JFR logs, 2026-10-16). A collection run here empties the old
 * generation too, which pushes the next natural one back; with these running whenever the user
 * pauses, a natural one needs ~10 s of scrolling without a single pause.
 *
 * Runs when all of these hold: no input for [IdleInputMs], no self-running animation for
 * [IdleInputMs] ([UiMotion]), no playback session, at least [MinIntervalMs] since the last one this
 * started, and either the heap has grown by [MemoryUse.idleGrowthMb] since the last full collection
 * or it has grown by [MemoryUse.idleSlowGrowthMb] and [SlowIntervalMs] have passed. The slow rule takes over skiko's own
 * job, see [start]. Native only: the JVM build's G1 pauses for milliseconds and already collects
 * concurrently.
 */
internal object IdleHeapCollector {
    private const val CheckIntervalMs = 250L
    private const val IdleInputMs = 2_500L
    private const val MinIntervalMs = 20_000L
    // Growth thresholds come from Settings -> Advanced -> Memory use (MemoryUse). Low on purpose: an
    // idle collection is invisible, a natural one lands mid-scroll. At 128 / 32 MB natural ones still
    // won 9 to 3 in a 10-minute session; at 48 / 16 MB, 4 to 14 (2026-10-16).
    private const val SlowIntervalMs = 60_000L
    private const val Mb = 1024L * 1024

    private val log = Logger.withTag("IdleHeapCollector")
    private val started = AtomicBoolean(false)
    private var lastRunAtMs = 0L

    fun start() {
        if (!started.compareAndSet(false, true)) return
        if (NativeImageSupport.inImage) {
            // skiko's FrameWatcher calls System.gc() every 30 s once enough frames have rendered,
            // to get native Skia objects released. The JVM build makes that concurrent
            // (-XX:+ExplicitGCInvokesConcurrent); on the native image it is a full stop-the-world
            // collection, ~200 ms, every 30 s for every user - the regular hitch in normal use
            // (FullGc log, 2026-10-16: 25 of 27 collections in a 10-minute session, 30.2 s apart).
            // The slow rule below does the same job, but only while nobody is looking.
            // FrameWatcher is `internal` to skiko, so by reflection (its setter is not name-mangled).
            // Native-image metadata for it: composeApp/native-image/skiko/.
            runCatching {
                val watcher = Class.forName("org.jetbrains.skiko.FrameWatcher")
                val instance = watcher.getField("INSTANCE").get(null)
                watcher.getMethod("setGcDelayMillis", Long::class.javaPrimitiveType).invoke(instance, Long.MAX_VALUE)
                log.i { "skiko's periodic GC stopped; idle collection replaces it" }
            }.onFailure { error -> log.w(error) { "could not stop skiko's periodic GC" } }
        }
        UserInputActivity.install()
        // Batched cache rewrites wait for the same quiet moment this collector does, so the garbage
        // they make is collected here instead of by a natural full GC mid-scroll.
        CachePersistGate.awaitQuietMoment = {
            while (
                System.currentTimeMillis() - UserInputActivity.lastInputAtMs < IdleInputMs ||
                UiMotion.quietForMs() < IdleInputMs
            ) {
                kotlinx.coroutines.delay(CheckIntervalMs)
            }
        }
        // Memory use (thresholds here, hero cap in DesktopArtworkMemoryCache) is read live from it.
        runCatching { ImageCacheSettingsRepository.ensureLoaded() }
        FullGcLog.poll()
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "nuvio-idle-heap-collector").apply { isDaemon = true }
        }.scheduleWithFixedDelay(::check, CheckIntervalMs, CheckIntervalMs, TimeUnit.MILLISECONDS)
    }

    private fun check() {
        runCatching {
            HeapDumpOnHighLiveSet.notePlayback(DesktopArtworkCaches.isPlaybackActive)
            FullGcLog.poll()
            StartupYoungGcTrace.sample()
            if (!NativeImageSupport.inImage) return
            val now = System.currentTimeMillis()
            val grown = FullGcLog.usedHeapBytes() - FullGcLog.usedAfterLastFullBytes
            val memoryUse = ImageCacheSettingsRepository.settings.value.memoryUse
            val collect = shouldCollect(
                growthBytes = memoryUse.idleGrowthMb * Mb,
                slowGrowthBytes = memoryUse.idleSlowGrowthMb * Mb,
                sinceLastRunMs = now - lastRunAtMs,
                sinceInputMs = now - UserInputActivity.lastInputAtMs,
                motionQuietMs = UiMotion.quietForMs(),
                playbackActive = DesktopArtworkCaches.isPlaybackActive,
                grownBytes = grown,
                sinceLastFullMs = now - FullGcLog.lastFullAtMs,
            )
            if (!collect) return
            lastRunAtMs = now
            val startedAt = System.nanoTime()
            FullGcLog.explicit("idle, heap +${grown / (1024 * 1024)}MB") { System.gc() }
            log.d { "idle collection took ${(System.nanoTime() - startedAt) / 1_000_000}ms" }
        }.onFailure { error -> log.w(error) { "idle collection check failed" } }
    }

    internal fun shouldCollect(
        growthBytes: Long,
        slowGrowthBytes: Long,
        sinceLastRunMs: Long,
        sinceInputMs: Long,
        motionQuietMs: Long,
        playbackActive: Boolean,
        grownBytes: Long,
        sinceLastFullMs: Long,
    ): Boolean =
        sinceLastRunMs >= MinIntervalMs &&
            sinceInputMs >= IdleInputMs &&
            motionQuietMs >= IdleInputMs &&
            !playbackActive &&
            (grownBytes >= growthBytes || (grownBytes >= slowGrowthBytes && sinceLastFullMs >= SlowIntervalMs))
}
