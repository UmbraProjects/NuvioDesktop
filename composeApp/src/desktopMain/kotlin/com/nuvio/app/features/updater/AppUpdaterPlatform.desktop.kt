package com.nuvio.app.features.updater

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.AppVersionPolicy
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
private const val snoozedUntilKey = "update_snoozed_until_ms"
private const val inPlaceApplyKey = "in_place_apply"
private const val updateChannelKey = "update_channel"
private const val installedNightlyIdKey = "installed_nightly_id"
private const val installedNightlyPublishedKey = "installed_nightly_published_at"
private const val pendingNightlySwapKey = "pending_nightly_swap"
private const val lastSeenBuildIdKey = "last_seen_build_id"

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

    // Stable unless explicitly opted in, so an unreadable or missing value never silently moves
    // someone onto prerelease builds.
    actual fun getUpdateChannel(): UpdateChannel =
        if (UpdateChannel.Nightly.name.equals(store.getString(updateChannelKey), ignoreCase = true)) {
            UpdateChannel.Nightly
        } else {
            UpdateChannel.Stable
        }

    actual fun setUpdateChannel(channel: UpdateChannel) {
        store.putString(updateChannelKey, channel.name)
    }

    actual fun getInstalledNightlyBuild(): InstalledNightlyBuild? {
        reconcileInstalledBuild()
        val id = store.getString(installedNightlyIdKey)?.takeIf { it.isNotBlank() } ?: return null
        return InstalledNightlyBuild(
            id = id,
            publishedAt = store.getString(installedNightlyPublishedKey)?.takeIf { it.isNotBlank() },
        )
    }

    actual fun setInstalledNightlyBuild(build: InstalledNightlyBuild?) {
        store.putString(installedNightlyIdKey, build?.id)
        store.putString(installedNightlyPublishedKey, build?.publishedAt)
        // The swap runs after this process exits, so the marker is a claim about a build that is
        // not installed yet. Flag it, and let the next launch confirm it against the build stamp.
        store.putString(pendingNightlySwapKey, if (build != null) "1" else null)
    }

    private var reconciledInstalledBuild = false

    /**
     * Checks the stored nightly marker against the build actually running, once per process.
     *
     * Driven from [getInstalledNightlyBuild] rather than from startup: everything that reads the
     * marker goes through there, so no launch path can skip it.
     */
    @Synchronized
    private fun reconcileInstalledBuild() {
        if (reconciledInstalledBuild) return
        reconciledInstalledBuild = true
        val decision = decideNightlyMarker(
            hasMarker = !store.getString(installedNightlyIdKey).isNullOrBlank(),
            swapPending = store.getString(pendingNightlySwapKey) == "1",
            runningBuildId = AppVersionPolicy.packagedBuild?.id,
            lastSeenBuildId = store.getString(lastSeenBuildIdKey)?.takeIf { it.isNotBlank() },
        )
        if (!decision.keepMarker) {
            store.putString(installedNightlyIdKey, null)
            store.putString(installedNightlyPublishedKey, null)
        }
        if (decision.clearPending) store.putString(pendingNightlySwapKey, null)
        decision.recordBuildId?.let { store.putString(lastSeenBuildIdKey, it) }
    }

    actual fun getIgnoredTag(): String? = store.getString(ignoredTagKey)

    actual fun setIgnoredTag(tag: String?) {
        store.putString(ignoredTagKey, tag)
    }

    actual fun getSnoozedUntilMs(): Long? = store.getString(snoozedUntilKey)?.toLongOrNull()

    actual fun setSnoozedUntilMs(epochMs: Long?) {
        store.putString(snoozedUntilKey, epochMs?.toString())
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
            // Fail closed. The update is unpacked and run automatically, so an asset with no
            // published digest cannot be checked at all; GitHub has published one for every asset
            // uploaded since mid-2025, so a missing one is itself a sign something is off.
            requireNotNull(expectedSha256?.takeIf { it.isNotBlank() }) {
                "Update refused: GitHub published no SHA-256 checksum for this download, so it cannot be verified."
            }
            tryDeltaDownload(assetUrl, onProgress)?.let { marker -> return@runCatching marker.absolutePath }

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
            // it to the ready-to-install location.
            run {
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

        if (archive.name == deltaMarkerName) {
            // Only the changed files were downloaded, so there is no archive to reveal: a delta is
            // either swapped in or abandoned, and the retry downloads the whole zip.
            val applied = runCatching { applyDeltaUpdateInPlace(archive) }.getOrElse { error ->
                updaterLog.w(error) { "Delta update could not be applied" }
                false
            }
            if (applied) return@runCatching UpdateInstallOutcome.RESTARTING
            deltaDisabledThisSession = true
            archive.delete()
            error("The update could not be applied. Try again to download the full update.")
        }

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

/** Beside the exe of a build folder; see [inPlaceTarget]. Kept in step with build.gradle.kts. */
private const val devBuildMarkerName = "dev-build"

/**
 * Stages the verified archive and launches a detached [cmd] + [robocopy] helper that swaps the
 * new build in once this process exits, then relaunches. Returns false (without side effects that
 * can't be recovered) whenever a precondition isn't met, so the caller can fall back to manual.
 */
private fun applyPortableUpdateInPlace(archive: File): Boolean {
    val target = inPlaceTarget() ?: return false
    val stagingDir = File(target.updatesDir, "staged")
    deleteRecursively(stagingDir)
    stagingDir.mkdirs()

    extractZip(archive, stagingDir)
    val sourceRoot = locateAppImageRoot(stagingDir) ?: run {
        deleteRecursively(stagingDir)
        return false
    }
    val newFiles = sourceRoot.walkTopDown()
        .filter(File::isFile)
        .map { file -> file.relativeTo(sourceRoot).path.replace('/', '\\') }
        .toList()
    launchSwap(target, sourceRoot = sourceRoot, stagingDir = stagingDir, newFiles = newFiles)
    return true
}

/** Where an in-place swap would write, or null when one isn't possible from this run. */
private class InPlaceTarget(val installRoot: File, val updatesDir: File)

private fun inPlaceTarget(): InPlaceTarget? {
    val installRoot = resolveInstallRoot() ?: return null
    // A build folder (gradlew createNativeDistributable writes this marker; the release zip leaves
    // it out) is never swapped for a downloaded release: it is a stamped nightly, so it is offered
    // updates like any install, and the swap would replace the build under test.
    if (File(installRoot, devBuildMarkerName).isFile) {
        updaterLog.i { "In-place update skipped: ${installRoot.absolutePath} is a development build" }
        return null
    }
    val updatesDir = portableUpdatesDirectory()

    // The helper is an ANSI .bat with the paths baked in; non-ASCII paths (e.g. a localized user
    // folder) can't be represented reliably in a batch console, and `%`, `!` or `"` in a path would
    // be expanded or break the quoting, so hand those off to manual.
    if (!installRoot.absolutePath.isBatchSafe() || !updatesDir.absolutePath.isBatchSafe()) return null
    if (!isWritable(installRoot)) return null
    return InPlaceTarget(installRoot, updatesDir)
}

/**
 * Writes the manifest and helper for [sourceRoot] (a staged app image — complete for a zip, only
 * the changed files for a delta), starts the helper and schedules this process's exit. [newFiles]
 * is every file of the new build, which the stale-file computation needs even when the staged
 * tree is partial.
 */
private fun launchSwap(target: InPlaceTarget, sourceRoot: File, stagingDir: File, newFiles: List<String>) {
    val staleFiles = writeInstallManifest(sourceRoot = sourceRoot, installRoot = target.installRoot, newFiles = newFiles)
    val staleList = File(target.updatesDir, "stale-files.txt").apply {
        writeText(staleFiles.joinToString("\r\n"), Charsets.US_ASCII)
    }

    val launcherExe = resolveLauncherExe(target.installRoot)
    val batFile = writeHelperScript(
        updatesDir = target.updatesDir,
        pid = ProcessHandle.current().pid(),
        sourceRoot = sourceRoot,
        installRoot = target.installRoot,
        launcherExe = launcherExe,
        stagingDir = stagingDir,
        staleList = staleList,
    )

    // Launch minimized and detached: the child cmd outlives our JVM and does the swap post-exit.
    // The inner "cmd /c" is required — letting `start` run the .bat via file association can leave
    // a non-/c shell that drops to an interactive prompt when the script self-deletes (a stuck
    // window) instead of terminating. Routing through an explicit /c guarantees a clean exit.
    ProcessBuilder("cmd.exe", "/c", "start", "Nuvio Update", "/min", "cmd.exe", "/c", batFile.absolutePath)
        .directory(target.updatesDir)
        .start()

    scheduleExit()
}

private val updaterLog = Logger.withTag("AppUpdater")

/** Name of the file [AppUpdaterPlatform.downloadApk] returns for a staged delta. */
private const val deltaMarkerName = "delta-update.txt"
private const val deltaStagingName = "staged-delta"

/** Above this share of the archive, a delta saves too little to give up the whole-zip SHA-256. */
private const val deltaMaxShare = 0.75

/** Set after a delta fails to apply, so the retry downloads the whole zip. */
@Volatile
private var deltaDisabledThisSession = false

/**
 * Downloads only the files that differ from the installed build (see DeltaUpdate.desktop.kt) and
 * returns a marker listing the new build's files, or null to have the caller download the whole
 * zip. Null whenever the in-place swap couldn't run anyway: the manual fallback needs a full zip.
 */
private fun tryDeltaDownload(
    assetUrl: String,
    onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
): File? {
    if (deltaDisabledThisSession || !AppUpdaterPlatform.isInPlaceUpdateEnabled()) return null
    val target = inPlaceTarget() ?: return null
    val stagingDir = File(target.updatesDir, deltaStagingName)
    return runCatching {
        val source = HttpZipRangeSource(updaterHttpClient, assetUrl)
        val index = ZipDelta.readIndex(source)
        val plan = checkNotNull(ZipDelta.plan(index.entries, target.installRoot, launcherExeName)) {
            "No app image in the archive"
        }
        updaterLog.i {
            "Delta update: ${plan.changed.size} of ${plan.newFiles.size} files changed, " +
                "${plan.changedCompressedBytes} of ${source.length} bytes"
        }
        if (plan.changedCompressedBytes > source.length * deltaMaxShare) return null

        deleteRecursively(stagingDir)
        stagingDir.mkdirs()
        ZipDelta.fetchChanged(source, index, plan, stagingDir) { downloaded, total ->
            onProgress(downloaded, total.takeIf { it > 0L })
        }
        File(target.updatesDir, deltaMarkerName).apply {
            writeText(plan.newFiles.joinToString("\r\n"), Charsets.UTF_8)
        }
    }.onFailure { error ->
        updaterLog.w(error) { "Delta update unavailable; downloading the full archive" }
        deleteRecursively(stagingDir)
    }.getOrNull()
}

private fun applyDeltaUpdateInPlace(marker: File): Boolean {
    val target = inPlaceTarget() ?: return false
    val stagingDir = File(target.updatesDir, deltaStagingName)
    if (!stagingDir.isDirectory) return false
    val newFiles = marker.readLines(Charsets.UTF_8).map(String::trim).filter(String::isNotEmpty)
    if (newFiles.none { it.equals(launcherExeName, ignoreCase = true) }) return false
    marker.delete()
    launchSwap(target, sourceRoot = stagingDir, stagingDir = stagingDir, newFiles = newFiles)
    return true
}

internal const val installManifestName = "install-manifest.txt"

/**
 * Records every file of the new build in [installManifestName] (copied into the install folder with
 * the rest) and returns the files to delete: those the *previous* manifest listed, plus the
 * build-owned leftovers in `app\` and `runtime\` (see [buildOwnedLeftovers]), that the new build no
 * longer ships. Never a blind mirror, so anything the user keeps in the install folder survives.
 */
internal fun writeInstallManifest(sourceRoot: File, installRoot: File, newFiles: List<String>): List<String> {
    val newFiles = newFiles
        .filterNot { it.equals(installManifestName, ignoreCase = true) }
        .toSortedSet(String.CASE_INSENSITIVE_ORDER)
    File(sourceRoot, installManifestName).writeText(newFiles.joinToString("\r\n"), Charsets.UTF_8)
    val previous = File(installRoot, installManifestName).takeIf(File::isFile)
        ?.readLines(Charsets.UTF_8)
        .orEmpty()
    return (previous.map(String::trim) + buildOwnedLeftovers(installRoot))
        .filter { it.isNotEmpty() && it !in newFiles }
        // Relative paths inside the install folder only, and nothing the batch loop could misread.
        .filter { it.isBatchSafe() && !it.startsWith("\\") && ':' !in it && ".." !in it.split('\\') }
        .distinctBy { it.lowercase() }
}

/**
 * Files in places only the build ever writes: `app\*.jar`, `app\*.jsa` and everything under
 * `runtime\`. Whatever of these the new build does not ship goes, manifest or not. Jar names carry
 * a content hash, so every update unzipped by hand before the manifest existed left the previous
 * jars behind, and no manifest ever named them: one user's `app` folder had grown to 1.4 GB
 * (2026-10-17). The move to the native build would otherwise also keep the whole JVM runtime of an
 * install that never had a manifest.
 */
private fun buildOwnedLeftovers(installRoot: File): List<String> {
    fun relative(file: File) = file.relativeTo(installRoot).path.replace('/', '\\')
    val appArchives = File(installRoot, "app").listFiles { file ->
        file.isFile && (file.extension.equals("jar", ignoreCase = true) || file.extension.equals("jsa", ignoreCase = true))
    }.orEmpty().map(::relative)
    val runtimeFiles = File(installRoot, "runtime").takeIf(File::isDirectory)
        ?.walkTopDown()?.filter(File::isFile)?.map(::relative)?.toList()
        .orEmpty()
    return appArchives + runtimeFiles
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
    staleList: File,
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
        "set \"STALE=${staleList.absolutePath}\"",
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
        "echo [%date% %time%] removing files the new build dropped>> \"%LOG%\"",
        "if exist \"%STALE%\" for /f \"usebackq delims=\" %%F in (\"%STALE%\") do del /f /q \"%DST%\\%%F\" >>\"%LOG%\" 2>&1",
        "del /f /q \"%STALE%\" 2>nul",
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

/**
 * Give the Result a moment to propagate / the UI a frame to react, then exit so files unlock.
 * Through the normal exit path, so window geometry, pending store writes and the coalesced caches
 * are flushed and the P2P engine (and any TorrServer child) is shut down; a hard exitProcess only
 * if that path has not finished well after.
 */
private fun scheduleExit() {
    Thread {
        runCatching { Thread.sleep(1200) }
        val requested = runCatching {
            var accepted = false
            javax.swing.SwingUtilities.invokeAndWait { accepted = com.nuvio.app.DesktopApplicationExit.request() }
            accepted
        }.getOrDefault(false)
        if (requested) runCatching { Thread.sleep(15_000) }
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

private fun String.isBatchSafe(): Boolean = all { it.code in 32..126 && it != '%' && it != '!' && it != '"' }

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
