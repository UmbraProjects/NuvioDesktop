package com.nuvio.app.features.player

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

actual object LocalFileDrop {
    actual val events: SharedFlow<String> = MutableSharedFlow<String>().asSharedFlow()
}
