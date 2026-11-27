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
import java.lang.management.MemoryPoolMXBean
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
    private var lastEdenMb = -1L
    private var lastOldMb = -1L

    /** Heap in use right after the most recent full collection, whoever started it. */
    @Volatile
    var usedAfterLastFullBytes: Long = usedHeapBytes()
        private set

    /** Old-generation bytes in use right after the most recent full collection (-1 if unavailable). */
    @Volatile
    var oldUsedAfterLastFullBytes: Long = HeapPools.oldUsedBytes()
        private set

    /** When the most recent full collection was seen, whoever started it. */
    @Volatile
    var lastFullAtMs: Long = System.currentTimeMillis()
        private set

    fun usedHeapBytes(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    /** Old-generation growth since the last full collection (falls back to total heap if unavailable). */
    fun oldGenGrowthBytes(): Long {
        val currentOld = HeapPools.oldUsedBytes()
        val baselineOld = oldUsedAfterLastFullBytes
        if (currentOld >= 0L && baselineOld >= 0L) return currentOld - baselineOld
        return usedHeapBytes() - usedAfterLastFullBytes
    }

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
        val pools = HeapPools.sampleMb()
        if (lastCount < 0) {
            lastCount = count
            lastTimeMs = timeMs
            lastEdenMb = pools.edenMb
            lastOldMb = pools.oldMb
            return
        }
        if (count <= lastCount) {
            lastEdenMb = pools.edenMb
            lastOldMb = pools.oldMb
            return
        }
        val prevOld = lastOldMb
        val prevEden = lastEdenMb
        val used = usedHeapBytes()
        usedAfterLastFullBytes = used
        oldUsedAfterLastFullBytes = HeapPools.oldUsedBytes()
        lastFullAtMs = System.currentTimeMillis()
        IdleHeapCollector.resetArtworkDecoded()
        val trigger = explicitReason ?: "natural"
        log.i {
            "$trigger: ${count - lastCount}x ${timeMs - lastTimeMs}ms, heap now ${used / (1024 * 1024)}MB " +
                "(old ${prevOld}->${pools.oldMb}MB, prevEden ${prevEden}MB)"
        }
        lastCount = count
        lastTimeMs = timeMs
        lastEdenMb = pools.edenMb
        lastOldMb = pools.oldMb
        // Idle too: with the Proportionate policy (2026-11-25) natural full GCs are rare, and an idle
        // collection is the one moment a ~3 s dump freeze goes unseen.
        if (trigger == "natural" || trigger.startsWith("idle")) HeapDumpOnHighLiveSet.check(used)
    }
}

internal data class HeapPoolsMb(val edenMb: Long, val survivorMb: Long, val oldMb: Long)

internal object HeapPools {
    private const val Mb = 1024L * 1024
    private val pools: List<MemoryPoolMXBean> = runCatching {
        ManagementFactory.getMemoryPoolMXBeans().filter { it.isValid }
    }.getOrDefault(emptyList())
    private val edenPool = pools.firstOrNull { "eden" in it.name.lowercase() }
    private val survivorPool = pools.firstOrNull { "survivor" in it.name.lowercase() }
    private val oldPool = pools.firstOrNull {
        val n = it.name.lowercase()
        "old" in n || "tenured" in n
    }

    private fun MemoryPoolMXBean?.usedMb(): Long =
        this?.let { runCatching { it.usage.used / Mb }.getOrNull() } ?: -1L

    fun oldUsedBytes(): Long =
        oldPool?.let { runCatching { it.usage.used }.getOrNull() } ?: -1L

    fun sampleMb(): HeapPoolsMb = HeapPoolsMb(
        edenMb = edenPool.usedMb(),
        survivorMb = survivorPool.usedMb(),
        oldMb = oldPool.usedMb(),
    )
}

/**
 * Perf logging only: one `(HeapTrend)` line every [IntervalMs] showing young/full GC counts,
 * eden/survivor/old pool usage, and old-gen growth rate across browsing and playback, plus two
 * browsing `(MemoryBreakdown)` baselines (~30s and ~90s after launch).
 */
internal object HeapTrendTrace {
    private const val IntervalMs = 15_000L
    private const val HomeBaselineAtMs = 30_000L
    private const val BrowsingLaterAtMs = 90_000L
    private const val Mb = 1024L * 1024

    private val log = Logger.withTag("HeapTrend")
    private val startedAtMs = System.currentTimeMillis()
    private val collectors: List<GarbageCollectorMXBean> = runCatching {
        ManagementFactory.getGarbageCollectorMXBeans()
    }.getOrDefault(emptyList())
    private val youngCollectors = collectors.filterNot { bean ->
        val n = bean.name.lowercase()
        "complete" in n || "old" in n || "marksweep" in n
    }
    private val fullCollectors = collectors.filter { bean ->
        val n = bean.name.lowercase()
        "complete" in n || "old" in n || "marksweep" in n
    }

    private var lastSampleAtMs = 0L
    private var lastYoungCount = -1L
    private var lastYoungMs = 0L
    private var lastFullCount = -1L
    private var lastFullMs = 0L
    private var lastOldMb = -1L
    private var loggedHomeBaseline = false
    private var loggedBrowsingLater = false

    fun sample() {
        if (!PerfTelemetry.enabled) return
        val now = System.currentTimeMillis()
        val uptimeMs = now - startedAtMs
        val playbackActive = com.nuvio.app.core.ui.DesktopArtworkCaches.isPlaybackActive
        if (!playbackActive && !loggedHomeBaseline && uptimeMs >= HomeBaselineAtMs) {
            loggedHomeBaseline = true
            runCatching { com.nuvio.app.core.diagnostics.MemoryBreakdownSampler.log("home +30s") }
        } else if (!playbackActive && !loggedBrowsingLater && uptimeMs >= BrowsingLaterAtMs) {
            loggedBrowsingLater = true
            runCatching { com.nuvio.app.core.diagnostics.MemoryBreakdownSampler.log("browsing +90s") }
        }
        if (lastSampleAtMs == 0L) {
            lastSampleAtMs = now
            lastYoungCount = youngCollectors.sumOf { it.collectionCount.coerceAtLeast(0) }
            lastYoungMs = youngCollectors.sumOf { it.collectionTime.coerceAtLeast(0) }
            lastFullCount = fullCollectors.sumOf { it.collectionCount.coerceAtLeast(0) }
            lastFullMs = fullCollectors.sumOf { it.collectionTime.coerceAtLeast(0) }
            lastOldMb = HeapPools.sampleMb().oldMb
            return
        }
        if (now - lastSampleAtMs < IntervalMs) return
        val yCount = youngCollectors.sumOf { it.collectionCount.coerceAtLeast(0) }
        val yMs = youngCollectors.sumOf { it.collectionTime.coerceAtLeast(0) }
        val fCount = fullCollectors.sumOf { it.collectionCount.coerceAtLeast(0) }
        val fMs = fullCollectors.sumOf { it.collectionTime.coerceAtLeast(0) }
        val pools = HeapPools.sampleMb()
        val heapUsage = runCatching { ManagementFactory.getMemoryMXBean().heapMemoryUsage }.getOrNull()
        val oldDelta = if (lastOldMb >= 0 && pools.oldMb >= 0) pools.oldMb - lastOldMb else 0L
        val deltaSign = if (oldDelta >= 0) "+" else ""
        val mode = if (playbackActive) "playback" else "browsing"
        log.i {
            "mode=$mode young=${yCount - lastYoungCount}x/${yMs - lastYoungMs}ms " +
                "full=${fCount - lastFullCount}x/${fMs - lastFullMs}ms " +
                "eden=${pools.edenMb}MB surv=${pools.survivorMb}MB old=${pools.oldMb}MB(${deltaSign}${oldDelta}MB) " +
                "heap=${heapUsage?.used?.div(Mb) ?: -1}/${heapUsage?.committed?.div(Mb) ?: -1}MB"
        }
        lastSampleAtMs = now
        lastYoungCount = yCount
        lastYoungMs = yMs
        lastFullCount = fCount
        lastFullMs = fMs
        lastOldMb = pools.oldMb
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
    // Temporary baseline capture (2026-11-25): captures one baseline dump (>= 170 MB, e.g. settled
    // Home) and optionally one high-heap dump (>= 350 MB, e.g. after opening Live TV). Flip Enabled
    // back to false once the baseline capture is read and delete the .hprof files (they hold tokens).
    private const val BaselineThresholdBytes = 170L * 1024 * 1024
    private const val HighThresholdBytes = 350L * 1024 * 1024
    private val log = Logger.withTag("HeapDump")
    private val baselineDone = AtomicBoolean(false)
    private val highDone = AtomicBoolean(false)

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

    // Off again: baseline dump read on 2026-11-25 (nuvio-heap-baseline-20261125-050642.hprof).
    private const val Enabled = false

    fun check(usedBytes: Long) {
        if (!Enabled || !PerfTelemetry.enabled) return
        if (DesktopArtworkCaches.isPlaybackActive ||
            System.currentTimeMillis() - lastPlaybackSeenAtMs < AfterPlaybackQuietMs
        ) {
            return
        }
        val label = when {
            usedBytes >= HighThresholdBytes && highDone.compareAndSet(false, true) -> "high"
            usedBytes >= BaselineThresholdBytes && baselineDone.compareAndSet(false, true) -> "baseline"
            else -> return
        }
        runCatching {
            val logs = DesktopStorage.rootDir.resolve("logs")
            Files.createDirectories(logs)
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val file = logs.resolve("nuvio-heap-$label-$stamp.hprof")
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
                "live heap ${usedBytes / (1024 * 1024)}MB ($label) after full GC: dumped to $file in " +
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
    /** Native Skia pixels decoded since the last full GC before the slow cleaner rule fires. */
    internal const val DefaultArtworkCleanBytes = 128L * 1024 * 1024
    /** Heavy native Skia pixel backlog: reclaim on the fast 20 s idle rule instead of waiting 60 s. */
    internal const val DefaultArtworkFastCleanBytes = 512L * 1024 * 1024
    private const val Mb = 1024L * 1024

    private val log = Logger.withTag("IdleHeapCollector")
    private val started = AtomicBoolean(false)
    private var lastRunAtMs = 0L
    private var lastStoreEvictAtMs = 0L
    private val artworkDecodedSinceFull = AtomicLong(0L)

    /** Called from the artwork decode path (any thread) to track unfinalized native Skia pixels. */
    fun noteArtworkDecoded(bytes: Long) {
        if (bytes > 0L) artworkDecodedSinceFull.addAndGet(bytes)
    }

    internal fun resetArtworkDecoded() {
        artworkDecodedSinceFull.set(0L)
    }

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
            HeapTrendTrace.sample()
            val now = System.currentTimeMillis()
            if (now - lastStoreEvictAtMs >= 30_000L) {
                lastStoreEvictAtMs = now
                val evictedChars = com.nuvio.app.core.storage.DesktopStorage.evictIdleStores(now)
                if (evictedChars > 0L) {
                    log.d { "evicted ${evictedChars / 1024}K chars from idle DesktopStorage stores" }
                }
            }
            if (!NativeImageSupport.inImage) return
            // Measure old-generation growth, not total heap (eden + survivor + old): Eden filling
            // with 48 MB of transient garbage used to trigger a 250-330 ms full STW GC every
            // 40-60 s even when old gen grew by 0-4 MB (2026-11-25 baseline capture).
            val grown = FullGcLog.oldGenGrowthBytes()
            val decodedArtwork = artworkDecodedSinceFull.get()
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
                decodedArtworkBytes = decodedArtwork,
            )
            if (!collect) return
            lastRunAtMs = now
            val startedAt = System.nanoTime()
            val reason = if (grown >= memoryUse.idleSlowGrowthMb * Mb) {
                "idle, old +${grown / (1024 * 1024)}MB"
            } else {
                "idle, artwork ${decodedArtwork / (1024 * 1024)}MB decoded"
            }
            FullGcLog.explicit(reason) { System.gc() }
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
        decodedArtworkBytes: Long = 0L,
        artworkCleanBytes: Long = DefaultArtworkCleanBytes,
        artworkFastCleanBytes: Long = DefaultArtworkFastCleanBytes,
    ): Boolean =
        sinceLastRunMs >= MinIntervalMs &&
            sinceLastFullMs >= MinIntervalMs &&
            sinceInputMs >= IdleInputMs &&
            motionQuietMs >= IdleInputMs &&
            !playbackActive &&
            (
                grownBytes >= growthBytes ||
                    decodedArtworkBytes >= artworkFastCleanBytes ||
                    (sinceLastFullMs >= SlowIntervalMs && (grownBytes >= slowGrowthBytes || decodedArtworkBytes >= artworkCleanBytes))
                )
}
