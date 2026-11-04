package com.nuvio.app.features.music.booklet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

internal actual object FactsFilePicker {
    actual suspend fun openText(): Pair<String, String>? = withContext(Dispatchers.IO) {
        val chosen = chooseFile(save = false, suggestedName = null) ?: return@withContext null
        // A file this big is not one of ours; refusing it here keeps a wrong pick from filling memory.
        if (chosen.length() > 40_000_000L) return@withContext chosen.name to ""
        chosen.name to chosen.readText()
    }

    actual suspend fun saveText(suggestedName: String, contents: String): String? = withContext(Dispatchers.IO) {
        val chosen = chooseFile(save = true, suggestedName = suggestedName) ?: return@withContext null
        val target = if (chosen.extension.isNotBlank()) chosen else File("${chosen.path}.json")
        target.writeText(contents)
        target.name
    }

    private fun chooseFile(save: Boolean, suggestedName: String?): File? {
        val holder = arrayOfNulls<File>(1)
        val run = Runnable {
            runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
            val chooser = JFileChooser().apply {
                fileSelectionMode = JFileChooser.FILES_ONLY
                isMultiSelectionEnabled = false
                dialogTitle = if (save) "Export music facts" else "Import music facts"
                fileFilter = FileNameExtensionFilter("Music facts (*.json)", "json")
                suggestedName?.let { selectedFile = File(it) }
            }
            val result = if (save) chooser.showSaveDialog(null) else chooser.showOpenDialog(null)
            if (result == JFileChooser.APPROVE_OPTION) holder[0] = chooser.selectedFile
        }
        if (SwingUtilities.isEventDispatchThread()) run.run() else SwingUtilities.invokeAndWait(run)
        return holder[0]
    }
}
