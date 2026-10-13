package com.nuvio.app.core.build

import java.io.File
import java.io.InputStream

/**
 * Large, rarely-changing resources that a packaged build ships as plain files under
 * `<install>\resources\` instead of inside the app jar.
 *
 * The app jar changes every release, so everything inside it is re-downloaded on every update,
 * while a loose file only is when it actually changed. The build (`externalDesktopResources` in
 * build.gradle.kts) excludes these paths from the jar and stages them here with the same relative
 * path, so callers keep using the classpath path they always had.
 *
 * Lookup order: the install folder, then the classpath (a self-contained jar built with
 * `-Pnuvio.desktop.leanJar=false`, or a `gradlew run`), then — for dev runs only, see
 * [DesktopDevRun] — the source tree.
 */
internal object PackagedResources {
    private val installResourcesDir: File? by lazy {
        System.getProperty("jpackage.app-path")
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it).parentFile?.resolve("resources") }
    }

    /** [path] is a classpath-style relative path such as `backdrop-framing/u2netp.onnx`. */
    fun open(path: String): InputStream? {
        val relative = path.removePrefix("/")
        installResourcesDir?.resolve(relative)?.takeIf(File::isFile)?.let { return it.inputStream() }
        PackagedResources::class.java.getResourceAsStream("/$relative")?.let { return it }
        if (!DesktopDevRun.allowsLocalNativeBuilds) return null
        return listOf("composeApp/src/desktopMain/resources", "src/desktopMain/resources")
            .map { File(it, relative) }
            .firstOrNull(File::isFile)
            ?.inputStream()
    }
}
