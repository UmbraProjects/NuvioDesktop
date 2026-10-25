package com.nuvio.app.core.build

import java.lang.management.ManagementFactory
import java.nio.file.Path

/**
 * The app class-data archive the packaged launcher asks for (`-XX:+AutoCreateSharedArchive
 * -XX:SharedArchiveFile=…`, see build.gradle.kts), as seen from inside this process.
 *
 * Every launch is one of three kinds:
 *  - [managed] false: a Gradle or IDE run, no archive involved;
 *  - [building]: the archive is missing or stale (first run, an update, a new JDK), so the JVM is
 *    recording this run and writes a fresh archive at exit — a launch in which defining each class
 *    is several times slower than normal;
 *  - managed and not building: the archive is mapped and classes come from it pre-parsed.
 */
internal object AppClassArchive {

    private val inputArguments: List<String> by lazy {
        runCatching { ManagementFactory.getRuntimeMXBean().inputArguments }.getOrDefault(emptyList())
    }

    val managed: Boolean by lazy { "-XX:+AutoCreateSharedArchive" in inputArguments }

    /**
     * From the JDK itself; needs `--add-exports java.base/jdk.internal.misc` (in the launcher
     * flags). Without it, or on a JDK without the call, this answers false.
     */
    val building: Boolean by lazy {
        managed && runCatching {
            Class.forName("jdk.internal.misc.CDS").getMethod("isDumpingArchive").invoke(null) as Boolean
        }.getOrDefault(false)
    }

    val archiveFile: Path? by lazy {
        inputArguments.firstOrNull { it.startsWith("-XX:SharedArchiveFile=") }
            ?.substringAfter('=')
            ?.let { runCatching { Path.of(it) }.getOrNull() }
    }
}
