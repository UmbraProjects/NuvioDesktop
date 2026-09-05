import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

abstract class GenerateRuntimeConfigsTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Optional
    @get:InputFile
    abstract val localPropertiesFile: RegularFileProperty

    @get:Input
    abstract val appVersionName: Property<String>

    @get:Input
    abstract val appVersionCode: Property<Int>

    @get:Input
    abstract val desktopAppVersionName: Property<String>

    @get:Input
    abstract val desktopAppVersionCode: Property<Int>

    @TaskAction
    fun generate() {
        val props = Properties()
        localPropertiesFile.asFile.orNull?.takeIf { it.exists() }?.inputStream()?.use { props.load(it) }

        val outDir = outputDir.get().asFile
        outDir.resolve("com/nuvio/app/core/network").apply {
            mkdirs()
            resolve("SupabaseConfig.kt").writeText(
                """
                |package com.nuvio.app.core.network
                |
                |object SupabaseConfig {
                |    const val URL = "${props.getProperty("SUPABASE_URL", "")}" 
                |    const val ANON_KEY = "${props.getProperty("SUPABASE_ANON_KEY", "")}" 
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/tmdb/TmdbConfig.kt").delete()

        outDir.resolve("com/nuvio/app/features/trakt").apply {
            mkdirs()
            resolve("TraktConfig.kt").writeText(
                """
                |package com.nuvio.app.features.trakt
                |
                |object TraktConfig {
                |    const val CLIENT_ID = "" 
                |    const val CLIENT_SECRET = "" 
                |    const val REDIRECT_URI = "http://localhost:53682/callback" 
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/player/skip").apply {
            mkdirs()
            resolve("IntroDbConfig.kt").writeText(
                """
                |package com.nuvio.app.features.player.skip
                |
                |object IntroDbConfig {
                |    const val URL = "${props.getProperty("INTRODB_API_URL", "")}" 
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/details").apply {
            mkdirs()
            resolve("ImdbEpisodeRatingsConfig.kt").writeText(
                """
                |package com.nuvio.app.features.details
                |
                |object ImdbEpisodeRatingsConfig {
                |    const val IMDB_RATINGS_API_BASE_URL = "${props.getProperty("IMDB_RATINGS_API_BASE_URL", "")}" 
                |    const val IMDB_TAPFRAME_API_BASE_URL = "${props.getProperty("IMDB_TAPFRAME_API_BASE_URL", "")}" 
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/debrid").apply {
            mkdirs()
            resolve("PremiumizeConfig.kt").writeText(
                """
                |package com.nuvio.app.features.debrid
                |
                |object PremiumizeConfig {
                |    const val CLIENT_ID = "${props.getProperty("PREMIUMIZE_CLIENT_ID", "")}"
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/core/build").apply {
            mkdirs()
            resolve("AppVersionConfig.kt").writeText(
                """
                |package com.nuvio.app.core.build
                |
                |object AppVersionConfig {
                |    const val VERSION_NAME = "${appVersionName.get()}"
                |    const val VERSION_CODE = ${appVersionCode.get()}
                |    const val DESKTOP_VERSION_NAME = "${desktopAppVersionName.get()}"
                |    const val DESKTOP_VERSION_CODE = ${desktopAppVersionCode.get()}
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/settings").apply {
            mkdirs()
            resolve("CommunityConfig.kt").writeText(
                """
                |package com.nuvio.app.features.settings
                |
                |object CommunityConfig {
                |    const val CONTRIBUTIONS_URL = "${props.getProperty("CONTRIBUTIONS_URL", "")}" 
                |    const val DONATIONS_BASE_URL = "${props.getProperty("DONATIONS_BASE_URL", "")}" 
                |    const val DONATIONS_DONATE_URL = "${props.getProperty("DONATIONS_DONATE_URL", "")}" 
                |}
                """.trimMargin()
            )
        }
    }
}

fun readXcconfigValue(file: File, key: String): String? {
    if (!file.exists()) return null
    return file.readLines()
        .asSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
        .map { line ->
            val separatorIndex = line.indexOf('=')
            line.substring(0, separatorIndex).trim() to line.substring(separatorIndex + 1).trim()
        }
        .firstOrNull { (entryKey, _) -> entryKey == key }
        ?.second
}

fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

fun cmdQuote(value: String): String = "\"${value.replace("\"", "\"\"")}\""

fun psSingleQuote(value: String): String = "'${value.replace("'", "''")}'"

fun semanticVersionSortKey(value: String): String =
    value.split('.', '-', '_')
        .joinToString(".") { part ->
            part.toIntOrNull()?.toString()?.padStart(8, '0') ?: part
        }

fun newestDirectory(root: File): File? =
    root.takeIf(File::exists)
        ?.listFiles(File::isDirectory)
        ?.maxByOrNull { semanticVersionSortKey(it.name) }

fun jpackageCompatibleVersion(version: String): String {
    val versionCore = version.substringBefore('-').substringBefore('+').trim()
    val parts = versionCore.split('.').filter { it.isNotBlank() }
    require(parts.isNotEmpty() && parts.size <= 3) {
        "Desktop package version must use one to three numeric components: $version"
    }
    val numbers = parts.map { part ->
        part.toIntOrNull() ?: error("Desktop package version component is not numeric: $version")
    }.toMutableList()
    require(numbers.all { it >= 0 }) {
        "Desktop package version components must not be negative: $version"
    }
    while (numbers.size < 3) {
        numbers += 0
    }
    numbers[0] = numbers[0].coerceAtLeast(1)
    return numbers.joinToString(".")
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
}

val supabaseProps = Properties().apply {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) propsFile.inputStream().use { load(it) }
}
fun localOrEnvProperty(name: String): String? =
    (
        providers.gradleProperty(name).orNull
            ?: System.getenv(name)
            ?: supabaseProps.getProperty(name)
        )
        ?.trim()
        ?.takeIf { it.isNotBlank() }

val macosSigningIdentity = localOrEnvProperty("NUVIO_MACOS_SIGNING_IDENTITY")
val macosNotaryAppleId = localOrEnvProperty("NUVIO_MACOS_NOTARY_APPLE_ID")
val macosNotaryTeamId = localOrEnvProperty("NUVIO_MACOS_NOTARY_TEAM_ID")
val macosNotaryPassword = localOrEnvProperty("NUVIO_MACOS_NOTARY_PASSWORD")

val desktopVersionConfigFile = rootProject.file("composeApp/Configuration/DesktopVersion.properties")
val desktopVersionProps = Properties().apply {
    if (desktopVersionConfigFile.exists()) {
        desktopVersionConfigFile.inputStream().use { load(it) }
    }
}
val desktopReleaseVersionName = (
    providers.gradleProperty("nuvio.desktop.versionName").orNull
        ?: System.getenv("NUVIO_DESKTOP_VERSION_NAME")
        ?: supabaseProps.getProperty("NUVIO_DESKTOP_VERSION_NAME")
        ?: desktopVersionProps.getProperty("VERSION_NAME")
        ?: "0.1.0"
    ).trim()
require(desktopReleaseVersionName.isNotBlank()) {
    "Desktop version name must not be blank."
}
val desktopReleaseVersionCode = (
    providers.gradleProperty("nuvio.desktop.versionCode").orNull
        ?: System.getenv("NUVIO_DESKTOP_VERSION_CODE")
        ?: supabaseProps.getProperty("NUVIO_DESKTOP_VERSION_CODE")
        ?: desktopVersionProps.getProperty("VERSION_CODE")
    )?.trim()
    ?.takeIf { it.isNotBlank() }
    ?.toIntOrNull()
    ?: 1
// An explicit override for a one-off build; when absent the channel is taken from the app's own
// Update Channel setting at packaging time (see resolveDesktopChannel). Stable releases are a
// couple a month and nightlies are everything else, so the setting this machine already carries is
// a better default than anything a build file could guess.
//   ./gradlew :composeApp:createReleaseDistributable -Pnuvio.desktop.channel=stable
val desktopChannelOverride = (
    providers.gradleProperty("nuvio.desktop.channel").orNull
        ?: System.getenv("NUVIO_DESKTOP_CHANNEL")
        ?: supabaseProps.getProperty("NUVIO_DESKTOP_CHANNEL")
    )
    ?.trim()
    ?.lowercase()
    ?.takeIf { it.isNotBlank() }
require(desktopChannelOverride == null || desktopChannelOverride == "stable" || desktopChannelOverride == "nightly") {
    "Desktop release channel must be either stable or nightly: $desktopChannelOverride"
}
val releaseAppVersionName = desktopReleaseVersionName
val releaseAppVersionCode = desktopReleaseVersionCode
val desktopReleasePackageVersion = jpackageCompatibleVersion(desktopReleaseVersionName)
val fullCommonSourceDir = project.file("src/fullCommonMain/kotlin")
val fullPluginSourceDir = fullCommonSourceDir.resolve("com/nuvio/app/features/plugins")
val generatedRuntimeConfigDir = layout.buildDirectory.dir("generated/runtime-config/kotlin")
val requestedGradleTasks = gradle.startParameter.taskNames.map { taskName ->
    taskName.substringAfterLast(':').lowercase()
}
val isAndroidAppBundleBuild = requestedGradleTasks.any { taskName ->
    taskName == "bundle" ||
        taskName == "bundlerelease" ||
        taskName == "bundledebug" ||
        taskName.startsWith("bundleplaystore") ||
        taskName.startsWith("bundlefull") ||
        taskName.endsWith("bundle")
}

val generateRuntimeConfigs = tasks.register<GenerateRuntimeConfigsTask>("generateRuntimeConfigs") {
    outputDir.set(generatedRuntimeConfigDir)
    localPropertiesFile.set(rootProject.layout.projectDirectory.file("local.properties"))
    appVersionName.set(releaseAppVersionName)
    appVersionCode.set(releaseAppVersionCode)
    desktopAppVersionName.set(desktopReleaseVersionName)
    desktopAppVersionCode.set(desktopReleaseVersionCode)
}

val isMacHost = System.getProperty("os.name").contains("mac", ignoreCase = true)
val isWindowsHost = System.getProperty("os.name").contains("win", ignoreCase = true)
val mpvKitDir = providers.gradleProperty("nuvio.mpvkit.dir")
    .orElse(rootProject.layout.projectDirectory.dir("MPVKit").asFile.absolutePath)
val macosPlayerBridgeSource = layout.projectDirectory.file("src/desktopMain/native/macos/player_bridge.mm")
val macosPlayerBridgeOutput = layout.buildDirectory.file("native/macos/libplayer_bridge.dylib")
val macosPlayerBridgeArch = when (System.getProperty("os.arch").lowercase()) {
    "aarch64", "arm64" -> "arm64"
    else -> "x86_64"
}
val mpvKitRoot = File(mpvKitDir.get())
val mpvKitDistRoot = File(mpvKitRoot, "dist")
val mpvKitLibmpvRoot = File(mpvKitDistRoot, "libmpv/macos/thin/$macosPlayerBridgeArch")
val mpvKitLibmpvPkgConfigFile = File(mpvKitLibmpvRoot, "lib/pkgconfig/mpv.pc")
val mpvKitGeneratedPkgConfigDirs = if (mpvKitDistRoot.exists()) {
    mpvKitDistRoot.walkTopDown()
        .filter { it.isDirectory && it.invariantSeparatorsPath.endsWith("/macos/thin/$macosPlayerBridgeArch/lib/pkgconfig") }
        .toList()
        .sortedBy { it.absolutePath }
} else {
    emptyList()
}
val mpvKitGeneratedLibSearchArgs = mpvKitGeneratedPkgConfigDirs
    .mapNotNull { it.parentFile }
    .distinctBy { it.absolutePath }
    .joinToString(" ") { "-L${shellQuote(it.absolutePath)}" }
val missingMpvKitMacosFrameworks = if (mpvKitLibmpvPkgConfigFile.exists()) emptyList() else listOf("mpv.pc")
val missingMpvKitMacosMessage = """
    MPVKit macOS libmpv artifacts are missing for $macosPlayerBridgeArch: ${missingMpvKitMacosFrameworks.joinToString()}.
    Build MPVKit's macOS runtime first:
      cd ${mpvKitRoot.absolutePath}
      make build platform=macos
    Or pass -Pnuvio.mpvkit.dir=/absolute/path/to/MPVKit.
""".trimIndent()
val missingMpvKitMacosShellMessage = missingMpvKitMacosMessage.replace("'", "'\"'\"'")
val macosPlayerBridgeSourceFile = macosPlayerBridgeSource.asFile
val macosPlayerBridgeOutputFile = macosPlayerBridgeOutput.get().asFile
val macosPlayerBridgeJavaHome = providers.systemProperty("java.home").get()
val mpvKitLibmpvStaticLib = File(mpvKitLibmpvRoot, "lib/libmpv.a")
if (isMacHost) {
    macosPlayerBridgeOutputFile.parentFile.mkdirs()
}
val macosPlayerBridgeCommand = if (missingMpvKitMacosFrameworks.isNotEmpty()) {
    listOf(
        "/bin/sh",
        "-c",
        "printf '%s\\n' '$missingMpvKitMacosShellMessage' >&2; exit 1",
    )
} else {
    mutableListOf(
        "/bin/sh",
        "-c",
        """
        set -eu
        SDKROOT="${'$'}(xcrun --sdk macosx --show-sdk-path)"
        SWIFTC="${'$'}(xcrun --toolchain XcodeDefault --find swiftc)"
        SWIFT_TOOLCHAIN="${'$'}{SWIFTC%/usr/bin/swiftc}"
        SWIFT_LIB="${'$'}{SWIFT_TOOLCHAIN}/usr/lib/swift/macosx"
        DEFAULT_PC="${'$'}(pkg-config --variable pc_path pkg-config)"
        export PKG_CONFIG_LIBDIR=${shellQuote(mpvKitGeneratedPkgConfigDirs.joinToString(":"))}:"${'$'}{DEFAULT_PC}"
        exec xcrun clang++ \
          -std=c++17 \
          -dynamiclib \
          -fobjc-arc \
          -ObjC++ \
          -arch ${shellQuote(macosPlayerBridgeArch)} \
          -isysroot "${'$'}{SDKROOT}" \
          -mmacosx-version-min=11.0 \
          ${shellQuote(macosPlayerBridgeSourceFile.absolutePath)} \
          -o ${shellQuote(macosPlayerBridgeOutputFile.absolutePath)} \
          -I${shellQuote("$macosPlayerBridgeJavaHome/include")} \
          -I${shellQuote("$macosPlayerBridgeJavaHome/include/darwin")} \
          -I${shellQuote(File(mpvKitLibmpvRoot, "include").absolutePath)} \
          $mpvKitGeneratedLibSearchArgs \
          -L"${'$'}{SWIFT_LIB}" \
          -L/usr/lib/swift \
          -framework AppKit \
          -framework WebKit \
          -framework Metal \
          -framework Security \
          -lswiftCompatibility56 \
          -lswiftCompatibilityConcurrency \
          -lswiftCompatibilityPacks \
          -lc++ \
          ${'$'}(pkg-config --libs --static mpv)
        """.trimIndent(),
    )
}
val buildMacosPlayerBridge = tasks.register<Exec>("buildMacosPlayerBridge") {
    notCompatibleWithConfigurationCache("Builds a host-local player bridge against MPVKit's macOS libmpv artifacts.")
    enabled = isMacHost
    inputs.file(macosPlayerBridgeSource)
    if (mpvKitLibmpvStaticLib.exists()) {
        inputs.file(mpvKitLibmpvStaticLib)
    }
    if (mpvKitLibmpvPkgConfigFile.exists()) {
        inputs.file(mpvKitLibmpvPkgConfigFile)
    }
    inputs.files(mpvKitGeneratedPkgConfigDirs.mapNotNull { it.parentFile?.resolve("lib")?.takeIf(File::exists) })
    outputs.file(macosPlayerBridgeOutput)
    commandLine(macosPlayerBridgeCommand)
}

val windowsPlayerBridgeArch = when (System.getProperty("os.arch").lowercase()) {
    "aarch64", "arm64" -> "arm64"
    "x86" -> "x86"
    else -> "x64"
}
val windowsPlayerBridgeSource = layout.projectDirectory.file("src/desktopMain/native/windows/player_bridge.cpp")
val windowsPlayerBridgeOutput = layout.buildDirectory.file("native/windows/player_bridge.dll")
val windowsPlayerBridgeImportLib = layout.buildDirectory.file("native/windows/player_bridge.lib")
val windowsPlayerBridgePdb = layout.buildDirectory.file("native/windows/player_bridge.pdb")
val windowsPlayerBridgeObj = layout.buildDirectory.file("native/windows/player_bridge.obj")
val windowsPlayerBridgeScript = layout.buildDirectory.file("native/windows/build-player-bridge.bat")
val windowsPlayerRuntimeOutput = layout.buildDirectory.dir("native/windows-runtime")
if (isWindowsHost) {
    windowsPlayerBridgeOutput.get().asFile.parentFile.mkdirs()
}
val windowsWebView2Root = providers.gradleProperty("nuvio.webview2.dir").orNull
    ?.takeIf { it.isNotBlank() }
    ?.let(::File)
    ?: newestDirectory(File(System.getProperty("user.home"), ".nuget/packages/microsoft.web.webview2"))
    ?: File("__missing_webview2__")
val windowsWebView2IncludeDir = File(windowsWebView2Root, "build/native/include")
val windowsWebView2NativeDir = File(windowsWebView2Root, "build/native/$windowsPlayerBridgeArch")
val windowsWebView2LoaderLib = File(windowsWebView2NativeDir, "WebView2Loader.dll.lib")
val windowsWebView2LoaderDll = File(windowsWebView2NativeDir, "WebView2Loader.dll")
fun File.hasWindowsLibmpvRuntime(): Boolean =
    isDirectory &&
        resolve("libmpv-2.dll").exists() &&
        (listFiles { file -> file.isFile && file.name.matches(Regex("avcodec-.*\\.dll", RegexOption.IGNORE_CASE)) }
            ?.isNotEmpty() == true)

val windowsLibmpvRuntimeDir = providers.gradleProperty("nuvio.windows.libmpv.runtimeDir").orNull
    ?.takeIf { it.isNotBlank() }
    ?.let(::File)
    ?: listOf(
        File("C:/Program Files (x86)/Nuvio/app/native"),
        File("C:/Program Files/Nuvio/app/native"),
        File("C:/msys64/ucrt64/bin"),
        File("C:/msys64/mingw64/bin"),
    ).firstOrNull { it.hasWindowsLibmpvRuntime() }

// SVP (anime motion interpolation) needs VapourSynth's Python scripting layer to actually run
// svp_main.vpy, not just the vsscript.dll it dlopen()s at runtime. This is the curated,
// empirically-verified minimal file set (traced by really importing svp_main.vpy's dependency
// chain under a real interpreter and validating a from-scratch PYTHONHOME built from exactly
// this list): Python's own boot/site requirements plus what the script itself imports
// (logging, multiprocessing, math, fractions, subprocess, gc, traceback) plus the vapoursynth
// Python binding. ~4MB, vs. ~250MB for the full stdlib. Optional: if the source tree isn't
// found, SVP simply stays unavailable (DesktopAnimeSvp already degrades gracefully for that).
val windowsPythonLibFiles = listOf(
    "__future__.py", "_collections_abc.py", "_colorize.py", "_compat_pickle.py",
    "_opcode_metadata.py", "_py_warnings.py", "_sitebuiltins.py", "_weakrefset.py", "abc.py",
    "annotationlib.py", "ast.py", "codecs.py", "codeop.py", "collections/__init__.py",
    "concurrent/__init__.py", "concurrent/futures/__init__.py", "concurrent/futures/_base.py",
    "contextlib.py", "copy.py", "copyreg.py", "ctypes/__init__.py", "ctypes/_endian.py",
    "dataclasses.py", "dis.py", "encodings/__init__.py", "encodings/_win_cp_codecs.py",
    "encodings/aliases.py", "encodings/ascii.py", "encodings/cp1252.py", "encodings/latin_1.py",
    "encodings/utf_8.py", "enum.py", "fractions.py", "functools.py", "genericpath.py",
    "importlib/__init__.py", "importlib/_bootstrap.py", "importlib/_bootstrap_external.py",
    "importlib/machinery.py", "inspect.py", "io.py", "keyword.py",
    "lib-dynload/_ctypes.cp314-mingw_x86_64_ucrt_gnu.pyd",
    "lib-dynload/_interpreters.cp314-mingw_x86_64_ucrt_gnu.pyd",
    "lib-dynload/_pickle.cp314-mingw_x86_64_ucrt_gnu.pyd",
    "lib-dynload/_socket.cp314-mingw_x86_64_ucrt_gnu.pyd",
    "lib-dynload/_struct.cp314-mingw_x86_64_ucrt_gnu.pyd",
    "lib-dynload/math.cp314-mingw_x86_64_ucrt_gnu.pyd",
    "lib-dynload/zlib.cp314-mingw_x86_64_ucrt_gnu.pyd",
    "linecache.py", "locale.py", "logging/__init__.py", "multiprocessing/__init__.py",
    "multiprocessing/context.py", "multiprocessing/process.py", "multiprocessing/reduction.py",
    "ntpath.py", "numbers.py", "opcode.py", "operator.py", "os.py", "pickle.py",
    "re/__init__.py", "re/_casefix.py", "re/_compiler.py", "re/_constants.py", "re/_parser.py",
    "reprlib.py", "signal.py", "site-packages/vapoursynth.cp314-mingw_x86_64_ucrt_gnu.pyd",
    "site.py", "socket.py", "stat.py", "string/__init__.py", "struct.py", "subprocess.py",
    "sysconfig/__init__.py", "textwrap.py", "threading.py", "token.py", "tokenize.py",
    "traceback.py", "types.py", "typing.py", "warnings.py", "weakref.py", "zipimport.py",
)
val windowsPythonLibSourceDir = providers.gradleProperty("nuvio.windows.python.libDir").orNull
    ?.takeIf { it.isNotBlank() }
    ?.let(::File)
    ?: listOf(
        File("C:/Program Files (x86)/Nuvio/app/native/pylib/lib/python3.14"),
        File("C:/Program Files/Nuvio/app/native/pylib/lib/python3.14"),
        File("C:/msys64/ucrt64/lib/python3.14"),
        File("C:/msys64/mingw64/lib/python3.14"),
    ).firstOrNull { it.isDirectory && it.resolve("os.py").exists() }
val windowsPythonLibOutput = layout.buildDirectory.dir("native/windows-pylib")
val windowsVsWhere = File("C:/Program Files (x86)/Microsoft Visual Studio/Installer/vswhere.exe")
val windowsVcvarsRelativePath = when (windowsPlayerBridgeArch) {
    "x86" -> "VC\\Auxiliary\\Build\\vcvars32.bat"
    "arm64" -> "VC\\Auxiliary\\Build\\vcvarsarm64.bat"
    else -> "VC\\Auxiliary\\Build\\vcvars64.bat"
}
val windowsVcvarsPath = providers.gradleProperty("nuvio.windows.vcvars.path").orNull
    ?.takeIf { it.isNotBlank() }
val windowsPlayerBridgeJavaHome = providers.systemProperty("java.home").get()
val missingWindowsPlayerBridgeInputs = listOfNotNull(
    "WebView2.h".takeUnless { windowsWebView2IncludeDir.resolve("WebView2.h").exists() },
    "WebView2Loader.dll.lib".takeUnless { windowsWebView2LoaderLib.exists() },
)
val missingWindowsPlayerRuntimeInputs = listOfNotNull(
    "WebView2Loader.dll".takeUnless { windowsWebView2LoaderDll.exists() },
    "full libmpv runtime directory".takeUnless { windowsLibmpvRuntimeDir?.hasWindowsLibmpvRuntime() == true },
)
val missingWindowsPlayerBridgeMessage = """
    Windows desktop player bridge inputs are missing: ${missingWindowsPlayerBridgeInputs.joinToString()}.
    Install the Microsoft.Web.WebView2 NuGet package or pass -Pnuvio.webview2.dir=C:/path/to/microsoft.web.webview2/version.
    libmpv is loaded at runtime; pass -Pnuvio.windows.libmpv.runtimeDir=C:/path/to/mpv-dlls to bundle it.
""".trimIndent()
val windowsPlayerBridgeCommand = if (missingWindowsPlayerBridgeInputs.isNotEmpty()) {
    listOf(
        "cmd",
        "/c",
        "echo ${missingWindowsPlayerBridgeMessage.replace("\n", " ")} 1>&2 && exit /b 1",
    )
} else {
    val sourceFile = windowsPlayerBridgeSource.asFile
    val outputFile = windowsPlayerBridgeOutput.get().asFile
    val importLibFile = windowsPlayerBridgeImportLib.get().asFile
    val pdbFile = windowsPlayerBridgePdb.get().asFile
    val objFile = windowsPlayerBridgeObj.get().asFile
    val javaIncludeDir = File(windowsPlayerBridgeJavaHome, "include")
    val javaWin32IncludeDir = File(javaIncludeDir, "win32")
    val compileCommand = listOf(
        "cl",
        "/nologo",
        "/EHsc",
        "/std:c++17",
        "/LD",
        "/DUNICODE",
        "/D_UNICODE",
        "/DNOMINMAX",
        "/DWIN32_LEAN_AND_MEAN",
        "/permissive-",
        cmdQuote(sourceFile.absolutePath),
        "/I${cmdQuote(javaIncludeDir.absolutePath)}",
        "/I${cmdQuote(javaWin32IncludeDir.absolutePath)}",
        "/I${cmdQuote(windowsWebView2IncludeDir.absolutePath)}",
        "/Fo${cmdQuote(objFile.absolutePath)}",
        "/Fd${cmdQuote(pdbFile.absolutePath)}",
        "/Fe${cmdQuote(outputFile.absolutePath)}",
        "/link",
        "/NOLOGO",
        "/INCREMENTAL:NO",
        "/IMPLIB:${cmdQuote(importLibFile.absolutePath)}",
        cmdQuote(windowsWebView2LoaderLib.absolutePath),
        "Ole32.lib",
        "User32.lib",
        "Gdi32.lib",
        "Dwmapi.lib",
        // RoGetActivationFactory / WindowsCreateStringReference, for the System Media
        // Transport Controls session that gives the player global media-key handling.
        "RuntimeObject.lib",
        // SetWindowSubclass, for the WM_APPCOMMAND media-key fallback on the top-level window.
        "Comctl32.lib",
        // IShellLink / SHGetKnownFolderPath and InitPropVariantFromString, for the Start Menu
        // shortcut that carries the AppUserModelID Windows needs to name the media session.
        "Shell32.lib",
        "Propsys.lib",
    ).joinToString(" ")
    val powershellCompileCommand = compileCommand.replace("\"", "__DQ__")
    val powershellCommand = """
        ${'$'}ErrorActionPreference = 'Stop'
        ${'$'}dq = [char]34
        ${'$'}vcvars = ${psSingleQuote(windowsVcvarsPath.orEmpty())}
        if ([string]::IsNullOrWhiteSpace(${'$'}vcvars)) {
          ${'$'}vswhere = ${psSingleQuote(windowsVsWhere.absolutePath)}
          if (Test-Path -LiteralPath ${'$'}vswhere) {
            ${'$'}vcvars = & ${'$'}vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -find ${psSingleQuote(windowsVcvarsRelativePath)} | Select-Object -First 1
          }
        }
        if ([string]::IsNullOrWhiteSpace(${'$'}vcvars) -or -not (Test-Path -LiteralPath ${'$'}vcvars)) {
          Write-Error 'Visual Studio C++ toolchain was not found. Install MSVC or pass -Pnuvio.windows.vcvars.path=C:\path\to\vcvars64.bat.'
          exit 1
        }
        ${'$'}vcvars = ([string]${'$'}vcvars).Trim()
        ${'$'}bat = ${psSingleQuote(windowsPlayerBridgeScript.get().asFile.absolutePath)}
        ${'$'}compile = ${psSingleQuote(powershellCompileCommand)}.Replace('__DQ__', ${'$'}dq)
        ${'$'}lines = @(
          '@echo off',
          ('set {0}VCVARS={1}{0}' -f ${'$'}dq, ${'$'}vcvars),
          ('call {0}%VCVARS%{0} >nul' -f ${'$'}dq),
          'if errorlevel 1 exit /b %errorlevel%',
          ${'$'}compile,
          'exit /b %ERRORLEVEL%'
        )
        Set-Content -LiteralPath ${'$'}bat -Value ${'$'}lines -Encoding ASCII
        & cmd.exe /d /c ${'$'}bat
        ${'$'}code = ${'$'}LASTEXITCODE
        if (${'$'}code -ne 0) { exit ${'$'}code }
    """.trimIndent()
    listOf(
        "powershell",
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-Command",
        powershellCommand,
    )
}
val buildWindowsPlayerBridge = tasks.register<Exec>("buildWindowsPlayerBridge") {
    notCompatibleWithConfigurationCache("Builds a host-local player bridge against WebView2 and libmpv for Windows.")
    enabled = isWindowsHost
    inputs.file(windowsPlayerBridgeSource)
    if (windowsWebView2IncludeDir.exists()) {
        inputs.dir(windowsWebView2IncludeDir)
    }
    if (windowsWebView2LoaderLib.exists()) {
        inputs.file(windowsWebView2LoaderLib)
    }
    outputs.file(windowsPlayerBridgeOutput)
    outputs.file(windowsPlayerBridgeImportLib)
    outputs.file(windowsPlayerBridgePdb)
    commandLine(windowsPlayerBridgeCommand)
}

val windowsPlaybackStartupTestSource = layout.projectDirectory.file("src/desktopTest/native/windows/playback_startup_test.cpp")
val windowsPlaybackStartupTestOutput = layout.buildDirectory.file("native/windows/player_startup_test.exe")
val buildWindowsPlaybackStartupTests = tasks.register<Exec>("buildWindowsPlaybackStartupTests") {
    notCompatibleWithConfigurationCache("Builds native playback transition tests with the Windows bridge toolchain.")
    enabled = isWindowsHost
    inputs.files(windowsPlayerBridgeSource, windowsPlaybackStartupTestSource)
    outputs.file(windowsPlaybackStartupTestOutput)
    commandLine(windowsPlayerBridgeCommand.map { argument ->
        argument.replace(windowsPlayerBridgeSource.asFile.absolutePath, windowsPlaybackStartupTestSource.asFile.absolutePath)
            .replace("/LD ", "")
            .replace("player_bridge.dll", "player_startup_test.exe")
            .replace("player_bridge", "player_startup_test")
    })
}
tasks.register<Exec>("nativePlaybackStartupTest") {
    notCompatibleWithConfigurationCache("Runs the native playback tests on the Windows host.")
    enabled = isWindowsHost
    dependsOn(buildWindowsPlaybackStartupTests)
    workingDir(windowsPlaybackStartupTestOutput.get().asFile.parentFile)
    commandLine(windowsPlaybackStartupTestOutput.get().asFile.absolutePath)
}

val prepareWindowsPlayerRuntime = tasks.register<Sync>("prepareWindowsPlayerRuntime") {
    notCompatibleWithConfigurationCache("Validates and bundles host-local Windows native player runtime DLLs.")
    enabled = isWindowsHost
    into(windowsPlayerRuntimeOutput)
    doFirst {
        if (missingWindowsPlayerRuntimeInputs.isNotEmpty()) {
            throw GradleException(
                """
                Windows desktop player runtime inputs are missing: ${missingWindowsPlayerRuntimeInputs.joinToString()}.
                Pass -Pnuvio.windows.libmpv.runtimeDir=C:/path/to/mpv-dlls so the app bundles libmpv-2.dll and its dependent DLLs.
                """.trimIndent(),
            )
        }
        val avfilter = windowsLibmpvRuntimeDir
            ?.listFiles { file ->
                file.isFile && file.name.matches(Regex("avfilter-.*\\.dll", RegexOption.IGNORE_CASE))
            }
            ?.firstOrNull()
            ?: throw GradleException("The Windows libmpv runtime does not contain avfilter.dll.")
        val avfilterText = avfilter.readBytes().toString(Charsets.ISO_8859_1).lowercase()
        val forbiddenImports = listOf("libwhisper-1.dll", "ggml.dll").filter(avfilterText::contains)
        if (forbiddenImports.isNotEmpty()) {
            throw GradleException(
                "${avfilter.name} still imports ${forbiddenImports.joinToString()}. " +
                    "Rebuild FFmpeg without --enable-whisper before packaging Nuvio.",
            )
        }
    }
    if (windowsWebView2LoaderDll.exists()) {
        from(windowsWebView2LoaderDll)
    }
    if (windowsLibmpvRuntimeDir?.exists() == true) {
        from(windowsLibmpvRuntimeDir) {
            include("*.dll")
            exclude(windowsUnusedLibmpvRuntimeDlls)
        }
        // mpv's VapourSynth filter opens this fixed filename at runtime. Ship the alias in the
        // image instead of creating another executable file on first launch.
        from(windowsLibmpvRuntimeDir) {
            include("libvapoursynth-script-0.dll")
            rename { "vsscript.dll" }
        }
    }
}

// windowsLibmpvRuntimeDir is a raw MSYS2 mingw64/ucrt64 bin/ folder shared with every other
// package installed there, so `include("*.dll")` above grabs everything in it — not just what
// mpv/ffmpeg/vapoursynth/SVP actually use. This list was derived by tracing the real PE import
// table closure from player_bridge.dll/libmpv-2.dll/libvapoursynth*.dll/svpflow*_vs.dll outward
// (not guesswork): these DLLs are unreachable from that closure and belong to unrelated MSYS2
// packages that happen to share the bin/ folder — whisper.cpp/ggml packages that Nuvio's
// FFmpeg build intentionally does not link, GTK/GNOME peripherals, Tcl/Tk, ncurses, libcaca (ASCII
// art video output), OpenAL, an OpenEXR/Imath/PyImath image chain, and standalone glslang/
// SPIRV-Tools copies. ~140MB removed from the shipped app.
//
// NOT excluded (reverted after real-world failures):
// - glslang.dll, SPIRV.dll, libSPIRV-Tools*.dll, libglslang-default-resource-limits.dll: static
//   PE-import analysis showed libshaderc_shared.dll only imports Windows CRT DLLs, which read as
//   "these are unused" — but that only rules out *static* linking, not a dynamically loaded
//   optional backend at first-use.
// - SDL2.dll, libcaca-0.dll/libcaca++-0.dll, libopenal-1.dll: ffmpeg's avdevice module probes
//   and registers every compiled-in output device (SDL, caca/ASCII-art, OpenAL, etc.) at startup
//   regardless of whether it's ever used — these looked like dead weight by static analysis for
//   the same reason as above. Removing libcaca-0.dll specifically was caught live in a WinDbg
//   session: the failed lookup fell through to loading a second, complete copy of the whole
//   mpv/ffmpeg dependency chain from an unrelated system directory (two conflicting copies of
//   avutil/avcodec/etc. sharing one process), which corrupted enough state to crash Python's
//   own `encodings` import moments later — a real, evidenced crash cause, not a guess. Keep this
//   whole device-driver-shaped group bundled unless proven individually safe some other way.
// - ggml*.dll / libwhisper-1.dll: Nuvio's FFmpeg build omits --enable-whisper, so avfilter has
//   no imports from these libraries. prepareWindowsPlayerRuntime verifies that invariant before
//   copying any DLLs, preventing an unmodified MSYS2 FFmpeg package from reintroducing them.
val windowsUnusedLibmpvRuntimeDlls = listOf(
    "OpenCL.dll", "edit.dll",
    "ggml.dll", "ggml-base.dll", "ggml-blas.dll", "ggml-opencl.dll", "ggml-rpc.dll",
    "ggml-vulkan.dll", "libwhisper-1.dll",
    "ggml-cpu-alderlake.dll", "ggml-cpu-cannonlake.dll", "ggml-cpu-cascadelake.dll",
    "ggml-cpu-cooperlake.dll", "ggml-cpu-haswell.dll", "ggml-cpu-icelake.dll",
    "ggml-cpu-ivybridge.dll", "ggml-cpu-piledriver.dll", "ggml-cpu-sandybridge.dll",
    "ggml-cpu-sapphirerapids.dll", "ggml-cpu-skylakex.dll", "ggml-cpu-sse42.dll",
    "ggml-cpu-x64.dll", "ggml-cpu-zen4.dll",
    "libFLAC++.dll", "libFLAC.dll", "libIex-3_4.dll", "libIlmThread-3_4.dll",
    "libImath-3_2.dll", "libOpenEXR-3_4.dll", "libOpenEXRCore-3_4.dll", "libOpenEXRUtil-3_4.dll",
    "libPyImath_Python3_14-3_2.dll", "libasprintf-0.dll", "libatomic-1.dll",
    "libcairo-script-interpreter-2.dll", "libcddb-2.dll",
    "libcdio++-1.dll", "libcharset-1.dll", "libexif-12.dll", "libfftw3_omp-3.dll",
    "libfftw3_threads-3.dll", "libfftw3f-3.dll", "libfftw3f_omp-3.dll", "libfftw3f_threads-3.dll",
    "libfftw3l-3.dll", "libfftw3l_omp-3.dll", "libfftw3l_threads-3.dll", "libformw6.dll",
    "libgfortran-5.dll", "libgif-7.dll", "libgirepository-2.0-0.dll",
    "libgmpxx-4.dll", "libgnutls-openssl-27.dll",
    "libgnutlsxx-30.dll", "libgthread-2.0-0.dll", "libharfbuzz-gobject-0.dll",
    "libharfbuzz-raster-0.dll", "libharfbuzz-subset-0.dll", "libharfbuzz-vector-0.dll",
    "libhwy_contrib.dll", "libhwy_test.dll", "libisl-23.dll", "libiso9660++-1.dll",
    "libiso9660-12.dll", "liblcms2_fast_float-2.dll", "liblzo2-2.dll", "libmenuw6.dll",
    "libmpc-3.dll", "libmpdec++-4.dll", "libmpdec-4.dll", "libmpfr-6.dll", "libmpg123-0.dll",
    "libmysofa.dll", "libncurses++w6.dll", "libncursesw6.dll",
    "libopenblas.dll", "libopenjph-0.27.dll", "libopenjpip-7.dll", "libout123-0.dll",
    "libpanelw6.dll", "libpcre2-16-0.dll", "libpcre2-32-0.dll", "libpcre2-posix-3.dll",
    "libpkgconf-7.dll", "libpython3.dll", "libquadmath-0.dll", "libsndfile-1.dll",
    "libsoxr-lsr.dll", "libspeexdsp-1.dll", "libsqlite3-0.dll", "libssl-3-x64.dll",
    "libsyn123-0.dll", "libsystre-0.dll", "libtheora-1.dll", "libtiffxx-6.dll", "libtre-5.dll",
    "libturbojpeg.dll", "libudf-0.dll", "libvamp-hostsdk.dll", "libvamp-sdk.dll",
    "libvorbisfile-3.dll", "libwebpdecoder-3.dll", "libwebpdemux-2.dll", "tcl86.dll", "tk86.dll",
)

val prepareWindowsPythonLib = tasks.register<Sync>("prepareWindowsPythonLib") {
    notCompatibleWithConfigurationCache("Bundles a curated minimal Python stdlib subset for VapourSynth/SVP scripting.")
    enabled = isWindowsHost
    into(windowsPythonLibOutput)
    if (windowsPythonLibSourceDir?.isDirectory == true) {
        from(windowsPythonLibSourceDir) {
            windowsPythonLibFiles.forEach { include(it) }
        }
    }
}

val generateWindowsPythonLibIndex = tasks.register<GenerateNativeRuntimeIndexTask>("generateWindowsPythonLibIndex") {
    enabled = isWindowsHost
    dependsOn(prepareWindowsPythonLib)
    recursive.set(true)
    runtimeDir.set(windowsPythonLibOutput)
    indexFile.set(windowsPythonLibOutput.map { it.file("python-lib-files.txt") })
}

val generateWindowsPlayerRuntimeIndex = tasks.register<GenerateNativeRuntimeIndexTask>("generateWindowsPlayerRuntimeIndex") {
    enabled = isWindowsHost
    dependsOn(prepareWindowsPlayerRuntime)
    runtimeDir.set(windowsPlayerRuntimeOutput)
    indexFile.set(windowsPlayerRuntimeOutput.map { it.file("runtime-files.txt") })
}

abstract class GenerateNativeRuntimeIndexTask : DefaultTask() {
    @get:InputDirectory
    abstract val runtimeDir: DirectoryProperty

    @get:OutputFile
    abstract val indexFile: RegularFileProperty

    @get:Input
    @get:Optional
    abstract val recursive: Property<Boolean>

    @TaskAction
    fun generate() {
        val dir = runtimeDir.get().asFile
        val indexName = indexFile.get().asFile.name
        val files = if (recursive.getOrElse(false)) {
            dir.walkTopDown()
                .filter { it.isFile && it.name != indexName }
                .map { it.relativeTo(dir).invariantSeparatorsPath }
                .sorted()
                .toList()
        } else {
            dir.listFiles { file -> file.isFile && file.name != indexName }
                .orEmpty()
                .map { it.name }
                .sorted()
        }
        indexFile.get().asFile.writeText(files.joinToString(separator = "\n", postfix = "\n"))
    }
}

// The Windows player runtime was packed into every download twice: once into desktopJar for
// NativePlayerBridge's extraction fallback, and once beside Nuvio.exe by the app-image staging
// further down. Only the second copy is ever loaded. A packaged build resolves
// packagedRuntimeDir() first, and the extraction fallback underneath it refuses to run at all
// without -Dnuvio.nativeRuntimeExtractionEnabled=true; `gradlew run` loads the DLLs straight out
// of build/native/windows via findLocalBuildLibrary(). So the jar copy is dead weight in every
// layout this project actually builds — ~82MB of the shipped zip. runtime-files.txt has to stay
// in the jar: packagedRuntimeDir() reads it to know which files must exist beside Nuvio.exe.
//
// Build with -Pnuvio.desktop.leanJar=false to restore the self-contained jar. That is what an
// uber jar needs, and what a deliberately incomplete development layout needs before
// -Dnuvio.nativeRuntimeExtractionEnabled=true can do anything (with a lean jar the extraction
// path fails cleanly: copyResourceTo throws on the missing resource, loadNativeLibrary() reports
// "Unable to prepare the bundled native player runtime").
val leanDesktopJar = providers.gradleProperty("nuvio.desktop.leanJar")
    .map(String::toBoolean)
    .getOrElse(true)

tasks.withType<Jar>().configureEach {
    if (isMacHost && name == "desktopJar") {
        dependsOn(buildMacosPlayerBridge)
        from(macosPlayerBridgeOutput) {
            into("native/macos")
        }
    }
    if (isWindowsHost && name == "desktopJar") {
        dependsOn(
            buildWindowsPlayerBridge,
            prepareWindowsPlayerRuntime,
            generateWindowsPlayerRuntimeIndex,
            prepareWindowsPythonLib,
            generateWindowsPythonLibIndex,
        )
        from(windowsPlayerBridgeOutput) {
            into("native/windows")
        }
        from(windowsPlayerRuntimeOutput) {
            if (leanDesktopJar) {
                include("runtime-files.txt")
            }
            into("native/windows")
        }
        from(windowsPythonLibOutput) {
            into("native/windows/pylib")
        }
    }
}

if (isWindowsHost) {
    val desktopNativePlayerTasks = setOf(
        "run",
        "runRelease",
        "desktopRun",
        "runDistributable",
        "runReleaseDistributable",
        "desktopRunHot",
        "hotRunDesktop",
        "hotRunDesktopAsync",
        "hotDevDesktop",
        "hotDevDesktopAsync",
        "createDistributable",
        "createReleaseDistributable",
        "createRuntimeImage",
        "package",
        "packageDistributionForCurrentOS",
        "packageMsi",
        "packageUberJarForCurrentOS",
        "packageReleaseDistributionForCurrentOS",
        "packageReleaseMsi",
        "packageReleaseUberJarForCurrentOS",
    )
    tasks.matching { it.name in desktopNativePlayerTasks }.configureEach {
        dependsOn(
            buildWindowsPlayerBridge,
            prepareWindowsPlayerRuntime,
            generateWindowsPlayerRuntimeIndex,
            prepareWindowsPythonLib,
            generateWindowsPythonLibIndex,
        )
    }
    // Windows doesn't search a loaded DLL's own directory for its dependencies; the DLL
    // directory must be on PATH so player_bridge.dll can find libmpv-2.dll and friends.
    tasks.withType<JavaExec>().matching { it.name in desktopNativePlayerTasks }.configureEach {
        val nativeDllDir = layout.buildDirectory.dir("native/windows").get().asFile.absolutePath
        environment("PATH", "$nativeDllDir;${System.getenv("PATH") ?: ""}")
    }

    // Compose packages the native runtime inside the application JAR for development fallback.
    // A Windows distributable must also contain it beside Nuvio.exe so first launch only loads
    // files already installed by the package; it must not extract executable code at runtime.
    mapOf(
        "createDistributable" to "main/app/Nuvio",
        "createReleaseDistributable" to "main-release/app/Nuvio",
    ).forEach { (taskName, relativeImagePath) ->
        tasks.matching { it.name == taskName }.configureEach {
            notCompatibleWithConfigurationCache("Stages and verifies the Windows native runtime in the jpackage app image.")
            doLast {
                val imageDir = layout.buildDirectory.dir("compose/binaries/$relativeImagePath").get().asFile
                check(imageDir.resolve("Nuvio.exe").isFile) {
                    "Windows app image was not created at $imageDir"
                }
                copy {
                    from(windowsPlayerBridgeOutput)
                    from(windowsPlayerRuntimeOutput)
                    into(imageDir)
                }
                copy {
                    from(windowsPythonLibOutput)
                    into(imageDir.resolve("lib/python3.14"))
                }

                val requiredNativeFiles = listOf("player_bridge.dll") +
                    windowsPlayerRuntimeOutput.get().asFile
                        .listFiles { file -> file.isFile && file.name != "runtime-files.txt" }
                        .orEmpty()
                        .map { it.name }
                val missingNativeFiles = requiredNativeFiles.filterNot { imageDir.resolve(it).isFile }
                check(missingNativeFiles.isEmpty()) {
                    "Windows app image is missing native runtime files: ${missingNativeFiles.joinToString()}"
                }
                check(imageDir.resolve("vsscript.dll").isFile) {
                    "Windows app image is missing the packaged vsscript.dll alias"
                }
            }
        }
    }
}

// material-icons-extended ships 11,105 icons (~36MB compressed) and the app references about 75
// of them, so it was the second-largest thing in the download after the player runtime. ProGuard
// would remove the rest, but buildTypes.release.proguard is off because shrinking a Compose app
// wholesale is its own project; this trims that one jar and touches nothing else.
//
// The set of icons to keep is read out of the bytecode already staged in the app image rather
// than out of the sources, so it is exactly the reference closure that ships: an icon reached
// from a library, from generated code, or from Kotlin that never spells out an import is still
// found. It is deliberately over-inclusive — the scan matches icon class names anywhere in a
// class file's bytes, so a false positive costs a few hundred bytes while a false negative would
// be a NoClassDefFoundError in the packaged app.
//
// Not safe for a reflective Icons lookup (`Icons::class.members`, an icon-name-to-vector map
/**
 * The channel this image should claim, read from the app's own Update Channel setting.
 *
 * That setting is what this machine is already publishing for, so the build follows it instead of
 * asking for a flag on every cut. It lives in the desktop preference store, written by the running
 * app; when it cannot be read (another machine, a clean profile, CI) the answer is nightly, which
 * is what all but a couple of builds a month are.
 *
 * Read at execution time, never at configuration time: with the configuration cache on, a value
 * captured during configuration would be replayed from the cache and every later build would keep
 * claiming whatever the setting said the first time.
 */
fun resolveDesktopChannel(override: String?, logger: org.gradle.api.logging.Logger): String {
    if (override != null) return override
    val appData = System.getenv("LOCALAPPDATA")
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
        ?: File(System.getProperty("user.home"), "AppData/Local")
    val updaterPrefs = File(appData, "NuvioHTPC/nuvio_updater.properties")
    val stored = runCatching {
        Properties()
            .apply { updaterPrefs.inputStream().use { load(it) } }
            .getProperty("update_channel")
            ?.trim()
            ?.lowercase()
    }.getOrNull()
    return when (stored) {
        "stable" -> "stable"
        "nightly" -> "nightly"
        else -> {
            logger.lifecycle(
                "No Update Channel setting found at ${updaterPrefs.absolutePath}; stamping this image as nightly.",
            )
            "nightly"
        }
    }
}

/**
 * Stamps the packaged app image with the identity of this specific build.
 *
 * The version name and code only move when someone edits DesktopVersion.properties, so two
 * distributables cut from the same version are indistinguishable in the UI — which makes a
 * freshly installed build look like the update never landed. The build time (and commit, when
 * the tree is a git checkout) is what actually changes every time the image is rebuilt, so it
 * is written beside the jars and read back at runtime for the About/sidebar build line.
 *
 * Written from the packaging task's own execution, not from configuration: with the
 * configuration cache on, a timestamp captured at configuration time would be replayed from the
 * cache and every build would claim the same moment.
 */
fun writeAppImageBuildInfo(
    appDir: File,
    versionName: String,
    versionCode: Int,
    channelOverride: String?,
    repoRoot: File,
    logger: org.gradle.api.logging.Logger,
) {
    val channel = resolveDesktopChannel(channelOverride, logger)
    // UTC, not local time: release notes and GitHub release timestamps are posted in UTC, and a
    // build stamp that has to be mentally converted before it can be matched against them is a
    // stamp nobody checks.
    val now = ZonedDateTime.now(ZoneOffset.UTC).withNano(0)
    val buildId = now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val gitCommit = runCatching {
        val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .directory(repoRoot)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0) output.takeIf { it.matches(Regex("[0-9a-f]{6,40}")) } else null
    }.getOrNull()

    val buildInfo = appDir.resolve("build-info.properties")
    buildInfo.writeText(
        buildString {
            appendLine("# Generated by the packaging task. Identifies this exact app image.")
            appendLine("BUILD_ID=$buildId")
            appendLine("BUILD_TIME=${now.format(DateTimeFormatter.ISO_INSTANT)}")
            appendLine("VERSION_NAME=$versionName")
            appendLine("VERSION_CODE=$versionCode")
            appendLine("CHANNEL=$channel")
            if (gitCommit != null) appendLine("GIT_COMMIT=$gitCommit")
        },
    )
    logger.lifecycle(
        "Stamped ${buildInfo.name}: $versionName ($versionCode) $channel build $buildId UTC" +
            gitCommit?.let { " commit $it" }.orEmpty(),
    )
}

// built from strings). There is none today; add one and this has to grow an allowlist.
fun trimMaterialIconsExtendedJar(appDir: File, logger: org.gradle.api.logging.Logger) {
    val iconsJar = appDir
        .listFiles { file -> file.isFile && file.name.startsWith("material-icons-extended") && file.name.endsWith(".jar") }
        ?.singleOrNull()
        ?: return
    val iconPackage = "androidx/compose/material/icons/"
    val iconReference = Regex("androidx/compose/material/icons/[A-Za-z0-9_/]+Kt")

    fun iconReferencesIn(zip: ZipFile, filter: (String) -> Boolean): Set<String> =
        zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.endsWith(".class") && filter(it.name) }
            .flatMap { entry ->
                // ISO_8859_1 is a byte-for-byte decode, so this searches the constant pool
                // without paying for a real class parser.
                val text = zip.getInputStream(entry).use { it.readBytes() }.toString(Charsets.ISO_8859_1)
                if (iconPackage in text) iconReference.findAll(text).map { it.value } else emptySequence()
            }
            .toSet()

    val referenced = mutableSetOf<String>()
    appDir.listFiles { file -> file.isFile && file.name.endsWith(".jar") && file != iconsJar }
        .orEmpty()
        .forEach { jar -> ZipFile(jar).use { referenced += iconReferencesIn(it) { true } } }
    check(referenced.isNotEmpty()) {
        "Refusing to trim ${iconsJar.name}: no material icon references were found anywhere in " +
            "$appDir. That means this bytecode scan broke, not that the app stopped using icons."
    }

    // An icon class can reference another class in the icons package (they all reach IconsKt).
    // Close over that before deciding what to drop.
    ZipFile(iconsJar).use { zip ->
        var frontier = referenced.toSet()
        while (frontier.isNotEmpty()) {
            val names = frontier.map { "$it.class" }.toSet()
            val discovered = iconReferencesIn(zip) { it in names } - referenced
            referenced += discovered
            frontier = discovered
        }
    }

    val trimmed = File(iconsJar.parentFile, "${iconsJar.name}.trimmed")
    var kept = 0
    var dropped = 0
    ZipFile(iconsJar).use { zip ->
        ZipOutputStream(trimmed.outputStream().buffered()).use { out ->
            zip.entries().asSequence().forEach { entry ->
                val keep = entry.isDirectory ||
                    !entry.name.startsWith(iconPackage) ||
                    entry.name.removeSuffix(".class").substringBefore('$') in referenced
                if (!keep) {
                    dropped++
                    return@forEach
                }
                kept++
                out.putNextEntry(ZipEntry(entry.name))
                zip.getInputStream(entry).use { it.copyTo(out) }
                out.closeEntry()
            }
        }
    }

    val sizeBefore = iconsJar.length()
    check(iconsJar.delete()) { "Could not replace ${iconsJar.absolutePath} with its trimmed copy" }
    check(trimmed.renameTo(iconsJar)) { "Could not move ${trimmed.absolutePath} into place" }
    logger.lifecycle(
        "Trimmed ${iconsJar.name}: kept $kept entries for ${referenced.size} referenced icon " +
            "classes, dropped $dropped (${sizeBefore / 1024 / 1024}MB -> ${iconsJar.length() / 1024 / 1024}MB)",
    )
}

val repoRootDir = rootProject.layout.projectDirectory.asFile

mapOf(
    "createDistributable" to "main",
    "createReleaseDistributable" to "main-release",
).forEach { (taskName, imageFlavor) ->
    tasks.matching { it.name == taskName }.configureEach {
        notCompatibleWithConfigurationCache("Rewrites the packaged material-icons-extended jar in the app image.")
        doLast {
            val binariesDir = layout.buildDirectory.dir("compose/binaries/$imageFlavor/app").get().asFile
            val appDir = listOf("Nuvio/app", "Nuvio.app/Contents/app", "nuvio/lib/app")
                .map(binariesDir::resolve)
                .firstOrNull(File::isDirectory)
            check(appDir != null) { "No packaged app directory found under $binariesDir" }
            trimMaterialIconsExtendedJar(appDir, logger)
            writeAppImageBuildInfo(
                appDir = appDir,
                versionName = desktopReleaseVersionName,
                versionCode = desktopReleaseVersionCode,
                channelOverride = desktopChannelOverride,
                repoRoot = repoRootDir,
                logger = logger,
            )
        }
    }
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    dependsOn(generateRuntimeConfigs)
}

kotlin {
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
            freeCompilerArgs.addAll(
                "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
                "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            )
        }
    }

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(generatedRuntimeConfigDir)
        }
        val desktopMain by getting {
            kotlin.srcDir(fullPluginSourceDir)
            // In-app YouTube trailer extraction is shared with the "full" mobile
            // variants. Desktop pulls in the extractor and the resolver actual and
            // provides its own TrailerExtractionPlatform (java.net.http based).
            kotlin.srcDir(fullCommonSourceDir.resolve("com/nuvio/app/features/trailer"))
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.ktor.client.cio)
                implementation("com.squareup.okhttp3:okhttp:4.12.0")
                implementation(libs.quickjs.kt)
                implementation(libs.ksoup)
            }
        }
        commonMain.dependencies {
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
            implementation(libs.coil.svg)
            implementation("dev.chrisbanes.haze:haze:1.7.2")
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kmpalette.core)
            implementation(libs.androidx.navigation.compose)
            implementation(libs.kermit)
            implementation(libs.supabase.postgrest)
            implementation(libs.supabase.auth)
            implementation(libs.supabase.functions)
            implementation(libs.reorderable)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        val desktopTest by getting {
            dependencies {
                // Real pointer-event dispatch for the desktop-only gestures (right-click as the
                // secondary action). Reasoning about Compose's button filtering from the source
                // is how this got shipped broken once already.
                implementation(compose.desktop.currentOs)
                @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
                implementation(compose.uiTest)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.nuvio.app.MainKt"
        val smokePlayerUrl = providers.gradleProperty("nuvio.desktop.smokePlayerUrl").orNull
            ?: System.getenv("NUVIO_DESKTOP_SMOKE_PLAYER_URL")
        // Windows-only experiment: run Compose's own Skia rendering on Direct3D instead of
        // OpenGL so it shares one graphics API with mpv's D3D11 video surface (currently the
        // app mixes OpenGL-rendered UI with a D3D11 embedded video child window in the same
        // top-level window — testing whether that mismatch is what's causing poor DWM/driver
        // composition behavior on certain GPUs in borderless fullscreen).
        val skikoRenderApi = if (isWindowsHost) "DIRECT3D" else "OPENGL"
        jvmArgs += listOfNotNull(
            "-Dapple.awt.application.appearance=NSAppearanceNameDarkAqua",
            "-Dskiko.renderApi=$skikoRenderApi",
            "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.awt.windows=ALL-UNNAMED",
            // Native-crash diagnostics (chasing the silent, log-less CTD on plugin users —
            // suspected QuickJS/JNI access violation). On an EXCEPTION_ACCESS_VIOLATION the
            // HotSpot handler writes an hs_err_pid<pid>.log naming the faulting native module,
            // and CreateCoredumpOnCrash drops a .mdmp with the native stack beside it. Pinned to
            // C:\Users\Public (always exists + world-writable, so it survives an install under
            // Program Files) with %p to avoid PID collisions — ErrorFile is a static startup flag
            // that can't expand %LOCALAPPDATA% into the per-user log dir, so on the next launch
            // relocateNativeCrashArtifacts() in DesktopFileLogging.kt moves these files into
            // %LOCALAPPDATA%\NuvioHTPC\logs beside nuvio.log. Windows-only; harmless if empty.
            // NOTE: this does NOT catch abort()/fast-fail exits (e.g. a QuickJS assert) which
            // bypass the SEH handler — those need WER LocalDumps. No -Xrs is set, so the handler
            // stays installed.
            if (isWindowsHost) "-XX:ErrorFile=C:/Users/Public/nuvio_hs_err_pid%p.log" else null,
            if (isWindowsHost) "-XX:+CreateCoredumpOnCrash" else null,
            // Stop-the-world diagnostics (chasing the intermittent "UI froze then recovered"
            // hitch). A safepoint halts every Java thread — the Compose UI thread and all
            // coroutines — so a long one freezes the window and can't log a thing about itself:
            // nuvio.log just stops mid-burst with no error. Reconstructing one after the fact
            // from the JVM's cumulative counters only gets you a total (a real case: 7.7s at
            // safepoints across 57 of them, of which GC was 0.07s — so it was NOT GC, but the
            // counters can't say which VM operation it was). This names each operation and
            // splits reaching-the-safepoint from time-spent-at-it, which separates "the app
            // allocated too hard" from "the OS wasn't scheduling/paging our threads back in".
            // Cheap: one line per safepoint, capped at 3x2MB. Same fixed-path reasoning as
            // ErrorFile above — relocateJvmDiagnosticArtifacts() moves these next to nuvio.log.
            if (isWindowsHost) {
                "-Xlog:safepoint,gc:file=C:/Users/Public/nuvio_safepoint_pid%p.log" +
                    ":time,uptime,level,tags:filesize=2m,filecount=3"
            } else null,
            // Return idle heap to the OS. G1 only uncommits regions at the end of a concurrent
            // cycle, and with no flags set nothing triggers one while the app sits still — so the
            // heap ratchets up to whatever the busiest moment needed and stays there for the rest
            // of the session. The safepoint/gc logs above show exactly that: across seven long
            // sessions the live set after a collection was 205-359 MB while the committed heap sat
            // at 512 MB-1.4 GB. An HTPC spends most of its life parked on the home screen or
            // playing, neither of which allocates much, so those are hours of holding several
            // hundred MB of nothing.
            //
            // PeriodicGCInterval only fires when no GC has happened in the interval, i.e. only when
            // the app is genuinely idle, and the load threshold of 0 disables the "skip it if the
            // machine is busy" check (which reads system load average — unavailable on Windows,
            // where it reads as -1 and would suppress the collection entirely).
            //
            // InvokesConcurrent must be off. Left at its default the periodic collection runs a
            // concurrent cycle, which collects garbage but never uncommits: measured on a live
            // session, a full concurrent cycle completed with the heap still at 640 MB committed
            // against 164 MB used. Only a full collection resizes the heap. Forcing one on the same
            // session took it to 260 MB committed and the process working set from 1429 MB to
            // 1039 MB, in a 58 ms pause.
            //
            // The free ratios decide how far that shrink goes. At the default MaxHeapFreeRatio of
            // 70 the same collection would have stopped around 540 MB; at 30 it reached 260 MB.
            //
            // 15 minutes, not the interval's usual single-digit minutes, because the pause is the
            // whole cost here: the first collection after a burst reclaims, and every one after it
            // pays 58 ms to reclaim nothing. It cannot land while the app is being used (using it
            // allocates, which collects, which resets the timer) and it cannot touch playback (the
            // native player owns its own window and threads, outside the JVM), so the only case it
            // is perceptible is an animation running on an otherwise idle window. Hiding or
            // minimising the window trims immediately instead — see DesktopIdleHeapTrim, which is
            // where this reclaim happens for free and why the timer can afford to be this slow.
            //
            // All four are accepted silently under SerialGC too, which is what a machine with fewer
            // than two cores or under ~1.8 GB of RAM would pick instead of G1.
            "-XX:G1PeriodicGCInterval=900000",
            "-XX:G1PeriodicGCSystemLoadThreshold=0",
            "-XX:-G1PeriodicGCInvokesConcurrent",
            "-XX:MinHeapFreeRatio=10",
            "-XX:MaxHeapFreeRatio=30",
            smokePlayerUrl?.takeIf { it.isNotBlank() }?.let { "-Dnuvio.desktop.smokePlayerUrl=$it" },
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Nuvio"
            packageVersion = desktopReleasePackageVersion
            vendor = "Nuvio Media"
            // jdk.management is here for one call: the decoded-animation cache budget is a fraction
            // of physical RAM (see animatedImageCacheBudgetBytes), and OperatingSystemMXBean is the
            // only way to read that without going native. Measured cost of the module in a jlink
            // image: 2 MB.
            modules("java.net.http", "jdk.httpserver", "jdk.management")
            macOS {
                bundleID = "com.nuvio.media.desktop"
                iconFile.set(project.file("src/desktopMain/resources/icons/nuvio-app-icon.icns"))
                if (macosSigningIdentity != null) {
                    signing {
                        sign.set(true)
                        identity.set(macosSigningIdentity)
                    }
                }
                if (macosNotaryAppleId != null && macosNotaryTeamId != null && macosNotaryPassword != null) {
                    notarization {
                        appleID.set(macosNotaryAppleId)
                        teamID.set(macosNotaryTeamId)
                        password.set(macosNotaryPassword)
                    }
                }
            }
            windows {
                iconFile.set(project.file("src/desktopMain/resources/icons/nuvio-app-icon.ico"))
                shortcut = true
                menu = true
                menuGroup = "Nuvio"
            }
            linux {
                iconFile.set(project.file("src/desktopMain/resources/icons/nuvio-app-icon.png"))
            }
        }

        buildTypes.release.proguard {
            isEnabled.set(false)
        }
    }
}


