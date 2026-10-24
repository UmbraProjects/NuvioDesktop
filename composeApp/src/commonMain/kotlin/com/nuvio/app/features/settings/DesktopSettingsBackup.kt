package com.nuvio.app.features.settings

internal sealed interface DesktopSettingsBackupResult {
    data class Saved(val path: String) : DesktopSettingsBackupResult
    data class Failed(val message: String) : DesktopSettingsBackupResult
    data object Cancelled : DesktopSettingsBackupResult
}

internal sealed interface DesktopSettingsImportResult {
    /** The backup is staged; [restarting] is false when Nuvio could not relaunch itself. */
    data class Staged(val restarting: Boolean) : DesktopSettingsImportResult
    data class Failed(val message: String) : DesktopSettingsImportResult
    data object Cancelled : DesktopSettingsImportResult
}

internal expect object DesktopSettingsBackup {
    fun create(includeCredentials: Boolean): DesktopSettingsBackupResult
    fun import(): DesktopSettingsImportResult
}
