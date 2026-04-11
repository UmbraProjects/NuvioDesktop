package com.nuvio.app.features.downloads

internal expect object DownloadsClock {
    fun nowEpochMs(): Long
}
