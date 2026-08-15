package com.nuvio.app.features.player

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.filechooser.FileNameExtensionFilter

private const val SubtitleDownloadDestinationStoreName = "nuvio_addon_subtitle_download"
private const val LastSubtitleDownloadDirectoryKey = "last_directory"
private val subtitleDownloadDestinationStore by lazy {
    DesktopStorage.store(SubtitleDownloadDestinationStoreName)
}

actual object AddonSubtitleDownloadProvider {
    actual suspend fun download(request: AddonSubtitleDownloadRequest): AddonSubtitleDownloadResult {
        val downloaded = withContext(Dispatchers.IO) {
            downloadSubtitleFile(request.subtitleUrl)
        } ?: return AddonSubtitleDownloadResult.Failed("The provider did not return a valid subtitle file.")

        val localMedia = withContext(Dispatchers.IO) {
            resolveLocalMediaFile(request.activeMediaSource)
        }
        val automaticTarget = localMedia
            ?.parentFile
            ?.takeIf { it.isDirectory && it.canWrite() }
            ?.resolve("${localMedia.nameWithoutExtension}.${downloaded.extension}")

        val target = if (automaticTarget != null) {
            if (!confirmOverwrite(automaticTarget)) return AddonSubtitleDownloadResult.Cancelled
            automaticTarget
        } else {
            chooseSubtitleDestination(
                suggestedBaseName = request.suggestedBaseName,
                extension = downloaded.extension,
            ) ?: return AddonSubtitleDownloadResult.Cancelled
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                writeSubtitleAtomically(target, downloaded.bytes)
                AddonSubtitleDownloadResult.Saved(
                    path = target.absolutePath,
                    savedBesideMedia = automaticTarget != null,
                )
            }.getOrElse { error ->
                AddonSubtitleDownloadResult.Failed(error.message ?: "The subtitle could not be written.")
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
        val chooser = JFileChooser(initialDirectory).apply {
            dialogTitle = "Save subtitle"
            fileFilter = FileNameExtensionFilter("${extension.uppercase()} subtitle (*.$extension)", extension)
            selectedFile = initialDirectory.resolve("$safeBase.$extension")
        }
        val result = chooser.showSaveDialog(null)
        // Preserve the directory the chooser was left in even when the user cancels after
        // browsing. The next subtitle dialog therefore opens exactly where they left it.
        rememberSubtitleSaveDirectory(chooser.currentDirectory)
        if (result != JFileChooser.APPROVE_OPTION) return@withContext null
        val selected = subtitleDestinationWithExtension(chooser.selectedFile, extension)
        rememberSubtitleSaveDirectory(selected.parentFile)
        if (!confirmOverwriteOnSwingThread(selected)) return@withContext null
        selected
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
        null,
        "${target.name} already exists. Replace it?",
        "Replace subtitle?",
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
