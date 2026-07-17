package com.nuvio.app.features.settings

import java.awt.Color
import javax.swing.JColorChooser
import javax.swing.SwingUtilities

/**
 * Native theme-colour picker. Uses Swing's [JColorChooser] on every platform — it ships with the
 * JVM (RGB/HSV/HSL tabs + swatches), needs no external process, and deliberately avoids spawning
 * `powershell.exe` for the Windows ColorDialog: encoded-command / `-ExecutionPolicy Bypass`
 * PowerShell launches are a top behavioural heuristic for AV engines (Kaspersky flagged it), and
 * the marginally nicer native dialog isn't worth the false-positive.
 */
internal actual fun pickCustomThemeColor(initialHex: String): String? {
    val initialColor = initialHex.toAwtColor() ?: Color(0xFF, 0xD7, 0x00)
    var selectedColor: Color? = null
    val showDialog = {
        selectedColor = JColorChooser.showDialog(null, "Choose theme color", initialColor)
    }
    if (SwingUtilities.isEventDispatchThread()) {
        showDialog()
    } else {
        SwingUtilities.invokeAndWait(showDialog)
    }
    return selectedColor?.toThemeHex()
}

private fun String.toAwtColor(): Color? {
    val cleaned = trim().removePrefix("#")
    if (cleaned.length != 6 || !cleaned.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
        return null
    }
    return runCatching {
        Color(
            cleaned.substring(0, 2).toInt(16),
            cleaned.substring(2, 4).toInt(16),
            cleaned.substring(4, 6).toInt(16),
        )
    }.getOrNull()
}

private fun Color.toThemeHex(): String =
    "#%02X%02X%02X".format(red, green, blue)
