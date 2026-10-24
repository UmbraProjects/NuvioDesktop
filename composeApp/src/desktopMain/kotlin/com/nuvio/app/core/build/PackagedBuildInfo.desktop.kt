package com.nuvio.app.core.build

import java.io.File
import java.util.Properties

/**
 * Reads the build stamp the packaging task wrote beside the application jars.
 *
 * The file sits in the app image's `app/` directory, which is also where this class's own jar
 * lives, so the running build is located from its own code source rather than from a guess about
 * the install layout. An in-place update replaces that directory wholesale, so the stamp always
 * describes the code that is actually executing — including a build the user dropped in by hand,
 * which is the case the version name alone could never show.
 */
internal object PackagedBuildInfo {
    val current: PackagedBuild? by lazy { read() }

    private fun read(): PackagedBuild? {
        val file = locate() ?: return null
        val props = runCatching {
            Properties().apply { file.inputStream().use { load(it) } }
        }.getOrNull() ?: return null
        val id = props.getProperty("BUILD_ID")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return PackagedBuild(
            id = id,
            buildTime = props.getProperty("BUILD_TIME")?.trim()?.takeIf { it.isNotBlank() },
            gitCommit = props.getProperty("GIT_COMMIT")?.trim()?.takeIf { it.isNotBlank() },
            channel = props.getProperty("CHANNEL")?.trim()?.takeIf { it.isNotBlank() },
        )
    }

    private fun locate(): File? {
        val codeSource = runCatching {
            PackagedBuildInfo::class.java.protectionDomain?.codeSource?.location?.toURI()?.let(::File)
        }.getOrNull()
        // A packaged run resolves to <install>/app/<jar>; a `gradlew run` launch resolves to a
        // classes directory, where no stamp exists and callers fall back to the version alone.
        val appDir = codeSource?.let { if (it.isFile) it.parentFile else it } ?: return null
        return appDir.resolve("build-info.properties").takeIf(File::isFile)
    }
}
