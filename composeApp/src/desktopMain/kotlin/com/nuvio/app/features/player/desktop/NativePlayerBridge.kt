package com.nuvio.app.features.player.desktop

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.storage.DesktopStorage
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

private val log = Logger.withTag("NativePlayerBridge")

internal fun findPackagedNativeRuntime(
    platform: DesktopHostOs,
    javaHome: File?,
    requiredFiles: List<String>,
): File? {
    if (platform != DesktopHostOs.WINDOWS) return null
    val installDir = javaHome?.parentFile?.takeIf { it.isDirectory } ?: return null
    return installDir.takeIf { dir -> requiredFiles.all { dir.resolve(it).isFile } }
}

internal fun interface NativePlayerEventSink {
    fun onPlayerEvent(type: String, value: Double)
}

internal object NativePlayerBridge {
    private var loadedRuntimeDir: File? = null

    init {
        loadNativeLibrary()
    }

    external fun create(
        hostViewPtr: Long,
        sourceUrl: String,
        sourceAudioUrl: String?,
        headerLines: Array<String>,
        playWhenReady: Boolean,
        initialPositionMs: Long,
        initialProgressFraction: Double,
        controlsPageUrl: String,
        nvidiaRtxSuperResolutionEnabled: Boolean,
        nvidiaRtxHdrEnabled: Boolean,
        isAnimeContent: Boolean,
        animeSvpFilter: String?,
        extraMpvOptions: Array<String>,
        eventSink: NativePlayerEventSink,
    ): Long

    external fun dispose(handle: Long)
    external fun updateControls(handle: Long, controlsJson: String)
    external fun runJavaScript(handle: Long, script: String)
    external fun requestSeekThumbnail(handle: Long, positionMs: Long)
    external fun setCursorHidden(handle: Long, hidden: Boolean)
    external fun setPaused(handle: Long, paused: Boolean)
    external fun completeSvpStartupProfile(handle: Long)
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
    external fun setVolume(handle: Long, volume: Float)
    external fun volume(handle: Long): Float
    external fun setMute(handle: Long, muted: Boolean)
    external fun isMuted(handle: Long): Boolean
    external fun audioTracksJson(handle: Long): String
    external fun subtitleTracksJson(handle: Long): String
    external fun chaptersJson(handle: Long): String
    external fun selectAudioTrack(handle: Long, trackId: Int): Boolean
    external fun selectSubtitleTrack(handle: Long, trackId: Int): Boolean
    external fun addSubtitleUrl(handle: Long, url: String)
    external fun clearExternalSubtitles(handle: Long)
    external fun clearExternalSubtitlesAndSelect(handle: Long, trackId: Int)
    external fun applyWindowChrome(
        windowHwnd: Long,
        darkMode: Boolean,
        captionColorRgb: Int,
        borderColorRgb: Int,
        textColorRgb: Int,
    )

    external fun setBorderlessFullscreen(windowHwnd: Long, enabled: Boolean)
    external fun setBorderlessFullscreenSuspended(windowHwnd: Long, suspended: Boolean)
    external fun setCompactPlayerWindow(windowHwnd: Long, enabled: Boolean)
    external fun beginCompactPlayerWindowMove(windowHwnd: Long)
    external fun beginCompactPlayerWindowResize(windowHwnd: Long, edge: Int)
    external fun updateCompactPlayerWindowInteraction(windowHwnd: Long)
    external fun endCompactPlayerWindowInteraction(windowHwnd: Long)

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
        fontName: String,
    )
    external fun setMpvProperty(handle: Long, key: String, value: String)
    external fun toggleStatsOverlay(handle: Long)
    external fun forceVideoRedraw(handle: Long)

    val controlsPageUrl: String by lazy { controlsPageAssets.url }
    private val controlsPageAssets: ControlsPageAssets by lazy { exportControlsPageAssets() }

    /**
     * Forces the object initializer (and therefore [loadNativeLibrary]) to run. Callers use this
     * to pay the DLL extraction/link cost on a thread of their choosing instead of wherever the
     * first real bridge call happens to land (historically the AWT event thread).
     */
    fun ensureNativeLibraryLoaded() = Unit

    private fun loadNativeLibrary() {
        val platform = DesktopHostOs.current
        require(platform == DesktopHostOs.MACOS || platform == DesktopHostOs.WINDOWS) {
            "Native desktop playback is not implemented for $platform yet."
        }

        val libraryName = nativeLibraryName(platform)
        val platformDir = nativeDirectoryName(platform)
        findLocalBuildLibrary(platformDir, libraryName)?.let { localLibrary ->
            copyLocalRuntimeResources(platformDir, localLibrary.parentFile)
            loadedRuntimeDir = localLibrary.parentFile
            System.load(localLibrary.absolutePath)
            return
        }

        // A packaged Windows app ships the complete native runtime beside Nuvio.exe. Read it
        // directly even when the installation directory is read-only (for example Program Files).
        // Requiring canWrite() here used to ignore a perfectly valid packaged runtime and fall
        // through to extracting a randomly named DLL under %TEMP%.
        packagedRuntimeDir(platform, platformDir, libraryName)?.let { runtimeDir ->
            loadedRuntimeDir = runtimeDir
            System.load(runtimeDir.resolve(libraryName).absolutePath)
            return
        }

        // Release builds must never create executable files at runtime. A deliberately incomplete
        // development layout can opt into the legacy extraction fallback explicitly.
        val extractionEnabled = System.getProperty("nuvio.nativeRuntimeExtractionEnabled")
            ?.equals("true", ignoreCase = true) == true
        check(extractionEnabled) {
            "The packaged native player runtime is incomplete. Reinstall Nuvio or, for a local " +
                "development layout only, pass -Dnuvio.nativeRuntimeExtractionEnabled=true."
        }
        val runtimeDir = userRuntimeDir(platformDir)
        if (extractBundledNativeLibraryIfNeeded(platformDir, libraryName, runtimeDir)) {
            extractPythonLibIfNeeded(platformDir, runtimeDir)
            loadedRuntimeDir = runtimeDir
            System.load(runtimeDir.resolve(libraryName).absolutePath)
            return
        }
        error("Unable to prepare the bundled native player runtime in $runtimeDir")
    }

    internal fun runtimeDllDir(): File? = loadedRuntimeDir

    private fun packagedRuntimeDir(
        platform: DesktopHostOs,
        platformDir: String,
        libraryName: String,
    ): File? {
        val requiredFiles = listOf(libraryName) + bundledRuntimeResourceNames(platformDir)
        val javaHome = System.getProperty("java.home")?.takeIf { it.isNotBlank() }?.let(::File)
        return findPackagedNativeRuntime(platform, javaHome, requiredFiles)
    }

    private fun userRuntimeDir(platformDir: String): File {
        val version = AppVersionConfig.DESKTOP_VERSION_NAME
            .ifBlank { "dev" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        return DesktopStorage.rootDir
            .resolve("runtime")
            .resolve(version)
            .resolve(platformDir)
            .toFile()
            .apply { mkdirs() }
    }

    private fun extractBundledNativeLibraryIfNeeded(platformDir: String, libraryName: String, dir: File): Boolean {
        val mainFile = dir.resolve(libraryName)
        val runtimeNames = bundledRuntimeResourceNames(platformDir)
        if (mainFile.exists() && runtimeNames.all { dir.resolve(it).exists() }) {
            aliasVapourSynthScriptLibrary(dir)
            return true
        }
        return runCatching {
            copyResourceTo("/native/$platformDir/$libraryName", mainFile)
            runtimeNames.forEach { name ->
                copyResourceTo("/native/$platformDir/$name", dir.resolve(name))
            }
            aliasVapourSynthScriptLibrary(dir)
            true
        }.onFailure { error ->
            log.e(error) { "Failed to extract bundled native runtime into $dir" }
        }.getOrElse { false }
    }

    // Bundles a curated, minimal Python stdlib subset (see windowsPythonLibFiles in
    // build.gradle.kts) so VapourSynth's vsscript can actually execute svp_main.vpy — vsscript.dll
    // loading (aliased above) only gets Python's *interpreter* working; without its own stdlib,
    // Py_Initialize()/`import os` etc. inside the script would still fail. Extracted to
    // <installDir>/lib/python3.14/... — NOT <installDir>/pylib/lib/python3.14 (an earlier,
    // incorrect layout). Confirmed via a WinDbg trace of libpython3.14's actual landmark search
    // (breakpoints on CreateFileW/GetFileAttributesW during a real vsscript_init() call) that this
    // build's getpath algorithm does not consult PYTHONHOME at all when invoked through
    // vsscript_init — it walks upward from Nuvio.exe's own directory looking for a bare
    // lib/pythonX.Y/os.py, so the stdlib must live at <installDir>/lib/python3.14 to ever be
    // found. Best-effort and silent on failure: SVP simply stays unavailable, exactly as when
    // VapourSynth itself is missing.
    private fun extractPythonLibIfNeeded(platformDir: String, installDir: File) {
        val indexResource = "/native/$platformDir/pylib/python-lib-files.txt"
        val relativePaths = NativePlayerBridge::class.java.getResourceAsStream(indexResource)
            ?.bufferedReader()
            ?.useLines { lines -> lines.map(String::trim).filter { it.isNotEmpty() }.toList() }
            .orEmpty()
        if (relativePaths.isEmpty()) return
        val pythonLibDir = installDir.resolve("lib").resolve("python3.14")
        if (relativePaths.all { pythonLibDir.resolve(it).exists() }) return
        runCatching {
            relativePaths.forEach { relativePath ->
                val target = pythonLibDir.resolve(relativePath)
                target.parentFile?.mkdirs()
                copyResourceTo("/native/$platformDir/pylib/$relativePath", target)
            }
        }.onFailure { error ->
            log.w(error) { "Failed to extract bundled Python stdlib subset into $pythonLibDir" }
        }
    }

    // The bundled mpv build was compiled against MSYS2/mingw-w64's VapourSynth package, whose
    // scripting library ships as libvapoursynth-script-0.dll. mpv's own vf_vapoursynth filter
    // dlopen()s a hardcoded "vsscript.dll" on Windows regardless of how mpv itself was built
    // (that's the literal name in mpv's upstream source for the Windows case), so without this
    // alias the filter fails with "specified module could not be found" even though the real
    // library is sitting right next to it under its mingw name.
    private fun aliasVapourSynthScriptLibrary(dir: File) {
        val source = dir.resolve("libvapoursynth-script-0.dll")
        val alias = dir.resolve("vsscript.dll")
        if (!source.isFile || alias.isFile) return
        runCatching { source.copyTo(alias, overwrite = true) }
            .onFailure { error -> log.w(error) { "Failed to alias $source as $alias" } }
    }

    private fun copyResourceTo(resource: String, target: File) {
        val input = NativePlayerBridge::class.java.getResourceAsStream(resource)
            ?: error("Missing bundled native resource: $resource")
        target.parentFile?.mkdirs()
        val staging = target.resolveSibling("${target.name}.part-${UUID.randomUUID()}")
        try {
            input.use { source ->
                staging.outputStream().use { output -> source.copyTo(output) }
            }
            runCatching {
                Files.move(
                    staging.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.recoverCatching {
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }.getOrThrow()
        } finally {
            runCatching { Files.deleteIfExists(staging.toPath()) }
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
        aliasVapourSynthScriptLibrary(targetDir)
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

    private fun exportControlsPageAssets(): ControlsPageAssets {
        val root = File(System.getProperty("java.io.tmpdir"), "nuvio-player-ui").apply { mkdirs() }
        val fontsDir = root.resolve("fonts").apply { mkdirs() }
        val htmlFile = root.resolve("controls.html")
        writeTextIfChanged(
            target = htmlFile,
            text = readTextResource("/player-ui/controls.html"),
        )
        writeTextIfChanged(
            target = root.resolve("controls.css"),
            text = readTextResource("/player-ui/controls.css")
                .replace("/* __NUVIO_PLAYER_FONT_FACES__ */", nativePlayerFontFaces()),
        )
        copyResourceIfChanged(
            resource = "/player-ui/controls.js",
            target = root.resolve("controls.js"),
        )
        copyResourceIfChanged(
            resource = "/composeResources/nuvio.composeapp.generated.resources/font/jetbrains_sans_regular.ttf",
            target = fontsDir.resolve("jetbrains_sans_regular.ttf"),
        )
        copyResourceIfChanged(
            resource = "/composeResources/nuvio.composeapp.generated.resources/font/jetbrains_sans_semibold.ttf",
            target = fontsDir.resolve("jetbrains_sans_semibold.ttf"),
        )
        copyResourceIfChanged(
            resource = "/composeResources/nuvio.composeapp.generated.resources/font/jetbrains_sans_bold.ttf",
            target = fontsDir.resolve("jetbrains_sans_bold.ttf"),
        )
        return ControlsPageAssets(
            url = htmlFile.toURI().toASCIIString(),
        )
    }

    private fun nativePlayerFontFaces(): String =
        """
            @font-face {
              font-family: "Nuvio JetBrains Sans";
              src: url("fonts/jetbrains_sans_regular.ttf") format("truetype");
              font-weight: 400;
              font-style: normal;
              font-display: block;
            }
            @font-face {
              font-family: "Nuvio JetBrains Sans";
              src: url("fonts/jetbrains_sans_semibold.ttf") format("truetype");
              font-weight: 600;
              font-style: normal;
              font-display: block;
            }
            @font-face {
              font-family: "Nuvio JetBrains Sans";
              src: url("fonts/jetbrains_sans_bold.ttf") format("truetype");
              font-weight: 700 900;
              font-style: normal;
              font-display: block;
            }
        """.trimIndent()

    private fun readTextResource(resource: String): String =
        NativePlayerBridge::class.java.getResourceAsStream(resource)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            ?: error("Missing native player controls resource: $resource")

    private fun writeTextIfChanged(target: File, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (target.exists() && target.readBytes().contentEquals(bytes)) return
        target.writeBytes(bytes)
    }

    private fun copyResourceIfChanged(resource: String, target: File) {
        val bytes = NativePlayerBridge::class.java.getResourceAsStream(resource)
            ?.use { it.readBytes() }
            ?: error("Missing native player controls resource: $resource")
        if (target.exists() && target.readBytes().contentEquals(bytes)) return
        Files.createDirectories(target.parentFile.toPath())
        target.writeBytes(bytes)
    }

    private data class ControlsPageAssets(
        val url: String,
    )
}

/** Blocks until the native bridge library is loaded (concurrent callers wait on class init). */
internal fun ensureNativePlayerBridgeLoaded() {
    NativePlayerBridge.ensureNativeLibraryLoaded()
}
