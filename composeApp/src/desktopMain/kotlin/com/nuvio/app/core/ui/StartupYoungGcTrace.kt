package com.nuvio.app.core.ui

import co.touchlab.kermit.Logger
import com.nuvio.app.core.perf.PerfTelemetry
import java.lang.management.GarbageCollectorMXBean
import java.lang.management.ManagementFactory
import java.lang.management.MemoryPoolMXBean

/**
 * Perf logging only: one `(YoungGc)` report covering the first [WindowMs] of the process, from
 * samples [IdleHeapCollector] takes every 250 ms anyway.
 *
 * Startup shows a 60-100 ms young collection every ~150 ms for over a second after the first Home
 * frame (2026-10-19). With a 512 MB heap floor and a 192 MB young-generation cap that cadence means
 * either ~1 GB/s of allocation or a much smaller eden than the cap; the existing lines cannot tell
 * which. This logs young-collection count and time per sample alongside the eden / survivor / old
 * pool sizes, so the next startup answers it.
 */
internal object StartupYoungGcTrace {
    private const val WindowMs = 20_000L
    private val log = Logger.withTag("YoungGc")
    private val startedAtMs = System.currentTimeMillis()

    private val youngCollectors: List<GarbageCollectorMXBean> = runCatching {
        ManagementFactory.getGarbageCollectorMXBeans().filterNot { bean ->
            val name = bean.name.lowercase()
            "complete" in name || "old" in name || "marksweep" in name
        }
    }.getOrDefault(emptyList())

    private val pools: List<MemoryPoolMXBean> = runCatching {
        ManagementFactory.getMemoryPoolMXBeans().filter { it.isValid }
    }.getOrDefault(emptyList())

    private val lines = StringBuilder()
    private var lastCount = -1L
    private var lastTimeMs = 0L
    private var gcs = 0L
    private var gcMs = 0L
    private var maxMs = 0L
    private var done = false

    /** Called from IdleHeapCollector's thread; a no-op once the window has been reported. */
    fun sample() {
        if (done) return
        if (!PerfTelemetry.enabled || youngCollectors.isEmpty()) {
            done = true
            return
        }
        val now = System.currentTimeMillis()
        val count = youngCollectors.sumOf { it.collectionCount.coerceAtLeast(0) }
        val timeMs = youngCollectors.sumOf { it.collectionTime.coerceAtLeast(0) }
        if (lastCount >= 0 && count > lastCount) {
            val n = count - lastCount
            val ms = timeMs - lastTimeMs
            gcs += n
            gcMs += ms
            maxMs = maxOf(maxMs, ms / n)
            val mb = 1024 * 1024
            val heap = Runtime.getRuntime()
            lines.append("\n  +").append(now - startedAtMs).append("ms: ").append(n).append("x ").append(ms)
                .append("ms, heap ").append((heap.totalMemory() - heap.freeMemory()) / mb).append('/')
                .append(heap.totalMemory() / mb).append("MB")
            pools.forEach { pool ->
                val usage = runCatching { pool.usage }.getOrNull() ?: return@forEach
                lines.append(", ").append(pool.name).append(' ').append(usage.used / mb).append('/')
                    .append(usage.committed / mb).append("MB")
            }
        }
        lastCount = count
        lastTimeMs = timeMs
        if (now - startedAtMs >= WindowMs) {
            done = true
            log.i {
                "first ${WindowMs / 1000}s: $gcs young collections, ${gcMs}ms total, worst ~${maxMs}ms; " +
                    "collectors=${youngCollectors.joinToString { it.name }} pools=${pools.joinToString { it.name }}$lines"
            }
        }
    }
}
