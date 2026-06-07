package com.nuvio.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.PlatformPlayerSurface

fun main() = application {
    val smokePlayerUrl = (
        System.getProperty("nuvio.desktop.smokePlayerUrl")
            ?: System.getenv("NUVIO_DESKTOP_SMOKE_PLAYER_URL")
        )
        ?.takeIf { it.isNotBlank() }

    Window(
        onCloseRequest = ::exitApplication,
        title = if (smokePlayerUrl == null) "Nuvio" else "Nuvio Player Smoke",
        state = WindowState(width = 1280.dp, height = 820.dp),
    ) {
        if (smokePlayerUrl == null) {
            App()
        } else {
            var error by remember { mutableStateOf<String?>(null) }
            PlatformPlayerSurface(
                sourceUrl = smokePlayerUrl,
                modifier = Modifier.fillMaxSize(),
                onControllerReady = {},
                onSnapshot = {},
                onError = { error = it },
            )
            error?.let { println("Nuvio desktop player smoke error: $it") }
        }
    }
}
