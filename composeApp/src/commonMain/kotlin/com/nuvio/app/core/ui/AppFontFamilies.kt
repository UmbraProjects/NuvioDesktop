package com.nuvio.app.core.ui

import androidx.compose.ui.text.font.FontFamily

/**
 * Every font family installed on the host, sorted, with no blank sentinel — callers that offer a
 * "default" entry prepend their own. Empty on a host whose fonts cannot be enumerated.
 */
expect fun systemFontFamilies(): List<String>

/**
 * The [FontFamily] for an installed family [name], or null when [name] is blank or names a font
 * this host does not have. Callers fall back to the bundled face on null rather than handing the
 * name to the text stack, which would substitute something arbitrary and still look "applied".
 */
expect fun systemFontFamilyOrNull(name: String): FontFamily?
