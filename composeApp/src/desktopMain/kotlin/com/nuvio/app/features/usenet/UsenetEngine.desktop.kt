package com.nuvio.app.features.usenet

import com.nuvio.app.core.storage.DesktopStorage
import java.io.File

private const val ENGINE_EXE = "nuvio-usenet.exe"

/** Beside Nuvio.exe in a built app; on PATH in a development run (build/native/windows is put there). */
internal actual fun usenetEngineExecutable(): File? {
    val dirs = buildList {
        System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }?.let { File(it).parentFile }?.let(::add)
        System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
            .map(String::trim).filter(String::isNotEmpty).forEach { add(File(it)) }
    }
    return dirs.asSequence().map { File(it, ENGINE_EXE) }.firstOrNull { it.isFile }
}

/** Next to the account's stores, so a portable install keeps it in its own folder. */
internal actual fun usenetNzbCacheDir(): File =
    DesktopStorage.storeFile("nuvio_usenet").toFile().parentFile.resolve("cache/usenet-nzb")
