package com.nuvio.app.core.ui

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.NativeImageSupport
import java.lang.management.ManagementFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Frees the pixels behind artwork the caches have already let go of, after a burst of decoding,
 * once the screen has gone quiet.
 *
 * Decoded artwork is native Skia memory owned by Java wrappers; an evicted bitmap's pixels are
 * only released when a collection runs its cleaner. Scrolling Home decodes fast — measured 0.5–1.3
 * GB of pixels per 30–60 s with the 326 MB cache full the whole time — and nothing in normal use
 * runs a collection soon after, so the evicted pixels sat in the working set and held the process
 * near 2 GB (Docs/plans/image-memory-plan.md, step 2). Playback and a hidden window already trim
 * (DesktopIdleHeapTrim); this covers staying on Home.
 *
 * `System.gc()` is concurrent here (`-XX:+ExplicitGCInvokesConcurrent`, build.gradle.kts), which
 * is what makes this safe while the window is visible: no stop-the-world pause, and the cycle still
 * runs the cleaners — measured on the playback trim, it freed as much as a full collection would.
 *
 * JVM build only. The native image has Serial GC, where `System.gc()` is a full stop-the-world
 * collection: measured 183–295 ms per trim on Home, a visible hitch every time it fired.
 */
internal object ArtworkChurnTrim {
    /** Decoded since the last trim before a trim is worth a collection: ~20 hero backdrops. */
    private const val TrimAfterDecodedBytes = 512L * 1024 * 1024

    /** No decode for this long counts as the screen having settled. */
    private const val QuietMillis = 3_000L

    /** Never more often than this, however much is decoded. */
    private const val MinTrimIntervalMillis = 60_000L

    private const val CheckIntervalMillis = 1_000L

    private val decodedSinceTrim = AtomicLong(0)
    private val lastDecodeAtMillis = AtomicLong(0)
    private val lastTrimAtMillis = AtomicLong(0)
    private val started = AtomicBoolean(false)

    /** From the decode path (any thread); cheap. */
    fun onDecoded(bytes: Long) {
        if (NativeImageSupport.inImage) return
        decodedSinceTrim.addAndGet(bytes)
        lastDecodeAtMillis.set(System.currentTimeMillis())
        if (started.compareAndSet(false, true)) startChecker()
    }

    private fun startChecker() {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "nuvio-artwork-churn-trim").apply { isDaemon = true }
        }.scheduleWithFixedDelay(::checkOnce, CheckIntervalMillis, CheckIntervalMillis, TimeUnit.MILLISECONDS)
    }

    private fun checkOnce() {
        val now = System.currentTimeMillis()
        val decoded = decodedSinceTrim.get()
        if (!shouldTrim(decoded, now - lastDecodeAtMillis.get(), now - lastTrimAtMillis.get())) return
        // Playback has its own trim and drops the caches first; a second collection there buys nothing.
        if (DesktopArtworkCaches.isPlaybackActive) return
        lastTrimAtMillis.set(now)
        decodedSinceTrim.addAndGet(-decoded)
        // On this daemon thread: a concurrent explicit GC blocks its caller until the cycle ends.
        MemoryTrimLog.trim(reason = "artwork churn ${decoded / (1024 * 1024)}MB decoded") { System.gc() }
    }

    internal fun shouldTrim(decodedBytes: Long, sinceLastDecodeMillis: Long, sinceLastTrimMillis: Long): Boolean =
        decodedBytes >= TrimAfterDecodedBytes &&
            sinceLastDecodeMillis >= QuietMillis &&
            sinceLastTrimMillis >= MinTrimIntervalMillis
}

/**
 * Private bytes before and after each memory trim, so a perf-log session shows what a trim
 * actually returned (one `(MemoryTrim)` line per trim, never per frame).
 */
internal object MemoryTrimLog {
    private val log = Logger.withTag("MemoryTrim")

    private fun privateBytes(): Long? = runCatching {
        (ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean)
            .committedVirtualMemorySize
    }.getOrNull()?.takeIf { it > 0 }

    /**
     * Runs [action] (a collection) and logs private bytes before, straight after, and
     * [SettleMillis] later. The later reading is the real one: a collection only makes the Skia
     * wrappers unreachable, and their pixels are freed afterwards on the cleaner thread — the first
     * version of this log read memory as the GC returned and showed almost nothing freed.
     * Call from a background thread; it sleeps.
     */
    fun trim(reason: String, action: () -> Unit) {
        val before = privateBytes()
        val startedAt = System.currentTimeMillis()
        FullGcLog.explicit(reason, action)
        val gcMillis = System.currentTimeMillis() - startedAt
        val after = privateBytes()
        runCatching { Thread.sleep(SettleMillis) }
        val settled = privateBytes()
        val mb = 1024 * 1024
        log.i {
            "$reason: private ${before?.div(mb) ?: "?"}MB -> ${after?.div(mb) ?: "?"}MB after gc " +
                "(${gcMillis}ms) -> ${settled?.div(mb) ?: "?"}MB after ${SettleMillis / 1000}s"
        }
    }

    private const val SettleMillis = 3_000L
}
