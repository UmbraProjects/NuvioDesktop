rootProject.name = "Nuvio"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// fork:innertubex — opt-in (-Pnuvio.innertubex=true). Adds the YouTube stream resolver from BitChord's
// stack (InnerTubeX), which is published on JitPack, so JitPack is only a repository when it is on.
val innerTubeXEnabled = providers.gradleProperty("nuvio.innertubex").map { it.equals("true", ignoreCase = true) }.getOrElse(false)

dependencyResolutionManagement {
    repositories {
        if (innerTubeXEnabled) {
            maven("https://jitpack.io") {
                content { includeGroupByRegex("com\\.github\\..*") }
            }
        }
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(":composeApp")