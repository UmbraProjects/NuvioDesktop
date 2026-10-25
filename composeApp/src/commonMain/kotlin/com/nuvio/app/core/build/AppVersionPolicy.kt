package com.nuvio.app.core.build

expect object AppVersionPolicy {
    val displayVersionName: String
    val displayVersionCode: Int
    val basedOnVersionName: String?

    /**
     * Identity of the packaged build that is running, or null when this isn't a packaged run
     * (a `gradlew run` launch has no app image to read the stamp from).
     */
    val packagedBuild: PackagedBuild?
}

/**
 * The build stamp written into the app image when the distributable was created.
 *
 * The version name and code only move when someone edits DesktopVersion.properties, so they
 * cannot tell two builds of the same version apart — which is exactly what a user needs to see
 * to know an update actually landed. [id] changes with every packaged build.
 */
data class PackagedBuild(
    val id: String,
    val buildTime: String? = null,
    val gitCommit: String? = null,
    val channel: String? = null,
) {
    /** Short UTC label for display: "2026-08-25 21:14", or the raw build id when the time is absent. */
    val label: String
        get() = buildTime
            ?.take(16)
            ?.takeIf { it.length == 16 }
            ?.replace('T', ' ')
            ?: id

    /**
     * Whether this image was packaged for the nightly channel.
     *
     * Only the packaging invocation knows this — the same commit ships as a nightly and later as
     * the stable release — so an unstamped or unmarked build is treated as stable.
     */
    val isNightly: Boolean get() = channel.equals("nightly", ignoreCase = true)
}
