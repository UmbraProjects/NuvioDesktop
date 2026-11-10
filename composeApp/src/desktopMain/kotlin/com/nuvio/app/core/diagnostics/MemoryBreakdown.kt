package com.nuvio.app.core.diagnostics

import co.touchlab.kermit.Logger
import com.nuvio.app.core.ui.DesktopArtworkCaches
import com.nuvio.app.core.ui.ScaledBitmapCache
import com.nuvio.app.core.ui.decodedAnimationCacheBytes
import com.nuvio.app.features.player.desktop.NativeAudioDuck
import com.nuvio.app.features.player.desktop.NativePlayerBridge
import java.lang.management.BufferPoolMXBean
import java.lang.management.ManagementFactory

/**
 * Where this process's memory is, as far as it can be told from inside. All sizes in bytes; -1 is
 * "could not be read" (an older Windows, a player library without the export).
 *
 * The process figures come from Windows (`player_bridge.dll`): [privateWorkingSet] is the number
 * Task Manager's Memory column shows, [privateCommit] what is committed whether or not it is in RAM.
 * [driverMapped] is write-combined memory, which only graphics drivers map into a process: video
 * frames and render targets, mostly the player's. The rest is the JVM's own accounting and the
 * app's caches. [unattributedNative] is what is left of private memory once those are taken out:
 * Skia and Compose, the WebView2 host, mpv's decoder and filter buffers, thread stacks.
 */
internal data class MemoryBreakdown(
    val privateCommit: Long,
    val workingSet: Long,
    val privateWorkingSet: Long,
    val driverMapped: Long,
    val otherPrivate: Long,
    val images: Long,
    val mappedFiles: Long,
    val videoMemoryDedicated: Long,
    val videoMemoryShared: Long,
    val heapUsed: Long,
    val heapCommitted: Long,
    val heapMax: Long,
    val nonHeapCommitted: Long,
    val directBuffers: Long,
    val artworkCache: Long,
    val artworkCacheBudget: Long,
    val scaledArtworkCache: Long,
    val animationCache: Long,
    val players: Int,
    val mpvBuffer: Long,
    val mpvBufferAhead: Long,
    /** The loaded speech model and what loading it cost; null when none is loaded. */
    val voiceModel: NativeMemoryUsers.VoiceModel? = null,
    /** Whether voice control is listening for its wake word, which keeps the model loaded. */
    val wakeWordListening: Boolean = false,
    /** Native engines loaded in the process ([NotableNativeEngines]). */
    val engines: List<String> = emptyList(),
    /** Child processes; Task Manager counts them under Nuvio's group, not its own row. */
    val children: List<ChildProcessGroup> = emptyList(),
) {
    val unattributedNative: Long
        get() = if (otherPrivate < 0) -1 else (
            otherPrivate - heapCommitted - nonHeapCommitted.coerceAtLeast(0) - directBuffers.coerceAtLeast(0) -
                mpvBuffer.coerceAtLeast(0) - (voiceModel?.costBytes ?: 0).coerceAtLeast(0)
            ).coerceAtLeast(0)

    /** Child processes' private working sets together; -1 when none could be read. */
    val childrenPrivateWorkingSet: Long
        get() = children.map { it.privateWorkingSet }.filter { it >= 0 }.takeIf { it.isNotEmpty() }?.sum() ?: -1

    /** This process and its children: what Task Manager's Nuvio group adds up to. */
    val withHelpers: Long
        get() = if (privateWorkingSet < 0) -1 else privateWorkingSet + childrenPrivateWorkingSet.coerceAtLeast(0)
}

internal object MemoryBreakdownSampler {
    private val log = Logger.withTag("MemoryBreakdown")

    /** Never throws: a part that cannot be read is -1 and the rest is still worth showing. */
    fun sample(): MemoryBreakdown {
        val process = LongArray(9) { -1 }
        runCatching { NativePlayerBridge.processMemoryBreakdown(process) }
        val memory = ManagementFactory.getMemoryMXBean()
        val heap = memory.heapMemoryUsage
        val nonHeap = memory.nonHeapMemoryUsage
        val direct = runCatching {
            ManagementFactory.getPlatformMXBeans(BufferPoolMXBean::class.java)
                .filter { it.name == "direct" || it.name == "mapped" }
                .sumOf { it.memoryUsed }
        }.getOrDefault(-1L)
        val artwork = runCatching { DesktopArtworkCaches.memoryBytes() }.getOrNull()
        val handles = runCatching { NativeAudioDuck.liveHandles() }.getOrDefault(emptyList())
        var mpvBuffer = -1L
        var mpvAhead = -1L
        val cache = LongArray(2)
        for (handle in handles) {
            val read = runCatching { NativePlayerBridge.demuxerCacheBytes(handle, cache) }.getOrDefault(false)
            if (!read) continue
            if (cache[0] >= 0) mpvBuffer = mpvBuffer.coerceAtLeast(0) + cache[0]
            if (cache[1] >= 0) mpvAhead = mpvAhead.coerceAtLeast(0) + cache[1]
        }
        val voiceSettings = runCatching {
            com.nuvio.app.features.voice.VoiceControlRepository.uiState.value.settings
        }.getOrNull()
        val engines = runCatching { notableEngines(NativePlayerBridge.loadedModuleNames()) }.getOrDefault(emptyList())
        val children = runCatching {
            groupChildProcesses(
                ProcessHandle.current().descendants().toList().map { child ->
                    val name = child.info().command().map { java.io.File(it).name }.orElse("pid ${child.pid()}")
                    name to runCatching { NativePlayerBridge.processPrivateWorkingSet(child.pid()) }.getOrDefault(-1L)
                },
            )
        }.getOrDefault(emptyList())
        return MemoryBreakdown(
            privateCommit = process[0],
            workingSet = process[1],
            privateWorkingSet = process[2],
            driverMapped = process[3],
            otherPrivate = process[4],
            images = process[5],
            mappedFiles = process[6],
            videoMemoryDedicated = process[7],
            videoMemoryShared = process[8],
            heapUsed = heap.used,
            heapCommitted = heap.committed,
            heapMax = heap.max,
            nonHeapCommitted = nonHeap.committed,
            directBuffers = direct,
            artworkCache = artwork?.first ?: -1,
            artworkCacheBudget = artwork?.second ?: -1,
            scaledArtworkCache = runCatching { ScaledBitmapCache.sizeBytes() }.getOrDefault(-1L),
            animationCache = runCatching { decodedAnimationCacheBytes() }.getOrDefault(-1L),
            players = handles.size,
            mpvBuffer = mpvBuffer,
            mpvBufferAhead = mpvAhead,
            voiceModel = NativeMemoryUsers.voiceModel,
            wakeWordListening = voiceSettings?.let { it.enabled && it.wakeWord } ?: false,
            engines = engines,
            children = children,
        )
    }

    /** This process's committed private memory now, or -1. For measuring what a native load cost. */
    fun privateCommitBytes(): Long {
        val process = LongArray(9) { -1 }
        return runCatching { if (NativePlayerBridge.processMemoryBreakdown(process)) process[0] else -1L }.getOrDefault(-1L)
    }

    /** One `(MemoryBreakdown)` log line: compact, every figure in MB. */
    fun log(reason: String, breakdown: MemoryBreakdown = sample()) {
        log.i { "$reason: ${logLine(breakdown)}" }
    }

    internal fun logLine(b: MemoryBreakdown): String =
        "privateWS=${mb(b.privateWorkingSet)} commit=${mb(b.privateCommit)} ws=${mb(b.workingSet)} " +
            "driverMapped=${mb(b.driverMapped)} vram=${mb(b.videoMemoryDedicated)} sharedGpu=${mb(b.videoMemoryShared)} " +
            "heap=${mb(b.heapUsed)}/${mb(b.heapCommitted)} nonHeap=${mb(b.nonHeapCommitted)} direct=${mb(b.directBuffers)} " +
            "artwork=${mb(b.artworkCache)}/${mb(b.artworkCacheBudget)} scaled=${mb(b.scaledArtworkCache)} " +
            "animated=${mb(b.animationCache)} players=${b.players} mpvBuffer=${mb(b.mpvBuffer)} " +
            "voiceModel=${b.voiceModel?.let { "${it.fileName}:${mb(it.costBytes)}${if (it.exact) "" else "~"}" } ?: "none"} " +
            "wakeWord=${b.wakeWordListening} unattributedNative=${mb(b.unattributedNative)} " +
            "dlls=${mb(b.images)} mapped=${mb(b.mappedFiles)} withHelpers=${mb(b.withHelpers)} " +
            "children=[${b.children.joinToString(",") { "${it.name}x${it.count}:${mb(it.privateWorkingSet)}" }}] " +
            "engines=[${b.engines.joinToString(",")}]"

    /** The overlay's rows: label to value, in reading order. */
    internal fun overlayRows(b: MemoryBreakdown): List<Pair<String, String>> = buildList {
        add("Memory (Task Manager)" to mb(b.privateWorkingSet, " MB"))
        if (b.children.isNotEmpty()) add("With helper processes" to mb(b.withHelpers, " MB"))
        add("Committed" to mb(b.privateCommit, " MB"))
        add("Graphics driver" to mb(b.driverMapped, " MB"))
        add("Video memory" to "${mb(b.videoMemoryDedicated, " MB")} · shared ${mb(b.videoMemoryShared, " MB")}")
        add("Java heap" to "${mb(b.heapUsed)} / ${mb(b.heapCommitted, " MB")}")
        add("JVM other" to "${mb(b.nonHeapCommitted, " MB")} · direct ${mb(b.directBuffers, " MB")}")
        add("Artwork cache" to "${mb(b.artworkCache)} / ${mb(b.artworkCacheBudget, " MB")}")
        add("Scaled · animated" to "${mb(b.scaledArtworkCache, " MB")} · ${mb(b.animationCache, " MB")}")
        add(
            "mpv buffer" to if (b.players == 0) {
                "no player"
            } else {
                "${mb(b.mpvBuffer, " MB")} · ${mb(b.mpvBufferAhead, " MB")} ahead"
            },
        )
        add(
            "Voice model" to (
                b.voiceModel?.let { model ->
                    val name = model.fileName.removePrefix("ggml-").removeSuffix(".bin")
                    val cost = if (model.exact) "${mb(model.costBytes, " MB")} allocated" else "~${mb(model.costBytes, " MB")}"
                    "$name · $cost" + if (b.wakeWordListening) " · wake word" else ""
                } ?: "not loaded"
                ),
        )
        add("Other native" to mb(b.unattributedNative, " MB"))
        // Separate processes: their memory is not in the rows above, but Task Manager groups it under Nuvio.
        b.children.take(3).forEach { child ->
            val label = if (child.count > 1) "${child.name} ×${child.count}" else child.name
            add(label to mb(child.privateWorkingSet, " MB"))
        }
        if (b.engines.isNotEmpty()) add("Engines loaded" to b.engines.joinToString(", "))
    }

    private fun mb(bytes: Long, unit: String = ""): String =
        if (bytes < 0) "?" else "%,d".format(bytes / (1024 * 1024)) + unit
}
