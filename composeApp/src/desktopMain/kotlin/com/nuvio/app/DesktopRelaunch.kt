package com.nuvio.app

import com.nuvio.app.core.storage.DesktopStorage
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * Starts a fresh Nuvio once this process has exited, so the new copy wins the single-instance
 * claim and starts from what this one staged (a settings import, another account's folder), then
 * exits this one. False on a dev run, which has no launcher to start; nothing is exited then.
 */
internal fun relaunchAfterExit(scriptName: String): Boolean {
    val launcher = System.getProperty("jpackage.app-path")
        ?.takeIf { it.isNotBlank() }
        ?.let(Path::of)
        ?.takeIf(Files::isRegularFile)
        ?: return false
    val pid = ProcessHandle.current().pid()
    val script = DesktopStorage.rootDir.resolve(scriptName)
    val lines = listOf(
        "@echo off",
        "setlocal enableextensions",
        "set \"PID=$pid\"",
        "set \"EXE=$launcher\"",
        ":waitloop",
        "tasklist /fi \"PID eq %PID%\" /nh 2>nul | findstr \"%PID%\" >nul",
        "if not errorlevel 1 (",
        "  timeout /t 1 /nobreak >nul",
        "  goto waitloop",
        ")",
        "start \"\" \"%EXE%\"",
        "(goto) 2>nul & del \"%~f0\"",
        "",
    )
    Files.writeString(script, lines.joinToString("\r\n"), Charsets.US_ASCII)
    // Same inner "cmd /c" as the updater's helper, so the self-deleting script cannot leave a shell open.
    ProcessBuilder("cmd.exe", "/c", "start", "Nuvio Restart", "/min", "cmd.exe", "/c", script.toString())
        .directory(DesktopStorage.rootDir.toFile())
        .start()
    exitSoon()
    return true
}

/**
 * Closes Nuvio through the window's normal exit (so stores flush and the player shuts down),
 * forcing it if that has not finished within 15 seconds.
 */
internal fun exitSoon() {
    Thread {
        runCatching { Thread.sleep(800) }
        val requested = runCatching {
            var accepted = false
            SwingUtilities.invokeAndWait { accepted = DesktopApplicationExit.request() }
            accepted
        }.getOrDefault(false)
        if (requested) runCatching { Thread.sleep(15_000) }
        exitProcess(0)
    }.apply {
        isDaemon = true
        name = "nuvio-restart-exit"
    }.start()
}
