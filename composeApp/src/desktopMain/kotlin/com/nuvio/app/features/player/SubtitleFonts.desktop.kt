package com.nuvio.app.features.player

import java.awt.GraphicsEnvironment
import java.util.concurrent.atomic.AtomicBoolean

// Enumerating installed fonts via AWT can take a few hundred ms the first time, so cache it
// for the session. Prepend "" so the player default is always the first option.
//
// Deliberately not the app font's Skia-backed list (core/ui/AppFontFamilies.desktop.kt): these
// names go to mpv, which does its own matching and takes AWT's style variants ("Arial Black",
// "Calibri Light") as families in their own right. Narrowing this to Skia's typographic families
// would drop subtitle fonts that work today.
private fun enumerateSubtitleFontFamilies(): List<String> {
    val families = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment()
            .availableFontFamilyNames
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedBy { it.lowercase() }
            .toList()
    }.onFailure { error ->
        // Never silent: this is usually AWT's font manager failing its static init, which then
        // breaks every later AWT font use (the tray menu) with "Could not initialize class".
        co.touchlab.kermit.Logger.withTag("SubtitleFonts").e(error) { "AWT font enumeration failed" }
    }.getOrDefault(emptyList())
    return listOf("") + families
}

@Volatile private var subtitleFontFamilies: List<String>? = null
private val subtitleFontWarmStarted = AtomicBoolean(false)

/**
 * The installed font families, or only the player default while they are still being listed.
 * Never waits: the list used to be a lazy the player's UI thread read while the warm-up thread
 * held it, and on one launch that wait was an 18.7 s UI stall (2026-11-24 startup report). The
 * player rebuilds its controls model continually, so the full list appears on the next rebuild.
 */
actual fun availableSubtitleFontFamilies(): List<String> =
    subtitleFontFamilies ?: run {
        warmSubtitleFontCache()
        DefaultOnlyFontFamilies
    }

private val DefaultOnlyFontFamilies = listOf("")

/** Lists the system fonts off the UI thread, once, so the player finds them ready. */
fun warmSubtitleFontCache() {
    if (!subtitleFontWarmStarted.compareAndSet(false, true)) return
    Thread { subtitleFontFamilies = enumerateSubtitleFontFamilies() }
        .apply {
            name = "nuvio-subtitle-font-warmup"
            isDaemon = true
        }
        .start()
}
