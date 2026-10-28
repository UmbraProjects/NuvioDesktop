package com.nuvio.app.features.locallibrary

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.UIManager
import nuvio.composeapp.generated.resources.*
import com.nuvio.app.core.i18n.localizedString

internal actual object LocalDirectoryPicker {

    actual suspend fun pick(): String? = withContext(Dispatchers.IO) {
        val holder = arrayOfNulls<String>(1)
        val run = Runnable {
            runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
            val chooser = JFileChooser().apply {
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                dialogTitle = localizedString(Res.string.local_library_choose_a_media_folder)
                isMultiSelectionEnabled = false
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                holder[0] = chooser.selectedFile?.absolutePath
            }
        }
        if (SwingUtilities.isEventDispatchThread()) run.run() else SwingUtilities.invokeAndWait(run)
        holder[0]
    }
}
