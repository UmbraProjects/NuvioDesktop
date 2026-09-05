package com.nuvio.app.core.ui

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import org.jetbrains.skia.FontMgr
import java.util.concurrent.ConcurrentHashMap

/**
 * Enumerated through Skia rather than AWT because Skia is what resolves a family name for Compose,
 * and the two do not agree on what a family is: AWT lists style and width variants as families of
 * their own ("Arial Black", "Calibri Light", "Franklin Gothic Medium", plus its own logical
 * "Dialog"), where Skia groups those under the typographic family and picks the face from the
 * requested weight. On this machine 66 of AWT's 230 names resolve to nothing under Skia, and Skia
 * knows 24 families AWT never names. Listing Skia's families is what keeps every entry in the
 * picker one that will actually apply — and grouping is what the app font wants anyway, since the
 * type scale asks the family for three different weights.
 *
 * Costs about 120ms cold, so it is cached for the session and warmed off the UI thread at startup.
 */
private val installedFontFamilies: List<String> by lazy {
    runCatching {
        val fontMgr = FontMgr.default
        (0 until fontMgr.familiesCount)
            .asSequence()
            .map { fontMgr.getFamilyName(it).trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedBy { it.lowercase() }
            .toList()
    }.getOrDefault(emptyList())
}

actual fun systemFontFamilies(): List<String> = installedFontFamilies

private val resolvedFontFamilies = ConcurrentHashMap<String, FontFamily>()

/**
 * Matched against [installedFontFamilies] rather than asked of Skia directly: `matchFamilyStyle`
 * answers an unknown family with a substituted face on some hosts instead of null, which would let
 * an uninstalled font read as applied in settings while rendering as something else entirely.
 */
@OptIn(ExperimentalTextApi::class)
actual fun systemFontFamilyOrNull(name: String): FontFamily? {
    val requested = name.trim()
    if (requested.isEmpty()) return null
    resolvedFontFamilies[requested]?.let { return it }
    val installed = installedFontFamilies.firstOrNull { it.equals(requested, ignoreCase = true) }
        ?: return null
    return FontFamily(installed).also { resolvedFontFamilies[requested] = it }
}

/** Warms the installed-font list off the UI thread so the first picker open isn't janky. */
fun warmSystemFontCache() {
    Thread { installedFontFamilies }
        .apply {
            name = "nuvio-system-font-warmup"
            isDaemon = true
        }
        .start()
}
