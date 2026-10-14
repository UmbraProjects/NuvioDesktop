package com.nuvio.app.features.streams

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor

internal actual fun streamPickerResizeCursor(edge: StreamPickerResizeEdge): PointerIcon = PointerIcon(
    Cursor(
        when (edge) {
            StreamPickerResizeEdge.Left, StreamPickerResizeEdge.Right -> Cursor.E_RESIZE_CURSOR
            StreamPickerResizeEdge.Top, StreamPickerResizeEdge.Bottom -> Cursor.N_RESIZE_CURSOR
            StreamPickerResizeEdge.TopLeft, StreamPickerResizeEdge.BottomRight -> Cursor.NW_RESIZE_CURSOR
            StreamPickerResizeEdge.TopRight, StreamPickerResizeEdge.BottomLeft -> Cursor.NE_RESIZE_CURSOR
        },
    ),
)
