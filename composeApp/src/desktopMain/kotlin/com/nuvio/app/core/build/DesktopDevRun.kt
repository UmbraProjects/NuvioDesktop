package com.nuvio.app.core.build

/**
 * True for a Gradle `run` (no jpackage launcher), or when `-Dnuvio.dev.localNatives=true` is
 * passed explicitly. Only then may the app look for native binaries under `build/`, `vendor/` and
 * similar paths. Those paths are relative to the working directory, which for a packaged app is
 * whatever the launcher chose (a shortcut's "Start in", a prompt, another program); a planted
 * `build\native\windows\player_bridge.dll` there would otherwise be loaded into the process.
 */
internal object DesktopDevRun {
    val allowsLocalNativeBuilds: Boolean by lazy {
        System.getProperty("jpackage.app-path").isNullOrBlank() ||
            System.getProperty("nuvio.dev.localNatives").toBoolean()
    }
}
