package com.nuvio.app.features.games

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

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
                dialogTitle = "Choose game executable"
                fileSelectionMode = JFileChooser.FILES_ONLY
                isMultiSelectionEnabled = false
                fileFilter = FileNameExtensionFilter("Applications (*.exe)", "exe")
                if (initialFile?.isFile == true) selectedFile = initialFile
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                result[0] = chooser.selectedFile?.absolutePath
            }
        }
        if (SwingUtilities.isEventDispatchThread()) showChooser.run() else SwingUtilities.invokeAndWait(showChooser)
        result[0]
    }

    fun launch(game: GameEntry, onExit: (Int) -> Unit = {}): Result<Long> = runCatching {
        val configuredPath = game.executablePath?.takeIf(String::isNotBlank)
            ?: error("${game.title} is tracked but not installed yet.")
        val executable = File(configuredPath)
        require(executable.isFile) { "Executable not found: $configuredPath" }
        val command = buildList {
            add(executable.absolutePath)
            addAll(game.arguments)
        }
        val workingDirectory = game.workingDirectory
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
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
}

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
