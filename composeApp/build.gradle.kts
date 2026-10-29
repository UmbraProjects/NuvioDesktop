import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.DefaultTask
import java.util.concurrent.TimeUnit
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

    // Bump when the built-in defaults written below change, so Gradle regenerates the file even
    // though local.properties (the other input) is absent and unchanged.
    @get:Input
    abstract val defaultsRevision: Property<String>

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

        // With no local.properties (or blank values) fall back to the official Nuvio backend. The
        // key is the public "anon" key the server itself publishes at
        // https://api.nuvio.tv/.well-known/nuvio; it is meant to ship inside every client.
        val supabaseUrl = props.getProperty("SUPABASE_URL").orEmpty().trim()
            .ifBlank { "https://api.nuvio.tv" }
        val supabaseAnonKey = props.getProperty("SUPABASE_ANON_KEY").orEmpty().trim()
            .ifBlank {
                "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJyb2xlIjoiYW5vbiIsImlzcyI6InN1cGFiYXNlIiwiaWF0IjoxNzgxNTIxMzQ2LCJleHAiOjE5MzkyMDEzNDZ9.tmQaj682pwzehpqlgCDMnySOqiUvpgRbrE43T4VJpDI"
            }

        val outDir = outputDir.get().asFile
        outDir.resolve("com/nuvio/app/core/network").apply {
            mkdirs()
            resolve("SupabaseConfig.kt").writeText(
                """
                |package com.nuvio.app.core.network
                |
                |object SupabaseConfig {
                |    const val URL = "$supabaseUrl" 
                |    const val ANON_KEY = "$supabaseAnonKey" 
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
                |    const val CLIENT_ID = "${props.getProperty("TRAKT_CLIENT_ID", "")}"
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
// The publisher Windows shows for Nuvio.exe (Explorer's Company, the jpackage vendor). This app is
// a fork published by Umbra Projects, not an official Nuvio Media release.
val desktopPublisher = "Umbra Projects"
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
    defaultsRevision.set("official-backend-fallback-1")
    val localProps = rootProject.layout.projectDirectory.file("local.properties")
    // @Optional only tolerates null, not a missing file (local.properties is gitignored, absent in CI).
    localPropertiesFile.set(providers.provider { localProps.takeIf { it.asFile.exists() } })
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
        // HidD_/HidP_ + SetupDi enumeration, for reading a DualSense directly. Unlike XInput these
        // are single-name core Windows DLLs present on every install, so a hard import is safe.
        "Hid.lib",
        "SetupAPI.lib",
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

// ---------------------------------------------------------------------------------------------
// Nuvio Engine (NuvioMedia/nuvio-engine): the in-process libtorrent P2P engine, built as one DLL
// that also carries the JNI bridge (see src/desktopMain/native/nuvio-engine/CMakeLists.txt).
//
// Upstream builds this with llvm-mingw inside Docker. Here it is the MSYS2 UCRT64 GCC toolchain
// plus the CMake that ships with Visual Studio Build Tools, both of which the player bridge
// already needs on a dev box. Nothing is downloaded at build time once the sibling checkout has
// fetched Boost/libtorrent (the first configure does that, ~200MB).
//
// The task is best-effort by design: without the engine checkout or the toolchain it logs why
// and produces nothing, and the app then falls back to TorrServer at runtime (the P2P settings
// row shows "not installed"). Only createDistributable treats a missing DLL as an error, so a
// release never silently ships without it.
// ---------------------------------------------------------------------------------------------
val nuvioEngineSourceDir = providers.gradleProperty("nuvio.engine.sourceDir").orNull
    ?.takeIf { it.isNotBlank() }
    ?.let(::File)
    ?: rootProject.projectDir.resolve("../nuvio-engine")
val nuvioEngineCMakeProject = layout.projectDirectory.dir("src/desktopMain/native/nuvio-engine")
val nuvioEngineBuildDir = layout.buildDirectory.dir("native/nuvio-engine/build")
val nuvioEngineOutput = layout.buildDirectory.file("native/windows/nuvio_engine.dll")
val nuvioEngineMsys2Root = providers.gradleProperty("nuvio.windows.msys2.ucrt64").orNull
    ?.takeIf { it.isNotBlank() }
    ?.let(::File)
    ?: File("C:/msys64/ucrt64")
val nuvioEngineConfiguredCMake = providers.gradleProperty("nuvio.windows.cmake").orNull
    ?.takeIf { it.isNotBlank() }
    ?.let(::File)
// CI/dev escape hatch: -Pnuvio.engine.optional=true lets a distributable be created without
// nuvio_engine.dll (P2P then falls back to TorrServer at runtime). Release builds leave it off.
val nuvioEngineOptional =
    providers.gradleProperty("nuvio.engine.optional").orNull?.toBoolean() == true
val nuvioEngineJniIncludeDir = File(providers.systemProperty("java.home").get()).resolve("include")

// Resolved at execution time: vswhere is a process launch, which the configuration cache forbids
// during configuration.
fun resolveNuvioEngineCMake(): File? {
    nuvioEngineConfiguredCMake?.let { return it.takeIf(File::isFile) }
    if (!windowsVsWhere.isFile) return null
    val vsRoot = runCatching {
        val process = ProcessBuilder(
            windowsVsWhere.absolutePath, "-latest", "-products", "*",
            "-requires", "Microsoft.VisualStudio.Component.VC.CMake.Project",
            "-property", "installationPath",
        ).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().readText().trim().lines().firstOrNull()?.trim()
    }.getOrNull()?.takeIf { it.isNotBlank() }?.let(::File) ?: return null
    return vsRoot.resolve("Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe").takeIf(File::isFile)
}

fun missingNuvioEngineInputs(cmake: File?): List<String> = listOfNotNull(
    "engine checkout ${nuvioEngineSourceDir.absolutePath} (clone NuvioMedia/nuvio-engine beside NuvioDesktop or pass -Pnuvio.engine.sourceDir)"
        .takeUnless { nuvioEngineSourceDir.resolve("CMakeLists.txt").isFile },
    "MSYS2 UCRT64 g++ under ${nuvioEngineMsys2Root.absolutePath} (pass -Pnuvio.windows.msys2.ucrt64)"
        .takeUnless { nuvioEngineMsys2Root.resolve("bin/g++.exe").isFile },
    "static OpenSSL in MSYS2 (pacman -S mingw-w64-ucrt-x86_64-openssl)"
        .takeUnless { nuvioEngineMsys2Root.resolve("lib/libssl.a").isFile },
    "CMake (Visual Studio 'C++ CMake tools' component, or -Pnuvio.windows.cmake=C:/path/cmake.exe)"
        .takeUnless { cmake?.isFile == true },
    "jni.h under ${nuvioEngineJniIncludeDir.absolutePath}"
        .takeUnless { nuvioEngineJniIncludeDir.resolve("jni.h").isFile },
)

val buildWindowsNuvioEngine = tasks.register("buildWindowsNuvioEngine") {
    notCompatibleWithConfigurationCache("Builds the host-local Nuvio Engine DLL with the MSYS2 toolchain.")
    enabled = isWindowsHost
    inputs.dir(nuvioEngineCMakeProject)
    if (nuvioEngineSourceDir.resolve("src").isDirectory) {
        inputs.dir(nuvioEngineSourceDir.resolve("src"))
        inputs.dir(nuvioEngineSourceDir.resolve("include"))
        inputs.dir(nuvioEngineSourceDir.resolve("cmake"))
        inputs.file(nuvioEngineSourceDir.resolve("CMakeLists.txt"))
    }
    outputs.file(nuvioEngineOutput)
    doLast {
        val output = nuvioEngineOutput.get().asFile
        val cmake = resolveNuvioEngineCMake()
        val missing = missingNuvioEngineInputs(cmake)
        if (missing.isNotEmpty()) {
            logger.warn(
                "Skipping Nuvio Engine build; P2P will use TorrServer. Missing: " + missing.joinToString("; "),
            )
            output.delete()
            return@doLast
        }
        checkNotNull(cmake)
        val buildDir = nuvioEngineBuildDir.get().asFile.apply { mkdirs() }
        val msysBin = nuvioEngineMsys2Root.resolve("bin")
        val msysForward = nuvioEngineMsys2Root.absolutePath.replace('\\', '/')
        val environment = System.getenv().toMutableMap().apply {
            this["PATH"] = "${msysBin.absolutePath};${this["PATH"] ?: this["Path"].orEmpty()}"
        }
        fun run(vararg command: String) {
            val process = ProcessBuilder(*command)
                .directory(buildDir)
                .redirectErrorStream(true)
                .apply { environment().putAll(environment) }
                .start()
            val log = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            if (code != 0) {
                throw GradleException("Nuvio Engine build step failed (exit $code): ${command.joinToString(" ")}\n${log.takeLast(6000)}")
            }
        }
        val commonFlags = "-D_WIN32_WINNT=0x0A00 -DWINVER=0x0A00 -ffunction-sections -fdata-sections"
        run(
            cmake.absolutePath,
            "-S", nuvioEngineCMakeProject.asFile.absolutePath,
            "-B", buildDir.absolutePath,
            "-G", "Ninja",
            "-DCMAKE_C_COMPILER=${msysBin.resolve("gcc.exe").absolutePath.replace('\\', '/')}",
            "-DCMAKE_CXX_COMPILER=${msysBin.resolve("g++.exe").absolutePath.replace('\\', '/')}",
            "-DCMAKE_MAKE_PROGRAM=${msysBin.resolve("ninja.exe").absolutePath.replace('\\', '/')}",
            "-DCMAKE_BUILD_TYPE=Release",
            "-DCMAKE_C_FLAGS=$commonFlags",
            "-DCMAKE_CXX_FLAGS=$commonFlags",
            // Fully static so the DLL carries its own libstdc++/winpthread; the JVM must not
            // need MSYS2 on PATH to load it.
            "-DCMAKE_SHARED_LINKER_FLAGS=-static -Wl,--dynamicbase,--nxcompat,--high-entropy-va,--gc-sections,--exclude-all-symbols",
            "-DCMAKE_POSITION_INDEPENDENT_CODE=ON",
            "-DNUVIO_ENGINE_ROOT=${nuvioEngineSourceDir.absolutePath.replace('\\', '/')}",
            "-DNUVIO_JNI_INCLUDE_DIR=${nuvioEngineJniIncludeDir.absolutePath.replace('\\', '/')}",
            "-DOPENSSL_ROOT_DIR=$msysForward",
            "-DOPENSSL_INCLUDE_DIR=$msysForward/include",
            "-DOPENSSL_SSL_LIBRARY=$msysForward/lib/libssl.a",
            "-DOPENSSL_CRYPTO_LIBRARY=$msysForward/lib/libcrypto.a",
            "-DOPENSSL_USE_STATIC_LIBS=TRUE",
        )
        run(cmake.absolutePath, "--build", buildDir.absolutePath, "--parallel")
        val built = buildDir.resolve("nuvio-engine-core/nuvio_engine.dll")
        check(built.isFile) { "Nuvio Engine build finished but ${built.absolutePath} is missing" }
        output.parentFile.mkdirs()
        built.copyTo(output, overwrite = true)
        // Symbols roughly triple the size; strip is best-effort because the DLL is complete without it.
        val strip = msysBin.resolve("strip.exe")
        if (strip.isFile) {
            runCatching { run(strip.absolutePath, "--strip-unneeded", output.absolutePath) }
                .onFailure { logger.warn("Could not strip ${output.name}: ${it.message}") }
        }
        logger.lifecycle("Nuvio Engine DLL: ${output.absolutePath} (${output.length() / (1024 * 1024)} MB)")
    }
}

// ---------------------------------------------------------------------------------------------
// quickjs-kt's native library, rebuilt from the vendored, patched sources in
// src/desktopMain/native/quickjs-kt (README there): every JS -> Kotlin binding call gets its own
// JNI local frame, so a scraper's page parse no longer grows the thread's handle table and keeps
// every fetched page alive until its job ends. Same MSYS2 UCRT64 GCC as the engine; one gcc call,
// no CMake. Best-effort like the engine: without the toolchain the app loads the jar's own
// (unpatched) copy, and only createDistributable insists on it.
// ---------------------------------------------------------------------------------------------
val quickJsSourceDir = layout.projectDirectory.dir("src/desktopMain/native/quickjs-kt")
val quickJsOutput = layout.buildDirectory.file("native/windows/libquickjs.dll")

val buildWindowsQuickJs = tasks.register("buildWindowsQuickJs") {
    notCompatibleWithConfigurationCache("Builds the host-local QuickJS DLL with the MSYS2 toolchain.")
    enabled = isWindowsHost
    inputs.dir(quickJsSourceDir)
    outputs.file(quickJsOutput)
    doLast {
        val output = quickJsOutput.get().asFile
        val msysBin = nuvioEngineMsys2Root.resolve("bin")
        val gcc = msysBin.resolve("gcc.exe")
        if (!gcc.isFile || !nuvioEngineJniIncludeDir.resolve("jni.h").isFile) {
            logger.warn("Skipping QuickJS build; plugins will use quickjs-kt's bundled DLL. Missing: MSYS2 UCRT64 gcc under ${msysBin.absolutePath} or jni.h")
            output.delete()
            return@doLast
        }
        val src = quickJsSourceDir.asFile
        val version = src.resolve("quickjs/VERSION").readText().trim()
        val sources = listOf("cutils", "dtoa", "libregexp", "libunicode", "quickjs").map { "quickjs/$it.c" } +
            listOf("common", "jni", "jni/mapping").flatMap { dir ->
                src.resolve(dir).listFiles { file -> file.extension == "c" }.orEmpty().sortedBy { it.name }.map { "$dir/${it.name}" }
            }
        output.parentFile.mkdirs()
        val command = listOf(
            gcc.absolutePath, "-O2", "-std=gnu99", "-shared", "-static", "-s",
            // ProcessBuilder passes embedded quotes through unescaped, and gcc's argv parsing would
            // strip them; the backslashes keep CONFIG_VERSION a string literal.
            "-DCONFIG_WIN32", "-DCONFIG_BIGNUM", "-DCONFIG_VERSION=\\\"$version\\\"",
            "-Iquickjs", "-Icommon", "-Ic-vector", "-Ijni", "-Ijni/mapping",
            "-I${nuvioEngineJniIncludeDir.absolutePath}", "-I${nuvioEngineJniIncludeDir.resolve("win32").absolutePath}",
        ) + sources + listOf(
            "quickjs.def",
            "-Wl,--dynamicbase,--nxcompat,--high-entropy-va,--no-insert-timestamp",
            "-o", output.absolutePath,
        )
        val process = ProcessBuilder(command)
            .directory(src)
            .redirectErrorStream(true)
            .apply { environment()["PATH"] = "${msysBin.absolutePath};${System.getenv("PATH").orEmpty()}" }
            .start()
        val log = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        if (code != 0 || !output.isFile) {
            val detail = "QuickJS build failed (exit $code)\n${log.takeLast(8000)}"
            if (nuvioEngineOptional) {
                // Same escape hatch as the Nuvio Engine: the app falls back to quickjs-kt's bundled DLL.
                logger.warn("$detail\nContinuing because -Pnuvio.engine.optional=true; using quickjs-kt's bundled DLL.")
                output.delete()
                return@doLast
            }
            throw GradleException(detail)
        }
        logger.lifecycle("QuickJS DLL: ${output.absolutePath} (${output.length() / 1024} KB)")
    }
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
        val forbiddenImports = if (windowsAllowWhisperAvfilter) emptyList() else
            listOf("libwhisper-1.dll", "ggml.dll").filter(avfilterText::contains)
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
// CI escape hatch: stock MSYS2 FFmpeg is built with --enable-whisper, so avfilter imports
// libwhisper-1.dll/ggml*.dll. With -Pnuvio.windows.allowWhisperAvfilter=true the check below is
// skipped and those DLLs are bundled (otherwise avfilter would fail to load). Release builds
// with Nuvio's own FFmpeg leave this off and keep the strict check + exclusion.
val windowsAllowWhisperAvfilter =
    providers.gradleProperty("nuvio.windows.allowWhisperAvfilter").orNull?.toBoolean() == true

val windowsUnusedLibmpvRuntimeDllsAll = listOf(
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

// CI escape hatch #2: the exclusion list above was traced against Nuvio's own FFmpeg/mpv build.
// A stock MSYS2 mpv/ffmpeg links a different set of optional libraries (libmysofa, libtheora,
// libFLAC, libvorbisfile, libsndfile, ...), and dropping any DLL that avcodec/avfilter/libmpv
// import makes LoadLibrary fail for libmpv-2.dll with a generic "Unable to load libmpv-2.dll".
// -Pnuvio.windows.bundleFullRuntime=true ships every DLL from the runtime dir instead.
val windowsBundleFullRuntime =
    providers.gradleProperty("nuvio.windows.bundleFullRuntime").orNull?.toBoolean() == true

val windowsUnusedLibmpvRuntimeDlls =
    if (windowsBundleFullRuntime) {
        emptyList()
    } else if (windowsAllowWhisperAvfilter) {
        windowsUnusedLibmpvRuntimeDllsAll.filterNot {
            it.startsWith("ggml", ignoreCase = true) || it.equals("libwhisper-1.dll", ignoreCase = true)
        }
    } else {
        windowsUnusedLibmpvRuntimeDllsAll
    }

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

// Large desktop resources that rarely change. The app jar changes every release, so everything in
// it is re-downloaded on every update; these ~30MB (compressed) instead ship as loose files under
// <install>\resources\ with the same relative path, staged by the app-image step below, and only
// download again when they actually change. Readers go through PackagedResources, which falls back
// to the classpath — so with -Pnuvio.desktop.leanJar=false they simply stay in the jar.
val externalDesktopResources = listOf(
    "torrserver/**",
    "backdrop-framing/**",
    "anime-list-mini.json",
)
val desktopResourcesSourceDir = layout.projectDirectory.dir("src/desktopMain/resources").asFile

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
        // Shipped as loose files under <install>\resources instead; see externalDesktopResources.
        if (leanDesktopJar) {
            exclude(externalDesktopResources)
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
            buildWindowsNuvioEngine,
            buildWindowsQuickJs,
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
            // The engine DLL is staged beside Nuvio.exe only (not via the jar), so it must be an
            // input here or a rebuilt DLL never reaches an otherwise up-to-date app image.
            inputs.files(nuvioEngineOutput).optional()
            inputs.files(quickJsOutput).optional()
            inputs.files(
                fileTree(desktopResourcesSourceDir) { include(externalDesktopResources) },
            )
            // jpackage clears this folder before writing the image. A build run from it in portable
            // mode keeps the user's whole data folder (settings, sign-ins, caches) right here, so
            // building would delete it — and zipping the folder for a release would publish it.
            doFirst {
                val imageDir = layout.buildDirectory.dir("compose/binaries/$relativeImagePath").get().asFile
                val portableData = listOf("data", "data.old", "portable", "portable.txt")
                    .map(imageDir::resolve)
                    .filter { it.exists() }
                if (portableData.isNotEmpty()) {
                    throw GradleException(
                        "Portable-mode data found in the build output, which this task would delete: " +
                            portableData.joinToString { it.name } + " in $imageDir. Turn Portable Mode off " +
                            "in Settings > Account and restart Nuvio once (that moves the data back to " +
                            "AppData), then delete data.old and build again.",
                    )
                }
            }
            doLast {
                val imageDir = layout.buildDirectory.dir("compose/binaries/$relativeImagePath").get().asFile
                check(imageDir.resolve("Nuvio.exe").isFile) {
                    "Windows app image was not created at $imageDir"
                }
                copy {
                    from(windowsPlayerBridgeOutput)
                    from(windowsPlayerRuntimeOutput)
                    from(nuvioEngineOutput)
                    from(quickJsOutput)
                    into(imageDir)
                }
                check(nuvioEngineOptional || nuvioEngineOutput.get().asFile.isFile) {
                    "Nuvio Engine DLL was not built; a distributable must not ship without it. " +
                        "Missing inputs: ${missingNuvioEngineInputs(resolveNuvioEngineCMake()).joinToString("; ").ifBlank { "(build failed, see buildWindowsNuvioEngine output)" }}"
                }
                check(nuvioEngineOptional || quickJsOutput.get().asFile.isFile) {
                    "libquickjs.dll was not built (see buildWindowsQuickJs); a distributable must not fall back to quickjs-kt's unpatched copy."
                }
                copy {
                    from(windowsPythonLibOutput)
                    into(imageDir.resolve("lib/python3.14"))
                }
                if (leanDesktopJar) {
                    // A previous build's copy would survive a resource that was since removed.
                    delete(imageDir.resolve("resources"))
                    copy {
                        from(desktopResourcesSourceDir) {
                            include(externalDesktopResources)
                        }
                        into(imageDir.resolve("resources"))
                    }
                    // TorrServer.exe is gitignored, so a checkout without it builds an image
                    // without TorrServer exactly as the jar used to.
                    if (!imageDir.resolve("resources/torrserver/windows-amd64/TorrServer.exe").isFile) {
                        logger.warn("TorrServer.exe not found under $desktopResourcesSourceDir; the app image ships without it.")
                    }
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
/** The checkout's short HEAD commit, or null outside a git checkout. */
fun gitShortCommit(repoRoot: File): String? = runCatching {
    val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .directory(repoRoot)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor() == 0) output.takeIf { it.matches(Regex("[0-9a-f]{6,40}")) } else null
}.getOrNull()

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
    val gitCommit = gitShortCommit(repoRoot)

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
                // The source entry's time, not now: an unchanged jar must trim to the same bytes,
                // or everything downstream of the app image (native-image above all) reruns.
                out.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
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

// The onnxruntime jar (hero backdrop framing) carries natives for five platforms, ~51MB; this
// app only ever loads win-x64 (~6MB compressed). Drop the rest from the packaged copy.
fun trimOnnxRuntimeJar(appDir: File, logger: org.gradle.api.logging.Logger) {
    val ortJar = appDir
        .listFiles { file -> file.isFile && file.name.startsWith("onnxruntime") && file.name.endsWith(".jar") }
        ?.singleOrNull()
        ?: return
    val nativeRoot = "ai/onnxruntime/native/"
    val keptNatives = "${nativeRoot}win-x64/"
    val trimmed = File(ortJar.parentFile, "${ortJar.name}.trimmed")
    var dropped = 0
    ZipFile(ortJar).use { zip ->
        check(zip.getEntry("${keptNatives}onnxruntime.dll") != null) {
            "Refusing to trim ${ortJar.name}: it has no ${keptNatives}onnxruntime.dll, so its layout changed."
        }
        ZipOutputStream(trimmed.outputStream().buffered()).use { out ->
            zip.entries().asSequence().forEach { entry ->
                val keep = !entry.name.startsWith(nativeRoot) ||
                    entry.name == nativeRoot ||
                    entry.name.startsWith(keptNatives)
                if (!keep) {
                    dropped++
                    return@forEach
                }
                // Same as the icons trim: keep the source time so the output is reproducible.
                out.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
                zip.getInputStream(entry).use { it.copyTo(out) }
                out.closeEntry()
            }
        }
    }
    val sizeBefore = ortJar.length()
    check(ortJar.delete()) { "Could not replace ${ortJar.absolutePath} with its trimmed copy" }
    check(trimmed.renameTo(ortJar)) { "Could not move ${trimmed.absolutePath} into place" }
    logger.lifecycle(
        "Trimmed ${ortJar.name} to win-x64 natives: dropped $dropped entries " +
            "(${sizeBefore / 1024 / 1024}MB -> ${ortJar.length() / 1024 / 1024}MB)",
    )
}

// Opts Nuvio.exe into the Windows Segment Heap (Windows 10 2004+; older builds ignore the element).
// Every malloc in the process — the JVM's own, Skia's decoded artwork, libmpv/ffmpeg — lands in the
// process heap, and on the default NT heap a 2026-10-15 dump on Home showed 149 MB of its 436 MB
// committed segment sitting in free blocks (34% external fragmentation) that it never decommits.
// The launcher's jpackage manifest (DPI awareness, supportedOS) is kept; only <heapType> is added.
// Perf-only, so a missing SDK tool warns instead of failing the build.
fun enableSegmentHeap(exe: File, logger: org.gradle.api.logging.Logger) {
    val mt = File("C:/Program Files (x86)/Windows Kits/10/bin").listFiles()
        ?.filter { it.isDirectory && it.name.startsWith("10.") }
        ?.sortedByDescending { it.name }
        ?.map { it.resolve("x64/mt.exe") }
        ?.firstOrNull(File::isFile)
    if (mt == null || !exe.isFile) {
        logger.warn("Segment Heap not enabled: ${if (mt == null) "mt.exe (Windows SDK) not found" else "$exe missing"}")
        return
    }
    fun run(vararg args: String) {
        val process = ProcessBuilder(mt.absolutePath, "-nologo", *args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "mt.exe ${args.joinToString(" ")} failed: $output" }
    }
    val manifest = File(exe.parentFile, "${exe.name}.manifest.tmp")
    try {
        run("-inputresource:${exe.absolutePath};#1", "-out:${manifest.absolutePath}")
        val original = manifest.readText()
        if ("SegmentHeap" in original) return
        val closing = "</asmv3:windowsSettings>"
        if (closing !in original) {
            logger.warn("Segment Heap not enabled: launcher manifest has no $closing to extend")
            return
        }
        manifest.writeText(
            original.replace(
                closing,
                "<heapType xmlns=\"http://schemas.microsoft.com/SMI/2020/WindowsSettings\">SegmentHeap</heapType>$closing",
            ),
        )
        // jpackage leaves the launcher read-only, which UpdateResource reports as "Access is denied".
        val wasReadOnly = !exe.canWrite()
        exe.setWritable(true)
        try {
            run("-manifest", manifest.absolutePath, "-outputresource:${exe.absolutePath};#1")
        } finally {
            if (wasReadOnly) exe.setReadOnly()
        }
        logger.lifecycle("Enabled Segment Heap in ${exe.name}")
    } finally {
        manifest.delete()
    }
}

// Gives the bundled runtime the default CDS archive (bin/server/classes.jsa) a stock JDK ships
// with and jlink leaves out. Without it every launch parses and verifies the ~1,300 JDK classes
// the JVM needs before main() from lib/modules; with it they are mapped in from the archive. The
// runtime keeps no java.exe (jpackage strips native commands), so the build JDK's launcher is
// copied in for the dump and removed again — valid only because the runtime's jvm.dll is that
// JDK's byte for byte, which is checked first. Startup-only, so any failure warns and skips.
fun generateBaseCdsArchive(runtimeDir: File, logger: org.gradle.api.logging.Logger) {
    val jdk = File(System.getProperty("java.home"))
    val runtimeJvm = runtimeDir.resolve("bin/server/jvm.dll")
    val jdkJvm = jdk.resolve("bin/server/jvm.dll")
    val jdkJava = jdk.resolve("bin/java.exe")
    if (!runtimeJvm.isFile || !jdkJvm.isFile || !jdkJava.isFile ||
        !runtimeJvm.readBytes().contentEquals(jdkJvm.readBytes())
    ) {
        logger.warn("Base CDS archive not generated: the runtime's jvm.dll is not the build JDK's ($jdk)")
        return
    }
    val java = runtimeDir.resolve("bin/java.exe")
    val archive = runtimeDir.resolve("bin/server/classes.jsa")
    try {
        jdkJava.copyTo(java, overwrite = true)
        val process = ProcessBuilder(java.absolutePath, "-Xshare:dump").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0 || !archive.isFile) {
            logger.warn("Base CDS archive not generated: java -Xshare:dump failed:\n$output")
            archive.delete()
            return
        }
        logger.lifecycle("Generated base CDS archive ${archive.name} (${archive.length() / 1024 / 1024} MB)")
    } finally {
        java.delete()
    }
}

// Ships startup-classes.lst beside the jars, keyed to this image, so StartupClassPreloader replays
// it on the first launch after an update instead of running its JFR learning pass (no preload plus
// a recording: the slowest, jankiest launch a user saw). The runtime accepts it without a key
// check — sitting beside these jars is what ties it to this build — so the header is informational.
fun bundleStartupClassList(appDir: File, source: File, logger: org.gradle.api.logging.Logger) {
    syncLearnedStartupClassList(source, logger)
    if (!source.isFile) {
        logger.warn("Startup class list not bundled: $source missing")
        return
    }
    val body = source.readText()
    appDir.resolve("startup-classes.lst").writeText("# nuvio-startup-classes bundled\n$body")
    logger.lifecycle("Bundled startup class list (${body.lines().count { it.isNotBlank() }} lines)")
}

// Keeps the shipped list fresh without a manual step: when this machine has learned a list newer
// than composeApp/startup-classes.lst (the app relearns on its own once the bundled list goes
// stale), its body replaces the repo copy before bundling, ready to commit with the release.
fun syncLearnedStartupClassList(target: File, logger: org.gradle.api.logging.Logger) {
    val learned = File(System.getenv("LOCALAPPDATA").orEmpty(), "NuvioHTPC/startup-classes.lst")
    if (!learned.isFile || (target.isFile && learned.lastModified() <= target.lastModified())) return
    val lines = learned.readLines()
    if (lines.firstOrNull()?.startsWith("# nuvio-startup-classes ") != true || lines.size < 1_000) return
    target.writeText(lines.drop(1).joinToString("\n", postfix = "\n"))
    logger.lifecycle("Updated ${target.name} from this machine's learned list ($learned, ${lines.size - 1} lines)")
}

// On by default; -Pnuvio.trainClassArchive=false skips it for a quick iteration loop (the first
// launch of that build then trains instead, behind its "Preparing Nuvio…" window).
val trainClassArchive = providers.gradleProperty("nuvio.trainClassArchive").orNull?.toBooleanStrictOrNull() ?: true

// A build heading for the GraalVM native image uses createDistributable only for its jars:
// native-image ignores the JVM's CDS archives (createNativeDistributable drops the .jsa), so
// generating the base archive and training the app archive there is time thrown away.
val buildingNativeImage = gradle.startParameter.taskNames.any { name ->
    name.substringAfterLast(':') in setOf(
        "generateNativeMetadata",
        "nativeCompile",
        "createNativeDistributable",
        "nativeSmoke",
        "packageNativeReleaseZip",
    )
}

// Builds the dev image's app class archive now, so its first launch is already a fast one rather
// than the training launch (AppClassArchiveTraining). Runs the packaged app with
// --build-class-archive: no window and none of the app's startup, it loads the startup class list
// and exits, which is when the JVM writes the archive. Startup-only, so a failure warns.
fun trainClassArchive(imageDir: File, logFile: File, logger: org.gradle.api.logging.Logger) {
    val exe = imageDir.resolve("Nuvio.exe")
    val archive = imageDir.resolve("app/nuvio-app.jsa")
    if (!exe.isFile) return
    archive.delete()
    logFile.parentFile.mkdirs()
    val startedAt = System.currentTimeMillis()
    val process = ProcessBuilder(exe.absolutePath, "--build-class-archive")
        .directory(imageDir)
        .redirectErrorStream(true)
        .redirectOutput(logFile)
        .start()
    if (!process.waitFor(3, TimeUnit.MINUTES)) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
        logger.warn("Class archive training timed out; the first launch will train instead. See $logFile")
        return
    }
    // The launcher may hand off to a child JVM; the archive is written while that one exits.
    val exePath = exe.canonicalPath
    val deadline = System.currentTimeMillis() + 60_000
    while (System.currentTimeMillis() < deadline &&
        ProcessHandle.allProcesses().anyMatch { handle ->
            handle.info().command().map { it.equals(exePath, ignoreCase = true) }.orElse(false)
        }
    ) {
        Thread.sleep(200)
    }
    val seconds = (System.currentTimeMillis() - startedAt) / 1000.0
    if (archive.isFile) {
        logger.lifecycle("Trained app class archive ${archive.name} (${archive.length() / 1024 / 1024} MB) in ${"%.1f".format(seconds)}s")
    } else {
        logger.warn("Class archive training wrote no archive (exit ${process.exitValue()}); the first launch will train instead. See $logFile")
    }
}

val repoRootDir = rootProject.layout.projectDirectory.asFile

mapOf(
    "createDistributable" to "main",
    "createReleaseDistributable" to "main-release",
).forEach { (taskName, imageFlavor) ->
    tasks.matching { it.name == taskName }.configureEach {
        notCompatibleWithConfigurationCache("Rewrites the packaged material-icons-extended and onnxruntime jars in the app image.")
        inputs.file(layout.projectDirectory.file("startup-classes.lst")).optional()
        // So a JVM build after a native one regenerates the archives the native one skipped.
        inputs.property("cdsArchives", !buildingNativeImage)
        // The channel and commit are stamped into build-info.properties below, so they are inputs:
        // otherwise a stable build right after a nightly (or a nightly right after a stable) from
        // unchanged code is UP-TO-DATE and ships the other channel's image with the old stamp.
        // createNativeDistributable copies build-info.properties, so the native zip follows.
        inputs.property("nuvioDesktopChannel", provider { resolveDesktopChannel(desktopChannelOverride, logger) })
        // providers.exec, not ProcessBuilder: the configuration cache resolves inputs when it stores
        // the build, and only allows external processes started this way.
        inputs.property(
            "nuvioGitCommit",
            providers.exec {
                commandLine("git", "rev-parse", "--short", "HEAD")
                workingDir = repoRootDir
                isIgnoreExitValue = true
            }.standardOutput.asText.map { it.trim() },
        )
        doLast {
            val binariesDir = layout.buildDirectory.dir("compose/binaries/$imageFlavor/app").get().asFile
            val appDir = listOf("Nuvio/app", "Nuvio.app/Contents/app", "nuvio/lib/app")
                .map(binariesDir::resolve)
                .firstOrNull(File::isDirectory)
            check(appDir != null) { "No packaged app directory found under $binariesDir" }
            trimMaterialIconsExtendedJar(appDir, logger)
            trimOnnxRuntimeJar(appDir, logger)
            // After the jar trims above: the key covers jar sizes, which they change.
            bundleStartupClassList(appDir, layout.projectDirectory.file("startup-classes.lst").asFile, logger)
            if (isWindowsHost) {
                enableSegmentHeap(appDir.parentFile.resolve("Nuvio.exe"), logger)
                if (!buildingNativeImage) generateBaseCdsArchive(appDir.parentFile.resolve("runtime"), logger)
            }
            writeAppImageBuildInfo(
                appDir = appDir,
                versionName = desktopReleaseVersionName,
                versionCode = desktopReleaseVersionCode,
                channelOverride = desktopChannelOverride,
                repoRoot = repoRootDir,
                logger = logger,
            )
            // Last: the archive is validated against the exact jars, so nothing may change them
            // after it is written. Dev images only — a release zip must not carry a 100 MB archive
            // tied to this machine's files; installs train their own on first launch.
            if (isWindowsHost && imageFlavor == "main" && trainClassArchive && !buildingNativeImage) {
                trainClassArchive(appDir.parentFile, layout.buildDirectory.file("tmp/class-archive-training.log").get().asFile, logger)
            }
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
                // Lambdas and string concatenation as ordinary classes and bytecode rather than
                // invokedynamic. Measured 2026-09-14 on the launch overlay: with every class
                // already preloaded (StartupClassPreloader), over half of the remaining ~1s UI-thread
                // stall was the JVM spinning a hidden proxy class per lambda call site the first
                // time Home executed it (InnerClassLambdaMetafactory / LambdaForm /
                // StringConcatFactory). Real classes can be learned and preloaded off the UI
                // thread; indy proxies only exist once the site runs. Costs jar size, not runtime.
                "-Xlambdas=class",
                "-Xsam-conversions=class",
                "-Xstring-concat=inline",
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
            // The album player resolves tracks through YouTube Music (MusicResolver, MusicMetadataClient),
            // which reaches into the trailer extractor above.
            kotlin.srcDir(fullCommonSourceDir.resolve("com/nuvio/app/features/music"))
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.ktor.client.cio)
                implementation("com.squareup.okhttp3:okhttp:4.12.0")
                // fork:music - reads MusicBrainz's .tar.xz data dumps for the offline copy (pure Java, public domain).
                implementation("org.tukaani:xz:1.10")
                implementation(libs.quickjs.kt)
                implementation(libs.ksoup)
                // Automatic hero backdrop framing (U2-Netp saliency + YuNet faces). The packaged jar
                // is trimmed to its win-x64 natives; see trimOnnxRuntimeJar.
                implementation("com.microsoft.onnxruntime:onnxruntime:1.31.0")
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
            implementation(libs.supabase.realtime)
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
                // Serves byte ranges to the AutoSync embedded-subtitle index loader tests.
                implementation("com.squareup.okhttp3:mockwebserver:4.12.0")
            }
        }
    }
}

// Two input tests need a real focused window (see GamepadInjectionRouteTest). Focus is global, so
// they briefly steal keystrokes from whatever the developer is typing into. They are therefore
// opt-in: run them with `-Pnuvio.focusTests` before shipping input changes, and on CI where nobody
// is at the keyboard.
tasks.withType<Test>().configureEach {
    // DesktopStorage would otherwise read and write the real %LOCALAPPDATA%/NuvioHTPC, including
    // moving its settings into account folders the first time a test opens a store.
    val testDataDir = layout.buildDirectory.dir("test-data-dir").get().asFile
    systemProperty("nuvio.desktop.dataDir", testDataDir.absolutePath)
    doFirst { testDataDir.deleteRecursively() }
    systemProperty(
        "nuvio.focusTests",
        (providers.gradleProperty("nuvio.focusTests").orNull ?: "false").ifBlank { "true" },
    )
    // Plugin tests run on the patched libquickjs.dll the app ships, not the jar's own copy.
    dependsOn(buildWindowsQuickJs)
    val quickJsDll = quickJsOutput
    inputs.files(quickJsDll).optional()
    doFirst {
        quickJsDll.get().asFile.takeIf(File::isFile)?.let { dll ->
            systemProperty("com.dokar.quickjs.library.path", dll.parentFile.absolutePath)
        }
    }
    // Opt-in live-swarm probe for the P2P engine (NuvioEngineNetworkProbeTest); off by default.
    systemProperty("nuvio.p2pProbeHash", providers.gradleProperty("nuvio.p2pProbeHash").orNull ?: "")
    systemProperty("nuvio.p2pProbePreloadMb", providers.gradleProperty("nuvio.p2pProbePreloadMb").orNull ?: "")
    systemProperty("nuvio.p2pSeekProbeHash", providers.gradleProperty("nuvio.p2pSeekProbeHash").orNull ?: "")
    systemProperty("nuvio.p2pSeekProbeOffsetsMb", providers.gradleProperty("nuvio.p2pSeekProbeOffsetsMb").orNull ?: "")
    // Engine-side diagnostics for the probes (read by nuvio_engine.dll via getenv).
    providers.gradleProperty("nuvio.engineEnv").orNull?.split(';')?.forEach { pair ->
        val (k, v) = pair.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "1" } }
        environment(k, v)
    }
}

compose.desktop {
    application {
        mainClass = "com.nuvio.app.MainKt"
        val smokePlayerUrl = providers.gradleProperty("nuvio.desktop.smokePlayerUrl").orNull
            ?: System.getenv("NUVIO_DESKTOP_SMOKE_PLAYER_URL")
        // Direct3D, matching DesktopRendererApi's default and what Settings shows. main() overwrites
        // this from the saved setting before the first window, so it only matters if that step
        // fails. OpenGL was the default in 1.15.0 and
        // black-screened fullscreen playback on some drivers (see DesktopRendererApi). The
        // 2026-09-07 measurement still holds — D3D has a ~3 ms per-frame floor OpenGL does not —
        // which is why OpenGL stays selectable in Settings.
        val skikoRenderApi = "DIRECT3D"
        jvmArgs += listOfNotNull(
            "-Dapple.awt.application.appearance=NSAppearanceNameDarkAqua",
            "-Dskiko.renderApi=$skikoRenderApi",
            // Java2D's own Direct3D 9 pipeline. Compose draws through Skiko (D3D12/OpenGL) and mpv
            // through D3D11, so Java2D only paints the tray-menu dialog and the empty SwingPanel
            // hosts — not worth a third GPU device (and its driver memory) in the process. GDI
            // draws those few Swing pieces instead.
            "-Dsun.java2d.d3d=false",
            // App class-data sharing (JDK 19+). The JVM maps Nuvio's and its libraries' classes
            // pre-parsed and pre-verified from this archive instead of loading them out of the jars,
            // which was most of startup. Missing or stale (an update, a new JDK), the launch runs
            // normally and the JVM writes a fresh one at exit. It sits on the base archive that
            // generateBaseCdsArchive adds to the runtime. Never shipped: it is built on each machine.
            if (isWindowsHost) "-XX:+AutoCreateSharedArchive" else null,
            if (isWindowsHost) "-XX:SharedArchiveFile=\$APPDIR/nuvio-app.jsa" else null,
            // Lets AppClassArchive ask the JDK whether this launch is building the archive
            // (jdk.internal.misc.CDS.isDumpingArchive), which decides AppClassArchiveTraining.
            if (isWindowsHost) "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED" else null,
            "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.awt.windows=ALL-UNNAMED",
            // Native-crash diagnostics (chasing the silent, log-less CTD on plugin users —
            // suspected QuickJS/JNI access violation). On an EXCEPTION_ACCESS_VIOLATION the
            // HotSpot handler writes an hs_err_pid<pid>.log naming the faulting native module,
            // and CreateCoredumpOnCrash drops a .mdmp with the native stack in the working
            // directory. ErrorFile is a static startup flag that can't expand %LOCALAPPDATA%, so it
            // points at the app image ($APPDIR, expanded by the jpackage launcher; in a Gradle run
            // or an unwritable install the JVM falls back to the working directory, then TEMP), and
            // relocateJvmDiagnosticArtifacts() in DesktopFileLogging.kt moves both into
            // %LOCALAPPDATA%\NuvioHTPC\logs on the next launch, keeping the newest two dumps.
            // (It used to be C:\Users\Public, which every local account can read and pre-create.)
            // NOTE: this does NOT catch abort()/fast-fail exits (e.g. a QuickJS assert) which
            // bypass the SEH handler — those need WER LocalDumps. No -Xrs is set, so the handler
            // stays installed.
            if (isWindowsHost) "-XX:ErrorFile=\$APPDIR/nuvio_hs_err_pid%p.log" else null,
            if (isWindowsHost) "-XX:+CreateCoredumpOnCrash" else null,
            // The stop-the-world (safepoint + GC) log is no longer a startup flag: it was
            // -Xlog:...:file=C:/Users/Public/... only because -Xlog cannot expand %LOCALAPPDATA%.
            // enableSafepointLog() in DesktopFileLogging.kt turns it on from main() via VM.log,
            // straight into the per-user logs folder.
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
            // Explicit System.gc() calls run concurrently instead of as a stop-the-world Full GC.
            // Measured: two Full GCs per session, 30s apart, 52-75ms each, against 1-15ms for every
            // natural collection - and a 52-75ms pause is a frame gap, which on a variable-refresh
            // display collapses the refresh rate and dims the panel. This must live here, not in a
            // hand-edited Nuvio.cfg: createDistributable regenerates that file and has already
            // silently voided this experiment once.
            //
            // Trade-off, deliberate and worth revisiting: the line above forces PERIODIC GCs to stay
            // full and compacting because that is what returns memory to the OS. This makes the
            // EXPLICIT one in DesktopIdleHeapTrim (window hidden) concurrent too, so it compacts
            // less. That pause is invisible - nobody is looking at a hidden window - so if the heap
            // no longer shrinks on hide, prefer fixing the unintended 30s callers instead.
            "-XX:+ExplicitGCInvokesConcurrent",
            "-XX:MinHeapFreeRatio=10",
            "-XX:MaxHeapFreeRatio=30",
            // A ceiling, so allocation bursts cannot balloon the young generation. With no -Xmx
            // the JVM allowed 1/4 of RAM (8 GB on a 32 GB machine), and fast Home scrolling grew
            // the committed heap to 1,260 MB against a ~380 MB live set, which was the whole
            // 2.3 GB working-set peak (2026-10-16, Docs/plans/image-memory-plan.md). Live data
            // peaked ~550 MB in that day's logs, so 1 GB leaves ~2x headroom; if an
            // OutOfMemoryError ever appears in nuvio.log, raise this rather than remove it.
            "-Xmx1g",
            smokePlayerUrl?.takeIf { it.isNotBlank() }?.let { "-Dnuvio.desktop.smokePlayerUrl=$it" },
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Nuvio"
            packageVersion = desktopReleasePackageVersion
            vendor = desktopPublisher
            // Becomes the exe's FileDescription, which is the name Explorer shows for a pin made
            // straight from Nuvio.exe (and the taskbar tooltip). The exe itself stays Nuvio.exe:
            // the in-place updater, taskbar pins and the official app's shortcut-name checks all
            // key on that filename.
            description = "Nuvio HTPC"
            // jdk.management is here for one call: the decoded-animation cache budget is a fraction
            // of physical RAM (see animatedImageCacheBudgetBytes), and OperatingSystemMXBean is the
            // only way to read that without going native. Measured cost of the module in a jlink
            // image: 2 MB.
            // jdk.jfr: the sampling profiler FrameProfiler starts in bench mode. Small
            // addition to the runtime image, and the only way to attribute UI-thread CPU
            // that Compose draw probes cannot see.
            modules("java.net.http", "jdk.httpserver", "jdk.management", "jdk.jfr")
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

// ---------------------------------------------------------------------------------------------
// GraalVM native image (Docs/plans/native-image-shipping.md, step 2). These replace the spike's
// tools\ni-spike scripts. None of them runs as part of createDistributable or a normal build:
//
//   nativeImageTrace           run the JVM app image on GraalVM's JVM with the tracing agent
//                              (user-driven; merges into native-image/agent/)
//   nativeCompile              native-image over createDistributable's jars -> build/native-image/work
//   createNativeDistributable  lay that out like the jpackage image -> build/native-image/Nuvio
//   packageNativeReleaseZip    zip it as the release asset -> build/native-image/release/Nuvio.zip
//
// GraalVM for JDK 25 comes from -Pnuvio.graalvm.home, GRAALVM_HOME or local.properties. The build
// takes ~2.5 min and peaks at ~12 GB RSS in its own process; afterwards run `gradlew --stop` and
// kill leftover java.exe (a Kotlin daemon has been seen at 5 GB).
// ---------------------------------------------------------------------------------------------
val nativeImageInputsDir = layout.projectDirectory.dir("native-image")
val nativeJvmAppImageDir = layout.buildDirectory.dir("compose/binaries/main/app/Nuvio")
val nativeImageWorkDir = layout.buildDirectory.dir("native-image/work")
val nativeImageDistDir = layout.buildDirectory.dir("native-image/Nuvio")
val nativeImageReleaseDir = layout.buildDirectory.dir("native-image/release")
// -march=native would build for this PC's CPU only (illegal-instruction crash elsewhere);
// `compatibility` measured the same as `native` on a Ryzen 9 5900X (plan step 2b).
val nativeImageMarch = providers.gradleProperty("nuvio.native.march").orNull?.trim()
    ?.takeIf { it.isNotEmpty() } ?: "compatibility"
// -Pnuvio.native.qa=true: a test build that REPORTS each missing reflection / JNI / resource /
// serialization registration (with a stack trace) and carries on, instead of failing later at an
// arbitrary point. Used like a nightly, its native-qa-console.log lists every metadata gap the
// session hit (plan step 3). Never ship it.
val nativeImageQa = providers.gradleProperty("nuvio.native.qa").orNull.toBoolean()
// -Pnuvio.native.quick=true: GraalVM's quick build mode (-Ob) for local iteration. Skips most of the
// compiler's optimisation work, so the image builds faster and runs somewhat slower. Never ship it.
val nativeImageQuick = providers.gradleProperty("nuvio.native.quick").orNull.toBoolean()

fun resolveGraalVmHome(): File {
    val candidates = listOfNotNull(
        localOrEnvProperty("nuvio.graalvm.home"),
        localOrEnvProperty("GRAALVM_HOME"),
        "C:/Software/Claude/tools/graalvm25/graalvm-community-25.4.4.1.1+1.1",
    ).map(::File)
    val home = candidates.firstOrNull { it.resolve("bin/native-image.cmd").isFile }
        ?: throw GradleException(
            "GraalVM for JDK 25 was not found. Pass -Pnuvio.graalvm.home=C:/path/to/graalvm or set GRAALVM_HOME.",
        )
    val javaVersion = home.resolve("release").takeIf { it.isFile }?.readText()
        ?.let { Regex("JAVA_VERSION=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
    if (javaVersion == null || !javaVersion.startsWith("25")) {
        // CE 21.0.2 cannot build the app (Kotlin suspend try/catch parser bug), and the metadata
        // targets the JDK 25 class library.
        throw GradleException("native-image needs GraalVM for JDK 25; $home is ${javaVersion ?: "an unknown version"}.")
    }
    return home
}

fun resolveWindowsVcvars(): File {
    windowsVcvarsPath?.let { path ->
        return File(path).takeIf { it.isFile }
            ?: throw GradleException("-Pnuvio.windows.vcvars.path points at a missing file: $path")
    }
    if (windowsVsWhere.isFile) {
        val found = ProcessBuilder(
            windowsVsWhere.absolutePath, "-latest", "-products", "*",
            "-requires", "Microsoft.VisualStudio.Component.VC.Tools.x86.x64",
            "-find", windowsVcvarsRelativePath,
        ).redirectErrorStream(true).start().inputStream.bufferedReader().readText()
            .lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        if (found != null && File(found).isFile) return File(found)
    }
    throw GradleException(
        "Visual Studio C++ toolchain was not found. Install MSVC or pass -Pnuvio.windows.vcvars.path=C:\\path\\to\\vcvars64.bat.",
    )
}

/** What jpackage's Nuvio.cfg says about the app: classpath, main class, JVM options. */
class NativeAppImageConfig(val classpath: List<String>, val mainClass: String, val javaOptions: List<String>)

fun readNativeAppImageConfig(appDir: File): NativeAppImageConfig {
    val lines = appDir.resolve("Nuvio.cfg").readLines()
    fun values(key: String) = lines.filter { it.startsWith("$key=") }
        .map { it.substringAfter('=').replace("\$APPDIR", appDir.absolutePath) }
    return NativeAppImageConfig(
        classpath = values("app.classpath"),
        mainClass = values("app.mainclass").single(),
        javaOptions = values("java-options"),
    )
}

fun isRunningFrom(exe: File): Boolean {
    val target = exe.absoluteFile.normalize()
    return ProcessHandle.allProcesses().anyMatch { process ->
        process.info().command().map { File(it).absoluteFile.normalize() == target }.orElse(false)
    }
}

// ---- Generated metadata (plan step 4) --------------------------------------------------------
// Entries that follow a rule are derived at build time, so a new @Serializable class or a new JNI
// lookup in the app's C++ can never be the one somebody forgot to list.
val nativeImageGeneratedDir = layout.buildDirectory.dir("native-image/generated")
val nativeJniSources = fileTree("src/desktopMain/native") {
    include("windows/**/*.cpp", "windows/**/*.c", "nuvio-engine/**/*.cpp", "nuvio-engine/**/*.c")
}

/** One `reflection` entry of a reachability-metadata.json. */
class NativeMetadataEntry(val type: String) {
    var allDeclared = false
    var jniAccessible = false
    val methods = sortedMapOf<String, List<String>>()
    val fields = sortedSetOf<String>()

    fun toMap(): Map<String, Any> = buildMap {
        put("type", type)
        if (jniAccessible) put("jniAccessible", true)
        if (allDeclared) {
            put("allDeclaredConstructors", true)
            put("allDeclaredMethods", true)
            put("allDeclaredFields", true)
        }
        if (methods.isNotEmpty()) {
            put("methods", methods.map { (key, params) -> mapOf("name" to key.substringBefore('('), "parameterTypes" to params) })
        }
        if (fields.isNotEmpty()) put("fields", fields.map { mapOf("name" to it) })
    }
}

/**
 * Serializers: every `X$$serializer` in the app's jars registers `X`, `X$Companion` (when it
 * exists) and `X$$serializer`, plus every named class of `kotlinx.serialization.json`. Native
 * sign-in once failed with "Serializer for class 'JsonLiteral' is not found" because one of these
 * was missing; the spike listed ~1,900 of them by hand.
 */
fun collectSerializerTypes(jars: Collection<File>): Set<String> {
    val classes = HashSet<String>()
    jars.forEach { jar ->
        ZipFile(jar).use { zip ->
            zip.entries().asSequence()
                .map { it.name }
                .filter { it.endsWith(".class") && !it.startsWith("META-INF/") }
                .mapTo(classes) { it.removeSuffix(".class").replace('/', '.') }
        }
    }
    val anonymous = Regex("""\$\d""")
    return buildSet {
        classes.filter { it.endsWith("\$\$serializer") }.forEach { serializer ->
            val owner = serializer.removeSuffix("\$\$serializer")
            add(owner)
            add(serializer)
            "$owner\$Companion".takeIf { it in classes }?.let(::add)
        }
        classes.filter { name ->
            name.startsWith("kotlinx.serialization.json.") &&
                '.' !in name.removePrefix("kotlinx.serialization.json.") &&
                !anonymous.containsMatchIn(name)
        }.forEach(::add)
    }
}

/** `(Ljava/lang/String;[BJ)V` -> `["java.lang.String", "byte[]", "long"]`. */
fun jniParameterTypes(signature: String): List<String> {
    val params = signature.substringAfter('(').substringBefore(')')
    val primitives = mapOf('Z' to "boolean", 'B' to "byte", 'C' to "char", 'S' to "short", 'I' to "int", 'J' to "long", 'F' to "float", 'D' to "double")
    val result = mutableListOf<String>()
    var i = 0
    while (i < params.length) {
        var dims = 0
        while (params[i] == '[') { dims++; i++ }
        val base = if (params[i] == 'L') {
            val end = params.indexOf(';', i)
            params.substring(i + 1, end).replace('/', '.').also { i = end + 1 }
        } else {
            (primitives[params[i]] ?: throw GradleException("Unknown JNI type '${params[i]}' in $signature")).also { i++ }
        }
        result += base + "[]".repeat(dims)
    }
    return result
}

/**
 * Bridge JNI: the `FindClass` / `Get[Static]MethodID` / `Get[Static]FieldID` / `ThrowNew` literals
 * in the app's own C++. A playback failover loop once came from an unregistered sink class. Each
 * member lookup must name its class through a variable assigned from `FindClass("…")`; anything
 * else (`GetObjectClass`, an inline expression) fails the build here rather than at run time.
 */
fun collectJniEntries(sources: Collection<File>, into: MutableMap<String, NativeMetadataEntry>) {
    val call = """(?:env->|\(\*env\)->)"""
    val envArg = """(?:env\s*,\s*)?"""
    val token = Regex(
        """(\w+)\s*=\s*$call""" + """FindClass\(\s*$envArg"([^"]+)"\s*\)""" + // 1 var, 2 class
            """|${call}FindClass\(\s*$envArg"([^"]+)"""" + // 3 class
            """|${call}Get(?:Static)?MethodID\(\s*$envArg(\w+)\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"""" + // 4 var, 5 name, 6 sig
            """|${call}Get(?:Static)?FieldID\(\s*$envArg(\w+)\s*,\s*"([^"]+)"""" + // 7 var, 8 name
            """|${call}ThrowNew\(\s*$envArg(\w+)""" + // 9 var
            """|${call}(GetObjectClass|Get(?:Static)?(?:Method|Field)ID)\(""", // 10 anything unparsed
    )
    sources.sortedBy { it.path }.forEach { source ->
        val text = source.readText()
        val classOf = mutableMapOf<String, String>()
        fun entry(jniName: String) = into.getOrPut(jniName.replace('/', '.')) { NativeMetadataEntry(jniName.replace('/', '.')) }
            .apply { jniAccessible = true }
        fun owner(variable: String, at: Int): NativeMetadataEntry {
            val name = classOf[variable] ?: throw GradleException(
                "${source.name}:${text.take(at).count { it == '\n' } + 1}: JNI lookup on '$variable', which is not assigned " +
                    "from FindClass(\"…\") in this file, so its native-image metadata cannot be generated.",
            )
            return entry(name)
        }
        token.findAll(text).forEach { match ->
            val g = match.groupValues
            when {
                g[1].isNotEmpty() -> { classOf[g[1]] = g[2]; entry(g[2]) }
                g[3].isNotEmpty() -> entry(g[3])
                g[4].isNotEmpty() -> owner(g[4], match.range.first).methods["${g[5]}${g[6]}"] = jniParameterTypes(g[6])
                g[7].isNotEmpty() -> owner(g[7], match.range.first).fields += g[8]
                g[9].isNotEmpty() -> owner(g[9], match.range.first).methods["<init>(Ljava/lang/String;)V"] = listOf("java.lang.String")
                else -> throw GradleException(
                    "${source.name}:${text.take(match.range.first).count { it == '\n' } + 1}: ${g[10]} call the native-image " +
                        "metadata generator cannot resolve; look classes up with FindClass(\"literal\") into a variable.",
                )
            }
        }
    }
}

val generateNativeMetadata = tasks.register("generateNativeMetadata") {
    group = "native image"
    description = "Generates the rule-based native-image metadata (serializers, bridge JNI) into build/native-image/generated."
    notCompatibleWithConfigurationCache("Uses the script-level metadata generator functions.")
    dependsOn("createDistributable")
    val jars = nativeJvmAppImageDir.map { image -> fileTree(image.dir("app")) { include("*.jar") } }
    inputs.files(jars)
    inputs.files(nativeJniSources)
    outputs.dir(nativeImageGeneratedDir)
    doLast {
        val entries = sortedMapOf<String, NativeMetadataEntry>()
        collectSerializerTypes(jars.get().files).forEach { type ->
            entries.getOrPut(type) { NativeMetadataEntry(type) }.allDeclared = true
        }
        val serializerCount = entries.size
        collectJniEntries(nativeJniSources.files, entries)
        val out = nativeImageGeneratedDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val json = groovy.json.JsonOutput.prettyPrint(
            groovy.json.JsonOutput.toJson(mapOf("reflection" to entries.values.map { it.toMap() })),
        )
        // UTF-8 without a BOM: native-image refuses a BOM (see native-image/README.md).
        out.resolve("reachability-metadata.json").writeText(json + "\n", Charsets.UTF_8)
        logger.lifecycle("Generated native-image metadata: $serializerCount serializer types, ${entries.size - serializerCount} more from bridge JNI -> $out")
    }
}

val nativeCompile = tasks.register<Exec>("nativeCompile") {
    group = "native image"
    description = "Compiles createDistributable's jars into a native Nuvio.exe with GraalVM native-image."
    notCompatibleWithConfigurationCache("Runs GraalVM native-image inside a host-local MSVC environment.")
    enabled = isWindowsHost
    dependsOn("createDistributable", generateNativeMetadata)
    inputs.dir(nativeImageInputsDir)
    inputs.dir(nativeImageGeneratedDir)
    inputs.files(nativeJvmAppImageDir.map { image -> fileTree(image.dir("app")) { include("*.jar", "Nuvio.cfg") } })
    inputs.property("march", nativeImageMarch)
    inputs.property("qa", nativeImageQa)
    inputs.property("quick", nativeImageQuick)
    inputs.property("version", desktopReleaseVersionName)
    outputs.dir(nativeImageWorkDir)
    isIgnoreExitValue = true
    doFirst {
        val graal = resolveGraalVmHome()
        val vcvars = resolveWindowsVcvars()
        val work = nativeImageWorkDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val appDir = nativeJvmAppImageDir.get().asFile.resolve("app")
        val cfg = readNativeAppImageConfig(appDir)
        val metadataRoot = nativeImageInputsDir.asFile
        val metadataDirs = listOf(metadataRoot.resolve("agent")) +
            metadataRoot.listFiles { file -> file.isDirectory && file.name != "agent" }.orEmpty().sortedBy { it.name } +
            nativeImageGeneratedDir.get().asFile
        fun slashes(file: File) = file.absolutePath.replace('\\', '/')

        // Look like an app, not a console tool: native-image links a console program by default
        // (a terminal opened before the start screen) with no icon or version details.
        val rcFile = work.resolve("nuvio-native.rc")
        val resFile = work.resolve("nuvio-native.res")
        val numericVersion = (desktopReleasePackageVersion.split('.').map { part -> part.filter(Char::isDigit).ifEmpty { "0" } } +
            listOf("0", "0", "0", "0")).take(4).joinToString(",")
        val icon = project.file("src/desktopMain/resources/icons/nuvio-app-icon.ico")
        val year = ZonedDateTime.now(ZoneOffset.UTC).year
        rcFile.writeText(
            """
            1 ICON "${slashes(icon)}"
            1 VERSIONINFO
            FILEVERSION $numericVersion
            PRODUCTVERSION $numericVersion
            FILEOS 0x40004
            FILETYPE 0x1
            BEGIN
              BLOCK "StringFileInfo"
              BEGIN
                BLOCK "040904B0"
                BEGIN
                  VALUE "CompanyName", "$desktopPublisher"
                  VALUE "FileDescription", "Nuvio HTPC"
                  VALUE "FileVersion", "$desktopReleaseVersionName"
                  VALUE "InternalName", "Nuvio"
                  VALUE "LegalCopyright", "Copyright (C) $year"
                  VALUE "OriginalFilename", "Nuvio.exe"
                  VALUE "ProductName", "Nuvio"
                  VALUE "ProductVersion", "$desktopReleaseVersionName"
                END
              END
              BLOCK "VarFileInfo"
              BEGIN
                VALUE "Translation", 0x409, 1200
              END
            END
            """.trimIndent() + "\n",
            Charsets.US_ASCII,
        )

        val args = buildList {
            add("-H:ConfigurationFileDirectories=" + metadataDirs.joinToString(",") { slashes(it) })
            add("-H:+ReportExceptionStackTraces")
            add("-H:+UnlockExperimentalVMOptions")
            // Build-time -D values; NativeImageSupport re-applies Nuvio.cfg's at run time.
            add("-Djava.awt.headless=false")
            add("-Dskiko.renderApi=DIRECT3D")
            add("-Dsun.java2d.d3d=false")
            add("-Dcompose.application.configure.swing.globals=true")
            add("-Djpackage.app-version=$desktopReleasePackageVersion")
            add("-march=$nativeImageMarch")
            if (nativeImageQuick) add("-Ob")
            // native-image enables only file: and resource: URLs by default; without this every
            // java.net.URL http(s) connection throws (OkHttp is unaffected). The Discord artwork
            // probe read that as "proxy down" and sent posters un-letterboxed, so they rendered
            // squashed; NuvioEngine's localhost probe uses http: too.
            add("--enable-url-protocols=http,https")
            // HotSpotDiagnosticMXBean.dumpHeap for HeapDumpOnHighLiveSet (perf logging only).
            add("--enable-monitoring=heapdump")
            if (nativeImageQa) {
                add("--exact-reachability-metadata")
                add("-H:MissingRegistrationReportingMode=Warn")
                // Never -H:+JNIVerboseLookupErrors: on every failed JNI class lookup it dumps the
                // whole JNI dictionary to raw native stderr, and a windowed exe started normally
                // has none, so the write was fatal and SVM called abort(). quickjs-kt probes
                // FindClass("Error") on every JS error, so plugins crashed every double-clicked
                // QA build (2026-10-17). (-H:+PrintJNIMethods only listed native linkages.)
            }
            add("-H:NativeLinkerOption=/SUBSYSTEM:WINDOWS")
            add("-H:NativeLinkerOption=/ENTRY:mainCRTStartup")
            add("-H:NativeLinkerOption=${slashes(resFile)}")
            // The JVM build's -Xmx1g equivalent. MaxNewSize 512m was tried (2026-10-16): peak
            // private memory 1.5 -> 2.2 GB and no fewer stalls of 200 ms or more, so 192m.
            // -H:-RemoveLoopSafepoints was tried too and made no difference.
            add("-R:MaxHeapSize=1g")
            add("-R:MaxNewSize=192m")
            // Heap floor for the old generation. Serial GC's default policy (Adaptive2) kept the old
            // generation sized just above the ~200-280 MB live set, so ordinary browsing promoted
            // it full every 20-40 s: a natural 250-316 ms stop-the-world full GC each time, every
            // (UiStall) of 250 ms or more in the 2026-10-19 session, and back-to-back pairs ~400 ms
            // apart. 512m leaves ~320 MB of old generation, so most growth is collected by
            // IdleHeapCollector while the user is not interacting.
            add("-R:MinHeapSize=512m")
            // Objects are promoted after surviving 3 young collections instead of up to 15 (the
            // serial GC's default). Startup builds ~60 MB of long-lived data (caches, catalogs, meta)
            // that was copied between survivor spaces on every young collection until it aged out:
            // (YoungGc) 2026-10-19 18:46, 26 young collections / 1,253 ms in the first 3.3 s, survivor
            // space pinned at 55-63 MB, each pause 70-100 ms; once it tenured they took 7-10 ms.
            // Hosted (build-time) option.
            add("-H:MaxSurvivorSpaces=3")
            add("-o")
            add(slashes(work.resolve("Nuvio")))
            add("-cp")
            add("\"" + cfg.classpath.joinToString(";") { it.replace('\\', '/') } + "\"")
            // --add-opens/--add-exports only take effect at build time (Component.peer for the HWND).
            addAll(
                cfg.javaOptions.filter { it.startsWith("--add-opens=") || it.startsWith("--add-exports=") }
                    .filterNot { "lwawt" in it },
            )
            add(cfg.mainClass)
        }
        val argFile = work.resolve("ni-args.txt").apply { writeText(args.joinToString("\r\n") + "\r\n", Charsets.US_ASCII) }
        val manifest = metadataRoot.resolve("nuvio-launcher.manifest")
        val exe = work.resolve("Nuvio.exe")
        val log = work.resolve("build.log")
        val bat = work.resolve("native-compile.cmd")
        bat.writeText(
            listOf(
                "@echo off",
                "call ${cmdQuote(vcvars.absolutePath)} >nul",
                "if errorlevel 1 exit /b %errorlevel%",
                "set \"JAVA_HOME=${graal.absolutePath}\"",
                "rc.exe /nologo /fo ${cmdQuote(resFile.absolutePath)} ${cmdQuote(rcFile.absolutePath)} > ${cmdQuote(log.absolutePath)} 2>&1",
                "if errorlevel 1 exit /b %errorlevel%",
                "call ${cmdQuote(graal.resolve("bin/native-image.cmd").absolutePath)} @${cmdQuote(argFile.absolutePath)} >> ${cmdQuote(log.absolutePath)} 2>&1",
                "if errorlevel 1 exit /b %errorlevel%",
                // The image has no manifest; without the launcher's PerMonitorV2 one Windows
                // DPI-virtualises the window.
                "mt.exe -nologo -manifest ${cmdQuote(manifest.absolutePath)} -outputresource:${cmdQuote(exe.absolutePath)};#1 >> ${cmdQuote(log.absolutePath)} 2>&1",
                "exit /b %ERRORLEVEL%",
            ).joinToString("\r\n") + "\r\n",
            Charsets.US_ASCII,
        )
        logger.lifecycle(
            "native-image: GraalVM ${graal.name}, -march=$nativeImageMarch${if (nativeImageQa) ", QA (reports missing registrations)" else ""}${if (nativeImageQuick) ", quick build (-Ob)" else ""}, " +
                "${metadataDirs.size} metadata dirs; log in $log",
        )
        commandLine("cmd", "/d", "/c", bat.absolutePath)
    }
    doLast {
        val work = nativeImageWorkDir.get().asFile
        val log = work.resolve("build.log")
        if (executionResult.get().exitValue != 0 || !work.resolve("Nuvio.exe").isFile) {
            log.takeIf { it.isFile }?.readLines()?.takeLast(40)?.forEach { logger.error(it) }
            throw GradleException("native-image failed; full log: $log")
        }
        log.readLines().filter { "registered for" in it || "Finished generating" in it }.forEach { logger.lifecycle(it.trim()) }
    }
}

val createNativeDistributable = tasks.register("createNativeDistributable") {
    group = "native image"
    description = "Lays out the native Nuvio.exe like the jpackage image, in build/native-image/Nuvio."
    notCompatibleWithConfigurationCache("Copies host-local GraalVM and MSVC runtime files.")
    enabled = isWindowsHost
    dependsOn(nativeCompile)
    // Real inputs, or Gradle treats a task with outputs and no inputs as up to date whenever the
    // output folder is unchanged, and skips laying out a freshly compiled exe (2026-10-17).
    inputs.dir(nativeImageWorkDir)
    inputs.files(nativeJvmAppImageDir.map { image -> fileTree(image) { exclude("app/*.jar", "app/*.jsa", "runtime/**", "runtime-graal/**") } })
    inputs.property("qa", nativeImageQa)
    outputs.dir(nativeImageDistDir)
    doLast {
        val root = nativeJvmAppImageDir.get().asFile
        val work = nativeImageWorkDir.get().asFile
        val dist = nativeImageDistDir.get().asFile
        if (isRunningFrom(dist.resolve("Nuvio.exe"))) {
            throw GradleException("Nuvio is running from $dist; quit it first (this task replaces that folder).")
        }
        dist.deleteRecursively()
        dist.resolve("runtime/bin").mkdirs()
        dist.resolve("runtime/lib").mkdirs()
        dist.resolve("app").mkdirs()
        // Everything beside jpackage's launcher (mpv, player bridge, engine, resources\, ...).
        root.listFiles().orEmpty().forEach { entry ->
            when {
                entry.isFile && entry.name != "Nuvio.exe" -> entry.copyTo(dist.resolve(entry.name))
                entry.isDirectory && entry.name !in setOf("app", "runtime", "runtime-graal") ->
                    entry.copyRecursively(dist.resolve(entry.name))
            }
        }
        // app\ without the jars (compiled into the exe), the class archives, or the startup class
        // list (StartupClassPreloader is skipped natively).
        root.resolve("app").listFiles().orEmpty()
            .filterNot { it.extension == "jar" || it.extension == "jsa" || it.name == "startup-classes.lst" }
            .forEach { entry -> entry.copyRecursively(dist.resolve("app/${entry.name}")) }
        work.resolve("Nuvio.exe").copyTo(dist.resolve("Nuvio.exe"))
        // The JDK libraries native-image emits (awt, jawt, fontmanager, ...): beside the exe, where
        // the image loads them, and in runtime\bin, where skiko opens jawt via java.home.
        work.listFiles { file -> file.extension == "dll" }.orEmpty().forEach { dll ->
            dll.copyTo(dist.resolve(dll.name))
            dll.copyTo(dist.resolve("runtime/bin/${dll.name}"))
        }
        // AWT's font manager reads %java.home%\lib\fontconfig.bfc.
        resolveGraalVmHome().resolve("lib").listFiles { file ->
            file.isFile && (file.name.startsWith("fontconfig") || file.name.startsWith("psfont"))
        }.orEmpty().forEach { it.copyTo(dist.resolve("runtime/lib/${it.name}")) }
        // The MSVC runtime the backdrop framer preloads from runtime\bin.
        root.resolve("runtime/bin").listFiles { file ->
            file.name.startsWith("vcruntime") || file.name.startsWith("msvcp")
        }.orEmpty().forEach { it.copyTo(dist.resolve("runtime/bin/${it.name}")) }
        // QA builds: the marker makes NativeImageSupport copy the console (where the image reports
        // missing registrations) into native-qa-console.log beside the exe.
        if (nativeImageQa) {
            dist.resolve("native-qa").writeText("QA build: reports missing native-image registrations. Never ship.\n")
            logger.lifecycle("QA build: missing registrations will be logged to ${dist.resolve("native-qa-console.log")}")
            // A windowed exe has no stderr, so whatever a native crash prints first (an SVM fatal
            // error, a QuickJS assert before abort()) was lost. Started through this, the process
            // inherits a redirected stdout/stderr and that text lands in native-qa-stdio.log.
            dist.resolve("Nuvio-qa.cmd").writeText(
                "@echo off\r\n" +
                    "rem QA build: runs Nuvio with native stdout/stderr captured in native-qa-stdio.log.\r\n" +
                    "\"%~dp0Nuvio.exe\" %* 1>\"%~dp0native-qa-stdio.log\" 2>&1\r\n",
            )
        }
        // The updater never swaps a folder carrying this for a downloaded release (plan step 6):
        // this build is a stamped nightly, so it is offered updates. packageNativeReleaseZip leaves it out.
        dist.resolve("dev-build").writeText("Development build: the in-place updater leaves this folder alone.\n")
        val totalMb = dist.walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024)
        logger.lifecycle("Native image laid out in $dist ($totalMb MB, exe ${dist.resolve("Nuvio.exe").length() / (1024 * 1024)} MB)")
    }
}

// -Pnuvio.native.skipSmoke=true: zip without the smoke gate. Local iteration only, never a release.
val nativeSkipSmoke = providers.gradleProperty("nuvio.native.skipSmoke").orNull.toBoolean()

// Plan step 5: launches the laid-out exe 15 times (plus once as the player smoke harness) against
// a throwaway data directory and fails on an early exit, a missing start-screen / self-test marker,
// a DPI-virtualised window or a native failure in the log. The gate before every nightly upload;
// no CI can build a 1:1 release (the SVP DLLs are not in the repo). Opens real windows for ~2 min.
val nativeSmoke = tasks.register<Exec>("nativeSmoke") {
    group = "native image"
    description = "Launches the native build repeatedly with its self-test and fails on any native failure (plan step 5)."
    notCompatibleWithConfigurationCache("Launches the host-local native image.")
    enabled = isWindowsHost
    dependsOn(createNativeDistributable)
    val runs = providers.gradleProperty("nuvio.native.smokeRuns").orNull ?: "15"
    commandLine(
        "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
        "-File", project.file("scripts/native-smoke.ps1").absolutePath,
        "-Exe", nativeImageDistDir.get().asFile.resolve("Nuvio.exe").absolutePath,
        "-Runs", runs,
        "-WorkDir", layout.buildDirectory.dir("native-image/smoke").get().asFile.absolutePath,
    )
}

tasks.register<Zip>("packageNativeReleaseZip") {
    group = "native image"
    description = "Zips build/native-image/Nuvio as the release asset Nuvio.zip."
    notCompatibleWithConfigurationCache("Packages the host-local native image.")
    enabled = isWindowsHost
    dependsOn(createNativeDistributable)
    if (!nativeSkipSmoke) dependsOn(nativeSmoke)
    doFirst {
        if (nativeImageDistDir.get().asFile.resolve("native-qa").isFile) {
            throw GradleException(
                "build/native-image/Nuvio is a QA build (-Pnuvio.native.qa=true); rebuild without it before packaging a release.",
            )
        }
    }
    from(nativeImageDistDir) {
        into("Nuvio")
        // Build-folder only: the dev marker (it would stop every user's in-place updates), QA
        // files, and what a run of the build left behind (portable-mode data, logs).
        exclude("dev-build", "native-qa", "native-qa-console.log", "native-qa-stdio.log", "Nuvio-qa.cmd")
        exclude("data/**", "portable", "install-manifest.txt")
    }
    archiveFileName.set("Nuvio.zip")
    destinationDirectory.set(nativeImageReleaseDir)
}

tasks.register<Exec>("nativeImageTrace") {
    group = "native image"
    description = "Runs the JVM app image on GraalVM's JVM with the tracing agent (user-driven; merges into native-image/agent)."
    notCompatibleWithConfigurationCache("Launches the host-local app for an interactive tracing session.")
    enabled = isWindowsHost
    dependsOn("createDistributable")
    doFirst {
        if (ProcessHandle.allProcesses().anyMatch { process ->
                process.info().command().map { File(it).name.equals("Nuvio.exe", ignoreCase = true) }.orElse(false)
            }
        ) {
            throw GradleException("Close Nuvio first (it is single-instance).")
        }
        val root = nativeJvmAppImageDir.get().asFile
        val appDir = root.resolve("app")
        // Nuvio takes java.home's PARENT as its install folder (player bridge, engine, updater), so
        // GraalVM runs from a copy inside the app image. A copy, not a junction: deleting the build
        // folder could follow a junction and wipe the real GraalVM.
        val graalCopy = root.resolve("runtime-graal")
        val wantedRelease = resolveGraalVmHome().resolve("release").readText()
        if (graalCopy.resolve("release").takeIf { it.isFile }?.readText() != wantedRelease) {
            graalCopy.deleteRecursively()
            logger.lifecycle("Copying GraalVM into the app image (once per build)...")
            resolveGraalVmHome().copyRecursively(graalCopy)
        }
        val agentDir = nativeImageInputsDir.asFile.resolve("agent").apply { mkdirs() }
        val cfg = readNativeAppImageConfig(appDir)
        // The argfile carries local paths, so it stays in build/, not beside the agent output.
        val argFile = layout.buildDirectory.file("native-image/trace-args.txt").get().asFile.apply { parentFile.mkdirs() }
        // -Pnuvio.trace.freshProfile=true: a new, empty data folder for this run, so first-run paths
        // (setup wizard, empty Home) get traced too. The sign-in token is in the Windows registry
        // (Java Preferences), not in that folder, so the account stays signed in; sign out and back
        // in during that session to trace the sign-in screens.
        val freshProfile = providers.gradleProperty("nuvio.trace.freshProfile").orNull.toBoolean()
        val freshProfileDir = layout.buildDirectory.dir("native-image/trace-profile").get().asFile
        if (freshProfile) {
            freshProfileDir.deleteRecursively()
            freshProfileDir.mkdirs()
            logger.lifecycle("Fresh profile: data folder $freshProfileDir")
        }
        val lines = buildList {
            add("-agentlib:native-image-agent=config-merge-dir=${agentDir.absolutePath},config-write-period-secs=30,config-write-initial-delay-secs=20")
            addAll(cfg.javaOptions.filterNot { "SharedArchive" in it })
            add("-Djava.library.path=${appDir.absolutePath}")
            add("-Dnuvio.nativeImageTracing=1")
            if (freshProfile) add("-Dnuvio.desktop.dataDir=${freshProfileDir.absolutePath}")
            add("-cp")
            add("\"" + cfg.classpath.joinToString(";") + "\"")
            add(cfg.mainClass)
        }
        // Java argfiles treat backslash as an escape: forward slashes throughout.
        argFile.writeText(lines.joinToString("\r\n") { it.replace('\\', '/') } + "\r\n", Charsets.US_ASCII)
        logger.lifecycle("Tracing Nuvio on GraalVM. Use the app (Docs/Native-Spike-Test-Checklist.md), then quit from the tray.")
        logger.lifecycle("Agent output merges into $agentDir.")
        commandLine(graalCopy.resolve("bin/java.exe").absolutePath, "@${argFile.absolutePath}")
    }
}


