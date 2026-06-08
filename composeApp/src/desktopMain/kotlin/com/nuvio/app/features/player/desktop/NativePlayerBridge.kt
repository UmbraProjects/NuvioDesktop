package com.nuvio.app.features.player.desktop

import java.io.File
import java.nio.file.Files

internal fun interface NativePlayerEventSink {
    fun onPlayerEvent(type: String, value: Double)
}

internal object NativePlayerBridge {
    init {
        loadNativeLibrary()
    }

    external fun create(
        hostViewPtr: Long,
        sourceUrl: String,
        headerLines: Array<String>,
        playWhenReady: Boolean,
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
    }

    private fun loadNativeLibrary() {
        val platform = DesktopHostOs.current
        require(platform == DesktopHostOs.MACOS) {
            "Native desktop playback is not implemented for $platform yet."
        }

        val libraryName = nativeLibraryName(platform)
        val platformDir = nativeDirectoryName(platform)
        findLocalBuildLibrary(platformDir, libraryName)?.let { localLibrary ->
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
        input.use { source ->
            file.outputStream().use { target -> source.copyTo(target) }
        }
        System.load(file.absolutePath)
    }

    private fun findLocalBuildLibrary(platformDir: String, libraryName: String): File? {
        val candidates = listOf(
            File("composeApp/build/native/$platformDir/$libraryName"),
            File("build/native/$platformDir/$libraryName"),
        )
        return candidates.firstOrNull { it.exists() }
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
}
