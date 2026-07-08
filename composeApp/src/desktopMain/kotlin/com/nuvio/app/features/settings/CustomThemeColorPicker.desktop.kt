package com.nuvio.app.features.settings

import java.awt.Color
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.swing.JColorChooser
import javax.swing.SwingUtilities

internal actual fun pickCustomThemeColor(initialHex: String): String? {
    val initialColor = initialHex.toAwtColor() ?: Color(0xFF, 0xD7, 0x00)
    if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
        return pickWindowsThemeColor(initialColor)
    }
    var selectedColor: Color? = null
    val showDialog = {
        selectedColor = JColorChooser.showDialog(
            null,
            "Choose theme color",
            initialColor,
        )
    }
    if (SwingUtilities.isEventDispatchThread()) {
        showDialog()
    } else {
        SwingUtilities.invokeAndWait(showDialog)
    }
    return selectedColor?.toThemeHex()
}

private fun pickWindowsThemeColor(initialColor: Color): String? =
    runCatching {
        val script = """
            Add-Type -AssemblyName System.Drawing
            Add-Type -AssemblyName System.Windows.Forms
            [System.Windows.Forms.Application]::EnableVisualStyles()
            ${'$'}owner = New-Object System.Windows.Forms.Form
            ${'$'}owner.StartPosition = [System.Windows.Forms.FormStartPosition]::CenterScreen
            ${'$'}owner.Size = New-Object System.Drawing.Size(1, 1)
            ${'$'}owner.Opacity = 0
            ${'$'}owner.TopMost = ${'$'}true
            ${'$'}owner.ShowInTaskbar = ${'$'}false
            ${'$'}dialog = New-Object System.Windows.Forms.ColorDialog
            ${'$'}dialog.Color = [System.Drawing.Color]::FromArgb(${initialColor.red}, ${initialColor.green}, ${initialColor.blue})
            ${'$'}dialog.FullOpen = ${'$'}true
            try {
                ${'$'}owner.Show()
                ${'$'}result = ${'$'}dialog.ShowDialog(${'$'}owner)
                if (${'$'}result -eq [System.Windows.Forms.DialogResult]::OK) {
                    ${'$'}hex = [string]::Format("#{0:X2}{1:X2}{2:X2}", ${'$'}dialog.Color.R, ${'$'}dialog.Color.G, ${'$'}dialog.Color.B)
                    [Console]::Out.WriteLine("NUVIO_COLOR_HEX=${'$'}hex")
                }
            } finally {
                ${'$'}owner.Close()
                ${'$'}owner.Dispose()
            }
        """.trimIndent()
        val encodedCommand = Base64.getEncoder().encodeToString(
            script.toByteArray(StandardCharsets.UTF_16LE),
        )
        val process = ProcessBuilder(
            "powershell.exe",
            "-STA",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-EncodedCommand",
            encodedCommand,
        )
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        output
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("NUVIO_COLOR_HEX=") }
            ?.substringAfter('=')
            ?.takeIf { it.matches(Regex("#[0-9A-Fa-f]{6}")) }
            ?.uppercase()
    }.getOrNull()

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
