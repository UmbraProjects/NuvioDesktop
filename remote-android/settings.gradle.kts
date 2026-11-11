// fork:phone-remote — the Android remote for Nuvio HTPC. A standalone Gradle build on purpose:
// it shares nothing with composeApp, and keeping it separate means the Windows build never has to
// configure the Android plugin. Built by .github/workflows/android-remote.yml.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "NuvioRemote"
include(":app")
