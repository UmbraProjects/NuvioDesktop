package com.nuvio.app.features.player.desktop

import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

internal fun interface NativePlayerEventSink {
    fun onPlayerEvent(type: String, value: Double)
}

internal object NativePlayerBridge {
    private val preloadStarted = AtomicBoolean(false)

    init {
        loadNativeLibrary()
    }

    external fun create(
        hostViewPtr: Long,
        sourceUrl: String,
        headerLines: Array<String>,
        playWhenReady: Boolean,
        initialPositionMs: Long,
        controlsHtml: String,
        eventSink: NativePlayerEventSink,
    ): Long

    external fun dispose(handle: Long)
    external fun updateControls(handle: Long, controlsJson: String)
    external fun setPaused(handle: Long, paused: Boolean)
    external fun seekTo(handle: Long, positionMs: Long)
    external fun seekBy(handle: Long, offsetMs: Long)
    external fun setSpeed(handle: Long, speed: Float)
    external fun setResizeMode(handle: Long, mode: Int)
    external fun durationMs(handle: Long): Long
    external fun positionMs(handle: Long): Long
    external fun bufferedPositionMs(handle: Long): Long
    external fun isLoading(handle: Long): Boolean
    external fun isEnded(handle: Long): Boolean
    external fun isPaused(handle: Long): Boolean
    external fun speed(handle: Long): Float
    external fun audioTracksJson(handle: Long): String
    external fun subtitleTracksJson(handle: Long): String
    external fun selectAudioTrack(handle: Long, trackId: Int)
    external fun selectSubtitleTrack(handle: Long, trackId: Int)
    external fun addSubtitleUrl(handle: Long, url: String)
    external fun clearExternalSubtitles(handle: Long)
    external fun clearExternalSubtitlesAndSelect(handle: Long, trackId: Int)
    external fun setSubtitleDelayMs(handle: Long, delayMs: Int)
    external fun applySubtitleStyle(
        handle: Long,
        textColor: String,
        backgroundColor: String,
        outlineColor: String,
        outlineSize: Float,
        bold: Boolean,
        fontSize: Float,
        subPos: Int,
    )

    val controlsHtml: String by lazy {
        val resource = "/player-ui/controls.html"
        val input = NativePlayerBridge::class.java.getResourceAsStream(resource)
            ?: error("Missing native player controls resource: $resource")
        input.bufferedReader().use { it.readText() }
            .replace("/* __NUVIO_PLAYER_FONT_FACES__ */", nativePlayerFontFaces())
    }

    fun preloadAsync() {
        if (!preloadStarted.compareAndSet(false, true)) return
        Thread {
            runCatching { controlsHtml }
        }.apply {
            name = "nuvio-native-player-preload"
            isDaemon = true
            start()
        }
    }

    private fun loadNativeLibrary() {
        val platform = DesktopHostOs.current
        require(platform == DesktopHostOs.MACOS || platform == DesktopHostOs.WINDOWS) {
            "Native desktop playback is not implemented for $platform yet."
        }

        val libraryName = nativeLibraryName(platform)
        val platformDir = nativeDirectoryName(platform)
        findLocalBuildLibrary(platformDir, libraryName)?.let { localLibrary ->
            copyLocalRuntimeResources(platformDir, localLibrary.parentFile)
            System.load(localLibrary.absolutePath)
            return
        }

        val resource = "/native/$platformDir/$libraryName"
        val input = NativePlayerBridge::class.java.getResourceAsStream(resource)
            ?: error("Missing bundled native player bridge: $resource")
        val dir = File(System.getProperty("java.io.tmpdir"), "native-player-bridge").apply { mkdirs() }
        val suffix = libraryName.substringAfter("player_bridge", ".dylib")
        val file = Files.createTempFile(dir.toPath(), "player-bridge-", suffix).toFile()
        file.deleteOnExit()
        extractBundledRuntimeResources(platformDir, dir)
        input.use { source ->
            file.outputStream().use { target -> source.copyTo(target) }
        }
        System.load(file.absolutePath)
    }

    private fun extractBundledRuntimeResources(platformDir: String, dir: File) {
        val runtimeNames = bundledRuntimeResourceNames(platformDir)
        runtimeNames.forEach { name ->
            val resource = "/native/$platformDir/$name"
            val input = NativePlayerBridge::class.java.getResourceAsStream(resource) ?: return@forEach
            val target = dir.resolve(name)
            input.use { source ->
                target.outputStream().use { output -> source.copyTo(output) }
            }
            target.deleteOnExit()
        }
    }

    private fun bundledRuntimeResourceNames(platformDir: String): List<String> {
        val indexResource = "/native/$platformDir/runtime-files.txt"
        val indexed = NativePlayerBridge::class.java.getResourceAsStream(indexResource)
            ?.bufferedReader()
            ?.useLines { lines ->
                lines.map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toList()
            }
            .orEmpty()
        if (indexed.isNotEmpty()) return indexed

        return when (platformDir) {
            "windows" -> listOf("libmpv-2.dll")
            else -> emptyList()
        }
    }

    private fun findLocalBuildLibrary(platformDir: String, libraryName: String): File? {
        val candidates = listOf(
            File("composeApp/build/native/$platformDir/$libraryName"),
            File("build/native/$platformDir/$libraryName"),
        )
        return candidates.firstOrNull { it.exists() }
    }

    private fun copyLocalRuntimeResources(platformDir: String, targetDir: File) {
        val runtimeDirs = listOf(
            File("composeApp/build/native/$platformDir-runtime"),
            File("build/native/$platformDir-runtime"),
        )
        runtimeDirs.firstOrNull(File::isDirectory)
            ?.listFiles { file -> file.isFile }
            ?.forEach { runtimeFile ->
                val target = targetDir.resolve(runtimeFile.name)
                if (runtimeFile.absolutePath != target.absolutePath) {
                    runCatching { runtimeFile.copyTo(target, overwrite = true) }
                }
            }
    }

    private fun nativeDirectoryName(platform: DesktopHostOs): String =
        when (platform) {
            DesktopHostOs.MACOS -> "macos"
            DesktopHostOs.WINDOWS -> "windows"
            DesktopHostOs.LINUX -> "linux"
            DesktopHostOs.UNKNOWN -> "unknown"
        }

    private fun nativeLibraryName(platform: DesktopHostOs): String =
        when (platform) {
            DesktopHostOs.MACOS -> "libplayer_bridge.dylib"
            DesktopHostOs.WINDOWS -> "player_bridge.dll"
            DesktopHostOs.LINUX -> "libplayer_bridge.so"
            DesktopHostOs.UNKNOWN -> "player_bridge"
        }

    private fun nativePlayerFontFaces(): String =
        listOfNotNull(
            nativePlayerFontFace(
                fileName = "jetbrains_sans_regular.ttf",
                weight = "400",
            ),
            nativePlayerFontFace(
                fileName = "jetbrains_sans_semibold.ttf",
                weight = "600",
            ),
            nativePlayerFontFace(
                fileName = "jetbrains_sans_bold.ttf",
                weight = "700 900",
            ),
        ).joinToString(separator = "\n")

    private fun nativePlayerFontFace(fileName: String, weight: String): String? {
        val resource = "/composeResources/nuvio.composeapp.generated.resources/font/$fileName"
        val bytes = NativePlayerBridge::class.java.getResourceAsStream(resource)
            ?.use { it.readBytes() }
            ?: return null
        val encoded = Base64.getEncoder().encodeToString(bytes)
        return """
            @font-face {
              font-family: "Nuvio JetBrains Sans";
              src: url("data:font/ttf;base64,$encoded") format("truetype");
              font-weight: $weight;
              font-style: normal;
              font-display: block;
            }
        """.trimIndent()
    }
}

internal fun preloadNativePlayerBridgeAsync() {
    if (DesktopHostOs.current == DesktopHostOs.MACOS || DesktopHostOs.current == DesktopHostOs.WINDOWS) {
        NativePlayerBridge.preloadAsync()
    }
}
