package com.nuvio.app.features.downloads

/**
 * Extensions that can cause downloaded content to execute directly or through a script/runtime.
 *
 * Keep this policy in common code so every media acquisition path (managed downloads, browser
 * hand-off, subtitle caching and P2P file selection) makes the same conservative decision.
 */
internal val EXECUTABLE_DOWNLOAD_EXTENSIONS = setOf(
    "exe", "com", "scr", "pif", "msi", "msp", "mst", "dll", "ocx", "sys", "drv",
    "bat", "cmd", "ps1", "ps1xml", "psc1", "psd1", "psm1", "vbs", "vbe", "js", "jse",
    "wsf", "wsh", "hta", "cpl", "jar", "jnlp", "lnk", "scf", "reg", "inf", "chm",
    "app", "appimage", "apk", "xapk", "aab", "appx", "appxbundle", "msix", "msixbundle",
    "deb", "rpm", "pkg", "dmg", "iso", "sh", "bash", "zsh", "fish", "command", "run",
    "desktop", "elf", "bin",
)

private val encodedPercent = Regex("%25", RegexOption.IGNORE_CASE)
private val encodedDot = Regex("%2e", RegexOption.IGNORE_CASE)
private val encodedSlash = Regex("%2f", RegexOption.IGNORE_CASE)
private val encodedBackslash = Regex("%5c", RegexOption.IGNORE_CASE)
private val encodedColon = Regex("%3a", RegexOption.IGNORE_CASE)

/**
 * True when any filename-like portion of this value advertises an executable extension.
 *
 * This examines URL query parameters and Content-Disposition values as well as ordinary paths,
 * including repeatedly percent-encoded separators used to hide a dangerous suffix.
 */
internal fun String.hasExecutableDownloadExtension(): Boolean =
    downloadReferenceExtensions().any { it in EXECUTABLE_DOWNLOAD_EXTENSIONS }

/** True only for a video-looking reference that contains no executable-looking component. */
internal fun String.isSafeVideoDownloadReference(): Boolean {
    val extensions = downloadReferenceExtensions()
    return extensions.none { it in EXECUTABLE_DOWNLOAD_EXTENSIONS } &&
        extensions.any { it in SAFE_VIDEO_DOWNLOAD_EXTENSIONS }
}

private fun String.downloadReferenceExtensions(): Set<String> {
    var decoded = trim()
    repeat(3) {
        decoded = decoded
            .replace(encodedPercent, "%")
            .replace(encodedDot, ".")
            .replace(encodedSlash, "/")
            .replace(encodedBackslash, "\\")
            .replace(encodedColon, ":")
    }

    return decoded
        .split('/', '\\', '?', '#', '&', '=', ';', ':', ',', '"', '\'')
        .mapNotNullTo(linkedSetOf()) { component ->
            val normalized = component.trim().trimEnd(' ', '.')
            val extension = normalized
                .substringAfterLast('.', missingDelimiterValue = "")
                .trim()
                .trimEnd(' ', '.')
                .lowercase()
                .takeWhile { it.isLetterOrDigit() }
            extension.takeIf { it.isNotBlank() }
        }
}

internal fun String?.isExecutableDownloadContentType(): Boolean {
    val contentType = this
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
        .orEmpty()
    if (contentType.isBlank()) return false
    return contentType in setOf(
        "application/x-msdownload",
        "application/x-dosexec",
        "application/x-executable",
        "application/vnd.microsoft.portable-executable",
        "application/x-msi",
        "application/x-ms-installer",
        "application/x-sh",
        "application/x-shellscript",
        "application/x-bat",
        "application/x-java-archive",
        "application/java-archive",
        "application/vnd.android.package-archive",
    )
}

/** Common executable signatures used to reject disguised payloads before another component sees them. */
internal fun ByteArray.hasExecutableFileSignature(): Boolean {
    fun matches(vararg bytes: Int): Boolean =
        size >= bytes.size &&
            bytes.indices.all { index -> (this[index].toInt() and 0xff) == bytes[index] }

    if (matches(0x4d, 0x5a)) return true // DOS/Windows PE ("MZ")
    if (matches(0x7f, 0x45, 0x4c, 0x46)) return true // ELF
    if (matches(0xca, 0xfe, 0xba, 0xbe)) return true // Java class or fat Mach-O
    if (matches(0xfe, 0xed, 0xfa, 0xce) || matches(0xfe, 0xed, 0xfa, 0xcf)) return true
    if (matches(0xce, 0xfa, 0xed, 0xfe) || matches(0xcf, 0xfa, 0xed, 0xfe)) return true
    if (matches(0x23, 0x21)) return true // executable script shebang

    val leadingText = decodeToString(0, minOf(size, 96))
        .trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        .lowercase()
    return leadingText.startsWith("@echo off") ||
        leadingText.startsWith("setlocal ") ||
        leadingText.startsWith("windows registry editor")
}
