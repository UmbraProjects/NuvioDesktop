package com.nuvio.app.features.updater

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale

private const val updaterPreferencesName = "nuvio_updater"
private const val ignoredTagKey = "ignored_release_tag"

private val updaterHttpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(60))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

actual object AppUpdaterPlatform {
    private val store = DesktopStorage.store(updaterPreferencesName)

    actual val isSupported: Boolean =
        System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("win")

    actual fun getSupportedAbis(): List<String> {
        val arch = System.getProperty("os.arch").orEmpty().lowercase(Locale.ROOT)
        return when {
            arch == "aarch64" || arch == "arm64" -> listOf("arm64", "aarch64", "windows", "win")
            arch == "x86" || arch == "i386" || arch == "i686" -> listOf("x86", "i386", "windows", "win")
            arch.contains("64") -> listOf("x64", "x86_64", "amd64", "windows", "win")
            else -> listOf("windows", "win")
        }
    }

    actual fun getIgnoredTag(): String? = store.getString(ignoredTagKey)

    actual fun setIgnoredTag(tag: String?) {
        store.putString(ignoredTagKey, tag)
    }

    actual suspend fun downloadApk(
        assetUrl: String,
        assetName: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(assetName.endsWith(".zip", ignoreCase = true)) {
                "This portable build only supports ZIP update archives."
            }
            val safeName = assetName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val updateDirectory = portableUpdatesDirectory().apply { mkdirs() }
            val destination = File(updateDirectory, safeName)
            val partial = File(updateDirectory, "$safeName.part")
            destination.delete()
            partial.delete()

            val request = HttpRequest.newBuilder(URI.create(assetUrl))
                .timeout(Duration.ofMinutes(10))
                .header("Accept", "application/octet-stream")
                .GET()
                .build()
            val response = updaterHttpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
            check(response.statusCode() in 200..299) {
                "Update download failed with HTTP ${response.statusCode()}."
            }

            val totalBytes = response.headers().firstValue("Content-Length").orElse(null)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
            var downloadedBytes = 0L
            response.body().use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        onProgress(downloadedBytes, totalBytes)
                    }
                }
            }
            check(downloadedBytes > 0L) { "The downloaded update archive was empty." }

            if (!partial.renameTo(destination)) {
                partial.copyTo(destination, overwrite = true)
                partial.delete()
            }
            destination.absolutePath
        }
    }

    actual fun canRequestPackageInstalls(): Boolean = true

    actual fun openUnknownSourcesSettings() = Unit

    /**
     * Portable releases cannot safely overwrite the running application. Reveal the verified
     * download and let the user extract it over their existing portable folder after exiting.
     */
    actual fun installDownloadedApk(path: String): Result<Unit> = runCatching {
        val archive = File(path)
        check(archive.isFile) { "The downloaded update archive is missing." }
        ProcessBuilder("explorer.exe", "/select,${archive.absolutePath}").start()
    }
}

private fun portableUpdatesDirectory(): File {
    val localAppData = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)
    return if (localAppData != null) {
        File(localAppData, "Nuvio/updates")
    } else {
        File(System.getProperty("user.home"), "AppData/Local/Nuvio/updates")
    }
}
