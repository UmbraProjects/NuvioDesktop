package com.nuvio.app.core.ui

import co.touchlab.kermit.Logger
import com.nuvio.app.core.perf.PerfTelemetry
import java.awt.EventQueue
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Perf logging only: one `(UiFreeze)` line per UI-thread block of [ReportMs] or more, whatever the
 * user was doing. [com.nuvio.app.core.perf.UiStallSampler] only watches for 1.5 s after input, and
 * its input listener runs on the UI thread, so clicks made into a frozen window arm nothing: a
 * freeze that starts after a pause left no trace (the "huge freeze" of 2026-10-19 05:13).
 *
 * Posts a no-op to the AWT queue every [PingMs]; while one has waited longer than [SampleAfterMs]
 * it samples the UI thread's stack every [SampleMs]. A freeze still going after [InterimMs] logs
 * once early, in case it ends with the app being killed.
 */
internal object UiFreezeWatchdog {
    private const val PingMs = 250L
    private const val SampleAfterMs = 500L
    private const val SampleMs = 100L
    private const val ReportMs = 1_000L
    private const val InterimMs = 5_000L
    private const val AppFrames = 4
    private const val LeafFrames = 8

    private val log = Logger.withTag("UiFreeze")
    private val started = AtomicBoolean(false)
    private val collectors = runCatching { ManagementFactory.getGarbageCollectorMXBeans() }.getOrDefault(emptyList())

    @Volatile private var uiThread: Thread? = null
    @Volatile private var answeredAtNanos = 0L

    fun start() {
        if (PerfTelemetry.enabled) startWatching()
    }

    /** [start] without the perf-logging gate; for tests. */
    internal fun startWatching() {
        if (!started.compareAndSet(false, true)) return
        Thread(::loop, "nuvio-ui-freeze-watchdog").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun loop() {
        while (true) {
            runCatching { watchOnePing() }.onFailure { error -> log.w(error) { "watchdog failed" } }
            try {
                Thread.sleep(PingMs)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun gcCount() = collectors.sumOf { it.collectionCount.coerceAtLeast(0) }
    private fun gcMs() = collectors.sumOf { it.collectionTime.coerceAtLeast(0) }

    private fun watchOnePing() {
        val postedAt = System.nanoTime()
        val gcCountAtPost = gcCount()
        val gcMsAtPost = gcMs()
        EventQueue.invokeLater {
            uiThread = Thread.currentThread()
            answeredAtNanos = System.nanoTime()
        }
        val byApp = HashMap<String, Int>()
        val leafByApp = HashMap<String, String>()
        var samples = 0
        var running: String? = null
        var interimLogged = false
        while (answeredAtNanos < postedAt) {
            Thread.sleep(SampleMs)
            val waitedMs = (System.nanoTime() - postedAt) / 1_000_000
            if (waitedMs < SampleAfterMs) continue
            val thread = uiThread ?: continue
            val stack = thread.stackTrace
            if (stack.isEmpty()) continue
            samples++
            val key = appKey(stack)
            byApp[key] = (byApp[key] ?: 0) + 1
            leafByApp.getOrPut(key) { stack.take(LeafFrames).joinToString(" < ") { it.shortName() } }
            if (running == null) running = runningThreads(thread)
            if (!interimLogged && waitedMs >= InterimMs) {
                interimLogged = true
                log.w { line("still frozen after ${waitedMs}ms", samples, byApp, leafByApp, running, gcCountAtPost, gcMsAtPost) }
            }
        }
        val frozenMs = (answeredAtNanos - postedAt) / 1_000_000
        if (frozenMs >= ReportMs) {
            log.w { line("${frozenMs}ms", samples, byApp, leafByApp, running, gcCountAtPost, gcMsAtPost) }
        }
    }

    private fun line(
        what: String,
        samples: Int,
        byApp: Map<String, Int>,
        leafByApp: Map<String, String>,
        running: String?,
        gcCountAtPost: Long,
        gcMsAtPost: Long,
    ): String = buildString {
        val gcN = gcCount() - gcCountAtPost
        append("$what on ${PerfTelemetry.currentScreen}; samples $samples; gc ")
        append(if (gcN > 0) "${gcN}x ${gcMs() - gcMsAtPost}ms" else "none")
        val top = byApp.entries.sortedByDescending { it.value }.take(3)
        top.forEach { (key, n) -> append(" | ${n}x $key") }
        top.firstOrNull()?.let { (key, _) -> append(" || leaf: ${leafByApp[key]}") }
        running?.let { append(" || running: $it") }
    }

    /** First app frames, or failing that the first frames above JDK plumbing. */
    private fun appKey(stack: Array<StackTraceElement>): String =
        stack.asSequence()
            .filter { it.className.startsWith("com.nuvio.") }
            .take(AppFrames)
            .joinToString(" < ") { it.shortName() }
            .ifEmpty {
                stack.asSequence()
                    .filterNot { e -> Plumbing.any { e.className.startsWith(it) } }
                    .take(AppFrames)
                    .joinToString(" < ") { it.shortName() }
                    .ifEmpty { "(jdk only) " + stack.take(3).joinToString(" < ") { it.shortName() } }
            }

    private fun runningThreads(ui: Thread): String {
        val self = Thread.currentThread()
        return Thread.getAllStackTraces().entries.asSequence()
            .filter { (thread, stack) ->
                thread !== self && thread !== ui && thread.state == Thread.State.RUNNABLE && stack.isNotEmpty() &&
                    IdleNative.none { stack.first().methodName.contains(it) }
            }
            .take(6)
            .joinToString(" ; ") { (thread, stack) -> thread.name + ": " + stack.take(4).joinToString(" < ") { it.shortName() } }
            .ifEmpty { "none" }
    }

    private val Plumbing = listOf(
        "java.lang.", "java.util.", "jdk.", "sun.", "com.oracle.", "org.graalvm.", "kotlin.", "kotlinx.coroutines.",
    )
    private val IdleNative = listOf(
        "socketRead", "read0", "accept", "poll", "select", "WaitFor", "GetQueuedCompletionStatus",
        "readBytes", "connect0", "recv", "waitForEvent", "sleep",
    )

    private fun StackTraceElement.shortName(): String = className.substringAfterLast('.') + "." + methodName
}
