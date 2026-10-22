package com.nuvio.app.features.player.desktop

import java.io.File

/**
 * Exports Nuvio's own mpv Lua scripts (resources under `/player-scripts/`) next to the SVP files so
 * mpv can load them by path. A script that fails to export is simply not loaded.
 */
internal object DesktopMpvScripts {
    private val exportRoot: File by lazy {
        File(System.getProperty("java.io.tmpdir"), "nuvio-scripts").apply { mkdirs() }
    }

    /**
     * mpv's stats overlay with a Connection section added after Audio (`stats.lua`). It replaces
     * the built-in copy, so the MPV diagnostics toggle shows the read rate in the same layout.
     */
    val statsScriptPath: String? by lazy { export("stats.lua") }

    private fun export(name: String): String? = runCatching {
        val bytes = DesktopMpvScripts::class.java.getResourceAsStream("/player-scripts/$name")
            ?.use { it.readBytes() }
            ?: return@runCatching null
        val target = File(exportRoot, name)
        if (!target.exists() || !target.readBytes().contentEquals(bytes)) {
            target.writeBytes(bytes)
        }
        target.absolutePath
    }.getOrNull()
}
