package com.nuvio.app.features.player

import java.awt.GraphicsEnvironment

// Enumerating installed fonts via AWT can take a few hundred ms the first time, so cache it
// for the session. Prepend "" so the player default is always the first option.
private val systemSubtitleFontFamilies: List<String> by lazy {
    val families = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment()
            .availableFontFamilyNames
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedBy { it.lowercase() }
            .toList()
    }.getOrDefault(emptyList())
    listOf("") + families
}

actual fun availableSubtitleFontFamilies(): List<String> = systemSubtitleFontFamilies

/** Warms the system-font cache off the UI thread so the first player open isn't janky. */
fun warmSubtitleFontCache() {
    Thread { systemSubtitleFontFamilies }
        .apply {
            name = "nuvio-subtitle-font-warmup"
            isDaemon = true
        }
        .start()
}
