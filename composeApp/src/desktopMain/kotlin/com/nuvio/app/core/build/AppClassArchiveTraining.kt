package com.nuvio.app.core.build

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.relaunchAfterExit
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.nio.file.Files
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * Builds the app class archive in a short launch of its own instead of in the user's session.
 *
 * A launch with no valid archive (first run, or the first after an update) is one the JVM records
 * to write the archive at exit, and in that mode defining each class is several times slower:
 * measured, 10 s to Home and every screen first opened stuttering like a game compiling shaders.
 * So such a launch does no UI at all: it shows a small "preparing" window, loads the startup class
 * list on every spare core, exits (the JVM writes the archive while exiting) and starts Nuvio
 * again, which then opens on the archive.
 *
 * Tried once per build. If the next launch still finds no usable archive (the install folder is
 * not writable, the write failed), it runs as an ordinary launch rather than training again, so
 * this can never loop.
 */
internal object AppClassArchiveTraining {

    private const val MarkerName = "class-archive-training"

    /** The argument createDistributable launches the packaged app with; see [runForBuild]. */
    const val BuildArgument = "--build-class-archive"

    /**
     * The build-time variant (createDistributable runs the fresh image once with [BuildArgument]),
     * so a dev build's first launch is already fast instead of training. No window, no relaunch,
     * and none of main()'s startup — not even the single-instance claim or the settings import:
     * just load the list and exit, which is when the JVM writes the archive. Exit code 0 when an
     * archive is being written, non-zero when there was nothing to do.
     */
    fun runForBuild(): Nothing {
        if (!AppClassArchive.building) {
            println("Info: (ClassArchive) build training: nothing to build (managed=${AppClassArchive.managed})")
            kotlin.system.exitProcess(2)
        }
        val startedAt = System.currentTimeMillis()
        val loaded = StartupClassPreloader.preloadAllBlocking()
        println("Info: (ClassArchive) build training loaded $loaded classes in ${System.currentTimeMillis() - startedAt}ms")
        kotlin.system.exitProcess(if (loaded < 0) 3 else 0)
    }

    /** Never returns when it trains: the process exits and Nuvio is started again. */
    fun runIfNeeded() {
        if (!AppClassArchive.building) return
        val archiveDir = AppClassArchive.archiveFile?.parent ?: return
        if (!Files.isWritable(archiveDir)) {
            println("Info: (ClassArchive) archive folder is not writable; this launch builds nothing usable")
            return
        }
        val build = StartupClassPreloader.classPathFingerprint()
        val marker = DesktopStorage.rootDir.resolve(MarkerName)
        val lastTried = runCatching { Files.readString(marker).trim() }.getOrNull()
        if (lastTried == build) {
            println("Info: (ClassArchive) training already tried for this build; launching normally")
            return
        }
        runCatching { Files.writeString(marker, build) }

        val startedAt = System.currentTimeMillis()
        val window = runCatching { showPreparingWindow() }.getOrNull()
        val loaded = StartupClassPreloader.preloadAllBlocking()
        println(
            "Info: (ClassArchive) training loaded $loaded classes in " +
                "${System.currentTimeMillis() - startedAt}ms; exiting to write the archive",
        )
        if (loaded < 0 || !relaunchAfterExit("class-archive.cmd")) {
            // No list, or a run with no launcher to restart: carry on as an ordinary launch.
            window?.let { SwingUtilities.invokeLater { it.dispose() } }
            return
        }
        // relaunchAfterExit exits this process shortly; nothing else may start in the meantime.
        while (true) Thread.sleep(60_000)
    }

    private fun showPreparingWindow(): JWindow {
        lateinit var window: JWindow
        SwingUtilities.invokeAndWait {
            val title = JLabel("Preparing Nuvio…", SwingConstants.CENTER).apply {
                foreground = Color(0xF2, 0xF2, 0xF2)
                font = font.deriveFont(Font.BOLD, 18f)
            }
            val detail = JLabel("Once after installing or updating. Nuvio opens by itself in a moment.", SwingConstants.CENTER).apply {
                foreground = Color(0x96, 0x9C, 0xA3)
                font = font.deriveFont(13f)
            }
            val panel = JPanel(BorderLayout(0, 8)).apply {
                background = Color(0x0D, 0x0D, 0x0D)
                border = BorderFactory.createEmptyBorder(24, 32, 24, 32)
                add(title, BorderLayout.CENTER)
                add(detail, BorderLayout.SOUTH)
            }
            window = JWindow().apply {
                contentPane = panel
                pack()
                setLocationRelativeTo(null)
                isAlwaysOnTop = true
                isVisible = true
            }
        }
        return window
    }
}
