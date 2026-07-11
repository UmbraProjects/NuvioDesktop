package com.nuvio.app.features.locallibrary

/**
 * Walks a configured folder and groups its video files into [LocalMediaItem]s.
 * Desktop actual uses java.io.File; there is no other compiled target for this fork.
 */
internal expect object FolderScanner {
    suspend fun scan(folder: LocalFolder): LocalScanResult
}

/** Opens the native OS directory chooser and returns the chosen absolute path, or null if cancelled. */
internal expect object LocalDirectoryPicker {
    suspend fun pick(): String?
}
