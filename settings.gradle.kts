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

// Later phases (added once their code lands):
//   :core:audio, :data    (Phase 3, Android library modules)
//   :feature:transceiver  (Phase 4, Compose UI)
//   :app                  (Phase 4, application shell + Hilt)
