package com.nuvio.app.features.music

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * fork:music
 *
 * Glyphs the Music pages need and Material has no fitting one for. Drawn like the settings sidebar's own
 * ([com.nuvio.app.features.settings.SettingsCategoryIcons]): the 24-unit grid, a 2-unit round stroke, black so
 * [androidx.compose.material3.Icon] tints them.
 */
internal object MusicIcons {

    /** A record whose grooves are a sound wave: the album as a lossless file from the person's addon. */
    val Lossless: ImageVector by lazy {
        val builder = ImageVector.Builder(
            name = "MusicLossless",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        listOf(
            "M12,3a9,9 0 1 1 0,18a9,9 0 1 1 0,-18z",
            "M8.5,10.5v3",
            "M12,7.5v9",
            "M15.5,10.5v3",
        ).forEach { path ->
            builder.addPath(
                pathData = addPathNodes(path),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        builder.build()
    }
}
