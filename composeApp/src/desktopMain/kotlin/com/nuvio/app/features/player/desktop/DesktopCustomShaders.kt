package com.nuvio.app.features.player.desktop

import java.io.File
import java.net.URI

/**
 * Resolves user-provided mpv shader libraries into the active `glsl-shaders` string.
 *
 * Each non-empty, non-comment line can point at a `.glsl`/`.hook` file or a directory.
 * Directories expose their immediate shader children as a small user-managed catalog. Playback
 * uses only the selected shader file, so a dropped shader pack does not accidentally become a
 * giant active chain.
 */
internal object DesktopCustomShaders {
    private val supportedExtensions = setOf("glsl", "hook")

    fun shaderChain(pathsText: String, selectedPath: String): String {
        val available = availableShaders(pathsText)
        return available.firstOrNull { it.path == selectedPath }?.path
            ?: available.firstOrNull()?.path
            ?: ""
    }

    fun availableShaders(pathsText: String): List<CustomShaderFile> {
        val seen = linkedSetOf<String>()
        return pathsText
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .flatMap { entry -> entry.toShaderFiles().asSequence() }
            .filter { seen.add(it.absolutePath) }
            .map { file ->
                CustomShaderFile(
                    path = file.absolutePath,
                    label = file.nameWithoutExtension.ifBlank { file.name },
                    fileName = file.name,
                )
            }
            .toList()
    }

    private fun String.toLocalFile(): File? {
        val unquoted = trim().trim('"', '\'')
        if (unquoted.isBlank()) return null
        if (unquoted.startsWith("file:", ignoreCase = true)) {
            return runCatching { File(URI(unquoted)) }.getOrNull()
        }
        return File(unquoted)
    }

    private fun String.toShaderFiles(): List<File> {
        val file = toLocalFile() ?: return emptyList()
        return when {
            file.isSupportedShader() -> listOf(file)
            file.isDirectory -> file.listFiles()
                ?.filter { it.isSupportedShader() }
                ?.sortedBy { it.name.lowercase() }
                .orEmpty()
            else -> emptyList()
        }
    }

    private fun File.isSupportedShader(): Boolean =
        isFile && extension.lowercase() in supportedExtensions

    data class CustomShaderFile(
        val path: String,
        val label: String,
        val fileName: String,
    )
}
