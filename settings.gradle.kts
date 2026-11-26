pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "RadioMe"

// Phase 1: pure-Kotlin DSP core (JVM-testable, no Android dependencies).
include(":core:dsp")
// Phase 2: packetizer, image pipeline, chunk tracking, half-duplex ARQ (pure Kotlin).
include(":core:protocol")

// Android modules (Phase 3 and 4). They need an Android SDK (ANDROID_HOME / ANDROID_SDK_ROOT or sdk.dir in
// local.properties) and access to Google Maven, so they are skipped where neither is available and the
// pure-Kotlin modules still build and test on their own.
val androidSdkAvailable: Boolean = run {
    val localProps = file("local.properties")
    val fromLocal = localProps.exists() && localProps.readLines().any { it.trim().startsWith("sdk.dir") }
    fromLocal || System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null
}
if (androidSdkAvailable) {
    include(":core:audio")
    include(":core:voice")
    include(":data")
    // Phase 4: Compose UI and the application shell (Hilt).
    include(":feature:transceiver")
    include(":app")
} else {
    logger.lifecycle("No Android SDK found: building only the pure-Kotlin modules (:core:dsp, :core:protocol).")
}
