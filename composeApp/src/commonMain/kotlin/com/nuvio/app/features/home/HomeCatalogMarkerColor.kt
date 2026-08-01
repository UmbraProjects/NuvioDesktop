package com.nuvio.app.features.home

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Optional bookmark colour applied to a catalog's TV Mode row dot.
 *
 * Stable serialized names keep local preferences and account sync independent of enum renames.
 */
@Serializable
enum class HomeCatalogMarkerColor(
    internal val storageValue: String,
    internal val accessibleName: String,
) {
    @SerialName("red")
    Red("red", "Red"),

    @SerialName("orange")
    Orange("orange", "Orange"),

    @SerialName("yellow")
    Yellow("yellow", "Yellow"),

    @SerialName("green")
    Green("green", "Green"),

    @SerialName("cyan")
    Cyan("cyan", "Cyan"),

    @SerialName("blue")
    Blue("blue", "Blue"),

    @SerialName("purple")
    Purple("purple", "Purple"),

    @SerialName("pink")
    Pink("pink", "Pink"),
    ;

    companion object {
        internal fun fromStorageValue(value: String): HomeCatalogMarkerColor? =
            entries.firstOrNull { it.storageValue == value.trim().lowercase() }
    }
}

internal val HomeCatalogMarkerColor.composeColor: Color
    get() = when (this) {
        HomeCatalogMarkerColor.Red -> Color(0xFFFF5D73)
        HomeCatalogMarkerColor.Orange -> Color(0xFFFF9F43)
        HomeCatalogMarkerColor.Yellow -> Color(0xFFFFD166)
        HomeCatalogMarkerColor.Green -> Color(0xFF55D98A)
        HomeCatalogMarkerColor.Cyan -> Color(0xFF45C9D0)
        HomeCatalogMarkerColor.Blue -> Color(0xFF5B8DEF)
        HomeCatalogMarkerColor.Purple -> Color(0xFFA56EFF)
        HomeCatalogMarkerColor.Pink -> Color(0xFFEF6DB5)
    }

internal val HomeCatalogMarkerColor.prefersDarkForeground: Boolean
    get() = this == HomeCatalogMarkerColor.Yellow
