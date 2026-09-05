package com.nuvio.app.features.discover

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

internal actual object DiscoverCatalogFilePicker {

    actual suspend fun openForImport(): String? = withContext(Dispatchers.IO) {
        val chosen = chooseFile(save = false, suggestedName = null) ?: return@withContext null
        chosen.readText()
    }

    actual suspend fun saveExport(suggestedName: String, contents: String): String? =
        withContext(Dispatchers.IO) {
            val chosen = chooseFile(save = true, suggestedName = suggestedName)
                ?: return@withContext null
            // Appended rather than enforced in the dialog: a user who types their own extension
            // meant it, and Swing's chooser does not add one for them.
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
                dialogTitle = if (save) "Export Discover row" else "Import Discover row"
                fileFilter = FileNameExtensionFilter("Discover catalog (*.json)", "json")
                suggestedName?.let { selectedFile = File(it) }
            }
            val result = if (save) chooser.showSaveDialog(null) else chooser.showOpenDialog(null)
            if (result == JFileChooser.APPROVE_OPTION) holder[0] = chooser.selectedFile
        }
        if (SwingUtilities.isEventDispatchThread()) run.run() else SwingUtilities.invokeAndWait(run)
        return holder[0]
    }
}
