package com.nuvio.app

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Makes `nuvio://` links open this app on Windows, so a web page (a tracker's "Open in Nuvio")
 * can hand a title to it: `nuvio://meta?type=series&id=tt0944947` opens that title's page.
 *
 * The MSI doesn't register the scheme, so the installed app does it on each launch, under
 * HKEY_CURRENT_USER (no elevation) and pointing at the Nuvio.exe that is running — an update or a
 * move to another folder is picked up the next time it starts. Written as a .reg file and imported
 * with `reg import`, which takes the quoted command line as data instead of through argument
 * quoting. Packaged builds only: a Gradle run has no launcher to point at.
 */
object DesktopUrlScheme {

    fun registerInBackground() {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) return
        val exe = System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() } ?: return
        Thread({
            runCatching { register(exe) }
                .onFailure { System.err.println("Warn: (UrlScheme) couldn't register nuvio:// links: $it") }
        }, "nuvio-url-scheme").apply { isDaemon = true }.start()
    }

    private fun register(exe: String) {
        val escaped = exe.replace("\\", "\\\\").replace("\"", "\\\"")
        val reg = buildString {
            append("Windows Registry Editor Version 5.00\r\n\r\n")
            append("[HKEY_CURRENT_USER\\Software\\Classes\\nuvio]\r\n")
            append("@=\"URL:Nuvio\"\r\n")
            append("\"URL Protocol\"=\"\"\r\n\r\n")
            append("[HKEY_CURRENT_USER\\Software\\Classes\\nuvio\\DefaultIcon]\r\n")
            append("@=\"\\\"$escaped\\\",0\"\r\n\r\n")
            append("[HKEY_CURRENT_USER\\Software\\Classes\\nuvio\\shell\\open\\command]\r\n")
            append("@=\"\\\"$escaped\\\" \\\"%1\\\"\"\r\n")
        }
        val file = Files.createTempFile("nuvio-url-scheme", ".reg")
        try {
            // reg import reads UTF-16LE with a byte-order mark, so any install path survives.
            Files.write(file, byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + reg.toByteArray(StandardCharsets.UTF_16LE))
            val process = ProcessBuilder("reg", "import", file.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroy()
            else if (process.exitValue() == 0) System.out.println("Info: (UrlScheme) nuvio:// links open $exe")
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
