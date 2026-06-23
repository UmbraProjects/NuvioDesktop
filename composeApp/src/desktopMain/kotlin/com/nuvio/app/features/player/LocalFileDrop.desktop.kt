package com.nuvio.app.features.player

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

val VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v",
    "mpg", "mpeg", "ts", "m2ts", "mts", "vob", "ogv", "3gp",
    "rm", "rmvb", "divx", "xvid", "asf", "f4v", "m2v",
)

actual object LocalFileDrop {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    actual val events: SharedFlow<String> = _events.asSharedFlow()

    fun emit(fileUri: String) {
        _events.tryEmit(fileUri)
    }
}
