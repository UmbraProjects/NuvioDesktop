package com.nuvio.app.features.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Sidebar glyphs for the settings categories that had no Material icon of their own and were
 * sharing one (Tune for Details, Streams and Advanced; CloudDownload for both download pages), plus
 * Video & Audio. Drawn on the Material 24-unit grid with a 2-unit round stroke, the weight of the
 * line icons beside them (Tune, Link), and a solid accent shape in each so they hold their own next
 * to the filled ones. Black like the Material set; [androidx.compose.material3.Icon] tints them.
 */
internal object SettingsCategoryIcons {

    /** A poster beside lines of text: the page a title opens on. */
    val Details: ImageVector by lazy {
        icon("SettingsDetails") {
            stroke("M6,4h3a2,2 0 0 1 2,2v12a2,2 0 0 1 -2,2H6a2,2 0 0 1 -2,-2V6a2,2 0 0 1 2,-2z")
            fill("M6.5,7.5h2a0.5,0.5 0 0 1 0.5,0.5v3a0.5,0.5 0 0 1 -0.5,0.5h-2a0.5,0.5 0 0 1 -0.5,-0.5v-3a0.5,0.5 0 0 1 0.5,-0.5z")
            stroke("M14.5,7h5.5")
            stroke("M14.5,11h5.5")
            stroke("M14.5,15h3.5")
        }
    }

    /** A play mark sending out waves: sources being found and streamed. */
    val Streams: ImageVector by lazy {
        icon("SettingsStreams") {
            fill("M4,7.3v9.4a1.3,1.3 0 0 0 2,1.1l7.1,-4.7a1.3,1.3 0 0 0 0,-2.2L6,6.2a1.3,1.3 0 0 0 -2,1.1z")
            stroke("M16,8.5a5,5 0 0 1 0,7")
            stroke("M19,5.5a9.2,9.2 0 0 1 0,13")
        }
    }

    /** A terminal prompt: the under-the-hood options. */
    val Advanced: ImageVector by lazy {
        icon("SettingsAdvanced") {
            stroke("M5,4h14a2,2 0 0 1 2,2v12a2,2 0 0 1 -2,2H5a2,2 0 0 1 -2,-2V6a2,2 0 0 1 2,-2z")
            stroke("M7.5,9.5l3,2.5l-3,2.5")
            fill("M13,14h4a1,1 0 0 1 0,2h-4a1,1 0 0 1 0,-2z")
        }
    }

    /** A download arrow inside a cycling loop: grabs that run on their own schedule. */
    val AutoDownloads: ImageVector by lazy {
        icon("SettingsAutoDownloads") {
            stroke("M19.8,13.2A8,8 0 1 1 17.7,6.3")
            stroke("M18.2,2.8v3.9h-3.9")
            stroke("M12,8v7")
            stroke("M9,12.3l3,3l3,-3")
        }
    }

    /** An arrow landing in a tray. */
    val Downloads: ImageVector by lazy {
        icon("SettingsDownloads") {
            stroke("M12,4v10.5")
            stroke("M7.5,10.5l4.5,4.5l4.5,-4.5")
            stroke("M4,15.5v2.5a2,2 0 0 0 2,2h12a2,2 0 0 0 2,-2v-2.5")
        }
    }

    /** A screen with a sound wave across it: picture and sound together. */
    val VideoAudio: ImageVector by lazy {
        icon("SettingsVideoAudio") {
            stroke("M4,4h16a2,2 0 0 1 2,2v9a2,2 0 0 1 -2,2H4a2,2 0 0 1 -2,-2V6a2,2 0 0 1 2,-2z")
            stroke("M8.5,20.5h7")
            stroke("M7.5,9.5v2")
            stroke("M10.5,8v5")
            stroke("M13.5,7v7")
            stroke("M16.5,9v3")
        }
    }

    /** A page laid out as a hero over a row of tiles: the home screen layout. */
    val Homescreen: ImageVector by lazy {
        icon("SettingsHomescreen") {
            fill("M6,4h12a2,2 0 0 1 2,2v3.5H4V6a2,2 0 0 1 2,-2z")
            stroke("M6,4h12a2,2 0 0 1 2,2v12a2,2 0 0 1 -2,2H6a2,2 0 0 1 -2,-2V6a2,2 0 0 1 2,-2z")
            stroke("M4,9.5h16")
            stroke("M9.5,13v4")
            stroke("M14.5,13v4")
        }
    }

    /** A play mark inside a turning-back arrow: pick up where you left off. */
    val ContinueWatching: ImageVector by lazy {
        icon("SettingsContinueWatching") {
            stroke("M4.6,14.5A8,8 0 1 0 6.3,6.3")
            stroke("M5.8,2.8v3.9h3.9")
            fill("M10,9.6v4.8a0.8,0.8 0 0 0 1.2,0.7l3.9,-2.4a0.8,0.8 0 0 0 0,-1.4l-3.9,-2.4a0.8,0.8 0 0 0 -1.2,0.7z")
        }
    }
}

private class IconPaths(val builder: ImageVector.Builder) {
    fun stroke(pathData: String) {
        builder.addPath(
            pathData = addPathNodes(pathData),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }

    fun fill(pathData: String) {
        builder.addPath(
            pathData = addPathNodes(pathData),
            fill = SolidColor(Color.Black),
            pathFillType = PathFillType.NonZero,
        )
    }
}

private fun icon(name: String, paths: IconPaths.() -> Unit): ImageVector {
    val builder = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
    IconPaths(builder).paths()
    return builder.build()
}
