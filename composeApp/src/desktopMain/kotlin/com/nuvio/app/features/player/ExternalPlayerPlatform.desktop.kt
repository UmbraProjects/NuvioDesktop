package com.nuvio.app.features.player

import com.nuvio.app.core.storage.DesktopStorage
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

private data class DesktopExternalPlayerIntent(
    val request: ExternalPlayerPlaybackRequest,
    val playerId: String?,
)

/**
 * A known desktop media player and how to launch it.
 *
 * [candidatePaths] are absolute exe locations (with `%ENV%` placeholders expanded at lookup
 * time). [executableNames] also identifies a registered default player and provides a
 * subprocess-free PATH fallback for non-standard installations.
 * [buildArgs] produces the command-line arguments (after the exe and the URL) for a request,
 * using only options the player reliably supports.
 */
private class DesktopPlayerDefinition(
    val id: String,
    val displayName: String,
    val candidatePaths: List<String>,
    val executableNames: List<String>,
    val buildArgs: (ExternalPlayerPlaybackRequest) -> List<String>,
)

internal actual object ExternalPlayerPlatform {
    private const val systemPlayerId = "system"
    private val isWindows = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("win")
    private val customPlayerStore = DesktopStorage.store("nuvio_external_players")

    private val definitions: List<DesktopPlayerDefinition> = listOf(
        DesktopPlayerDefinition(
            id = "mpv",
            displayName = "mpv",
            candidatePaths = listOf(
                "%ProgramFiles%\\mpv\\mpv.exe",
                "%ProgramFiles%\\mpv.net\\mpvnet.exe",
                "%LOCALAPPDATA%\\Programs\\mpv.net\\mpvnet.exe",
            ),
            executableNames = listOf("mpv.exe", "mpvnet.exe"),
            buildArgs = { request ->
                buildList {
                    request.buildPlayerTitle(includeEpisodeTitle = true)
                        .takeIf { it.isNotBlank() }
                        ?.let { add("--force-media-title=$it") }
                    if (request.resumePositionMs > 0) {
                        add("--start=${(request.resumePositionMs / 1000L)}")
                    }
                    request.sourceHeaders.toMpvHeaderFields()?.let { add("--http-header-fields=$it") }
                    request.subtitles.orEmpty().forEach { add("--sub-file=${it.url}") }
                }
            },
        ),
        DesktopPlayerDefinition(
            id = "vlc",
            displayName = "VLC",
            candidatePaths = listOf(
                "%ProgramFiles%\\VideoLAN\\VLC\\vlc.exe",
                "%ProgramFiles(x86)%\\VideoLAN\\VLC\\vlc.exe",
            ),
            executableNames = listOf("vlc.exe"),
            buildArgs = { request ->
                buildList {
                    request.buildPlayerTitle(includeEpisodeTitle = true)
                        .takeIf { it.isNotBlank() }
                        ?.let { add("--meta-title=$it") }
                    if (request.resumePositionMs > 0) {
                        add("--start-time=${(request.resumePositionMs / 1000L)}")
                    }
                    // VLC only exposes a fixed set of HTTP headers, not arbitrary ones.
                    request.sourceHeaders.headerValue("user-agent")?.let { add("--http-user-agent=$it") }
                    request.sourceHeaders.headerValue("referer", "referrer")?.let { add("--http-referrer=$it") }
                    request.subtitles?.firstOrNull()?.let { add("--sub-file=${it.url}") }
                }
            },
        ),
        DesktopPlayerDefinition(
            id = "mpc-hc",
            displayName = "MPC-HC",
            candidatePaths = listOf(
                "%ProgramFiles%\\MPC-HC\\mpc-hc64.exe",
                "%ProgramFiles(x86)%\\MPC-HC\\mpc-hc.exe",
                "%ProgramFiles%\\MPC-HC64\\mpc-hc64.exe",
                "%ProgramFiles%\\K-Lite Codec Pack\\MPC-HC64\\mpc-hc64.exe",
            ),
            executableNames = listOf("mpc-hc64.exe", "mpc-hc.exe"),
            buildArgs = { request ->
                buildList {
                    // MPC-HC takes the resume position in milliseconds via /start.
                    if (request.resumePositionMs > 0) {
                        add("/start")
                        add(request.resumePositionMs.toString())
                    }
                    addAll(request.mpcSubtitleArgs())
                }
            },
        ),
        DesktopPlayerDefinition(
            id = "mpc-be",
            displayName = "MPC-BE",
            candidatePaths = listOf(
                "%ProgramFiles%\\MPC-BE\\mpc-be64.exe",
                "%ProgramFiles%\\MPC-BE x64\\mpc-be64.exe",
                "%ProgramFiles%\\MPC-BE\\mpc-be.exe",
                "%ProgramFiles(x86)%\\MPC-BE\\mpc-be.exe",
                "%ProgramFiles(x86)%\\MPC-BE x86\\mpc-be.exe",
                "%LOCALAPPDATA%\\Programs\\MPC-BE\\mpc-be64.exe",
                "%LOCALAPPDATA%\\Programs\\MPC-BE x64\\mpc-be64.exe",
                "%USERPROFILE%\\scoop\\apps\\mpc-be\\current\\mpc-be64.exe",
            ),
            executableNames = listOf("mpc-be64.exe", "mpc-be.exe"),
            buildArgs = { request ->
                buildList {
                    if (request.resumePositionMs > 0) {
                        add("/start")
                        add(request.resumePositionMs.toString())
                    }
                    addAll(request.mpcSubtitleArgs())
                }
            },
        ),
        DesktopPlayerDefinition(
            id = "potplayer",
            displayName = "PotPlayer",
            candidatePaths = listOf(
                "%ProgramFiles%\\DAUM\\PotPlayer\\PotPlayerMini64.exe",
                "%ProgramFiles(x86)%\\DAUM\\PotPlayer\\PotPlayerMini.exe",
                "%ProgramFiles%\\DAUM\\PotPlayer64\\PotPlayer64.exe",
            ),
            executableNames = listOf("PotPlayerMini64.exe", "PotPlayerMini.exe", "PotPlayer64.exe"),
            buildArgs = { request ->
                buildList {
                    if (request.resumePositionMs > 0) {
                        add("/seek=${formatHms(request.resumePositionMs)}")
                    }
                    request.sourceHeaders.headerValue("user-agent")?.let { add("/user_agent=$it") }
                    request.sourceHeaders.headerValue("referer", "referrer")?.let { add("/referer=$it") }
                    val otherHeaders = request.sourceHeaders.entries
                        .filter { entry ->
                            val keyLower = entry.key.lowercase(Locale.ROOT)
                            keyLower != "user-agent" && keyLower != "referer" && keyLower != "referrer" && entry.key.isNotBlank() && entry.value.isNotBlank()
                        }
                        .joinToString("\r\n") { "${it.key}: ${it.value}" }
                    if (otherHeaders.isNotEmpty()) {
                        add("/headers=$otherHeaders")
                    }
                    // Documented in PotPlayer's own CmdLine64.txt: /sub="subfile" loads the
                    // specified subtitle(s) from the given paths or URLs.
                    request.subtitles.orEmpty().forEach { add("/sub=${it.url}") }
                }
            },
        ),
    )

    /** Resolved only when a specific player is needed; startup no longer scans every player. */
    private val resolvedPaths = mutableMapOf<String, String?>()
    private val defaultMediaAssociation: WindowsMediaAssociation? by lazy {
        if (isWindows) detectWindowsDefaultMediaAssociation() else null
    }

    private fun resolvedPath(def: DesktopPlayerDefinition): String? = synchronized(resolvedPaths) {
        if (resolvedPaths.containsKey(def.id)) return@synchronized resolvedPaths[def.id]
        val customPath = customPlayerStore
            .getString(customPlayerPathKey(def.id))
            ?.let(::File)
            ?.takeIf(File::isFile)
            ?.absolutePath
        val associatedPath = defaultMediaAssociation
            ?.takeIf { association -> def.matchesExecutable(association.executablePath) }
            ?.executablePath
        (customPath ?: associatedPath ?: resolveExecutable(def)).also { resolvedPaths[def.id] = it }
    }

    actual fun defaultPlayerId(): String? =
        defaultMediaAssociation
            ?.let { association -> definitions.firstOrNull { it.matchesExecutable(association.executablePath) } }
            ?.also { def ->
                synchronized(resolvedPaths) {
                    resolvedPaths[def.id] = defaultMediaAssociation?.executablePath
                }
            }
            ?.id
            ?: definitions.firstOrNull { resolvedPath(it) != null }?.id
            ?: systemPlayerId

    actual fun availablePlayers(): List<ExternalPlayerApp> =
        buildList {
            definitions.forEach { def ->
                add(
                    ExternalPlayerApp(
                        id = def.id,
                        name = def.displayName,
                        isAvailable = resolvedPath(def) != null,
                    ),
                )
            }
            // Always offer the OS handler as a fallback (e.g. a player we don't detect, or the
            // user's own file/URL association). It hands the URL to whatever is registered.
            val defaultLabel = defaultMediaAssociation
                ?.displayName
                ?.takeIf { it.isNotBlank() }
                ?.let { "System default ($it)" }
                ?: "System default"
            add(ExternalPlayerApp(systemPlayerId, defaultLabel))
        }

    actual fun configurePlayer(playerId: String): Boolean {
        val def = definitions.firstOrNull { it.id == playerId } ?: return playerId == systemPlayerId
        val selectedPath = pickPlayerExecutable(def) ?: return false
        customPlayerStore.putString(customPlayerPathKey(def.id), selectedPath)
        synchronized(resolvedPaths) {
            resolvedPaths[def.id] = selectedPath
        }
        return true
    }

    actual fun open(
        request: ExternalPlayerPlaybackRequest,
        playerId: String?,
    ): ExternalPlayerOpenResult {
        val effectiveId = playerId?.takeIf { id ->
            id == systemPlayerId || definitions.any { it.id == id }
        } ?: defaultPlayerId()

        if (effectiveId == null || effectiveId == systemPlayerId) {
            defaultMediaAssociation?.let { association ->
                val knownDefinition = definitions.firstOrNull { it.matchesExecutable(association.executablePath) }
                val command = buildList {
                    add(association.executablePath)
                    if (knownDefinition != null) addAll(knownDefinition.buildArgs(request))
                    add(request.sourceUrl)
                }
                if (launchDetached(command)) return ExternalPlayerOpenResult.Opened
            }
            return if (openUri(request.sourceUrl)) ExternalPlayerOpenResult.Opened
            else ExternalPlayerOpenResult.Failed
        }

        val def = definitions.firstOrNull { it.id == effectiveId }
            ?: return ExternalPlayerOpenResult.Failed
        val exePath = resolvedPath(def)
            ?: return ExternalPlayerOpenResult.NoPlayerAvailable

        val command = buildList {
            add(exePath)
            addAll(def.buildArgs(request))
            add(request.sourceUrl)
        }
        return if (launchDetached(command)) ExternalPlayerOpenResult.Opened else ExternalPlayerOpenResult.Failed
    }

    /**
     * Starts a child process without keeping its stdio pipes attached. This is essential: with the
     * default [ProcessBuilder] behaviour the child's stdout/stderr are piped to us, and because we
     * never read them a chatty player (mpv prints a status line every frame; PotPlayer logs too)
     * fills the ~64 KB OS pipe buffer within seconds and then blocks on write — before it renders a
     * frame. That surfaced as "mpv is a black screen" / "PotPlayer is unresponsive" even though the
     * same URL plays instantly when opened by hand. Discarding the streams removes the pipe.
     */
    private fun launchDetached(command: List<String>): Boolean =
        runCatching {
            ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }.isSuccess

    actual fun buildIntent(
        request: ExternalPlayerPlaybackRequest,
        playerId: String?,
    ): ExternalPlayerIntentResult =
        ExternalPlayerIntentResult.Success(DesktopExternalPlayerIntent(request, playerId))

    internal fun launch(intent: Any): Boolean {
        val desktopIntent = intent as? DesktopExternalPlayerIntent ?: return false
        return open(desktopIntent.request, desktopIntent.playerId) == ExternalPlayerOpenResult.Opened
    }

    // --- executable resolution ------------------------------------------------------------

    private fun resolveExecutable(def: DesktopPlayerDefinition): String? {
        // Keep discovery in-process: check known install locations first, then PATH.
        def.candidatePaths.forEach { candidate ->
            val expanded = expandEnvPlaceholders(candidate)
            if (expanded != null && File(expanded).isFile) return expanded
        }
        def.executableNames.forEach { exe ->
            findOnPath(exe)?.let { return it }
        }
        return null
    }

    /** Expands `%VAR%` occurrences; returns null if any referenced variable is unset. */
    private fun expandEnvPlaceholders(path: String): String? {
        val regex = Regex("%([^%]+)%")
        var missing = false
        val result = regex.replace(path) { match ->
            val value = System.getenv(match.groupValues[1])
            if (value == null) { missing = true; "" } else value
        }
        return if (missing) null else result
    }

    private fun findOnPath(exe: String): String? {
        val pathEnv = System.getenv("PATH") ?: return null
        return pathEnv.split(File.pathSeparatorChar)
            .asSequence()
            .map { File(it.trim(), exe) }
            .firstOrNull { it.isFile }
            ?.absolutePath
    }

    private fun DesktopPlayerDefinition.matchesExecutable(path: String): Boolean {
        val fileName = File(path).name
        return executableNames.any { it.equals(fileName, ignoreCase = true) }
    }

    private fun pickPlayerExecutable(def: DesktopPlayerDefinition): String? {
        val holder = arrayOfNulls<String>(1)
        val choose = Runnable {
            runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
            val chooser = JFileChooser().apply {
                dialogTitle = "Locate ${def.displayName}"
                fileSelectionMode = JFileChooser.FILES_ONLY
                isMultiSelectionEnabled = false
                if (isWindows) {
                    fileFilter = FileNameExtensionFilter("Applications (*.exe)", "exe")
                }
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                val selected = chooser.selectedFile?.takeIf(File::isFile)
                val valid = selected != null && (
                    !isWindows || def.executableNames.any { it.equals(selected.name, ignoreCase = true) }
                )
                if (valid) holder[0] = selected?.absolutePath
            }
        }
        if (SwingUtilities.isEventDispatchThread()) {
            choose.run()
        } else {
            runCatching { SwingUtilities.invokeAndWait(choose) }
        }
        return holder[0]
    }

    private fun customPlayerPathKey(playerId: String): String = "path.$playerId"

    // --- Windows default media association ------------------------------------------------

    /**
     * Reads the current user's video association without probing or launching any player.
     * `reg.exe` is invoked directly (never through cmd/PowerShell), read-only, at most once per
     * process. This avoids executable crawling and extra native/JNA extraction that can look
     * suspicious to endpoint protection.
     */
    private fun detectWindowsDefaultMediaAssociation(): WindowsMediaAssociation? {
        val progId = listOf(".mkv", ".mp4")
            .firstNotNullOfOrNull { extension ->
                queryRegistryValue(
                    key = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\FileExts\\$extension\\UserChoice",
                    valueName = "ProgId",
                )
            }
            ?: return null
        val openCommand = queryRegistryValue(
            key = "HKCR\\$progId\\shell\\open\\command",
            valueName = null,
        ) ?: return null
        val executable = executableFromOpenCommand(openCommand)
            ?.let(::expandEnvPlaceholders)
            ?.let(::File)
            ?.takeIf(File::isFile)
            ?.absolutePath
            ?: return null
        val knownName = definitions
            .firstOrNull { it.matchesExecutable(executable) }
            ?.displayName
        return WindowsMediaAssociation(
            executablePath = executable,
            displayName = knownName ?: File(executable).nameWithoutExtension,
        )
    }

    private fun queryRegistryValue(key: String, valueName: String?): String? {
        val systemRoot = System.getenv("SystemRoot") ?: return null
        val regExe = File(systemRoot, "System32\\reg.exe").takeIf(File::isFile) ?: return null
        val command = buildList {
            add(regExe.absolutePath)
            add("query")
            add(key)
            if (valueName == null) {
                add("/ve")
            } else {
                add("/v")
                add(valueName)
            }
        }
        return runCatching {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return@runCatching null
            }
            if (process.exitValue() != 0) return@runCatching null
            process.inputStream.bufferedReader().use { reader ->
                reader.lineSequence()
                    .mapNotNull { line ->
                        REGISTRY_STRING_VALUE.find(line)?.groupValues?.getOrNull(1)?.trim()
                    }
                    .firstOrNull { it.isNotBlank() }
            }
        }.getOrNull()
    }

    private fun executableFromOpenCommand(command: String): String? {
        val trimmed = command.trim()
        if (trimmed.startsWith('"')) {
            return trimmed.substringAfter('"').substringBefore('"').takeIf { it.isNotBlank() }
        }
        val exeEnd = trimmed.indexOf(".exe", ignoreCase = true)
        return if (exeEnd >= 0) trimmed.substring(0, exeEnd + 4).trim() else null
    }

    // --- system-handler fallback (previous behaviour) -------------------------------------

    private fun openUri(rawUri: String): Boolean {
        val uri = runCatching { URI(rawUri) }.getOrNull() ?: return false
        val desktop = runCatching { Desktop.getDesktop() }.getOrNull()

        if (desktop != null && Desktop.isDesktopSupported()) {
            val opened = runCatching {
                if (uri.scheme.equals("file", ignoreCase = true)) {
                    desktop.open(File(uri))
                } else {
                    desktop.browse(uri)
                }
            }.isSuccess
            if (opened) return true
        }

        return openWithPlatformCommand(rawUri)
    }

    private fun openWithPlatformCommand(rawUri: String): Boolean {
        val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        val command = when {
            osName.contains("mac") -> listOf("open", rawUri)
            osName.contains("win") -> listOf("rundll32", "url.dll,FileProtocolHandler", rawUri)
            else -> listOf("xdg-open", rawUri)
        }
        return launchDetached(command)
    }
}

private data class WindowsMediaAssociation(
    val executablePath: String,
    val displayName: String,
)

private val REGISTRY_STRING_VALUE = Regex("""(?i)\sREG_(?:EXPAND_)?SZ\s+(.+)$""")

// --- header/format helpers ----------------------------------------------------------------

/**
 * MPC-HC and MPC-BE both document `/sub "subname"  Load an additional subtitle file` (verified
 * against the switch list embedded in mpc-hc64.exe). The switch takes the path as a separate
 * argument, and only local paths are reliable — which is why the desktop
 * [SubtitleCacheProvider] downloads addon subtitles before launch.
 */
private fun ExternalPlayerPlaybackRequest.mpcSubtitleArgs(): List<String> =
    subtitles.orEmpty().flatMap { listOf("/sub", it.url) }

private fun Map<String, String>.headerValue(vararg names: String): String? {
    if (isEmpty()) return null
    val lower = names.map { it.lowercase(Locale.ROOT) }.toSet()
    return entries.firstOrNull { it.key.lowercase(Locale.ROOT) in lower && it.value.isNotBlank() }?.value
}

/** mpv's `--http-header-fields` takes a comma-separated list; commas/backslashes are escaped. */
private fun Map<String, String>.toMpvHeaderFields(): String? {
    val fields = entries
        .filter { it.key.isNotBlank() && it.value.isNotBlank() }
        .map { (key, value) ->
            val line = "$key: $value"
            buildString {
                line.forEach { c ->
                    if (c == '\\' || c == ',') append('\\')
                    append(c)
                }
            }
        }
    return fields.takeIf { it.isNotEmpty() }?.joinToString(",")
}

private fun formatHms(positionMs: Long): String {
    val totalSeconds = positionMs / 1000L
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}
