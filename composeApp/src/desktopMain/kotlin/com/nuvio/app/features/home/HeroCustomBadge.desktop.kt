package com.nuvio.app.features.home

import com.nuvio.app.core.storage.DesktopStorage
import java.io.File

private val BadgeFileExtensions = setOf("png", "jpg", "jpeg", "webp")

internal actual fun heroCustomBadgeModel(label: String, category: String): Any? {
    val badgeDir = resolveBadgeDirectory().takeIf(File::isDirectory) ?: return null
    val aliases = customBadgeAliases(label, category)
    return badgeDir
        .listFiles { file -> file.isFile && file.extension.lowercase() in BadgeFileExtensions }
        ?.firstOrNull { file -> file.nameWithoutExtension.normalizedBadgeName() in aliases }
}

internal actual fun heroBundledBadgeModel(fileName: String): Any? {
    if (fileName.any { it == '/' || it == '\\' }) return null
    val resourcePath = "composeResources/nuvio.composeapp.generated.resources/drawable/$fileName"
    val classLoader = Thread.currentThread().contextClassLoader
        ?: HeroCustomBadgeResourceAnchor::class.java.classLoader
        ?: return null
    val target = File(resolveBundledBadgeCacheDirectory(), fileName)
    return runCatching {
        classLoader.getResourceAsStream(resourcePath)?.use { input ->
            target.parentFile?.mkdirs()
            target.outputStream().use(input::copyTo)
            target
        }
    }.getOrNull()
}

private object HeroCustomBadgeResourceAnchor

private fun resolveBundledBadgeCacheDirectory(): File =
    File(System.getProperty("java.io.tmpdir"), "nuvio-bundled-badges")

private fun resolveBadgeDirectory(): File {
    return DesktopStorage.rootDir.resolve("Badges").toFile()
}

private fun customBadgeAliases(label: String, category: String): Set<String> =
    buildSet {
        add(label)
        add(category)
        add(category.removePrefix("award:"))
        // Oscar recognition is surfaced as Best Picture only, so the Oscar/Academy Award
        // filenames are folded into the Best Picture badge (there is no separate Oscar badge).
        when (category) {
            "award:best_picture" -> addAll(listOf("Best Picture", "Oscar", "Oscar Winner", "Oscar Best Picture", "Academy Award"))
            "award:best_picture_nom" -> addAll(listOf("Best Picture Nominee", "Best Picture Nominated", "Oscar Nominee", "Oscar Best Picture Nominee", "Academy Award Nominee"))
            "award:globe_win" -> addAll(listOf("Golden Globe", "Golden Globe Winner"))
            "award:globe_nom" -> addAll(listOf("Golden Globe Nominee", "Globe Nominee"))
            "award:emmy_win" -> addAll(listOf("Emmy", "Emmy Winner"))
            "award:emmy_nom" -> addAll(listOf("Emmy Nominee"))
            "award:palme" -> addAll(listOf("Palme d'Or", "Palme dOr", "Cannes"))
            "award:golden_lion" -> addAll(listOf("Golden Lion", "Venice"))
            "award:golden_bear" -> addAll(listOf("Golden Bear", "Berlin"))
            "award:people_choice" -> addAll(listOf("People's Choice", "TIFF", "Toronto"))
            "metacritic" -> addAll(listOf("Metacritic", "Must See", "Must-See"))
            "cult" -> addAll(listOf("Cult", "Cult Classic"))
            "trending" -> addAll(listOf("Trending"))
            "short_film" -> addAll(listOf("Short Film"))
            "mini_series" -> addAll(listOf("Mini Series", "Limited Series"))
            "binge_ready" -> addAll(listOf("Binge Ready", "Binge"))
            "true_story" -> addAll(listOf("True Story", "Based on a True Story"))
            // The label varies with which stinger keywords matched, so accept every spelling
            // rather than only the one this title happened to produce.
            "stinger" -> addAll(
                listOf(
                    "Stinger",
                    "Post-Credits Scene",
                    "Post Credits",
                    "After Credits",
                    "Mid-Credits Scene",
                    "Mid Credits",
                    "Mid & Post-Credits",
                ),
            )
            "new_release", "digital_release" -> addAll(listOf("New Release", "New"))
            // Director label is "Directed by <Name>"; also match the bare director name so a
            // per-director image (e.g. "David Fincher.png") resolves.
            "director" -> add(label.removePrefix("Directed by ").trim())
        }
    }.map(String::normalizedBadgeName).toSet()

private fun String.normalizedBadgeName(): String =
    lowercase()
        .replace("&", "and")
        .replace(Regex("""[^a-z0-9]+"""), "")
