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
import java.security.MessageDigest
import java.time.Duration
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.system.exitProcess

private const val updaterPreferencesName = "nuvio_updater"
private const val ignoredTagKey = "ignored_release_tag"
private const val inPlaceApplyKey = "in_place_apply"

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

    // Default ON: only an explicit "off" opts back into manual extraction.
    actual fun isInPlaceUpdateEnabled(): Boolean =
        !"off".equals(store.getString(inPlaceApplyKey), ignoreCase = true)

    actual fun setInPlaceUpdateEnabled(enabled: Boolean) {
        store.putString(inPlaceApplyKey, if (enabled) "on" else "off")
    }

    actual suspend fun downloadApk(
        assetUrl: String,
        assetName: String,
        expectedSha256: String?,
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
            // Hash while streaming to disk — no re-read of the (potentially large) archive.
            val digest = MessageDigest.getInstance("SHA-256")
            var downloadedBytes = 0L
            response.body().use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloadedBytes += read
                        onProgress(downloadedBytes, totalBytes)
                    }
                }
            }
            check(downloadedBytes > 0L) { "The downloaded update archive was empty." }

            // Verify integrity against the SHA-256 GitHub published for this asset (fetched over
            // HTTPS from the API). A mismatch means a corrupt or tampered download — never promote
            // it to the ready-to-install location. Skipped only when GitHub gave us no digest.
            if (expectedSha256 != null) {
                val actualSha256 = digest.digest().toHexString()
                if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                    partial.delete()
                    error(
                        "Update verification failed: the download's SHA-256 did not match the " +
                            "expected checksum. The file was discarded.",
                    )
                }
            }

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
     * Portable in-place update. The running app cannot overwrite its own locked files
     * (Nuvio.exe, the JVM runtime, mpv/SVP DLLs), so we stage the verified build, hand a
     * detached helper the job of swapping files once we exit, then relaunch. Any precondition
     * we can't satisfy falls back to the original behaviour: reveal the archive so the user can
     * extract it manually.
     */
    actual fun installDownloadedApk(path: String): Result<UpdateInstallOutcome> = runCatching {
        val archive = File(path)
        check(archive.isFile) { "The downloaded update archive is missing." }

        if (isInPlaceUpdateEnabled()) {
            val applied = runCatching { applyPortableUpdateInPlace(archive) }.getOrElse { error ->
                System.err.println("In-place update failed; falling back to manual: ${error.message}")
                false
            }
            // Helper spawned and app-exit scheduled — the caller shows a "restarting" toast.
            if (applied) return@runCatching UpdateInstallOutcome.RESTARTING
        }

        revealInExplorer(archive)
        UpdateInstallOutcome.REVEALED
    }
}

private const val launcherExeName = "Nuvio.exe"

/**
 * Stages the verified archive and launches a detached [cmd] + [robocopy] helper that swaps the
 * new build in once this process exits, then relaunches. Returns false (without side effects that
 * can't be recovered) whenever a precondition isn't met, so the caller can fall back to manual.
 */
private fun applyPortableUpdateInPlace(archive: File): Boolean {
    val installRoot = resolveInstallRoot() ?: return false
    val updatesDir = portableUpdatesDirectory()

    // The helper is an ANSI .bat with the paths baked in; non-ASCII paths (e.g. a localized user
    // folder) can't be represented reliably in a batch console, so hand those off to manual.
    if (!installRoot.absolutePath.isAscii() || !updatesDir.absolutePath.isAscii()) return false
    if (!isWritable(installRoot)) return false

    val stagingDir = File(updatesDir, "staged")
    deleteRecursively(stagingDir)
    stagingDir.mkdirs()

    extractZip(archive, stagingDir)
    val sourceRoot = locateAppImageRoot(stagingDir) ?: run {
        deleteRecursively(stagingDir)
        return false
    }

    val launcherExe = resolveLauncherExe(installRoot)
    val batFile = writeHelperScript(
        updatesDir = updatesDir,
        pid = ProcessHandle.current().pid(),
        sourceRoot = sourceRoot,
        installRoot = installRoot,
        launcherExe = launcherExe,
        stagingDir = stagingDir,
    )

    // Launch minimized and detached: the child cmd outlives our JVM and does the swap post-exit.
    // The inner "cmd /c" is required — letting `start` run the .bat via file association can leave
    // a non-/c shell that drops to an interactive prompt when the script self-deletes (a stuck
    // window) instead of terminating. Routing through an explicit /c guarantees a clean exit.
    ProcessBuilder("cmd.exe", "/c", "start", "Nuvio Update", "/min", "cmd.exe", "/c", batFile.absolutePath)
        .directory(updatesDir)
        .start()

    scheduleExit()
    return true
}

/** The portable app-image root that holds Nuvio.exe, or null when this isn't a packaged run. */
private fun resolveInstallRoot(): File? {
    System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }?.let { appPath ->
        val exe = File(appPath)
        exe.parentFile?.let { if (exe.isFile) return it }
    }
    // Fallback: java.home is <installRoot>/runtime for a jpackage image.
    val javaHome = System.getProperty("java.home")?.takeIf { it.isNotBlank() } ?: return null
    val root = File(javaHome).parentFile ?: return null
    return root.takeIf { File(it, launcherExeName).isFile }
}

private fun resolveLauncherExe(installRoot: File): File {
    System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }?.let { appPath ->
        val exe = File(appPath)
        if (exe.isFile) return exe
    }
    return File(installRoot, launcherExeName)
}

/**
 * The extracted tree either is the app image (Nuvio.exe at its root) or wraps it in one folder,
 * depending on how the release zip was built. Return whichever directory holds the launcher.
 */
private fun locateAppImageRoot(stagingDir: File): File? {
    if (File(stagingDir, launcherExeName).isFile) return stagingDir
    stagingDir.listFiles(File::isDirectory)?.forEach { child ->
        if (File(child, launcherExeName).isFile) return child
    }
    return null
}

private fun extractZip(zip: File, targetDir: File) {
    val targetRoot = targetDir.canonicalFile.toPath()
    ZipFile(zip).use { archive ->
        val entries = archive.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val outFile = File(targetDir, entry.name).canonicalFile
            // Zip-slip guard: reject any entry that would resolve outside the staging directory.
            check(outFile.toPath().startsWith(targetRoot)) {
                "Refusing to extract entry outside the staging directory: ${entry.name}"
            }
            if (entry.isDirectory) {
                outFile.mkdirs()
            } else {
                outFile.parentFile?.mkdirs()
                archive.getInputStream(entry).use { input ->
                    FileOutputStream(outFile).use { output -> input.copyTo(output) }
                }
            }
        }
    }
}

private fun writeHelperScript(
    updatesDir: File,
    pid: Long,
    sourceRoot: File,
    installRoot: File,
    launcherExe: File,
    stagingDir: File,
): File {
    // /E copies subdirs (no /MIR — we never purge, so a swap can't delete anything unexpected).
    // Retries cover file handles that linger a moment after the process dies. tasklist gates the
    // copy on our PID actually being gone; findstr on the PID drives the wait loop.
    val lines = listOf(
        "@echo off",
        "title Nuvio Update",
        "setlocal enableextensions",
        "set \"PID=$pid\"",
        "set \"SRC=${sourceRoot.absolutePath}\"",
        "set \"DST=${installRoot.absolutePath}\"",
        "set \"EXE=${launcherExe.absolutePath}\"",
        "set \"STAGE=${stagingDir.absolutePath}\"",
        "set \"LOG=%~dp0apply-update.log\"",
        "echo [%date% %time%] waiting for Nuvio (pid %PID%) to exit> \"%LOG%\"",
        ":waitloop",
        "tasklist /fi \"PID eq %PID%\" /nh 2>nul | findstr \"%PID%\" >nul",
        "if not errorlevel 1 (",
        "  timeout /t 1 /nobreak >nul",
        "  goto waitloop",
        ")",
        "echo [%date% %time%] copying update into place>> \"%LOG%\"",
        "robocopy \"%SRC%\" \"%DST%\" /E /R:15 /W:2 /NFL /NDL /NJH /NJS /NP >> \"%LOG%\" 2>&1",
        "if %ERRORLEVEL% GEQ 8 (",
        "  echo [%date% %time%] robocopy failed %ERRORLEVEL%; opening updates folder>> \"%LOG%\"",
        "  start \"\" explorer.exe \"%~dp0\"",
        "  goto done",
        ")",
        "echo [%date% %time%] relaunching Nuvio>> \"%LOG%\"",
        "start \"\" \"%EXE%\"",
        ":done",
        "rmdir /s /q \"%STAGE%\" 2>nul",
        "(goto) 2>nul & del \"%~f0\"",
        "",
    )
    val batFile = File(updatesDir, "apply-update.bat")
    batFile.writeText(lines.joinToString("\r\n"), Charsets.US_ASCII)
    return batFile
}

private fun revealInExplorer(archive: File) {
    ProcessBuilder("explorer.exe", "/select,${archive.absolutePath}").start()
}

/** Give the Result a moment to propagate / the UI a frame to react, then exit so files unlock. */
private fun scheduleExit() {
    Thread {
        runCatching { Thread.sleep(1200) }
        exitProcess(0)
    }.apply {
        isDaemon = true
        name = "nuvio-update-exit"
    }.start()
}

private fun isWritable(dir: File): Boolean = runCatching {
    val probe = File(dir, ".nuvio-update-probe-${System.nanoTime()}")
    probe.outputStream().use { it.write(0) }
    probe.delete()
    true
}.getOrDefault(false)

private fun deleteRecursively(dir: File) {
    if (!dir.exists()) return
    dir.walkBottomUp().forEach { runCatching { it.delete() } }
}

private fun String.isAscii(): Boolean = all { it.code in 0..127 }

private fun portableUpdatesDirectory(): File {
    return DesktopStorage.rootDir.resolve("updates").toFile()
}

private fun ByteArray.toHexString(): String {
    val hexChars = "0123456789abcdef"
    val result = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xFF
        result.append(hexChars[value shr 4])
        result.append(hexChars[value and 0x0F])
    }
    return result.toString()
}
