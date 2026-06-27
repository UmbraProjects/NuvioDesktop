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
import java.util.Properties

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
    }
    if (windowsWebView2LoaderDll.exists()) {
        from(windowsWebView2LoaderDll)
    }
    if (windowsLibmpvRuntimeDir?.exists() == true) {
        from(windowsLibmpvRuntimeDir) {
            include("*.dll")
        }
    }
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

    @TaskAction
    fun generate() {
        val dir = runtimeDir.get().asFile
        val files = dir
            .listFiles { file -> file.isFile && file.name != indexFile.get().asFile.name }
            .orEmpty()
            .map { it.name }
            .sorted()
        indexFile.get().asFile.writeText(files.joinToString(separator = "\n", postfix = "\n"))
    }
}

tasks.withType<Jar>().configureEach {
    if (isMacHost && name == "desktopJar") {
        dependsOn(buildMacosPlayerBridge)
        from(macosPlayerBridgeOutput) {
            into("native/macos")
        }
    }
    if (isWindowsHost && name == "desktopJar") {
        dependsOn(buildWindowsPlayerBridge, prepareWindowsPlayerRuntime, generateWindowsPlayerRuntimeIndex)
        from(windowsPlayerBridgeOutput) {
            into("native/windows")
        }
        from(windowsPlayerRuntimeOutput) {
            into("native/windows")
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
        dependsOn(buildWindowsPlayerBridge, prepareWindowsPlayerRuntime, generateWindowsPlayerRuntimeIndex)
    }
    // Windows doesn't search a loaded DLL's own directory for its dependencies; the DLL
    // directory must be on PATH so player_bridge.dll can find libmpv-2.dll and friends.
    tasks.withType<JavaExec>().matching { it.name in desktopNativePlayerTasks }.configureEach {
        val nativeDllDir = layout.buildDirectory.dir("native/windows").get().asFile.absolutePath
        environment("PATH", "$nativeDllDir;${System.getenv("PATH") ?: ""}")
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
    }
}

compose.desktop {
    application {
        mainClass = "com.nuvio.app.MainKt"
        val smokePlayerUrl = providers.gradleProperty("nuvio.desktop.smokePlayerUrl").orNull
            ?: System.getenv("NUVIO_DESKTOP_SMOKE_PLAYER_URL")
        jvmArgs += listOfNotNull(
            "-Dapple.awt.application.appearance=NSAppearanceNameDarkAqua",
            "-Dskiko.renderApi=OPENGL",
            "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.awt.windows=ALL-UNNAMED",
            smokePlayerUrl?.takeIf { it.isNotBlank() }?.let { "-Dnuvio.desktop.smokePlayerUrl=$it" },
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Nuvio"
            packageVersion = desktopReleasePackageVersion
            vendor = "Nuvio Media"
            modules("java.net.http", "jdk.httpserver")
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


