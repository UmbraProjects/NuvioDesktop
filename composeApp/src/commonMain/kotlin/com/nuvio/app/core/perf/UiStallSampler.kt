package com.nuvio.app.core.perf

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Says what the UI thread was doing during each hitch after input or a screen change, one
 * `(UiStall)` line per gap of [ReportMs] or more.
 *
 * `(FrameStall)` only measures gaps, and only while steady pacing renders every refresh; the launch
 * overlay's sampler only covers startup. A page transition's hitch starts on the frame after the
 * click or key (the new screen's first composition), before `(PerfScreen)` is even logged, so the
 * sampler is armed by input as well as by screen changes. While armed, a frame probe keeps the clock
 * running (see `UiStallProbe`), so a gap is a real stall, not an idle window.
 *
 * Off unless perf logging is on. Sampling only runs inside a gap: `Thread.getStackTrace` on another
 * thread is a handshake (a safepoint on the native build), which is nothing next to the stall.
 */
object UiStallSampler {
    private const val ArmMs = 1_500L
    private const val SampleAfterMs = 40L
    private const val PeriodMs = 15L
    private const val ReportMs = 80L
    private const val AppFramesPerKey = 3
    private const val LeafFrames = 6

    private val log = Logger.withTag("UiStall")

    private val PlumbingPrefixes = listOf(
        "java.lang.", "java.util.", "jdk.", "sun.", "com.oracle.", "org.graalvm.", "kotlin.",
        "kotlinx.coroutines.",
    )

    /** Top-of-stack methods of threads that are RUNNABLE only in name (blocked in native I/O). */
    private val IdleNativeMethods = listOf(
        "socketRead", "read0", "accept", "poll", "select", "WaitFor", "GetQueuedCompletionStatus",
        "readBytes", "connect0", "recv", "waitForEvent", "sleep",
    )

    private val _armedUntil = MutableStateFlow(0L)

    /** Wall-clock ms until which the frame probe should keep the clock running. */
    val armedUntil: StateFlow<Long> = _armedUntil

    @Volatile private var uiThread: Thread? = null
    @Volatile private var lastFrameNanos = 0L
    @Volatile private var armedBy = ""
    @Volatile private var started = false

    private val collectors = runCatching { java.lang.management.ManagementFactory.getGarbageCollectorMXBeans() }
        .getOrDefault(emptyList())

    // Sampler-thread state for the gap in progress.
    private var episodeFrame = 0L
    private var samples = 0
    private val byApp = HashMap<String, Int>()
    private val leafByApp = HashMap<String, String>()
    private val byPhase = HashMap<String, Int>()
    private var gcCountAtStart = 0L
    private var gcMsAtStart = 0L
    private var lastGcCount = 0L
    private var lastGcMs = 0L
    private var busyThreads: String? = null

    /** Keeps sampling for [ArmMs] from now. Cheap; safe from any thread. */
    fun arm(reason: String) {
        if (!PerfTelemetry.enabled) return
        ensureStarted()
        val now = System.currentTimeMillis()
        if (_armedUntil.value < now) {
            // A fresh window: do not count the idle time before it as a gap.
            lastFrameNanos = System.nanoTime()
        }
        armedBy = reason
        _armedUntil.value = now + ArmMs
    }

    /** Call from a frame callback on the UI thread. */
    fun markFrame() {
        uiThread = Thread.currentThread()
        lastFrameNanos = System.nanoTime()
    }

    private fun ensureStarted() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            Thread({ loop() }, "nuvio-ui-stall-sampler").apply {
                isDaemon = true
                priority = Thread.MAX_PRIORITY
                start()
            }
        }
    }

    private fun loop() {
        while (true) {
            try {
                Thread.sleep(PeriodMs)
            } catch (_: InterruptedException) {
                return
            }
            runCatching { tick() }
        }
    }

    private fun tick() {
        val frame = lastFrameNanos
        if (samples > 0 && frame != episodeFrame) {
            finishEpisode(frame)
        }
        if (System.currentTimeMillis() > _armedUntil.value) {
            lastGcCount = collectors.sumOf { it.collectionCount }
            lastGcMs = collectors.sumOf { it.collectionTime }
            return
        }
        val thread = uiThread ?: return
        val gapMs = (System.nanoTime() - frame) / 1_000_000
        if (gapMs < SampleAfterMs) {
            if (samples == 0) {
                lastGcCount = collectors.sumOf { it.collectionCount }
                lastGcMs = collectors.sumOf { it.collectionTime }
            }
            return
        }
        val stack = thread.stackTrace
        if (stack.isEmpty()) return
        if (samples == 0) {
            episodeFrame = frame
            // The counters from the last tick before this one: a stop-the-world pause freezes this
            // thread too, so reading them now would already include the collection that caused it.
            gcCountAtStart = lastGcCount
            gcMsAtStart = lastGcMs
        }
        samples++
        // Our own frames explain a stall best; without any, the first frames above the JDK/VM
        // plumbing (a park says nothing, what parked does: EventQueue.getNextEvent = idle).
        val app = stack.asSequence()
            .filter { it.className.startsWith("com.nuvio.") }
            .take(AppFramesPerKey)
            .joinToString(" < ") { it.shortName() }
            .ifEmpty {
                stack.asSequence()
                    .filterNot { element -> PlumbingPrefixes.any { element.className.startsWith(it) } }
                    .take(AppFramesPerKey)
                    .joinToString(" < ") { it.shortName() }
                    .ifEmpty { "(vm only)" }
            }
        byApp[app] = (byApp[app] ?: 0) + 1
        leafByApp.getOrPut(app) { stack.take(LeafFrames).joinToString(" < ") { it.shortName() } }
        val phase = phaseOf(stack)
        byPhase[phase] = (byPhase[phase] ?: 0) + 1
        // The UI thread waiting means someone else holds the frame back: name who is running,
        // once per gap (one all-threads snapshot is a single safepoint).
        if (phase == "blocked" && busyThreads == null && gapMs >= 100) {
            busyThreads = runningThreads()
        }
    }

    private fun runningThreads(): String {
        val self = Thread.currentThread()
        return Thread.getAllStackTraces().entries.asSequence()
            .filter { (thread, stack) ->
                thread !== self && thread.state == Thread.State.RUNNABLE && stack.isNotEmpty() &&
                    !stack.first().let { top -> IdleNativeMethods.any { top.methodName.contains(it) } }
            }
            .take(6)
            .joinToString(" ; ") { (thread, stack) ->
                thread.name + ": " + stack.take(4).joinToString(" < ") { it.shortName() }
            }
            .ifEmpty { "none" }
    }

    private fun finishEpisode(frameNanos: Long) {
        val gapMs = (frameNanos - episodeFrame) / 1_000_000
        if (gapMs >= ReportMs) {
            val gcCount = collectors.sumOf { it.collectionCount } - gcCountAtStart
            val gcMs = collectors.sumOf { it.collectionTime } - gcMsAtStart
            val phases = byPhase.entries.sortedByDescending { it.value }.joinToString(" ") { "${it.key} ${it.value}" }
            val top = byApp.entries.sortedByDescending { it.value }.take(3)
            val line = buildString {
                append("${gapMs}ms on ${PerfTelemetry.currentScreen} (armed by $armedBy); ")
                append("samples $samples: $phases; gc ")
                append(if (gcCount > 0) "${gcCount}x ${gcMs}ms" else "none")
                top.forEach { (key, n) -> append(" | ${n}x $key") }
                top.firstOrNull()?.let { (key, _) -> append(" || leaf: ${leafByApp[key]}") }
                busyThreads?.let { append(" || running: $it") }
            }
            log.i { line }
        }
        samples = 0
        busyThreads = null
        byApp.clear()
        leafByApp.clear()
        byPhase.clear()
    }

    /** Coarse frame phase from the leaf up: where in the frame the UI thread was. */
    private fun phaseOf(stack: Array<StackTraceElement>): String {
        for (element in stack) {
            val cls = element.className
            val method = element.methodName
            when {
                cls.startsWith("org.jetbrains.skia") || cls.startsWith("org.jetbrains.skiko") -> return "skia"
                cls.startsWith("java.") && (method == "park" || method == "wait" || method == "sleep") -> return "blocked"
                cls.startsWith("sun.nio") || cls.startsWith("java.io") || cls.startsWith("java.nio") -> return "io"
                cls.contains("Recomposer") || cls.contains("ComposerImpl") || cls.contains("SubcomposeLayoutState") ||
                    cls.contains("LayoutNodeSubcompositionsState") -> return "compose"
                cls.startsWith("androidx.compose.ui.node") && (method.contains("draw", true)) -> return "draw"
                cls.startsWith("androidx.compose.ui.node") &&
                    (method.contains("measure", true) || method.contains("layout", true) || method.contains("place", true)) -> return "layout"
            }
        }
        return "other"
    }

    private fun StackTraceElement.shortName(): String =
        className.substringAfterLast('.') + "." + methodName
}
