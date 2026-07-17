package com.nuvio.app.features.updater

/** How a downloaded update was applied, so the UI can message the user correctly. */
enum class UpdateInstallOutcome {
    /** Build staged; a helper swaps it in after the app exits, so the app is restarting now. */
    RESTARTING,

    /** The verified archive was revealed for the user to extract over their folder manually. */
    REVEALED,
}

expect object AppUpdaterPlatform {
    val isSupported: Boolean

    fun getSupportedAbis(): List<String>

    fun getIgnoredTag(): String?

    fun setIgnoredTag(tag: String?)

    /** Whether verified updates are applied in place (and the app restarted) automatically. */
    fun isInPlaceUpdateEnabled(): Boolean

    fun setInPlaceUpdateEnabled(enabled: Boolean)

    suspend fun downloadApk(
        assetUrl: String,
        assetName: String,
        expectedSha256: String?,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<String>

    fun canRequestPackageInstalls(): Boolean

    fun openUnknownSourcesSettings()

    fun installDownloadedApk(path: String): Result<UpdateInstallOutcome>
}