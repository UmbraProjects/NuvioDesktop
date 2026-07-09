package com.nuvio.app.features.player

data class DesktopCustomShaderOption(
    val path: String,
    val label: String,
    val fileName: String,
)

internal expect object DesktopCustomShaderCatalog {
    fun availableShaders(pathsText: String): List<DesktopCustomShaderOption>
}
