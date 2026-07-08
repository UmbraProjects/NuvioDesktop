package com.nuvio.app.features.player

import java.awt.Desktop
import java.io.File
import java.net.URI
import java.util.Locale

private data class DesktopExternalPlayerIntent(
    val request: ExternalPlayerPlaybackRequest,
    val playerId: String?,
)

/**
 * A known desktop media player and how to launch it.
 *
 * [candidatePaths] are absolute exe locations (with `%ENV%` placeholders expanded at lookup
 * time); [appPathsExe] is the executable name to look up under the Windows "App Paths" registry
 * key, which is how installers register their launch path regardless of install directory.
 * [buildArgs] produces the command-line arguments (after the exe and the URL) for a request,
 * using only options the player reliably supports.
 */
private class DesktopPlayerDefinition(
    val id: String,
    val displayName: String,
    val candidatePaths: List<String>,
    val appPathsExe: String? = null,
    val onPathExe: String? = null,
    val buildArgs: (ExternalPlayerPlaybackRequest) -> List<String>,
)

internal actual object ExternalPlayerPlatform {
    private const val systemPlayerId = "system"

    private val definitions: List<DesktopPlayerDefinition> = listOf(
        DesktopPlayerDefinition(
            id = "mpv",
            displayName = "mpv",
            candidatePaths = listOf(
                "%ProgramFiles%\\mpv\\mpv.exe",
                "%ProgramFiles%\\mpv.net\\mpvnet.exe",
                "%LOCALAPPDATA%\\Programs\\mpv.net\\mpvnet.exe",
            ),
            appPathsExe = "mpv.exe",
            onPathExe = "mpv.exe",
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
            appPathsExe = "vlc.exe",
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
            appPathsExe = "mpc-hc64.exe",
            buildArgs = { request ->
                // MPC-HC takes the resume position in milliseconds via /start.
                if (request.resumePositionMs > 0) listOf("/start", request.resumePositionMs.toString()) else emptyList()
            },
        ),
        DesktopPlayerDefinition(
            id = "mpc-be",
            displayName = "MPC-BE",
            candidatePaths = listOf(
                "%ProgramFiles%\\MPC-BE\\mpc-be64.exe",
                "%ProgramFiles(x86)%\\MPC-BE\\mpc-be.exe",
            ),
            appPathsExe = "mpc-be64.exe",
            buildArgs = { request ->
                if (request.resumePositionMs > 0) listOf("/start", request.resumePositionMs.toString()) else emptyList()
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
            appPathsExe = "PotPlayerMini64.exe",
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
                }
            },
        ),
    )

    /** Resolved absolute exe path per player id, computed once. `null` = not installed. */
    private val resolvedPaths: Map<String, String?> by lazy {
        definitions.associate { it.id to resolveExecutable(it) }
    }

    actual fun defaultPlayerId(): String? =
        definitions.firstOrNull { resolvedPaths[it.id] != null }?.id ?: systemPlayerId

    actual fun availablePlayers(): List<ExternalPlayerApp> =
        buildList {
            definitions.forEach { def ->
                if (resolvedPaths[def.id] != null) add(ExternalPlayerApp(def.id, def.displayName))
            }
            // Always offer the OS handler as a fallback (e.g. a player we don't detect, or the
            // user's own file/URL association). It hands the URL to whatever is registered.
            add(ExternalPlayerApp(systemPlayerId, "System default"))
        }

    actual fun open(
        request: ExternalPlayerPlaybackRequest,
        playerId: String?,
    ): ExternalPlayerOpenResult {
        val effectiveId = playerId?.takeIf { id ->
            id == systemPlayerId || definitions.any { it.id == id }
        } ?: defaultPlayerId()

        if (effectiveId == null || effectiveId == systemPlayerId) {
            return if (openUri(request.sourceUrl)) ExternalPlayerOpenResult.Opened
            else ExternalPlayerOpenResult.Failed
        }

        val def = definitions.firstOrNull { it.id == effectiveId }
            ?: return ExternalPlayerOpenResult.Failed
        val exePath = resolvedPaths[def.id]
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
        // Prefer the App Paths registry entry: it is what Windows itself resolves the bare exe name
        // to (Start menu / Run box), so it points at the install the user actually launches. Our
        // hard-coded candidate paths are only a fallback — a machine can have a second, stale or
        // broken install in the default Program Files location (e.g. a plugin-stripped 64-bit VLC
        // sitting next to a working 32-bit one) that a candidate path would otherwise match first.
        def.appPathsExe?.let { exe ->
            queryAppPath(exe)?.let { if (File(it).isFile) return it }
        }
        def.candidatePaths.forEach { candidate ->
            val expanded = expandEnvPlaceholders(candidate)
            if (expanded != null && File(expanded).isFile) return expanded
        }
        def.onPathExe?.let { exe ->
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

    /**
     * Reads the default value of
     * `HKLM/HKCU\SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\<exe>`, which installers set
     * to the executable's full path. Uses reg.exe to avoid a registry-access dependency.
     */
    private fun queryAppPath(exe: String): String? {
        val roots = listOf("HKCU", "HKLM")
        for (root in roots) {
            val key = "$root\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\$exe"
            val output = runCatching {
                val process = ProcessBuilder("reg", "query", key, "/ve")
                    .redirectErrorStream(true)
                    .start()
                val text = process.inputStream.bufferedReader().readText()
                process.waitFor()
                text
            }.getOrNull() ?: continue

            // A matching line looks like: "    (Default)    REG_SZ    C:\Path\player.exe"
            val path = output.lineSequence()
                .firstOrNull { it.contains("REG_SZ") }
                ?.substringAfter("REG_SZ")
                ?.trim()
                ?.trim('"')
            if (!path.isNullOrBlank() && File(path).isFile) return path
        }
        return null
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

// --- header/format helpers ----------------------------------------------------------------

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
