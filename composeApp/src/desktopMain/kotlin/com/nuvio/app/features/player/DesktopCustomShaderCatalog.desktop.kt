package com.nuvio.app.features.player

import com.nuvio.app.features.player.desktop.DesktopCustomShaders

internal actual object DesktopCustomShaderCatalog {
    actual fun availableShaders(pathsText: String): List<DesktopCustomShaderOption> =
        DesktopCustomShaders.availableShaders(pathsText).map { shader ->
            DesktopCustomShaderOption(
                path = shader.path,
                label = shader.label,
                fileName = shader.fileName,
            )
        }
}
