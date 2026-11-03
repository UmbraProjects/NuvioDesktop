package com.nuvio.app.features.youtube

import com.nuvio.app.core.storage.DesktopAccountSlots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

/** In the account's folder, beside its settings: the YouTube sign-in it copies is per account too. */
internal actual object YouTubeHistoryFile {
    private val path get() = DesktopAccountSlots.activeSlotDir().resolve("youtube-history.json")

    actual val location: String get() = path.toString()

    actual fun read(): String? = path.toFile().takeIf { it.isFile }?.readText()

    actual fun write(text: String) {
        val target = path
        Files.createDirectories(target.parent)
        // Written beside it and moved over, so a crash mid-write never leaves half a file.
        val temp = target.resolveSibling("youtube-history.json.tmp")
        Files.writeString(temp, text)
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    actual suspend fun saveExport(suggestedName: String, contents: String): String? = withContext(Dispatchers.IO) {
        val chosen = chooseFile(suggestedName) ?: return@withContext null
        val target = if (chosen.extension.isNotBlank()) chosen else File("${chosen.path}.csv")
        // With a byte order mark, so Excel reads the titles' accents and emoji as UTF-8.
        target.writeText("﻿" + contents)
        target.name
    }

    private fun chooseFile(suggestedName: String): File? {
        val holder = arrayOfNulls<File>(1)
        val run = Runnable {
            runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
            val chooser = JFileChooser().apply {
                fileSelectionMode = JFileChooser.FILES_ONLY
                dialogTitle = "Export YouTube watch history"
                fileFilter = FileNameExtensionFilter("Spreadsheet (*.csv)", "csv")
                selectedFile = File(suggestedName)
            }
            if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) holder[0] = chooser.selectedFile
        }
        if (SwingUtilities.isEventDispatchThread()) run.run() else SwingUtilities.invokeAndWait(run)
        return holder[0]
    }
}
