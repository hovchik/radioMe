buildscript {
    // Android plugins go on the root classpath only when the Android modules are part of the build, so the
    // pure-Kotlin modules keep building where Google Maven is unreachable. Versions mirror gradle/libs.versions.toml.
    if (findProject(":data") != null) {
        repositories {
            google()
            mavenCentral()
        }
        dependencies {
            classpath("com.android.tools.build:gradle:8.13.0")
            classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.6")
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}
