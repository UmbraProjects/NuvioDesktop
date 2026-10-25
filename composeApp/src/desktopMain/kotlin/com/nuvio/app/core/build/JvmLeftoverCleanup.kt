package com.nuvio.app.core.build

import co.touchlab.kermit.Logger
import java.io.File

/**
 * Deletes what a JVM build left in the install folder, once this install runs the native build.
 *
 * A native image can never load a jar, a class archive or the JVM, so in a native install these are
 * dead weight with no exceptions: `app\*.jar`, `app\*.jsa`, and the JVM runtime's own files under
 * `runtime\` (`bin\server\`, `lib\modules`, `legal\`, `conf\`, `release`). The native build's
 * `runtime\` files (jawt and the MSVC runtime in `bin\`, the font config in `lib\`) are none of
 * those, so they stay.
 *
 * The updater's stale-file pass is not enough on its own, because the build performing an update is
 * the old one. The JVM build moving an install to native only removes what its manifest listed, and
 * jars from updates unzipped by hand were in no manifest: one user's `app` folder had reached 1.4 GB
 * (2026-10-17). Development build folders (`dev-build` marker) are left alone.
 */
internal object JvmLeftoverCleanup {
    private val log = Logger.withTag("JvmLeftoverCleanup")

    private val runtimeLeftovers = listOf("bin/server", "lib/modules", "legal", "conf", "release")

    fun startIfNative() {
        if (!NativeImageSupport.inImage) return
        val installRoot = runCatching {
            ProcessHandle.current().info().command().orElse(null)?.let { File(it).parentFile }
        }.getOrNull() ?: return
        if (File(installRoot, "dev-build").isFile) return
        Thread {
            runCatching { clean(installRoot) }
                .onFailure { error -> log.w(error) { "JVM leftover cleanup failed" } }
        }.apply { name = "nuvio-jvm-leftover-cleanup"; isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /** Returns the bytes freed. Visible for tests. */
    internal fun clean(installRoot: File): Long {
        val targets = buildList {
            File(installRoot, "app").listFiles { file ->
                file.isFile && (file.extension.equals("jar", ignoreCase = true) || file.extension.equals("jsa", ignoreCase = true))
            }?.let(::addAll)
            runtimeLeftovers.map { File(installRoot, "runtime/$it") }.filter(File::exists).forEach(::add)
        }
        if (targets.isEmpty()) return 0L
        var freed = 0L
        var files = 0
        targets.forEach { target ->
            target.walkBottomUp().forEach { file ->
                val size = if (file.isFile) file.length() else 0L
                if (file.delete() && size > 0) {
                    freed += size
                    files++
                }
            }
        }
        log.i { "Removed $files files the JVM build left behind (${freed / (1024 * 1024)} MB)" }
        return freed
    }
}
