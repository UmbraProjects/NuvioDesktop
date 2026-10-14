package com.nuvio.app

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
    // Children too (TorrServer, WebView2): a portable switch copies the data folder at the next
    // startup, and TorrServer holds a lock on its database until it has really gone. Captured
    // before the helper below is started, so the helper never waits on itself.
    val childPids = ProcessHandle.current().descendants().map { it.pid() }.toList()
    val installDir = launcher.parent
    // Neither the script nor anyone's working directory may sit in the data folder: Windows won't
    // rename a folder that is some process's current directory or holds an open file, and a
    // portable switch renames it (`data` -> `data.old`) at the very next startup.
    val script = Path.of(System.getProperty("java.io.tmpdir")).resolve("nuvio-$pid-$scriptName")
    val lines = buildList {
        add("@echo off")
        add("setlocal enableextensions")
        add("set \"PID=$pid\"")
        add("set \"EXE=$launcher\"")
        add(":waitloop")
        add("tasklist /fi \"PID eq %PID%\" /nh 2>nul | findstr \"%PID%\" >nul")
        add("if not errorlevel 1 (")
        add("  timeout /t 1 /nobreak >nul")
        add("  goto waitloop")
        add(")")
        // Children get a bounded wait each: an orphan that refuses to die must not stop the
        // restart altogether.
        childPids.forEachIndexed { index, child ->
            add("set /a TRIES=0")
            add(":child$index")
            add("tasklist /fi \"PID eq $child\" /nh 2>nul | findstr \"$child\" >nul")
            add("if not errorlevel 1 if %TRIES% lss 15 (")
            add("  set /a TRIES+=1")
            add("  timeout /t 1 /nobreak >nul")
            add("  goto child$index")
            add(")")
        }
        add("start \"\" /D \"$installDir\" \"%EXE%\"")
        add("(goto) 2>nul & del \"%~f0\"")
        add("")
    }
    Files.writeString(script, lines.joinToString("\r\n"), Charsets.US_ASCII)
    // Same inner "cmd /c" as the updater's helper, so the self-deleting script cannot leave a shell open.
    ProcessBuilder("cmd.exe", "/c", "start", "Nuvio Restart", "/min", "cmd.exe", "/c", script.toString())
        .directory(installDir.toFile())
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
