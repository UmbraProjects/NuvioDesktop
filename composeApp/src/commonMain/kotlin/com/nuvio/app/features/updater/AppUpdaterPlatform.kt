package com.nuvio.app.features.updater

/** How a downloaded update was applied, so the UI can message the user correctly. */
enum class UpdateInstallOutcome {
    /** Build staged; a helper swaps it in after the app exits, so the app is restarting now. */
    RESTARTING,

    /** The verified archive was revealed for the user to extract over their folder manually. */
    REVEALED,
}

/** Which GitHub release the in-app updater follows. */
enum class UpdateChannel {
    /** The newest published, non-prerelease release. */
    Stable,

    /** The rolling "Nightly" prerelease, re-uploaded under one tag. */
    Nightly,
}

/**
 * Identity of the nightly build currently installed.
 *
 * Every nightly ships under the same tag and the same version name, so neither can say whether a
 * given nightly is already installed. The release asset's checksum can, and [publishedAt] gives the
 * build a human-readable name for the About row and for bug reports.
 */
data class InstalledNightlyBuild(
    val id: String,
    val publishedAt: String? = null,
) {
    /** Short label for display: the upload date when known, else a checksum prefix. */
    val label: String get() = publishedAt?.take(10)?.takeIf { it.isNotBlank() } ?: id.take(7)
}

expect object AppUpdaterPlatform {
    val isSupported: Boolean

    fun getSupportedAbis(): List<String>

    /** The release channel the updater follows; defaults to [UpdateChannel.Stable]. */
    fun getUpdateChannel(): UpdateChannel

    fun setUpdateChannel(channel: UpdateChannel)

    /** The installed nightly build, or null when this install came from the stable channel. */
    fun getInstalledNightlyBuild(): InstalledNightlyBuild?

    fun setInstalledNightlyBuild(build: InstalledNightlyBuild?)

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