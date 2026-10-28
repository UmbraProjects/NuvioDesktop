package com.nuvio.app.features.games

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter
import nuvio.composeapp.generated.resources.*
import com.nuvio.app.core.i18n.localizedString

object GameLauncher {
    suspend fun chooseExecutable(initialPath: String? = null): String? = withContext(Dispatchers.IO) {
        val result = arrayOfNulls<String>(1)
        val showChooser = Runnable {
            runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
            val initialFile = initialPath?.takeIf { it.isNotBlank() }?.let(::File)
            val initialDirectory = when {
                initialFile?.isDirectory == true -> initialFile
                initialFile?.parentFile?.isDirectory == true -> initialFile.parentFile
                else -> null
            }
            val chooser = JFileChooser(initialDirectory).apply {
                dialogTitle = localizedString(Res.string.games_launcher_choose_executable)
                fileSelectionMode = JFileChooser.FILES_ONLY
                isMultiSelectionEnabled = false
                fileFilter = FileNameExtensionFilter(localizedString(Res.string.games_launcher_filter_apps), "exe", "lnk", "url")
                if (initialFile?.isFile == true) selectedFile = initialFile
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                result[0] = chooser.selectedFile?.absolutePath
            }
        }
        if (SwingUtilities.isEventDispatchThread()) showChooser.run() else SwingUtilities.invokeAndWait(showChooser)
        result[0]
    }

    /** A folder of games to scan, for the import; null when the user backs out. */
    suspend fun chooseGamesFolder(initialPath: String? = null): String? = withContext(Dispatchers.IO) {
        val result = arrayOfNulls<String>(1)
        val showChooser = Runnable {
            runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
            val initialFile = initialPath?.takeIf { it.isNotBlank() }?.let(::File)
            val initialDirectory = when {
                initialFile?.isDirectory == true -> initialFile
                initialFile?.parentFile?.isDirectory == true -> initialFile.parentFile
                else -> null
            }
            val chooser = JFileChooser(initialDirectory).apply {
                dialogTitle = localizedString(Res.string.games_launcher_choose_folder)
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                isMultiSelectionEnabled = false
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                result[0] = chooser.selectedFile?.absolutePath
            }
        }
        if (SwingUtilities.isEventDispatchThread()) showChooser.run() else SwingUtilities.invokeAndWait(showChooser)
        result[0]
    }

    /**
     * Starts [game] and returns the process id, or null when the launch went through the shell —
     * a protocol handler (`steam://rungameid/<id>` and the like) or a shortcut that names no
     * executable — and so has no process of its own to watch. [onExit] is only ever called for a
     * directly launched executable.
     *
     * A `.lnk` or `.url` entry is followed to what it launches (see [resolveGameLaunchTarget]);
     * the shortcut's own arguments come first, the entry's are appended.
     */
    fun launch(game: GameEntry, onExit: (Int) -> Unit = {}): Result<Long?> = runCatching {
        val configuredPath = game.executablePath?.takeIf(String::isNotBlank)
            ?: error("${game.title} is tracked but not installed yet.")
        val target = when (val resolved = resolveGameLaunchTarget(configuredPath)) {
            is GameLaunchTarget.Protocol -> {
                launchProtocol(resolved.url, game.arguments)
                return@runCatching null
            }
            is GameLaunchTarget.ShellShortcut -> {
                launchShellItem(resolved.path)
                return@runCatching null
            }
            is GameLaunchTarget.Executable -> resolved
        }
        val executable = File(target.path)
        require(executable.isFile) {
            if (isShortcutFile(configuredPath)) localizedString(Res.string.games_launcher_shortcut_target_missing, target.path)
            else localizedString(Res.string.games_launcher_executable_missing, target.path)
        }
        val command = buildList {
            add(executable.absolutePath)
            addAll(target.arguments)
            addAll(game.arguments)
        }
        val workingDirectory = game.workingDirectory
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: target.workingDirectory?.let(::File)?.takeIf(File::isDirectory)
            ?: executable.parentFile
        val process = ProcessBuilder(command)
            .directory(workingDirectory)
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        process.onExit().thenAccept { onExit(it.exitValue()) }
        process.pid()
    }

    /**
     * Hands a protocol URL straight to whatever the OS has registered for it.
     *
     * Not `Desktop.browse`: on Windows that goes through the default browser, which does not own
     * `steam://` and so shows an "Open Steam?" prompt before bouncing the URL back to the shell.
     * `explorer.exe <url>` calls ShellExecute on the URL itself and Steam opens directly — the same
     * hand-off the external player uses for its protocol URIs. Arguments have nowhere to go on a
     * URL, so a launcher that takes them expects them inside it (`steam://run/<id>//-windowed/`);
     * anything typed into the arguments field is a mistake worth refusing rather than dropping.
     */
    private fun launchProtocol(url: String, arguments: List<String>) {
        require(arguments.isEmpty()) { localizedString(Res.string.games_launcher_args_with_url) }
        val target = url.trim()
        runCatching { URI(target) }.getOrElse { error(localizedString(Res.string.games_launcher_invalid_url, url)) }
        openWithShell(target)
    }

    /**
     * Runs a shortcut the shell alone can interpret — one whose target is a Store app or an
     * MSI-advertised install rather than a file. Same `explorer.exe` hand-off as a protocol URL:
     * the shell performs the shortcut's default verb, which is to launch it.
     */
    private fun launchShellItem(path: String) {
        require(File(path).isFile) { localizedString(Res.string.games_launcher_shortcut_missing, path) }
        openWithShell(File(path).absolutePath)
    }

    private fun openWithShell(target: String) {
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        val command = when {
            osName.contains("win") -> listOf("explorer.exe", target)
            osName.contains("mac") -> listOf("open", target)
            else -> listOf("xdg-open", target)
        }
        ProcessBuilder(command)
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }
}

/**
 * Whether a launch target is a protocol URL rather than a path on disk — `steam://rungameid/620`,
 * `com.epicgames.launcher://apps/...`, `goggalaxy://openGameView/...`.
 *
 * A Windows drive letter (`C:\...`) is one letter followed by a colon, never by `://`, so it does not
 * match; the scheme grammar is RFC 3986's, which is what the OS handler registry keys on.
 */
fun isProtocolLaunchTarget(path: String): Boolean = PROTOCOL_LAUNCH_TARGET.containsMatchIn(path.trim())

private val PROTOCOL_LAUNCH_TARGET = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

fun executableDisplayName(path: String): String =
    File(path).nameWithoutExtension
        .replace(Regex("[_-]+"), " ")
        .trim()
        .replaceFirstChar { it.uppercase() }

/** Splits a conventional quoted command-line argument string without invoking a shell. */
fun parseArguments(value: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var escaping = false
    value.forEach { character ->
        when {
            escaping -> {
                current.append(character)
                escaping = false
            }
            character == '\\' && quoted -> escaping = true
            character == '"' -> quoted = !quoted
            character.isWhitespace() && !quoted -> {
                if (current.isNotEmpty()) {
                    result += current.toString()
                    current.clear()
                }
            }
            else -> current.append(character)
        }
    }
    if (escaping) current.append('\\')
    if (current.isNotEmpty()) result += current.toString()
    return result
}
