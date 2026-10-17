package com.nuvio.app.features.home.components

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import co.touchlab.kermit.Logger
import coil3.BitmapImage
import coil3.Image
import com.nuvio.app.core.build.PackagedResources
import com.nuvio.app.core.storage.DesktopStorage
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.FloatBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

private val framingLog = Logger.withTag("HeroBackdropFraming")

internal actual object HeroBackdropFramer {
    /** One analysis at a time: it is a background nicety and should never compete for cores. */
    private val analysisDispatcher = Dispatchers.Default.limitedParallelism(1)

    private val memory = object : LinkedHashMap<String, HeroBackdropFramingProfile>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, HeroBackdropFramingProfile>?) =
            size > MemoryEntries
    }
    private val inFlight = HashMap<String, CompletableDeferred<HeroBackdropFramingProfile?>>()

    actual fun peek(key: String): HeroBackdropFramingProfile? = synchronized(memory) { memory[key] }

    /** Loads onnxruntime and both models (once; the result is cached). For the native self-test. */
    fun modelsAvailable(): Boolean = FramingModels.get() != null

    actual suspend fun profile(key: String, image: Image?): HeroBackdropFramingProfile? {
        peek(key)?.let { return it }
        withContext(Dispatchers.IO) { FramingDiskCache.read(key) }?.let { stored ->
            synchronized(memory) { memory[key] = stored }
            return stored
        }
        val bitmap = (image as? BitmapImage)?.bitmap ?: return null
        if (bitmap.width <= 0 || bitmap.height <= 0) return null

        // Two layers showing the same backdrop (a fade back to it) share one analysis.
        val (deferred, owner) = synchronized(inFlight) {
            inFlight[key]?.let { it to false }
                ?: CompletableDeferred<HeroBackdropFramingProfile?>().also { inFlight[key] = it }.let { it to true }
        }
        if (!owner) return deferred.await()
        var result: HeroBackdropFramingProfile? = null
        try {
            result = withContext(analysisDispatcher) {
                readRgba(bitmap)?.let { pixels ->
                    val started = System.nanoTime()
                    analyzeHeroBackdropPixels(pixels, bitmap.width, bitmap.height)?.also {
                        framingLog.d {
                            "Analysed ${bitmap.width}x${bitmap.height} in " +
                                "${(System.nanoTime() - started) / 1_000_000}ms, ${it.faces.size} faces"
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            // The layer that asked went away (the user scrolled on). Queued analyses for it are
            // dropped before they run, which is what keeps a held arrow key from piling up work.
            throw cancelled
        } catch (error: Throwable) {
            framingLog.w(error) { "Backdrop framing analysis failed" }
        } finally {
            synchronized(inFlight) { inFlight.remove(key) }
            // Always, even when cancelled: another layer may be waiting on this analysis.
            deferred.complete(result)
        }
        if (result != null) {
            synchronized(memory) { memory[key] = result }
            withContext(Dispatchers.IO) { FramingDiskCache.write(key, result) }
        }
        return result
    }

    private fun readRgba(bitmap: org.jetbrains.skia.Bitmap): ByteArray? {
        val info = ImageInfo(bitmap.width, bitmap.height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
        return bitmap.readPixels(info, bitmap.width * 4, 0, 0)
    }

    private const val MemoryEntries = 512
}

/**
 * Analyses unpremultiplied RGBA pixels, touching no cache. Null when the models cannot load.
 */
internal fun analyzeHeroBackdropPixels(rgba: ByteArray, width: Int, height: Int): HeroBackdropFramingProfile? =
    FramingModels.get()?.analyze(rgba, width, height)

/**
 * The two ONNX sessions, loaded once on first use.
 *
 * A load failure (missing natives, a blocked DLL) is remembered and logged once; framing then
 * quietly falls back to the manual bias for the rest of the session.
 */
private class FramingModels private constructor(
    private val environment: OrtEnvironment,
    private val saliency: OrtSession,
    private val faces: OrtSession,
) {
    fun analyze(rgba: ByteArray, width: Int, height: Int): HeroBackdropFramingProfile {
        val saliencyMap = runSaliency(rgba, width, height)
        val (subjectMass, edgeCut) = rowProfiles(saliencyMap)
        return HeroBackdropFramingProfile(
            imageAspect = width.toFloat() / height,
            subjectMass = subjectMass,
            edgeCut = edgeCut,
            faces = runFaces(rgba, width, height),
        )
    }

    /** U2-Netp: 320x320 RGB, ImageNet-normalised; first output, min-max normalised to 0..1. */
    private fun runSaliency(rgba: ByteArray, width: Int, height: Int): FloatArray {
        val size = SaliencySize
        val rgb = areaResample(rgba, width, height, size, size)
        val plane = size * size
        val input = FloatArray(3 * plane)
        for (index in 0 until plane) {
            for (channel in 0 until 3) {
                input[channel * plane + index] =
                    (rgb[index * 3 + channel] / 255f - ImageNetMean[channel]) / ImageNetStd[channel]
            }
        }
        val output = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), longArrayOf(1, 3, size.toLong(), size.toLong())).use { tensor ->
            saliency.run(mapOf(saliency.inputNames.first() to tensor)).use { result ->
                val buffer = (result.get(0) as OnnxTensor).floatBuffer
                FloatArray(buffer.remaining()).also { buffer.get(it) }
            }
        }
        var low = Float.POSITIVE_INFINITY
        var high = Float.NEGATIVE_INFINITY
        for (value in output) {
            low = min(low, value)
            high = max(high, value)
        }
        val range = max(high - low, 1e-6f)
        for (index in output.indices) output[index] = (output[index] - low) / range
        return output
    }

    /** Column-weighted subject mass per row, and how much subject an edge at each row slices. */
    private fun rowProfiles(map: FloatArray): Pair<FloatArray, FloatArray> {
        val size = SaliencySize
        val columnWeight = FloatArray(size) { column ->
            val x = (column + 0.5f) / size
            (0.25f + 0.75f * x / 0.45f).coerceIn(0.25f, 1f)
        }
        val weightSum = columnWeight.sum()
        val mass = FloatArray(size)
        val hot = Array(size) { BooleanArray(size) }
        for (row in 0 until size) {
            var rowMass = 0f
            for (column in 0 until size) {
                val value = map[row * size + column]
                rowMass += value * columnWeight[column]
                hot[row][column] = value > 0.5f
            }
            mass[row] = rowMass
        }
        // An edge "cuts" a column when the subject is present within two rows of it, so a thin
        // gap in the mask doesn't read as a clean place to crop.
        val cut = FloatArray(size) { row ->
            var sliced = 0f
            for (column in 0 until size) {
                var present = false
                for (near in max(0, row - 2)..min(size - 1, row + 2)) {
                    if (hot[near][column]) {
                        present = true
                        break
                    }
                }
                if (present) sliced += columnWeight[column]
            }
            sliced / weightSum
        }
        val group = size / ProfileRows
        val totalMass = max(mass.sum(), 1e-6f)
        val profileMass = FloatArray(ProfileRows) { bin ->
            var sum = 0f
            for (row in bin * group until (bin + 1) * group) sum += mass[row]
            sum / totalMass
        }
        val profileCut = FloatArray(ProfileRows) { bin ->
            var worst = 0f
            for (row in bin * group until (bin + 1) * group) worst = max(worst, cut[row])
            worst
        }
        return profileMass to profileCut
    }

    /**
     * YuNet (2023mar): a fixed 640x640 BGR 0..255 input, the image scaled to fit and padded at the
     * bottom/right. Decoded the way OpenCV's FaceDetectorYN does: per-stride anchor-free boxes,
     * score = sqrt(cls * obj), then greedy NMS.
     */
    private fun runFaces(rgba: ByteArray, width: Int, height: Int): List<HeroBackdropFace> {
        val side = FaceInputSize
        val scale = side.toFloat() / max(width, height)
        val scaledWidth = (width * scale).roundToInt().coerceIn(1, side)
        val scaledHeight = (height * scale).roundToInt().coerceIn(1, side)
        val rgb = areaResample(rgba, width, height, scaledWidth, scaledHeight)
        val plane = side * side
        val input = FloatArray(3 * plane)
        for (y in 0 until scaledHeight) {
            for (x in 0 until scaledWidth) {
                val source = (y * scaledWidth + x) * 3
                val target = y * side + x
                input[target] = rgb[source + 2]
                input[plane + target] = rgb[source + 1]
                input[2 * plane + target] = rgb[source]
            }
        }
        val candidates = ArrayList<FaceCandidate>()
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), longArrayOf(1, 3, side.toLong(), side.toLong())).use { tensor ->
            faces.run(mapOf(faces.inputNames.first() to tensor)).use { result ->
                fun output(name: String): FloatBuffer = (result.get(name).get() as OnnxTensor).floatBuffer
                for (stride in FaceStrides) {
                    val columns = side / stride
                    val cls = output("cls_$stride")
                    val obj = output("obj_$stride")
                    val box = output("bbox_$stride")
                    val anchors = cls.remaining()
                    for (index in 0 until anchors) {
                        val score = sqrt(cls.get(index).coerceIn(0f, 1f) * obj.get(index).coerceIn(0f, 1f))
                        if (score < FaceScoreThreshold) continue
                        val row = index / columns
                        val column = index % columns
                        val centerX = (column + box.get(index * 4)) * stride
                        val centerY = (row + box.get(index * 4 + 1)) * stride
                        val boxWidth = exp(box.get(index * 4 + 2)) * stride
                        val boxHeight = exp(box.get(index * 4 + 3)) * stride
                        candidates += FaceCandidate(
                            score,
                            centerX - boxWidth / 2f,
                            centerY - boxHeight / 2f,
                            boxWidth,
                            boxHeight,
                        )
                    }
                }
            }
        }
        candidates.sortByDescending { it.score }
        val kept = ArrayList<FaceCandidate>()
        for (candidate in candidates) {
            if (kept.all { it.iou(candidate) <= FaceNmsThreshold }) kept += candidate
            if (kept.size == FaceTopK) break
        }
        return kept.map {
            HeroBackdropFace(
                left = it.x / scaledWidth,
                top = it.y / scaledHeight,
                right = (it.x + it.width) / scaledWidth,
                bottom = (it.y + it.height) / scaledHeight,
            )
        }
    }

    companion object {
        @Volatile private var loaded: FramingModels? = null
        @Volatile private var failed = false

        fun get(): FramingModels? {
            loaded?.let { return it }
            if (failed) return null
            synchronized(this) {
                loaded?.let { return it }
                if (failed) return null
                return runCatching { load() }
                    .onFailure {
                        failed = true
                        framingLog.w(it) { "Backdrop framing models unavailable; using the manual position" }
                    }
                    .getOrNull()
                    ?.also { loaded = it }
            }
        }

        private fun load(): FramingModels {
            OnnxRuntimeNatives.install()
            val environment = OrtEnvironment.getEnvironment()
            fun session(name: String): OrtSession {
                val bytes = checkNotNull(PackagedResources.open("backdrop-framing/$name")) {
                    "Missing model resource $name"
                }.use { it.readBytes() }
                val options = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                return environment.createSession(bytes, options)
            }
            return FramingModels(environment, session("u2netp.onnx"), session("yunet.onnx"))
        }
    }
}

private class FaceCandidate(val score: Float, val x: Float, val y: Float, val width: Float, val height: Float) {
    fun iou(other: FaceCandidate): Float {
        val overlapWidth = (min(x + width, other.x + other.width) - max(x, other.x)).coerceAtLeast(0f)
        val overlapHeight = (min(y + height, other.y + other.height) - max(y, other.y)).coerceAtLeast(0f)
        val overlap = overlapWidth * overlapHeight
        return overlap / (width * height + other.width * other.height - overlap)
    }
}

/**
 * Area-averaging resample of RGBA bytes to packed RGB floats (0..255), alpha dropped.
 *
 * Exact box coverage, fractional at the edges — the same filter the framing was tuned with.
 * Bilinear sampling moved some crops by half the range on the bench, so this is not cosmetic.
 */
private fun areaResample(rgba: ByteArray, width: Int, height: Int, targetWidth: Int, targetHeight: Int): FloatArray {
    val horizontal = AreaWeights(width, targetWidth)
    val vertical = AreaWeights(height, targetHeight)
    // Horizontal pass: one row of the source at a time into targetWidth x height.
    val rows = FloatArray(targetWidth * height * 3)
    for (y in 0 until height) {
        val sourceRow = y * width * 4
        val targetRow = y * targetWidth * 3
        for (x in 0 until targetWidth) {
            var red = 0f
            var green = 0f
            var blue = 0f
            for (tap in horizontal.start[x] until horizontal.start[x + 1]) {
                val source = sourceRow + horizontal.index[tap] * 4
                val weight = horizontal.weight[tap]
                red += (rgba[source].toInt() and 0xFF) * weight
                green += (rgba[source + 1].toInt() and 0xFF) * weight
                blue += (rgba[source + 2].toInt() and 0xFF) * weight
            }
            rows[targetRow + x * 3] = red
            rows[targetRow + x * 3 + 1] = green
            rows[targetRow + x * 3 + 2] = blue
        }
    }
    val out = FloatArray(targetWidth * targetHeight * 3)
    for (y in 0 until targetHeight) {
        for (tap in vertical.start[y] until vertical.start[y + 1]) {
            val sourceRow = vertical.index[tap] * targetWidth * 3
            val weight = vertical.weight[tap]
            val targetRow = y * targetWidth * 3
            for (i in 0 until targetWidth * 3) out[targetRow + i] += rows[sourceRow + i] * weight
        }
    }
    return out
}

/** Per target pixel, the source pixels it covers and by how much (weights sum to 1). */
private class AreaWeights(sourceSize: Int, targetSize: Int) {
    val start = IntArray(targetSize + 1)
    val index: IntArray
    val weight: FloatArray

    init {
        val ratio = sourceSize.toDouble() / targetSize
        val indices = ArrayList<Int>()
        val weights = ArrayList<Float>()
        for (target in 0 until targetSize) {
            start[target] = indices.size
            val from = target * ratio
            val to = (target + 1) * ratio
            var source = floor(from).toInt()
            while (source < to && source < sourceSize) {
                val covered = min(to, source + 1.0) - max(from, source.toDouble())
                if (covered > 0) {
                    indices += source
                    weights += (covered / ratio).toFloat()
                }
                source++
            }
        }
        start[targetSize] = indices.size
        index = indices.toIntArray()
        weight = weights.toFloatArray()
    }
}

/**
 * ONNX Runtime's own loader extracts its DLLs to a fresh temp directory every launch and, on
 * Windows, cannot delete them while loaded — so each session would strand ~17MB in %TEMP%.
 * Extract them once into the app data directory instead and point the loader there.
 */
private object OnnxRuntimeNatives {
    // Keep in step with the onnxruntime dependency in composeApp/build.gradle.kts.
    private const val Version = "1.31.0"
    private const val NativePathProperty = "onnxruntime.native.path"
    private val libraries = listOf("onnxruntime.dll", "onnxruntime4j_jni.dll", "onnxruntime_providers_shared.dll")

    fun install() {
        // An explicit location (a developer's, or a test's) wins.
        if (System.getProperty(NativePathProperty) != null) return
        val directory = DesktopStorage.rootDir.resolve("native").resolve("onnxruntime-$Version")
        Files.createDirectories(directory)
        for (library in libraries) {
            val target = directory.resolve(library)
            val resource = "/ai/onnxruntime/native/win-x64/$library"
            if (target.exists() && target.fileSize() > 0) continue
            val stream = OnnxRuntimeNatives::class.java.getResourceAsStream(resource) ?: continue
            val partial = directory.resolve("$library.part")
            stream.use { input -> partial.outputStream().use { input.copyTo(it) } }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        System.setProperty(NativePathProperty, directory.toAbsolutePath().toString())
        preloadBundledCppRuntime()
    }

    /**
     * onnxruntime.dll imports the MSVC C++ runtime, and Windows doesn't look beside the bundled
     * Java runtime for it. Loading the copies that runtime ships by full path makes them resolve
     * by name. MSVCP140_1.dll isn't among them and comes from the system's VC++ redistributable;
     * without it the models fail to load and framing falls back to the manual position.
     */
    private fun preloadBundledCppRuntime() {
        val runtimeBin = Path.of(System.getProperty("java.home")).resolve("bin")
        for (library in listOf("vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll", "msvcp140_1.dll")) {
            val file = runtimeBin.resolve(library)
            if (file.exists()) runCatching { System.load(file.toAbsolutePath().toString()) }
        }
    }
}

/**
 * Profiles on disk, one small file per artwork url, so a backdrop is analysed once rather than
 * once per launch. The format is versioned; a file from another version is a miss.
 */
private object FramingDiskCache {
    private const val Magic = 0x4E424652 // "NBFR"
    private const val FormatVersion = 1
    private const val MaxFiles = 4000

    private val directory: Path by lazy {
        DesktopStorage.rootDir.resolve("cache").resolve("backdrop-framing").also { Files.createDirectories(it) }
    }

    fun read(key: String): HeroBackdropFramingProfile? = runCatching {
        val file = directory.resolve(fileName(key))
        if (!file.exists()) return null
        DataInputStream(file.inputStream().buffered()).use { input ->
            if (input.readInt() != Magic || input.readInt() != FormatVersion) return null
            val aspect = input.readFloat()
            val rows = input.readInt()
            if (rows !in 1..4096) return null
            val mass = FloatArray(rows) { input.readFloat() }
            val cut = FloatArray(rows) { input.readFloat() }
            val faceCount = input.readInt()
            if (faceCount !in 0..FaceTopK) return null
            val faces = List(faceCount) {
                HeroBackdropFace(input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat())
            }
            HeroBackdropFramingProfile(aspect, mass, cut, faces)
        }
    }.onFailure { framingLog.d { "Unreadable framing cache entry: ${it.message}" } }.getOrNull()

    fun write(key: String, profile: HeroBackdropFramingProfile) {
        runCatching {
            val file = directory.resolve(fileName(key))
            val partial = directory.resolve("${fileName(key)}.part")
            DataOutputStream(partial.outputStream().buffered()).use { output ->
                output.writeInt(Magic)
                output.writeInt(FormatVersion)
                output.writeFloat(profile.imageAspect)
                output.writeInt(profile.subjectMass.size)
                profile.subjectMass.forEach(output::writeFloat)
                profile.edgeCut.forEach(output::writeFloat)
                output.writeInt(profile.faces.size)
                profile.faces.forEach {
                    output.writeFloat(it.left)
                    output.writeFloat(it.top)
                    output.writeFloat(it.right)
                    output.writeFloat(it.bottom)
                }
            }
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            pruneOccasionally()
        }.onFailure { framingLog.d { "Could not store framing profile: ${it.message}" } }
    }

    private var writesSincePrune = 0

    /** ~1.3KB a file; past [MaxFiles] the least recently written quarter goes. */
    private fun pruneOccasionally() {
        if (++writesSincePrune < 200) return
        writesSincePrune = 0
        val files = directory.toFile().listFiles { file -> file.name.endsWith(".bin") } ?: return
        if (files.size <= MaxFiles) return
        files.sortedBy { it.lastModified() }.take(files.size - MaxFiles * 3 / 4).forEach { it.delete() }
    }

    private fun fileName(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return digest.joinToString("") { "%02x".format(it) } + ".bin"
    }
}

private const val SaliencySize = 320
private const val ProfileRows = 160
private const val FaceInputSize = 640
private val FaceStrides = intArrayOf(8, 16, 32)
private const val FaceScoreThreshold = 0.7f
private const val FaceNmsThreshold = 0.3f
private const val FaceTopK = 50
private val ImageNetMean = floatArrayOf(0.485f, 0.456f, 0.406f)
private val ImageNetStd = floatArrayOf(0.229f, 0.224f, 0.225f)
