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
        maven("https://jitpack.io")
    }
}

rootProject.name = "DownloadHub"
include(":app")

// Portable engine code shared by the Android app and the Windows desktop app.
// Everything in :core is plain Kotlin/JVM with no Android APIs, so the desktop
// build can reuse the HTTP, speed-limiting and page-scanning logic unchanged.
include(":core")

// Compose Multiplatform desktop (Windows) build of the same app.
include(":desktop")
