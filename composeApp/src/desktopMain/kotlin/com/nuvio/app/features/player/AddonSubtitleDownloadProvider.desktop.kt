package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.Dialog
import java.awt.FileDialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.swing.JOptionPane
import nuvio.composeapp.generated.resources.*
import com.nuvio.app.core.i18n.localizedString

private const val SubtitleDownloadDestinationStoreName = "nuvio_addon_subtitle_download"
private const val LastSubtitleDownloadDirectoryKey = "last_directory"
private val subtitleDownloadLog = Logger.withTag("SubtitleDownload")
private val subtitleDownloadDestinationStore by lazy {
    DesktopStorage.store(SubtitleDownloadDestinationStoreName)
}

actual object AddonSubtitleDownloadProvider {
    actual suspend fun download(request: AddonSubtitleDownloadRequest): AddonSubtitleDownloadResult {
        subtitleDownloadLog.i { "Download requested: ${request.subtitleLabel} (${request.language})" }
        val downloaded = withContext(Dispatchers.IO) {
            downloadSubtitleFile(request.subtitleUrl)
        } ?: run {
            subtitleDownloadLog.w { "Fetch failed or rejected for ${request.subtitleLabel}" }
            return AddonSubtitleDownloadResult.Failed(localizedString(Res.string.subtitle_download_the_provider_did_not_return_a))
        }
        subtitleDownloadLog.i { "Fetched ${downloaded.bytes.size} bytes as .${downloaded.extension}" }

        val localMedia = withContext(Dispatchers.IO) {
            resolveLocalMediaFile(request.activeMediaSource)
        }
        val automaticTarget = localMedia
            ?.parentFile
            ?.takeIf { it.isDirectory && it.canWrite() }
            ?.resolve("${localMedia.nameWithoutExtension}.${downloaded.extension}")

        val target = if (automaticTarget != null) {
            subtitleDownloadLog.i { "Local media playing; saving beside it: ${automaticTarget.absolutePath}" }
            if (!confirmOverwrite(automaticTarget)) {
                subtitleDownloadLog.i { "Overwrite declined" }
                return AddonSubtitleDownloadResult.Cancelled
            }
            automaticTarget
        } else {
            chooseSubtitleDestination(
                suggestedBaseName = request.suggestedBaseName,
                extension = downloaded.extension,
            ) ?: run {
                subtitleDownloadLog.i { "Save dialog cancelled" }
                return AddonSubtitleDownloadResult.Cancelled
            }
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                writeSubtitleAtomically(target, downloaded.bytes)
                subtitleDownloadLog.i { "Saved ${target.absolutePath}" }
                AddonSubtitleDownloadResult.Saved(
                    path = target.absolutePath,
                    savedBesideMedia = automaticTarget != null,
                )
            }.getOrElse { error ->
                subtitleDownloadLog.w(error) { "Write failed for ${target.absolutePath}" }
                AddonSubtitleDownloadResult.Failed(error.message ?: localizedString(Res.string.subtitle_download_the_subtitle_could_not_be_written))
            }
        }
    }
}

internal fun resolveLocalMediaFile(source: String): File? {
    val raw = source.trim()
    if (raw.isBlank() || raw.isRemoteHttpUrl()) return null
    val file = runCatching {
        if (raw.startsWith("file:", ignoreCase = true)) File(URI(raw)) else File(raw)
    }.getOrNull() ?: return null
    return file.toPath().toAbsolutePath().normalize().toFile().takeIf(File::isFile)
}

internal fun subtitleDestinationWithExtension(file: File, extension: String): File {
    val normalizedExtension = extension.trim().lowercase().ifBlank { "srt" }
    return if (file.extension.equals(normalizedExtension, ignoreCase = true)) {
        file
    } else {
        file.parentFile?.resolve("${file.name}.$normalizedExtension")
            ?: File("${file.name}.$normalizedExtension")
    }
}

private suspend fun chooseSubtitleDestination(suggestedBaseName: String, extension: String): File? =
    withContext(Dispatchers.Swing) {
        val safeBase = suggestedBaseName.sanitizedForFileName().ifBlank { "Subtitle" }
        val initialDirectory = preferredSubtitleSaveDirectory(
            rememberedPath = runCatching {
                subtitleDownloadDestinationStore.getString(LastSubtitleDownloadDirectoryKey)
            }.getOrNull(),
        )
        // The native Windows save dialog, owned by the app window. An unowned dialog (the old
        // JFileChooser with a null parent) could open behind the borderless-fullscreen player,
        // leaving the download "in progress" with every download button disabled.
        val owner = subtitleDialogOwner()
        subtitleDownloadLog.i { "Opening save dialog (owner=${owner?.javaClass?.simpleName ?: "none"}) in $initialDirectory" }
        val dialog = when (owner) {
            is Frame -> FileDialog(owner, "Save subtitle", FileDialog.SAVE)
            is Dialog -> FileDialog(owner, "Save subtitle", FileDialog.SAVE)
            else -> FileDialog(null as Frame?, "Save subtitle", FileDialog.SAVE)
        }.apply {
            directory = initialDirectory.absolutePath
            file = "$safeBase.$extension"
        }
        dialog.isVisible = true
        val chosenDirectory = dialog.directory?.let(::File)
        val chosenName = dialog.file
        dialog.dispose()
        // Preserve the directory the dialog was left in even when the user cancels after
        // browsing. The next subtitle dialog therefore opens exactly where they left it.
        rememberSubtitleSaveDirectory(chosenDirectory)
        if (chosenDirectory == null || chosenName.isNullOrBlank()) return@withContext null
        val selected = subtitleDestinationWithExtension(chosenDirectory.resolve(chosenName), extension)
        // The native dialog already asked about replacing the typed name; ask again only when
        // the extension we appended points at a different, existing file.
        if (selected.name != chosenName && !confirmOverwriteOnSwingThread(selected)) return@withContext null
        selected
    }

private fun subtitleDialogOwner(): Window? {
    val active = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
    if (active?.isShowing == true && (active is Frame || active is Dialog)) return active
    // Focus is usually inside the WebView2 HUD (a non-AWT child), so AWT may report no active
    // window; fall back to the largest showing frame, which is the player's app window.
    return Window.getWindows()
        .filter { it.isShowing && it is Frame }
        .maxByOrNull { it.width.toLong() * it.height }
}

internal fun preferredSubtitleSaveDirectory(
    rememberedPath: String?,
    userHome: File = File(System.getProperty("user.home").orEmpty()),
): File {
    rememberedPath
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let(::File)
        ?.normalizedExistingDirectory()
        ?.let { return it }

    userHome.resolve("Downloads")
        .normalizedExistingDirectory()
        ?.let { return it }

    return userHome.normalizedExistingDirectory()
        ?: File(".").toPath().toAbsolutePath().normalize().toFile()
}

private fun File.normalizedExistingDirectory(): File? =
    toPath().toAbsolutePath().normalize().toFile().takeIf(File::isDirectory)

private fun rememberSubtitleSaveDirectory(directory: File?) {
    val normalized = directory?.normalizedExistingDirectory() ?: return
    runCatching {
        subtitleDownloadDestinationStore.putString(
            LastSubtitleDownloadDirectoryKey,
            normalized.absolutePath,
        )
    }
}

private suspend fun confirmOverwrite(target: File): Boolean {
    if (!target.exists()) return true
    return withContext(Dispatchers.Swing) { confirmOverwriteOnSwingThread(target) }
}

private fun confirmOverwriteOnSwingThread(target: File): Boolean {
    if (!target.exists()) return true
    return JOptionPane.showConfirmDialog(
        subtitleDialogOwner(),
        "${target.name} already exists. Replace it?",
        localizedString(Res.string.subtitle_download_replace_subtitle),
        JOptionPane.YES_NO_OPTION,
        JOptionPane.WARNING_MESSAGE,
    ) == JOptionPane.YES_OPTION
}

private fun writeSubtitleAtomically(target: File, bytes: ByteArray) {
    val destination = target.toPath().toAbsolutePath().normalize()
    val parent = destination.parent ?: error("The selected destination has no parent directory.")
    require(Files.isDirectory(parent)) { "The selected destination directory does not exist." }
    val temporary = Files.createTempFile(parent, ".${destination.fileName}-", ".tmp")
    try {
        Files.write(temporary, bytes)
        runCatching {
            Files.move(
                temporary,
                destination,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.recoverCatching {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
        }.getOrThrow()
    } catch (error: Throwable) {
        Files.deleteIfExists(temporary)
        throw error
    }
}
