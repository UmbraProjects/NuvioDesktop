package com.nuvio.app.core.ui

import co.touchlab.kermit.Logger
import coil3.disk.DiskCache
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private val warmupLog = Logger.withTag("ImageDiskCache")

/**
 * Opens the artwork disk cache at launch, on its own thread, instead of on the first image.
 *
 * Coil opens a disk cache lazily: the first image to reach it builds the cache and replays its
 * journal (76,000 entries, 6.3 MB, on 2026-11-19), holding the cache lock while it does, so every
 * other image waits behind it. On the first launch after a reboot (17:34 that day) the first two
 * images to need the disk spent 9.5 s in their fetch and every poster after them queued behind,
 * while a warm restart the same morning showed no wait at all. Starting the open at `main` lets
 * it run during the ~2.7 s before Home asks for its first poster, and the log line says how long
 * the open really took, which nothing measured before.
 *
 * [cache] is what `configurePlatformImageLoader` hands Coil: if the warm-up is still running when
 * the first image arrives, that image waits for it, exactly as it waited for the lazy open before.
 */
internal object ImageDiskCacheWarmup {

    private var builder: (() -> DiskCache)? = null

    /** Built once, by whichever of the warm-up thread or Coil gets here first. */
    val cache: DiskCache by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val build = checkNotNull(builder) { "ImageDiskCacheWarmup.install was not called" }
        val startedAt = System.nanoTime()
        build().also {
            warmupLog.i { "built in ${(System.nanoTime() - startedAt) / 1_000_000}ms on ${Thread.currentThread().name}" }
        }
    }

    fun install(build: () -> DiskCache) {
        if (builder == null) builder = build
    }

    private val started = AtomicBoolean(false)

    /** Called from `main`; [install] must already have been called or the open is left to Coil. */
    fun start(build: () -> DiskCache) {
        install(build)
        if (!started.compareAndSet(false, true)) return
        Thread({
            runCatching {
                val startedAt = System.nanoTime()
                val disk = cache
                val builtAt = System.nanoTime()
                // Any key: Coil hashes it. The read is what makes DiskLruCache replay its journal;
                // the partitioned cache opens all its journals (the stills shards side by side).
                (disk as? PartitionedImageDiskCache)?.warm(WarmupKey) ?: disk.openSnapshot(WarmupKey)?.close()
                val openedAt = System.nanoTime()
                warmupLog.i {
                    "opened at launch in ${(openedAt - startedAt) / 1_000_000}ms " +
                        "(build ${(builtAt - startedAt) / 1_000_000}ms, journal ${(openedAt - builtAt) / 1_000_000}ms), " +
                        "${disk.size / (1024 * 1024)} MB cached"
                }
            }.onFailure { warmupLog.w(it) { "warm-up open failed; Coil will open the cache on the first image" } }
        }, "nuvio-image-cache-open").apply {
            isDaemon = true
            start()
        }
        ImageFetchHangProbe.start()
    }

    private const val WarmupKey = "nuvio-disk-cache-warmup"
}

/**
 * Logs every thread's stack, once, when an image fetch has been running for [HangMs] within the
 * first [WatchMs] after launch.
 *
 * Two startups (2026-11-18 and 11-19) saw images and unrelated work release at the same instant
 * after 9–15 s, and the log could not say what they were waiting on. One dump at the moment of the
 * hang answers that. Bounded to startup and to one dump so it costs nothing afterwards.
 */
internal object ImageFetchHangProbe {

    private const val HangMs = 3_000L
    private const val WatchMs = 120_000L
    private const val PollMs = 500L
    private const val FramesPerThread = 18

    private val inFlight = ConcurrentHashMap<Any, Long>()
    private val launchedAt = AtomicLong(0)
    private val log = Logger.withTag("ImageFetchHang")

    fun fetchStarted(token: Any) {
        if (launchedAt.get() <= 0L) return
        inFlight[token] = System.nanoTime()
    }

    fun fetchFinished(token: Any) {
        inFlight.remove(token)
    }

    fun start() {
        if (!launchedAt.compareAndSet(0L, System.nanoTime())) return
        Thread({
            val deadline = launchedAt.get() + WatchMs * 1_000_000
            while (System.nanoTime() < deadline) {
                runCatching { Thread.sleep(PollMs) }
                val now = System.nanoTime()
                val oldest = inFlight.values.minOrNull() ?: continue
                val heldMs = (now - oldest) / 1_000_000
                if (heldMs < HangMs) continue
                runCatching { dump(heldMs) }
                break
            }
            inFlight.clear()
            launchedAt.set(-1) // stop tracking for the rest of the session
        }, "nuvio-image-hang-probe").apply {
            isDaemon = true
            start()
        }
    }

    private fun dump(heldMs: Long) {
        val self = Thread.currentThread()
        val threads = Thread.getAllStackTraces().entries
            .filter { (thread, stack) -> thread !== self && stack.isNotEmpty() }
            .sortedBy { (thread, _) -> thread.name }
        val text = buildString {
            append("an image fetch has run ${heldMs}ms (${inFlight.size} in flight); ${threads.size} threads:\n")
            for ((thread, stack) in threads) {
                append("  [").append(thread.name).append("] ").append(thread.state).append('\n')
                for (frame in stack.take(FramesPerThread)) {
                    append("      at ").append(frame.className).append('.').append(frame.methodName)
                    if (frame.lineNumber > 0) append(':').append(frame.lineNumber)
                    append('\n')
                }
            }
        }
        log.i { text }
    }
}
