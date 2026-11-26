import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.radiome.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core:protocol"))
    // RadioDatabase (a RoomDatabase) is part of this module's public API.
    api(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Robolectric downloads its Android jar from Maven Central at test time, with no retry, and CI runners hit HTTP 429
// there. Resolve the jar through Gradle instead (retried and cached) and run Robolectric offline against it.
// Keep the version in step with the robolectric version and the @Config sdk (34 = 14-robolectric-10818077, i7 for 4.16).
val robolectricAndroid: Configuration by configurations.creating
dependencies {
    robolectricAndroid("org.robolectric:android-all-instrumented:14-robolectric-10818077-i7")
}
tasks.withType<Test>().configureEach {
    val jars = robolectricAndroid
    inputs.files(jars).withPropertyName("robolectricAndroid").withNormalizer(ClasspathNormalizer::class.java)
    jvmArgumentProviders += CommandLineArgumentProvider {
        listOf("-Drobolectric.offline=true", "-Drobolectric.dependency.dir=${jars.singleFile.parentFile.absolutePath}")
    }
}
