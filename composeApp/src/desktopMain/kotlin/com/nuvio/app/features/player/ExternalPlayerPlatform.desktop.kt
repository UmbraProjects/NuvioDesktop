package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.lights.LightsController
import com.nuvio.app.features.lights.LightsPlaybackSource
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

private val externalPlayerLog = Logger.withTag("ExternalPlayer")

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
    /** Rejects an install that exists but cannot play network streams, so discovery moves on. */
    val isUsableInstall: (File) -> Boolean = { true },
    /** How the source goes on the command line; most players take it as a bare last argument. */
    val sourceArgs: (String) -> List<String> = { listOf(it) },
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
            // A VLC install with a stripped plugins/access folder (no http/https access modules)
            // fails every stream with "no access modules matched". Seen with a side-by-side
            // 64-bit install next to a working 32-bit one.
            isUsableInstall = { exe ->
                val access = File(exe.parentFile, "plugins\\access")
                !access.isDirectory ||
                    File(access, "libhttps_plugin.dll").isFile ||
                    File(access, "libhttp_plugin.dll").isFile
            },
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
                    // --sub-file takes one track and selects it; the rest ride along as
                    // `#`-separated --input-slave entries. VLC types a slave by its file
                    // extension (anything unrecognised is loaded as an audio track), and `#`
                    // is the separator, so only extension-typed, `#`-free references qualify.
                    val subtitleUrls = request.subtitles.orEmpty().map { it.url }
                    subtitleUrls.firstOrNull()?.let { add("--sub-file=$it") }
                    subtitleUrls.drop(1)
                        .filter { url -> '#' !in url && url.hasVlcSubtitleExtension() }
                        .takeIf { it.isNotEmpty() }
                        ?.let { add("--input-slave=${it.joinToString("#")}") }
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
        // Microsoft Store (MSIX) app: there is no install folder to find, only the execution
        // alias it registers under WindowsApps (also on PATH). Switches are from the author's
        // Kodi/Stremio integration guides: the source must go through --path=, subtitles through
        // --subs-path=, and there is no start-time, title or header switch.
        DesktopPlayerDefinition(
            id = "energy",
            displayName = "Energy Media Player",
            candidatePaths = listOf(
                "%LOCALAPPDATA%\\Microsoft\\WindowsApps\\EnergyPlayer.exe",
                "%LOCALAPPDATA%\\Microsoft\\WindowsApps\\EnergyPlayerWin.exe",
                "%LOCALAPPDATA%\\Microsoft\\WindowsApps\\EnergyPlayerForWindows.exe",
            ),
            executableNames = listOf("EnergyPlayer.exe", "EnergyPlayerWin.exe", "EnergyPlayerForWindows.exe"),
            buildArgs = { request ->
                // Only one subtitle switch is documented, so forward the best-ranked track. The
                // app is sandboxed and cannot read Nuvio's subtitle cache (a cached path left it
                // stuck loading), so it gets the original addon URL, as Stremio's integration does.
                request.subtitles.orEmpty().firstOrNull()
                    ?.let { listOf("--subs-path=${it.sourceUrl ?: it.url}") }
                    .orEmpty()
            },
            sourceArgs = { source -> listOf("--path=$source") },
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
            ?.takeIf(File::isLaunchableFile)
            ?.absolutePath
        val associatedPath = defaultMediaAssociation
            ?.takeIf { association -> def.matchesExecutable(association.executablePath) }
            ?.takeIf { association -> def.isUsableInstall(File(association.executablePath)) }
            ?.executablePath
        val discoveredPath = if (customPath == null && associatedPath == null) resolveExecutable(def) else null
        val resolved = customPath ?: associatedPath ?: discoveredPath
        val source = when {
            customPath != null -> "configured"
            associatedPath != null -> "file-association"
            discoveredPath != null -> "known-path-or-PATH"
            else -> "not-found"
        }
        externalPlayerLog.i {
            "Resolved external player id=${def.id} source=$source executable=${resolved ?: "none"}"
        }
        resolved.also { resolvedPaths[def.id] = it }
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
                val executablePath = resolvedPath(def)
                add(
                    ExternalPlayerApp(
                        id = def.id,
                        name = def.displayName,
                        isAvailable = executablePath != null,
                        executablePath = executablePath,
                        canConfigure = true,
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
            add(
                ExternalPlayerApp(
                    id = systemPlayerId,
                    name = defaultLabel,
                    executablePath = defaultMediaAssociation?.executablePath,
                ),
            )
        }

    actual fun configurePlayer(playerId: String): Boolean {
        val def = definitions.firstOrNull { it.id == playerId } ?: return playerId == systemPlayerId
        val selectedPath = pickPlayerExecutable(def)
        if (selectedPath == null) {
            externalPlayerLog.i { "External player configuration cancelled id=$playerId" }
            return false
        }
        customPlayerStore.putString(customPlayerPathKey(def.id), selectedPath)
        synchronized(resolvedPaths) {
            resolvedPaths[def.id] = selectedPath
        }
        externalPlayerLog.i { "Configured external player id=$playerId executable=$selectedPath" }
        return true
    }

    actual fun open(
        request: ExternalPlayerPlaybackRequest,
        playerId: String?,
    ): ExternalPlayerOpenResult {
        val sourceSummary = externalPlayerSourceSummary(request.sourceUrl)
        val effectiveId = playerId?.takeIf { id ->
            id == systemPlayerId || definitions.any { it.id == id }
        } ?: defaultPlayerId()
        externalPlayerLog.i {
            "External playback requested selected=${playerId ?: "auto"} effective=${effectiveId ?: "none"} " +
                "source=$sourceSummary headers=${request.sourceHeaders.size} " +
                "subtitles=${request.subtitles.orEmpty().size} resumeMs=${request.resumePositionMs}"
        }
        val verdict = PlaybackSourcePolicy.check(request.sourceUrl)
        if (verdict is PlaybackSourcePolicy.Verdict.Rejected) {
            externalPlayerLog.w {
                "External playback refused: source rejected (${verdict.reason}) source=$sourceSummary"
            }
            return ExternalPlayerOpenResult.Failed
        }

        if (effectiveId == null || effectiveId == systemPlayerId) {
            defaultMediaAssociation?.let { association ->
                val knownDefinition = definitions.firstOrNull { it.matchesExecutable(association.executablePath) }
                val command = buildList {
                    add(association.executablePath)
                    if (knownDefinition != null) addAll(knownDefinition.buildArgs(request))
                    addEndOfOptions(association.executablePath)
                    if (knownDefinition != null) addAll(knownDefinition.sourceArgs(request.sourceUrl))
                    else add(request.sourceUrl)
                }
                if (
                    launchDetached(
                        command = command,
                        diagnosticContext = "player=system association=${knownDefinition?.id ?: "unknown"} source=$sourceSummary",
                    )
                ) return ExternalPlayerOpenResult.Opened
            }
            return if (openUri(request.sourceUrl, sourceSummary)) ExternalPlayerOpenResult.Opened
            else ExternalPlayerOpenResult.Failed
        }

        val def = definitions.firstOrNull { it.id == effectiveId } ?: run {
            externalPlayerLog.w { "External playback rejected: unknown player id=$effectiveId" }
            return ExternalPlayerOpenResult.Failed
        }
        val exePath = resolvedPath(def) ?: run {
            externalPlayerLog.w { "External playback unavailable: no executable for player=$effectiveId" }
            return ExternalPlayerOpenResult.NoPlayerAvailable
        }

        val command = buildList {
            add(exePath)
            addAll(def.buildArgs(request))
            addEndOfOptions(exePath)
            addAll(def.sourceArgs(request.sourceUrl))
        }
        return if (
            launchDetached(
                command = command,
                diagnosticContext = "player=$effectiveId source=$sourceSummary",
            )
        ) ExternalPlayerOpenResult.Opened else ExternalPlayerOpenResult.Failed
    }

    /**
     * Starts a child process without keeping its stdio pipes attached. This is essential: with the
     * default [ProcessBuilder] behaviour the child's stdout/stderr are piped to us, and because we
     * never read them a chatty player (mpv prints a status line every frame; PotPlayer logs too)
     * fills the ~64 KB OS pipe buffer within seconds and then blocks on write — before it renders a
     * frame. That surfaced as "mpv is a black screen" / "PotPlayer is unresponsive" even though the
     * same URL plays instantly when opened by hand. Discarding the streams removes the pipe.
     */
    private fun launchDetached(
        command: List<String>,
        diagnosticContext: String,
    ): Boolean {
        val executable = command.firstOrNull() ?: "none"
        externalPlayerLog.i {
            "Starting external process $diagnosticContext executable=$executable argumentCount=${(command.size - 1).coerceAtLeast(0)}"
        }
        return runCatching {
            ProcessBuilder(command)
                // Run from the player's own folder. Inheriting ours pins Nuvio's install
                // directory for as long as the player stays open, which blocks updates.
                .apply {
                    File(executable).takeIf(File::isAbsolute)?.parentFile
                        ?.takeIf(File::isDirectory)
                        ?.let(::directory)
                }
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }.onSuccess { process ->
            externalPlayerLog.i { "External process started $diagnosticContext pid=${process.pid()}" }
            // The process is the only playback signal an external player gives us: alive means
            // playing, so the lights go down now and come back when it exits. (The system-handler
            // fallback in [openUri] has no process to watch and so never touches the room.)
            LightsController.playing(LightsPlaybackSource.External)
            process.onExit().thenAccept { completed ->
                externalPlayerLog.i {
                    "External process exited $diagnosticContext pid=${completed.pid()} exitCode=${completed.exitValue()}"
                }
                LightsController.ended(LightsPlaybackSource.External)
            }
        }.onFailure { error ->
            externalPlayerLog.e(error) {
                "External process failed to start $diagnosticContext executable=$executable"
            }
        }.isSuccess
    }

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
        // The installer's App Paths registration comes first: it names the install the user
        // actually launches, which the hard-coded candidates can get wrong when two installs sit
        // side by side (64-bit and 32-bit VLC). Then known install locations, then PATH.
        val candidates = sequence {
            if (isWindows) {
                def.executableNames.forEach { exe ->
                    listOf("HKCU", "HKLM").forEach { hive ->
                        queryRegistryValue(
                            key = "$hive\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\$exe",
                            valueName = null,
                        )?.trim('"')?.let(::expandEnvPlaceholders)?.let { yield(it) }
                    }
                }
            }
            def.candidatePaths.forEach { candidate -> expandEnvPlaceholders(candidate)?.let { yield(it) } }
            def.executableNames.forEach { exe -> findOnPath(exe)?.let { yield(it) } }
        }
        return candidates
            .map(::File)
            .firstOrNull { it.isLaunchableFile() && def.isUsableInstall(it) }
            ?.absolutePath
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
            .firstOrNull { it.isLaunchableFile() }
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
                val selected = chooser.selectedFile?.takeIf(File::isLaunchableFile)
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

    /**
     * mpv documents `--` as the end of options, so even a source that slipped past
     * [PlaybackSourcePolicy] cannot become a switch. Only for mpv.exe: mpv.net, VLC, MPC and
     * PotPlayer parse their own command lines and the policy's leading `-`/`/` check covers them.
     */
    private fun MutableList<String>.addEndOfOptions(executablePath: String) {
        if (File(executablePath).name.equals("mpv.exe", ignoreCase = true)) add("--")
    }

    // --- system-handler fallback (previous behaviour) -------------------------------------

    private fun openUri(rawUri: String, sourceSummary: String): Boolean {
        // ShellExecute *runs* .exe/.lnk/.bat targets and hands other schemes (ms-*:, search-ms:)
        // to whatever claims them; rundll32's FileProtocolHandler is a classic launcher. Only a
        // web stream is safe to give to "whatever is registered".
        if (!PlaybackSourcePolicy.allowsSystemHandler(rawUri)) {
            externalPlayerLog.w { "System handler refused: not a web stream or local media file source=$sourceSummary" }
            return false
        }
        if (PlaybackSourcePolicy.isLocalDrivePath(rawUri)) {
            return runCatching { Desktop.getDesktop().open(File(rawUri)) }
                .onFailure { error -> externalPlayerLog.w(error) { "Desktop API failed to open local file source=$sourceSummary" } }
                .isSuccess
        }
        val uri = runCatching { URI(rawUri) }.getOrNull() ?: return false
        val desktop = runCatching { Desktop.getDesktop() }.getOrNull()

        if (desktop != null && Desktop.isDesktopSupported()) {
            val opened = runCatching {
                if (uri.scheme.equals("file", ignoreCase = true)) {
                    desktop.open(File(uri))
                } else {
                    desktop.browse(uri)
                }
            }.onSuccess {
                externalPlayerLog.i { "Opened external source with Desktop API source=$sourceSummary" }
            }.onFailure { error ->
                externalPlayerLog.w(error) { "Desktop API failed to open external source=$sourceSummary" }
            }.isSuccess
            if (opened) return true
        }

        return openWithPlatformCommand(rawUri, sourceSummary)
    }

    private fun openWithPlatformCommand(rawUri: String, sourceSummary: String): Boolean {
        val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        val command = when {
            osName.contains("mac") -> listOf("open", rawUri)
            osName.contains("win") -> listOf("rundll32", "url.dll,FileProtocolHandler", rawUri)
            else -> listOf("xdg-open", rawUri)
        }
        return launchDetached(command, "player=system-handler source=$sourceSummary")
    }
}

private data class WindowsMediaAssociation(
    val executablePath: String,
    val displayName: String,
)

private val REGISTRY_STRING_VALUE = Regex("""(?i)\sREG_(?:EXPAND_)?SZ\s+(.+)$""")

/**
 * A regular file, or a Store app's execution alias (the 0-byte reparse points in WindowsApps).
 * java.io.File reports an alias as missing, so it is accepted when NIO, without following the
 * reparse point, sees a non-directory there. CreateProcess launches an alias like any other exe.
 */
private fun File.isLaunchableFile(): Boolean =
    isFile || runCatching {
        Files.readAttributes(toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isOther
    }.getOrDefault(false)

/** Returns only the source origin so diagnostic logs never contain path/query credentials. */
internal fun externalPlayerSourceSummary(rawUri: String): String {
    val uri = runCatching { URI(rawUri) }.getOrNull() ?: return "invalid-uri"
    val scheme = uri.scheme?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: "unknown"
    val host = uri.host?.takeIf { it.isNotBlank() }
    val port = uri.port.takeIf { it >= 0 }?.let { ":$it" }.orEmpty()
    return if (host != null) "$scheme://$host$port" else "$scheme:(no-host)"
}

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

private val VLC_SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "sub", "ttml", "smi", "txt")

internal fun String.hasVlcSubtitleExtension(): Boolean =
    substringBefore('?')
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .substringAfterLast('.', missingDelimiterValue = "")
        .lowercase(Locale.ROOT) in VLC_SUBTITLE_EXTENSIONS
