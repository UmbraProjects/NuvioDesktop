package com.nuvio.app

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.unit.dp

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Nuvio",
        state = WindowState(width = 1280.dp, height = 820.dp),
    ) {
        App()
    }
}
