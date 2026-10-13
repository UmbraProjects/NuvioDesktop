package com.nuvio.app.features.setup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.PackagedResources
import com.nuvio.app.features.home.HomeDisplayMode
import org.jetbrains.skia.Image

@Composable
internal actual fun setupModeScreenshotPainter(mode: HomeDisplayMode): Painter =
    remember(mode) {
        val path = "setup/${mode.screenshotFileName()}"
        runCatching {
            val bytes = checkNotNull(PackagedResources.open(path)) { "Missing $path" }.use { it.readBytes() }
            BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
        }.getOrElse { error ->
            Logger.withTag("SetupModeScreenshots").w(error) { "Could not load $path" }
            ColorPainter(Color.Transparent)
        }
    }

private fun HomeDisplayMode.screenshotFileName(): String = when (this) {
    HomeDisplayMode.Basic -> "setup_mode_basic.webp"
    HomeDisplayMode.Adaptive -> "setup_mode_adaptive.webp"
    HomeDisplayMode.AdaptiveAmbient -> "setup_mode_adaptive_ambient.webp"
    HomeDisplayMode.TvMode -> "setup_mode_tv.webp"
}
