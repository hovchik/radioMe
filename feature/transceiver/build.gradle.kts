import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.radiome.feature.transceiver"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core:audio"))
    implementation(project(":core:voice"))
    api(project(":data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    // OpenStreetMap view with an on-disk tile cache for offline use.
    implementation(libs.osmdroid)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    // Compose screens rendered under Robolectric, to catch crashes before they reach a phone.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

// Same offline Robolectric setup as :data (see the note there).
val robolectricAndroid: Configuration by configurations.creating
dependencies {
    robolectricAndroid("org.robolectric:android-all-instrumented:14-robolectric-10818077-i7")
}
tasks.withType<Test>().configureEach {
    // Full stack traces in the CI log, so a crash a test reproduces can be read there.
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        showCauses = true
    }
    val jars = robolectricAndroid
    inputs.files(jars).withPropertyName("robolectricAndroid").withNormalizer(ClasspathNormalizer::class.java)
    jvmArgumentProviders += CommandLineArgumentProvider {
        listOf("-Drobolectric.offline=true", "-Drobolectric.dependency.dir=${jars.singleFile.parentFile.absolutePath}")
    }
}
