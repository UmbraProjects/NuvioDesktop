package com.nuvio.app.features.streams

import androidx.compose.ui.input.pointer.PointerIcon

/** The edge or corner of the modal source picker a resize handle drags. */
internal enum class StreamPickerResizeEdge(val dx: Int, val dy: Int) {
    Left(-1, 0), Right(1, 0), Top(0, -1), Bottom(0, 1),
    TopLeft(-1, -1), TopRight(1, -1), BottomLeft(-1, 1), BottomRight(1, 1),
}

internal expect fun streamPickerResizeCursor(edge: StreamPickerResizeEdge): PointerIcon
