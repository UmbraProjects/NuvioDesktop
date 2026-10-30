package com.nuvio.app.features.preroll

import com.nuvio.app.core.storage.DesktopStorage
import java.io.File

private val MEDIA_EXTENSIONS = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "mpg", "mpeg", "ts", "wmv")
private val LAYOUT = listOf("cinema-intro", "bumper", "rating-bbfc", "rating-mpaa", "feature-presentation")

internal actual object PreshowLocalFiles {
    actual fun defaultFolder(): String = DesktopStorage.rootDir.resolve("preshow").toString()

    actual fun list(folder: String, subfolder: String): List<String> {
        val root = File(folder)
        // Creating the layout is how the user finds out where to drop files.
        runCatching { LAYOUT.forEach { File(root, it).mkdirs() } }
        return File(root, subfolder).listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.lowercase() in MEDIA_EXTENSIONS }
            .map { it.absolutePath }
            .sorted()
    }
}
